package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException

/** Automatic camera ticks may be dropped, unlike accepted human speech. Unknown tools still hold. */
internal fun canContinueAfterVideoFrameFailure(error: Throwable, before: List<UIMessage>,
    after: List<UIMessage>, recoveryBlocked: Boolean): Boolean {
    if (canContinueAfterIndependentModelFailure(error, before, after, recoveryBlocked)) return true
    if (recoveryBlocked || error is HttpException ||
        error !is CancellationException && error !is java.io.IOException) return false
    // A host interruption receipt has output (isExecuted), but still does not prove
    // whether its tool ran. Preserve that uncertainty even in unchanged history.
    if (after.hasUnfinishedVoiceReplyTools() ||
        after.any { it.parts.any { part -> part is UIMessagePart.ServerTool } }) return false
    val previous = before.associateBy { it.id }
    return after.filter { previous[it.id] != it }.all { message ->
        message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning }
    }
}
