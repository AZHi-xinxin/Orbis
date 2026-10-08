package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus

/** A permitted human answer never authorizes another derived/background model request. */
internal fun freshHumanModeAllowsDerivedRequests(status: FreshHumanRecoveryStatus): Boolean =
    status == FreshHumanRecoveryStatus.NONE

/** Detaching an old turn allows ordinary new input, not replay or automatic work from that turn. */
internal fun FreshHumanRecoveryStatus.requiresFreshPermit(): Boolean = when (this) {
    FreshHumanRecoveryStatus.NONE, FreshHumanRecoveryStatus.DETACHED -> false
    FreshHumanRecoveryStatus.ACTIVE, FreshHumanRecoveryStatus.OWNER_CHANGED,
    FreshHumanRecoveryStatus.UNAVAILABLE -> true
}

/** RAM-only routing snapshot. Full providers also bind a live call's pinned model. Never log it. */
internal data class FreshHumanInputScope(
    val conversationId: Uuid,
    val model: Model,
    val providers: List<ProviderSetting>,
) {
    override fun toString() = "FreshHumanInputScope(redacted)"
}

/** RAM-only, host-issued permission for ONE explicitly new human input; never serialized. */
class FreshHumanInputPermit internal constructor(
    internal val epoch: Any,
    internal val messageId: Uuid,
    internal val voiceCallId: String?,
    internal val voiceCallKind: String?,
) {
    @Volatile internal var completed = false
    override fun toString() = "FreshHumanInputPermit(redacted)"
}

/** Replacing this gate invalidates delayed pre-recovery speech and every old queued permission. */
internal class FreshHumanInputGate(val assistantId: Uuid, val scope: FreshHumanInputScope? = null) {
    private val epoch = Any()

    fun issue(messageId: Uuid, voiceCallId: String? = null, voiceCallKind: String? = null): FreshHumanInputPermit {
        require((voiceCallId == null && voiceCallKind == null) || (voiceCallId != null && voiceCallKind == "turn"))
        return FreshHumanInputPermit(epoch, messageId, voiceCallId, voiceCallKind)
    }

    fun permits(message: QueuedMessage, currentAssistant: Uuid, currentScope: FreshHumanInputScope? = null): Boolean {
        val permit = message.freshHumanInputPermit ?: return false
        return currentAssistant == assistantId && currentScope == scope && permit.epoch === epoch && !permit.completed &&
            message.id == permit.messageId && message.voiceCallId == permit.voiceCallId &&
            message.voiceCallKind == permit.voiceCallKind && message.orbisEventId == null &&
            message.recoveryHeldReason == null && !message.isEditing &&
            ((message.voiceCallId == null && message.voiceCallKind == null) ||
                (message.voiceCallId != null && message.voiceCallKind == "turn"))
    }
}
