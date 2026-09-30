package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionCredential
import java.io.File
import java.nio.ByteBuffer

internal enum class ConsultationLocalState {
    UNAVAILABLE, NOT_FOUND, PREPARED, RUNNING, WAITING_APPROVAL, COMPLETE,
    SUBMISSION_EXHAUSTED, SUBMITTED, UNKNOWN, BUSY,
}

internal enum class ConsultationLocalPhase { ACTIVE, ARCHIVING }

/** A deliberately content-free projection. UNAVAILABLE / NOT_FOUND do not establish
 * that a request was never executed; RUNNING only records a durable execution intent. */
internal data class ConsultationLocalStatus(
    val state: ConsultationLocalState,
    val phase: ConsultationLocalPhase? = null,
    val submitAttempts: Int = 0,
    val toolInFlight: Boolean = false,
    val failureCode: String? = null,
    val httpStatus: Int? = null,
    val leaseExpired: Boolean = false,
)

private val LOCAL_FAILURE_CODES = setOf(
    "generation_cancelled_no_automatic_retry",
    "provider_request_failed_no_automatic_retry",
    "network_interrupted_no_automatic_retry",
    "generation_deadline_expired_no_automatic_retry",
    "empty_or_oversized_final_no_automatic_retry",
    "binding_changed_no_automatic_retry",
    "execution_incomplete_no_automatic_retry",
    "gateway_busy_no_generation_no_automatic_retry",
    "submission_rejected_keep_checkpoint",
    "process_interrupted_evidence_recovered_no_automatic_retry",
    "session_ended_archive_without_active_replay",
    "generation_not_completed_no_automatic_retry",
    "unexecuted_tool_no_automatic_retry",
    "missing_terminal_assistant_no_automatic_retry",
    "tool_call_tail_no_automatic_retry",
    "terminal_response_unverified_no_automatic_retry",
    "empty_final_text_no_automatic_retry",
    "oversized_final_text_no_automatic_retry",
)

/** No messages, final text, tool arguments, model reasoning, tokens or error details
 * are materialized as model objects. Unknown checkpoint fields remain private. */
@Serializable
private class LocalCheckpointMetadata(
    val requestId: String,
    val sessionId: String,
    val phase: String,
    val assistantId: String,
    val state: String = "PREPARED",
    val submitAttempts: Int = 0,
    val toolInFlight: String? = null,
    val failure: String? = null,
    val httpStatus: Int? = null,
    val conversationTurn: ConsultationConversationTurn? = null,
)

private val localMetadataJson = Json { ignoreUnknownKeys = true }
private val localIdentifier = Regex("[a-f0-9]{32}")

/** Called only while ConsultationRuntimeStore holds the same mutex used by config,
 * checkpoint and head writers. Never enumerate unrelated sessions or create/recover
 * files. A missing checkpoint behind an existing head is unavailable, not empty. */
internal fun readConsultationLocalStatus(
    directory: File,
    sessionId: String,
    runtime: ConsultationRuntimeConfig,
    human: OrbisConnectionCredential,
    nowMillis: Long,
): ConsultationLocalStatus {
    val unavailable = ConsultationLocalStatus(ConsultationLocalState.UNAVAILABLE)
    return try {
        require(localIdentifier.matches(sessionId))
        require(runtime.baseUrl.isNotBlank() && runtime.baseUrl == human.baseUrl)
        require(runtime.humanToken.isNotBlank() && runtime.humanToken == human.token)
        require(runtime.assistantId.isNotBlank() && runtime.subject.isNotBlank() && runtime.revision >= 0)
        require(nowMillis >= 0)
        check(directory.canonicalFile == directory.absoluteFile)
        if (!directory.exists()) return ConsultationLocalStatus(ConsultationLocalState.NOT_FOUND)
        check(directory.isDirectory)
        val conversationId = consultationConversationId(sessionId, runtime.subject).toString()
        val head = File(directory, "conversation-$conversationId.head")
        val requestId = readExistingLocalFile(head, directory, 128)
            ?: return ConsultationLocalStatus(ConsultationLocalState.NOT_FOUND)
        require(localIdentifier.matches(requestId))
        val checkpoint = File(directory, "$requestId.json")
        val encoded = readExistingLocalFile(checkpoint, directory, 2 * 1024 * 1024) ?: return unavailable
        val value = localMetadataJson.decodeFromString<LocalCheckpointMetadata>(encoded)
        val turn = value.conversationTurn ?: return unavailable
        require(value.requestId == requestId && value.sessionId == sessionId && value.assistantId == runtime.assistantId)
        require(turn.requestId == requestId && turn.sessionId == sessionId && turn.assistantId == runtime.assistantId)
        require(turn.subject == runtime.subject && turn.configRevision == runtime.revision && turn.phase == value.phase)
        require(turn.conversationId.toString() == conversationId && turn.expiresAtMillis > 0)
        require(value.submitAttempts >= 0)
        val phase = ConsultationLocalPhase.entries.singleOrNull { it.name == value.phase } ?: return unavailable
        val state = ConsultationLocalState.entries.singleOrNull {
            it.name == value.state && it !in setOf(ConsultationLocalState.UNAVAILABLE,
                ConsultationLocalState.NOT_FOUND, ConsultationLocalState.SUBMISSION_EXHAUSTED)
        } ?: return unavailable
        // Also reject an interrupted external write or pointer replacement after reading.
        check(readExistingLocalFile(head, directory, 128) == requestId)
        checkLocalFilePath(checkpoint, directory)
        ConsultationLocalStatus(
            state = if (state == ConsultationLocalState.COMPLETE && value.submitAttempts >= 3)
                ConsultationLocalState.SUBMISSION_EXHAUSTED else state,
            phase = phase,
            submitAttempts = value.submitAttempts,
            toolInFlight = value.toolInFlight != null,
            failureCode = value.failure?.let { if (it in LOCAL_FAILURE_CODES) it else "unclassified_local_failure" },
            httpStatus = value.httpStatus?.takeIf { it in 100..599 },
            leaseExpired = nowMillis >= turn.expiresAtMillis,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable
    }
}

private fun checkLocalFilePath(file: File, directory: File) {
    check(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory.canonicalFile)
    check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
}

private fun readExistingLocalFile(file: File, directory: File, limit: Int): String? {
    checkLocalFilePath(file, directory)
    if (!file.exists()) return null
    check(file.isFile)
    val bytes = file.inputStream().use { it.cloudReadBounded(limit) }
    return try {
        checkLocalFilePath(file, directory)
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } finally { bytes.fill(0) }
}
