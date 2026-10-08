package me.rerere.rikkahub.data.orbis

import java.util.UUID

enum class FreshHumanRecoveryStatus { NONE, ACTIVE, DETACHED, OWNER_CHANGED, UNAVAILABLE }

/** A separate durable restriction/explicit-human authorization, never evidence of remote idle. */
class FreshHumanInputRecoveryStore(private val storage: OrbisQueuePauseStore) {
    fun status(conversationId: String, assistantId: String): FreshHumanRecoveryStatus = try {
        val expected = reason(assistantId)
        when (val saved = storage.pauseReason(conversationId)) {
            null -> FreshHumanRecoveryStatus.NONE
            expected -> FreshHumanRecoveryStatus.ACTIVE
            reason(assistantId, DETACHED_PREFIX) -> FreshHumanRecoveryStatus.DETACHED
            else -> if (saved.startsWith(PREFIX) || saved.startsWith(DETACHED_PREFIX)) FreshHumanRecoveryStatus.OWNER_CHANGED
                else FreshHumanRecoveryStatus.UNAVAILABLE
        }
    } catch (_: Exception) { FreshHumanRecoveryStatus.UNAVAILABLE }

    /** First explicit recovery only. Never replace another owner or clear an existing fact. */
    fun authorize(conversationId: String, assistantId: String) {
        check(status(conversationId, assistantId) in setOf(FreshHumanRecoveryStatus.NONE, FreshHumanRecoveryStatus.ACTIVE))
        storage.pause(conversationId, reason(assistantId))
        check(status(conversationId, assistantId) == FreshHumanRecoveryStatus.ACTIVE)
    }

    /** Human acknowledgement removes only the old turn's local block, never proves remote idle. */
    fun detachPreviousTurn(conversationId: String, assistantId: String) {
        val detached = reason(assistantId, DETACHED_PREFIX)
        when (status(conversationId, assistantId)) {
            FreshHumanRecoveryStatus.NONE -> storage.pause(conversationId, detached)
            FreshHumanRecoveryStatus.ACTIVE -> check(
                storage.replaceReasonIfExpected(conversationId, reason(assistantId), detached)
            )
            FreshHumanRecoveryStatus.DETACHED -> Unit
            else -> error("fresh_human_recovery_owner_changed")
        }
        check(status(conversationId, assistantId) == FreshHumanRecoveryStatus.DETACHED)
    }

    /** Trusted current-lane idle plus the caller's final CAS must precede this durable release. */
    fun clearAfterConfirmedIdle(conversationId: String, assistantId: String) {
        when (status(conversationId, assistantId)) {
            FreshHumanRecoveryStatus.NONE -> return
            FreshHumanRecoveryStatus.ACTIVE -> check(storage.resumeIfReason(conversationId, reason(assistantId)))
            FreshHumanRecoveryStatus.DETACHED -> check(
                storage.resumeIfReason(conversationId, reason(assistantId, DETACHED_PREFIX))
            )
            else -> error("fresh_human_recovery_owner_changed")
        }
        check(status(conversationId, assistantId) == FreshHumanRecoveryStatus.NONE)
    }

    private fun reason(assistantId: String, prefix: String = PREFIX): String {
        require(UUID.fromString(assistantId).toString() == assistantId)
        return prefix + assistantId.replace("-", "")
    }

    companion object {
        const val FILE_NAME = "orbis-fresh-human-input-recovery-v1.json"
        private const val PREFIX = "fresh_human_only_"
        private const val DETACHED_PREFIX = "fresh_human_detached_"
    }
}
