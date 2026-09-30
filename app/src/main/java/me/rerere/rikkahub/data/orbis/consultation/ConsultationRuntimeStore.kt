package me.rerere.rikkahub.data.orbis.consultation

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.integration.AndroidOrbisConnectionPersistence
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionCredential
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

internal fun consultationDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun consultationRandom(bytes: Int = 32): String = ByteArray(bytes).also(SecureRandom()::nextBytes)
    .joinToString("") { "%02x".format(it) }

/** Never exported with normal Settings. Deliberately no secret-bearing toString. */
@Serializable
internal class ConsultationRuntimeConfig(
    val baseUrl: String = "", val aiToken: String = "", val humanToken: String = "",
    val assistantId: String = "", val deviceId: String = "", val subject: String = "",
    val enabled: Boolean = false, val counselor: Boolean = false,
    val manual: String = "", val references: String = "", val revision: Long = 0,
    val approvedReadTools: Set<String> = emptySet(), val approvedArchiveTools: Set<String> = emptySet(),
    val requiresGatewayProfile: Boolean = true,
)

internal class ConsultationRuntimeStore private constructor(private val context: Context) {
    private val mutex = Mutex()
    private val secret = AndroidOrbisConnectionPersistence(context, "consultation-runtime")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val directory get() = File(context.noBackupFilesDir, "orbis-consultation-private").also {
        check(it.isDirectory || it.mkdirs()); check(it.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
    }
    suspend fun config(): ConsultationRuntimeConfig = mutex.withLock {
        secret.read()?.let { json.decodeFromString<ConsultationRuntimeConfig>(it) } ?: ConsultationRuntimeConfig()
    }
    /** Content-free diagnostics under the normal writer lock. This deliberately avoids
     * directory, read() and conversationHead(), whose AtomicFile reads may repair files. */
    suspend fun localStatus(sessionId: String, human: OrbisConnectionCredential,
        nowMillis: Long = System.currentTimeMillis()): ConsultationLocalStatus = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val encoded = secret.readExistingSnapshot()
                    ?: return@withContext ConsultationLocalStatus(ConsultationLocalState.UNAVAILABLE)
                val runtime = json.decodeFromString<ConsultationRuntimeConfig>(encoded)
                val trustedRoot = context.noBackupFilesDir.canonicalFile
                val existingDirectory = File(trustedRoot, "orbis-consultation-private")
                check(existingDirectory.canonicalFile.parentFile == trustedRoot)
                readConsultationLocalStatus(existingDirectory, sessionId, runtime, human, nowMillis)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ConsultationLocalStatus(ConsultationLocalState.UNAVAILABLE)
            }
        }
    }
    suspend fun saveConfig(value: ConsultationRuntimeConfig, expectedRevision: Long) = mutex.withLock {
        val old = secret.read()?.let { json.decodeFromString<ConsultationRuntimeConfig>(it) } ?: ConsultationRuntimeConfig()
        check(old.revision == expectedRevision) { "consultation_configuration_changed" }
        check(value.revision == expectedRevision + 1)
        require(value.manual.toByteArray().size <= 4096 && value.references.toByteArray().size <= 4096)
        require(value.approvedReadTools.size <= 40 && value.approvedArchiveTools.size <= 20)
        secret.write(json.encodeToString(value))
    }
    suspend fun saveDocuments(manual: String?, references: String?, expectedRevision: Long) = mutex.withLock {
        val old = secret.read()?.let { json.decodeFromString<ConsultationRuntimeConfig>(it) } ?: error("consultation_not_configured")
        check(old.revision == expectedRevision)
        val newManual = manual ?: old.manual; val newReferences = references ?: old.references
        require(newManual.toByteArray().size <= 4096 && newReferences.toByteArray().size <= 4096)
        // Working documents are snapshotted per turn. This cannot change an identity,
        // credential or tool permission, and need not invalidate the final archive ACK.
        secret.write(json.encodeToString(ConsultationRuntimeConfig(old.baseUrl, old.aiToken, old.humanToken,
            old.assistantId, old.deviceId, old.subject, old.enabled, old.counselor, newManual, newReferences,
            old.revision, old.approvedReadTools, old.approvedArchiveTools, old.requiresGatewayProfile)))
    }
    suspend fun read(requestId: String): ConsultationCheckpoint? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = checkpointFile(requestId)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
            file.openRead().use { input ->
                val bytes = input.cloudReadBounded(2 * 1024 * 1024)
                require(bytes.size <= 2 * 1024 * 1024)
                json.decodeFromString<ConsultationCheckpoint>(bytes.toString(Charsets.UTF_8))
            }
        }
    }
    suspend fun write(value: ConsultationCheckpoint) = mutex.withLock { withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 2 * 1024 * 1024) { "consultation_checkpoint_limit" }
        val file = checkpointFile(value.requestId)
        // Bound storage but never delete unresolved records or silently rotate privacy evidence.
        if (!file.baseFile.exists()) require(directory.listFiles().orEmpty().count { it.name.endsWith(".json") } < 300)
        val stream = file.startWrite()
        try {
            stream.write(encoded); stream.fd.sync(); file.finishWrite(stream)
            val verified = file.openRead().use { it.cloudReadBounded(encoded.size + 1) }
            check(verified.contentEquals(encoded)) { "consultation_checkpoint_write_failed" }
        } catch (error: Throwable) { file.failWrite(stream); throw error }
        finally { encoded.fill(0) }
    } }
    /** Durable nonce BEFORE a human-confirmed POST; never reset an old checkpoint. */
    suspend fun retryConsent(sessionId: String, requestId: String, checkpointHash: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(Regex("[a-f0-9]{32}").matches(sessionId) && Regex("[a-f0-9]{32}").matches(requestId))
            require(Regex("[a-f0-9]{64}").matches(checkpointHash))
            val file = AtomicFile(File(directory, "retry-$requestId.json"))
            check(file.baseFile.canonicalFile.parentFile == directory.canonicalFile)
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val consent = file.openRead().use { json.decodeFromString<ConsultationRetryConsent>(it.cloudReadBounded(4096).toString(Charsets.UTF_8)) }
                check(consent.sessionId == sessionId && consent.previousRequestId == requestId && consent.checkpointHash == checkpointHash)
                return@withContext consent.confirmationId
            }
            val consent = ConsultationRetryConsent(sessionId, requestId, checkpointHash, consultationRandom(16))
            val encoded = json.encodeToString(consent).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(encoded); stream.fd.sync(); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            val verified = file.openRead().use { it.cloudReadBounded(4096) }
            check(verified.contentEquals(encoded))
            consent.confirmationId
        }
    }
    private fun checkpointFile(id: String): AtomicFile {
        require(Regex("[a-f0-9]{32}").matches(id))
        val file = File(directory, "$id.json")
        check(file.canonicalFile.parentFile == directory.canonicalFile)
        return AtomicFile(file)
    }

    suspend fun hasRetryConsent(value: ConsultationCheckpoint): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        if (!canManuallyRetryConsultation(value)) return@withContext false
        val file = AtomicFile(File(directory, "retry-${value.requestId}.json"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext false
        val saved = file.openRead().use { json.decodeFromString<ConsultationRetryConsent>(it.cloudReadBounded(4096).toString(Charsets.UTF_8)) }
        saved.sessionId == value.sessionId && saved.previousRequestId == value.requestId &&
            saved.checkpointHash == consultationDigest(json.encodeToString(value))
    } }

    /** One durable active delivery pointer per hidden Conversation. Written before dispatch;
     * a missing/changed pointer never means permission to regenerate an old request. */
    suspend fun conversationHead(conversationId: String): String? = mutex.withLock { withContext(Dispatchers.IO) {
        val file = conversationHeadFile(conversationId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        file.openRead().use { it.cloudReadBounded(128).toString(Charsets.UTF_8) }.also {
            require(Regex("[a-f0-9]{32}").matches(it)) { "consultation_head_invalid" }
        }
    } }

    suspend fun setConversationHead(conversationId: String, requestId: String, expected: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(Regex("[a-f0-9]{32}").matches(requestId))
            val file = conversationHeadFile(conversationId)
            val current = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                file.openRead().use { it.cloudReadBounded(128).toString(Charsets.UTF_8) } else null
            check(current == expected || current == requestId) { "consultation_head_changed" }
            val bytes = requestId.toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            check(file.openRead().use { it.cloudReadBounded(128) }.contentEquals(bytes))
        }
    }

    private fun conversationHeadFile(id: String): AtomicFile {
        require(java.util.UUID.fromString(id).toString() == id)
        val file = File(directory, "conversation-$id.head")
        check(file.canonicalFile.parentFile == directory.canonicalFile)
        return AtomicFile(file)
    }
    companion object {
        @Volatile private var instance: ConsultationRuntimeStore? = null
        fun open(context: Context): ConsultationRuntimeStore = instance ?: synchronized(this) {
            instance ?: ConsultationRuntimeStore(context.applicationContext).also { instance = it }
        }
    }
}

@Serializable
internal data class ConsultationCheckpoint(
    val requestId: String, val sessionId: String, val phase: String, val assistantId: String,
    val bindingDigest: String, val inputDigest: String, val state: String = "PREPARED",
    val messages: List<UIMessage> = emptyList(), val finalText: String = "",
    val submitAttempts: Int = 0, val toolInFlight: String? = null, val failure: String? = null,
    val outputTokenLimit: Int? = null, val failedAtMillis: Long? = null, val httpStatus: Int? = null,
    val conversationTurn: ConsultationConversationTurn? = null,
    val finalMessageId: String? = null,
    val baselineMessageIds: Set<String> = emptySet(),
    val outputMessageIds: Set<String> = emptySet(),
    val allowCreateConversation: Boolean = false,
    val retryOfRequestId: String? = null,
    val closedTurnRequestId: String? = null,
    val executionProcessId: String? = null,
)

@Serializable
private data class ConsultationRetryConsent(val sessionId: String, val previousRequestId: String,
    val checkpointHash: String, val confirmationId: String)

internal fun canManuallyRetryConsultation(value: ConsultationCheckpoint): Boolean =
    value.phase == "ACTIVE" && value.state in setOf("UNKNOWN", "BUSY") &&
        value.submitAttempts == 0 && value.toolInFlight == null && value.finalText.isBlank() &&
        value.messages.all { message -> message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning } }

internal fun consultationOutputBudget(configured: Int?, phase: String, serverLimit: Int?): Int {
    require(phase in setOf("ACTIVE", "ARCHIVING"))
    val ceiling = (serverLimit ?: 32768).coerceIn(1, 32768)
    return (configured?.takeIf { it > 0 } ?: if (phase == "ACTIVE") 16384 else 8192).coerceIn(1, ceiling)
}

internal fun canStartConsultationGeneration(previous: ConsultationCheckpoint?): Boolean = previous == null || previous.state == "PREPARED"
internal fun canSubmitConsultationCheckpoint(value: ConsultationCheckpoint): Boolean =
    value.state == "COMPLETE" && value.finalText.isNotBlank() && value.submitAttempts < 3 && value.toolInFlight == null

/** A server-issued archive delivery may summarize a stopped turn, but cannot settle an
 * uncertain external tool or approve a pending one. Pending operations are denied locally,
 * without execution, before the new archive wake. It never resumes the stopped generation. */
internal fun canArchiveStoppedConsultation(value: ConsultationCheckpoint): Boolean =
    value.phase == "ACTIVE" && value.state in setOf("UNKNOWN", "BUSY", "SUBMITTED", "WAITING_APPROVAL", "COMPLETE") &&
        value.toolInFlight == null && value.messages.none { message ->
            message.getTools().any { !it.isExecuted && !it.isPending }
        } && (value.state != "COMPLETE" || (value.finalText.isNotBlank() &&
            value.messages.all { message -> message.getTools().all { it.isExecuted } }))
