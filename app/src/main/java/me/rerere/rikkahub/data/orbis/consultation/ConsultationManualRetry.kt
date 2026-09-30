package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.integration.*

internal data class ConsultationRetryPreview(val status: ConsultationRetryStatus,
    val checkpointHash: String, val configRevision: Long, val assistantId: String)

/** Read-only preflight. Only the failed speaker's bound device may request retry. */
internal class ConsultationManualRetry(private val store: ConsultationRuntimeStore,
    private val client: OrbisConsultationClient,
    private val recoverLocalEvidence: suspend (String) -> Unit = {}) {
    private val json = Json { encodeDefaults = true }
    private suspend fun binding(auth: ConsultationAuthorization) = store.config().also {
        check(it.enabled && it.assistantId.isNotBlank() && it.humanToken == auth.credential.token &&
            consultationBase(it.baseUrl) == consultationBase(auth.credential.baseUrl)) { "retry_device_binding_mismatch" }
    }
    suspend fun preview(auth: ConsultationAuthorization, sid: String): ConsultationRetryPreview {
        consultationFeature.requireEnabled()
        val config = binding(auth)
        recoverLocalEvidence(sid)
        val status = client.retryStatus(auth, sid)
        check(status.available && status.speaker == config.subject) { "retry_on_speaking_device_or_unavailable" }
        val saved = store.read(status.requestId) ?: error("retry_checkpoint_missing")
        check(saved.sessionId == sid && saved.assistantId == config.assistantId && canManuallyRetryConsultation(saved)) {
            "retry_has_submission_tool_or_unresolved_execution"
        }
        return ConsultationRetryPreview(status, consultationDigest(json.encodeToString(saved)), config.revision, config.assistantId)
    }
    suspend fun confirmOnce(auth: ConsultationAuthorization, expected: ConsultationRetryPreview): String {
        consultationFeature.requireEnabled()
        val config = binding(auth)
        check(config.revision == expected.configRevision && config.assistantId == expected.assistantId)
        val saved = store.read(expected.status.requestId) ?: error("retry_checkpoint_missing")
        check(saved.sessionId == expected.status.sessionId && canManuallyRetryConsultation(saved) &&
            consultationDigest(json.encodeToString(saved)) == expected.checkpointHash) { "retry_checkpoint_changed" }
        val nonce = store.retryConsent(saved.sessionId, saved.requestId, expected.checkpointHash)
        // No model invocation and no local checkpoint reset. The existing foreground
        // service sees the NEW delivery ID. The old ID/file can never restart.
        return client.retryOnce(auth, expected.status, nonce)
    }
}
