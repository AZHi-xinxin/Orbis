package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessagePart

/** A paused batch also contains Auto/read-only peers: they must not be dropped on resume. */
internal fun resumedApprovalBatch(tools: List<UIMessagePart.Tool>): List<UIMessagePart.Tool> {
    if (tools.any { it.isPending } || tools.none { it.canResumeExecution }) return emptyList()
    return tools.filterNot { it.isExecuted }
}
