package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import kotlin.uuid.Uuid

/** An approval belongs to the request that preceded this tool-bearing reply. */
internal fun toolApprovalSourceMessageIds(messages: List<UIMessage>, pendingOwnerId: Uuid): Set<Uuid> {
    require(messages.any { it.id == pendingOwnerId })
    return messages.takeWhile { it.id != pendingOwnerId }.map { it.id }.toSet()
}

/** Normal sends and interrupted-turn continuation must use this identical catalogue path.
 * Preparation is local; it never waits for an old invocation and never turns a timeout into
 * an empty directory. Cancellation belongs only to this request, not the next human send.
 */
internal suspend fun conversationToolSet(
    messages: List<UIMessage>,
    compaction: ConversationCompactionControl?,
    omitLocalMemory: Boolean = false,
    create: suspend (sourceMessageIds: Set<Uuid>) -> List<Tool>,
): List<Tool> {
    val tools = create(messages.map { it.id }.toSet())
    val contextTools = compaction?.tools().orEmpty()
    return refreshOrbisHelpForTools(tools.filterNot { tool ->
        contextTools.any { it.name == tool.name } || (omitLocalMemory && tool.name == ORBIS_MEMORY_TOOL)
    } + contextTools)
}

/** Partial regeneration keeps the same advertised schemas as approval-resume, but cannot
 * compact the full conversation. The compact tool's ordinary executor returns a refusal;
 * only the generation loop with a non-null execution control can commit a replacement. */
internal data class GenerationCompactionScope(
    val catalog: ConversationCompactionControl,
    val execution: ConversationCompactionControl?,
)
internal fun generationCompactionScope(control: ConversationCompactionControl, partial: Boolean) =
    GenerationCompactionScope(control, control.takeUnless { partial })
