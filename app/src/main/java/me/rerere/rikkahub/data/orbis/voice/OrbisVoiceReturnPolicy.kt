package me.rerere.rikkahub.data.orbis.voice

enum class OrbisVoiceReturnPhase { WAITING, FAILED, COMPLETE }

/**
 * Archive persistence, chat commit and observing the summary are separate asynchronous steps.
 * The caller supplies state for the same call, clears [loadFailed] after a successful reload,
 * and sets [summaryVisible] only for that call's summary (or when viewing another conversation).
 * An ending/retry operation may still carry an old error; a durable FAILED archive is not stale.
 * Observed durable completion wins over an earlier runtime error that caused the call to end.
 * This policy does not mutate a record, retry work, or treat UI folding as archive completion.
 */
fun orbisVoiceReturnPhase(
    record: OrbisVoiceCallRecord?,
    runtimeEnding: Boolean,
    runtimeError: String?,
    loadFailed: Boolean,
    summaryVisible: Boolean,
): OrbisVoiceReturnPhase = when {
    loadFailed || record?.archiveStatus == OrbisVoiceArchiveStatus.FAILED -> OrbisVoiceReturnPhase.FAILED
    record?.archiveStatus == OrbisVoiceArchiveStatus.READY && record.chatCommitted && summaryVisible ->
        OrbisVoiceReturnPhase.COMPLETE
    !runtimeEnding && (!record?.error.isNullOrBlank() || !runtimeError.isNullOrBlank()) -> OrbisVoiceReturnPhase.FAILED
    else -> OrbisVoiceReturnPhase.WAITING
}
