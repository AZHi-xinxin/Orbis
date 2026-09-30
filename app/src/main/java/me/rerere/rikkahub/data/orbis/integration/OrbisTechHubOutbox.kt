package me.rerere.rikkahub.data.orbis.integration

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded

internal const val HUB_OUTBOX_LIMIT = 32 * 1024
internal class HubOutboxException(val reason: String) : IOException(reason)

/** No text, token, credential fingerprint or filesystem path is exposed in UI state. */
internal data class HubOutboxState(
    val loaded: Boolean = false,
    val hasPending: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val receiptConfirmed: Boolean = false,
) {
    val canSendNew: Boolean get() = loaded && !hasPending && !busy && !failed
    val canRetry: Boolean get() = loaded && hasPending && !busy && !failed && !receiptConfirmed
}

internal interface HubOutboxPersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

private class AtomicHubOutbox(context: Context) : HubOutboxPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "orbis-techhub-outbox-v1.json"))
    override fun read(): ByteArray? = try {
        file.openRead().use { it.cloudReadBounded(HUB_OUTBOX_LIMIT) }
    } catch (missing: FileNotFoundException) {
        // A permissions/read error on an existing file is not an empty outbox.
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
        null
    }
    override fun write(bytes: ByteArray) {
        require(bytes.size <= HUB_OUTBOX_LIMIT)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            // finishWrite performs fsync then the atomic replacement.
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        // Android AtomicFile logs certain failures instead of throwing. Verify
        // exact durable contents before allowing any corresponding network call.
        val stored = file.openRead().use { it.cloudReadBounded(HUB_OUTBOX_LIMIT) }
        if (!stored.contentEquals(bytes)) throw IOException("outbox_write_failed")
    }
}

private data class HubPendingRecord(
    val scope: String, val room: String, val text: String, val key: String,
    val createdAtMillis: Long, val confirmed: Boolean = false, val attachment: HubUploadAttachment? = null,
) {
    fun bind(revision: Long) = HubSendIntent(room, text, key, createdAtMillis, revision, attachment)
}

/** Independent of volatile connection revision; raw credential is never persisted. */
internal fun hubCredentialFingerprint(credential: OrbisConnectionCredential): String =
    MessageDigest.getInstance("SHA-256").digest(
        (normalizeOrbisIntegrationUrl(credential.baseUrl) + "\u0000" + credential.token).toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * One global, durable, human-created pending send. Unknown outcomes survive
 * navigation, configuration changes and process death. Loading NEVER sends.
 * All state transitions share one mutex; callers cannot mint a second intent
 * while the first is in flight. A corrupt/failed store stays closed until the
 * human explicitly chooses to abandon the local retry record.
 */
internal class OrbisTechHubOutbox internal constructor(private val persistence: HubOutboxPersistence,
    private val discardAttachment: (HubUploadAttachment) -> Unit = {}) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(HubOutboxState())
    val state: StateFlow<HubOutboxState> = mutableState.asStateFlow()
    private var record: HubPendingRecord? = null

    companion object {
        @Volatile private var instance: OrbisTechHubOutbox? = null
        fun get(context: Context): OrbisTechHubOutbox = instance ?: synchronized(this) {
            instance ?: OrbisTechHubOutbox(AtomicHubOutbox(context.applicationContext),
                HubMediaFiles(context.applicationContext.noBackupFilesDir)::discard).also { instance = it }
        }
    }

    suspend fun load() = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (mutableState.value.loaded) return
        try {
            record = persistence.read()?.let(::decode)
            publish()
        } catch (_: Exception) { publish(failed = true) }
    }

    private fun publish(busy: Boolean = false, failed: Boolean = false) {
        mutableState.value = HubOutboxState(loaded = true, hasPending = record != null,
            busy = busy, failed = failed, receiptConfirmed = record?.confirmed == true)
    }

    suspend fun pendingFor(credential: OrbisConnectionCredential): HubSendIntent? = withContext(Dispatchers.IO) {
        lock.withLock {
            loadLocked()
            record?.takeIf { it.scope == hubCredentialFingerprint(credential) }?.bind(credential.revision)
        }
    }

    suspend fun send(
        credential: OrbisConnectionCredential, room: String, text: String, retry: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        attachment: HubUploadAttachment? = null,
        onAttachmentClaimed: (HubUploadAttachment) -> Unit = {},
        transport: suspend (HubSendIntent) -> List<HubMessage>,
    ): List<HubMessage> {
        var leaseAcquired = false
        try {
            val intent = withContext(Dispatchers.IO + NonCancellable) {
                lock.withLock {
                    loadLocked()
                    if (mutableState.value.failed) throw HubOutboxException("storage_unavailable")
                    if (mutableState.value.busy) throw HubOutboxException("send_in_progress")
                    val fingerprint = hubCredentialFingerprint(credential)
                    val selected = if (retry) {
                        val pending = record ?: throw HubOutboxException("no_pending")
                        if (pending.scope != fingerprint) throw HubOutboxException("configuration_changed")
                        if (pending.confirmed) throw HubOutboxException("already_confirmed")
                        pending.bind(credential.revision).also {
                            if (!it.retryAllowed(nowMillis)) throw HubOutboxException("retry_expired")
                        }
                    } else {
                        if (record != null) throw HubOutboxException("pending_exists")
                        require(nowMillis in 0..253_402_300_799_999L) { "invalid_time" }
                        val fresh = if (attachment == null) HubSendIntent.create(room, text, credential.revision, nowMillis)
                            else HubSendIntent.createMedia(room, text, credential.revision, attachment, nowMillis)
                        val next = HubPendingRecord(fingerprint, fresh.room, fresh.text, fresh.key, fresh.createdAtMillis,
                            attachment = fresh.attachment)
                        // Take exclusive ownership immediately before the first
                        // possible durable write. Cancellation before this point
                        // leaves cleanup to the UI; an uncertain write must retain bytes.
                        fresh.attachment?.let(onAttachmentClaimed)
                        try {
                            persistence.write(encode(next))
                            record = next
                        } catch (_: Exception) {
                            // Even an uncertain write is not retried/overwritten.
                            // A process restart may recover a committed old intent.
                            publish(failed = true)
                            throw HubOutboxException("storage_unavailable")
                        }
                        fresh
                    }
                    publish(busy = true)
                    leaseAcquired = true
                    selected
                }
            }
            currentCoroutineContext().ensureActive()
            val result = transport(intent)
            // Record receipt even if this UI was cancelled at the same instant.
            withContext(Dispatchers.IO + NonCancellable) {
                lock.withLock {
                    val confirmed = requireNotNull(record).copy(confirmed = true)
                    record = confirmed
                    try {
                        persistence.write(encode(confirmed))
                        persistence.write(encode(null))
                        record = null
                        // Only after the durable pending record is gone. Failure
                        // to clean an app-private payload must never replay it.
                        confirmed.attachment?.let { runCatching { discardAttachment(it) } }
                        // Keep the lease until finally; otherwise another caller
                        // could acquire it and be released by this call's cleanup.
                        publish(busy = true)
                    } catch (_: Exception) {
                        publish(busy = true, failed = true)
                        throw HubOutboxException("receipt_cleanup_failed")
                    }
                }
            }
            return result
        } finally {
            if (leaseAcquired) withContext(Dispatchers.IO + NonCancellable) {
                lock.withLock { publish(failed = mutableState.value.failed) }
            }
        }
    }

    /** Call only after explicit human confirmation; does not retract a server message. */
    suspend fun abandon() = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            loadLocked()
            if (mutableState.value.busy) throw HubOutboxException("send_in_progress")
            try {
                val discarded = record?.attachment
                persistence.write(encode(null))
                record = null
                discarded?.let { runCatching { discardAttachment(it) } }
                publish()
            } catch (_: Exception) {
                publish(failed = true)
                throw HubOutboxException("storage_unavailable")
            }
        }
    }

    private fun encode(value: HubPendingRecord?): ByteArray = buildJsonObject {
        put("schema", if (value?.attachment == null) "orbis.techhub.outbox/1" else "orbis.techhub.outbox/2")
        put("pending", value?.let { item -> buildJsonObject {
            put("scope", item.scope); put("room", item.room); put("text", item.text)
            put("key", item.key); put("createdAtMillis", item.createdAtMillis); put("confirmed", item.confirmed)
            item.attachment?.let { media -> put("attachment", buildJsonObject {
                put("payloadId", media.payloadId); put("filename", media.filename); put("mediaType", media.mediaType)
                put("size", media.size); put("sha256", media.sha256)
            }) }
        } } ?: JsonNull)
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= HUB_OUTBOX_LIMIT) }

    private fun decode(bytes: ByteArray): HubPendingRecord? {
        require(bytes.size <= HUB_OUTBOX_LIMIT)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        checkHubJson(text)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schema", "pending"))
        val version = root.getValue("schema").jsonPrimitive.content
        require(version in setOf("orbis.techhub.outbox/1", "orbis.techhub.outbox/2"))
        val pending = root.getValue("pending")
        if (pending == JsonNull) return null
        val item = pending.jsonObject
        val mediaVersion = version == "orbis.techhub.outbox/2"
        require(item.keys == setOf("scope", "room", "text", "key", "createdAtMillis", "confirmed") +
            if (mediaVersion) setOf("attachment") else emptySet())
        fun string(name: String): String = item.getValue(name).jsonPrimitive.let {
            require(it.isString); it.content
        }
        val scope = string("scope"); require(Regex("[0-9a-f]{64}").matches(scope))
        val room = string("room"); requireHubRoom(room)
        val content = string("text")
        require((mediaVersion || content.isNotBlank()) && content == content.trim() && content.codePointCount(0, content.length) <= 4000)
        val key = string("key"); val uuid = UUID.fromString(key)
        require(uuid.version() == 4 && uuid.variant() == 2 && uuid.toString() == key)
        val created = item.getValue("createdAtMillis").jsonPrimitive.let { require(!it.isString); it.long }
        require(created in 0..253_402_300_799_999L)
        val confirmed = item.getValue("confirmed").jsonPrimitive.let { require(!it.isString); it.boolean }
        val attachment = if (mediaVersion) item.getValue("attachment").jsonObject.let { media ->
            require(media.keys == setOf("payloadId", "filename", "mediaType", "size", "sha256"))
            fun field(name: String) = media.getValue(name).jsonPrimitive.let { require(it.isString); it.content }
            val size = media.getValue("size").jsonPrimitive.let { require(!it.isString); it.long }
            HubUploadAttachment(field("payloadId"), field("filename"), field("mediaType"), size, field("sha256"))
                .also(::validateHubUpload)
        } else null
        return HubPendingRecord(scope, room, content, key, created, confirmed, attachment)
    }
}
