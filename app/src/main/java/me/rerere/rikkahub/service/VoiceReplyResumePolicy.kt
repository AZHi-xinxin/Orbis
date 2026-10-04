package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Readiness only: never clears a checkpoint, resumes a queue, or replays an utterance. */
internal data class VoiceReplyResumeSnapshot(
    val activeCallMatches: Boolean,
    val sessionReady: Boolean,
    val queuePaused: Boolean,
    val queuedInputs: Int,
    val automaticInputs: Int,
    val generating: Boolean,
    val submitting: Boolean,
    val recoveryBlocked: Boolean,
    val manualWrite: Boolean,
    val pendingTool: Boolean,
    val checkpointExists: Boolean,
) {
    val ready: Boolean get() = activeCallMatches && sessionReady && !queuePaused &&
        queuedInputs == 0 && automaticInputs == 0 && !generating && !submitting &&
        !recoveryBlocked && !manualWrite && !pendingTool && !checkpointExists
}

internal fun List<UIMessage>.hasUnfinishedVoiceReplyTools(): Boolean = any { message ->
    message.parts.any { part ->
        when (part) {
            is UIMessagePart.Tool -> !part.isExecuted
            is UIMessagePart.ServerTool -> !part.isFinished
            else -> false
        }
    }
}
