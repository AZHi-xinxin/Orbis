package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.KnownEmptyCompletionFailure
import me.rerere.rikkahub.data.ai.hostToolFailure

/** Release only following independent items, never replay this failed request or any tool. */
internal fun canContinueAfterIndependentModelFailure(error: Throwable, before: List<UIMessage>,
    after: List<UIMessage>, recoveryBlocked: Boolean): Boolean {
    if (recoveryBlocked || after.any { message -> message.parts.any { it is UIMessagePart.ServerTool && !it.isFinished } ||
        message.getTools().any { tool ->
            !tool.isExecuted || tool.hostToolFailure()?.executionPerformed == null && tool.hostToolFailure() != null
        } }) return false
    // A local screen revocation happens BEFORE the next provider request. Completed tools are
    // durably retained by ChatService; unlike a transport failure there is no unknown new turn.
    if (error is KnownEmptyCompletionFailure ||
        error is me.rerere.rikkahub.data.ai.transformers.ScreenShareFrameRevokedException) return true
    val http = error as? HttpException ?: return false
    // A conflict explicitly means that another server-side generation/tool continuation may own
    // this channel. Do not turn a 409 into a burst of new requests or infer safety from error prose.
    if (http.httpStatus !in setOf(400, 401, 403, 404, 413, 422, 429, 500, 502, 503, 504)) return false
    val previous = before.associateBy { it.id }
    val changed = after.filter { previous[it.id] != it }
    return changed.all { message -> message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning } }
}
