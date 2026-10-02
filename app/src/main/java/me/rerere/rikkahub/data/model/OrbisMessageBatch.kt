package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import kotlin.uuid.Uuid

enum class OrbisMessageBatchOperation { DELETE, ARCHIVE }

/** Local immutable confirmation only. Never persist this preview in saved-state or send it to a model. */
data class OrbisMessageBatchPreview(
    val conversationId: Uuid,
    val assistantId: Uuid,
    val compactionEpoch: Long,
    val expectedFingerprint: String,
    val operation: OrbisMessageBatchOperation,
    val requestedNodeIds: Set<Uuid>,
    val affectedNodeIds: Set<Uuid>,
    val alternativeCount: Int,
    val voiceCallCount: Int,
    val beforeTokens: Long,
    val afterTokens: Long,
    val replacementNodes: List<MessageNode>,
    val archiveMetadata: OrbisCompactionMetadata?,
) {
    val affectedCount: Int get() = affectedNodeIds.size
    val additionalCount: Int get() = affectedCount - requestedNodeIds.size
}

data class OrbisMessageBatchResult(
    val operation: OrbisMessageBatchOperation,
    val affectedCount: Int,
    val archiveId: Uuid? = null,
)

data class OrbisMessageBatchCommit(val conversation: Conversation, val result: OrbisMessageBatchResult)

private const val BATCH_ARCHIVE_MARKER = "orbis_message_batch_archive"

private fun batchArchiveNotice(count: Int) =
    "[Orbis 人工批量归档记录；不是 AI 摘要]\n" +
        "人类已将本窗口的 $count 条记录移出活动上下文，完整原文另存于独立的“原文存档”窗口。" +
        "被归档的细节不在当前活动上下文中；本次没有生成或补写摘要。"

/** Whole nodes, including every alternative, travel together. Cross-node protocol/call groups
 * expand selection visibly; incomplete protocols are rejected rather than repaired or executed.
 */
@Suppress("DEPRECATION")
internal fun messageBatchClosure(conversation: Conversation, requested: Set<Uuid>): Set<Uuid> {
    require(!conversation.isConsultation) { "咨询室记录不能从普通消息预览批量整理。" }
    val nodes = conversation.messageNodes
    require(nodes.map { it.id }.distinct().size == nodes.size &&
        nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices }) { "消息或分支结构无效，原记录未改变。" }
    val nodeIds = nodes.map { it.id }.toSet()
    require(requested.isNotEmpty() && requested.all { it in nodeIds }) { "请选择当前窗口中仍然存在的消息。" }
    require(nodes.flatMap { it.messages }.map { it.id }.let { it.distinct().size == it.size }) {
        "消息身份重复，原记录未改变。"
    }
    val groups = mutableMapOf<String, MutableSet<Uuid>>()
    val calls = mutableSetOf<String>()
    val results = mutableSetOf<String>()
    val combined = mutableSetOf<String>()
    nodes.forEach { node ->
        node.messages.forEach { message ->
            message.orbisVoiceCallId?.let { groups.getOrPut("voice:$it") { mutableSetOf() }.add(node.id) }
            message.parts.forEach { part ->
                val toolId = when (part) {
                    is UIMessagePart.Tool -> {
                        require(part.isExecuted) { "存在未完成或待批准的工具，请先处理工具再整理。" }
                        combined.add(part.toolCallId); part.toolCallId
                    }
                    is UIMessagePart.ToolCall -> { calls.add(part.toolCallId); part.toolCallId }
                    is UIMessagePart.ToolResult -> { results.add(part.toolCallId); part.toolCallId }
                    is UIMessagePart.ServerTool -> {
                        require(part.isFinished) { "服务端工具尚未完成，原记录未改变。" }
                        part.toolCallId
                    }
                    else -> null
                }
                toolId?.let {
                    require(it.isNotBlank()) { "工具记录缺少身份，原记录未改变。" }
                    groups.getOrPut("tool:$it") { mutableSetOf() }.add(node.id)
                }
            }
        }
    }
    require(calls.all { it in results || it in combined } && results.all { it in calls || it in combined }) {
        "存在不完整的工具调用与结果，原记录未改变。"
    }
    // The currently selected request path must itself be valid, not merely paired by an unused branch.
    requireMessageBatchProtocol(conversation.currentMessages)
    val selected = requested.toMutableSet()
    val groupsByNode = mutableMapOf<Uuid, MutableList<Set<Uuid>>>()
    groups.values.forEach { group -> group.forEach { id -> groupsByNode.getOrPut(id) { mutableListOf() }.add(group) } }
    val queue = ArrayDeque(selected)
    val visitedGroups = mutableSetOf<Set<Uuid>>()
    while (queue.isNotEmpty()) {
        groupsByNode[queue.removeFirst()].orEmpty().forEach { group ->
            if (visitedGroups.add(group)) group.forEach { id -> if (selected.add(id)) queue.addLast(id) }
        }
    }
    requireMessageBatchProtocol(nodes.filterNot { it.id in selected }.map { it.currentMessage })
    return nodes.map { it.id }.filterTo(linkedSetOf()) { it in selected }
}

@Suppress("DEPRECATION")
private fun requireMessageBatchProtocol(messages: List<UIMessage>) {
    val parts = messages.flatMap { it.parts }
    val combined = parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolCallId }.toSet()
    val calls = parts.filterIsInstance<UIMessagePart.ToolCall>().map { it.toolCallId }.toSet()
    val results = parts.filterIsInstance<UIMessagePart.ToolResult>().map { it.toolCallId }.toSet()
    require(calls.all { it in results || it in combined } && results.all { it in calls || it in combined }) {
        "当前选中分支的工具记录不完整，原记录未改变。"
    }
}

fun prepareOrbisMessageBatch(
    conversation: Conversation,
    requestedNodeIds: Set<Uuid>,
    operation: OrbisMessageBatchOperation,
): OrbisMessageBatchPreview {
    val requested = requestedNodeIds.toSet()
    val affected = messageBatchClosure(conversation, requested)
    val removed = conversation.messageNodes.filter { it.id in affected }
    val retained = conversation.messageNodes.filterNot { it.id in affected }
        .map { node -> node.copy(messages = node.messages.map { it.copy(usageContextInvalidated = true) }) }
    val before = estimateCompactionTokens(conversation.currentMessages)
    val notice = if (operation == OrbisMessageBatchOperation.ARCHIVE) {
        val text = batchArchiveNotice(affected.size)
        UIMessage.assistant(text).copy(parts = listOf(UIMessagePart.Text(text, buildJsonObject {
            put(COMPACTION_SUMMARY_MARKER, true)
            put(ORBIS_MANUAL_CONTEXT_MARKER, true)
            put(BATCH_ARCHIVE_MARKER, true)
            put("human_authored", true)
            put("generated_summary", false)
        }))).toMessageNode()
    } else null
    val replacement = listOfNotNull(notice) + retained
    val after = estimateCompactionTokens(replacement.map { it.currentMessage })
    val metadata = notice?.let { OrbisCompactionMetadata(it.currentMessage.id, it.currentMessage.toText(), retained.size,
        before, after, "manual_selection_original_text_estimate", "manual_selection_projected_text_estimate") }
    return OrbisMessageBatchPreview(conversation.id, conversation.assistantId, conversation.compactionEpoch,
        manualContextFingerprint(conversation), operation, requested, affected,
        removed.sumOf { it.messages.size - 1 },
        removed.flatMap { it.messages }.mapNotNull { it.orbisVoiceCallId }.distinct().size,
        before, after, replacement, metadata)
}

/** Rebuild selection and retained nodes; caller-provided preview contents cannot smuggle edits. */
internal fun validateOrbisMessageBatch(expected: Conversation, preview: OrbisMessageBatchPreview) {
    require(expected.id == preview.conversationId && expected.assistantId == preview.assistantId &&
        expected.compactionEpoch == preview.compactionEpoch &&
        manualContextFingerprint(expected) == preview.expectedFingerprint) { "对话已变化，请重新选择并确认；本次未修改。" }
    val affected = messageBatchClosure(expected, preview.requestedNodeIds)
    val removed = expected.messageNodes.filter { it.id in affected }
    require(affected == preview.affectedNodeIds &&
        removed.sumOf { it.messages.size - 1 } == preview.alternativeCount &&
        removed.flatMap { it.messages }.mapNotNull { it.orbisVoiceCallId }.distinct().size == preview.voiceCallCount) {
        "批量范围已变化，请重新预览。"
    }
    val retained = expected.messageNodes.filterNot { it.id in affected }
        .map { node -> node.copy(messages = node.messages.map { it.copy(usageContextInvalidated = true) }) }
    if (preview.operation == OrbisMessageBatchOperation.DELETE) {
        require(preview.archiveMetadata == null && preview.replacementNodes == retained) { "删除预览不一致，原记录未改变。" }
    } else {
        val first = preview.replacementNodes.firstOrNull() ?: error("归档说明缺失。")
        val metadata = preview.archiveMetadata ?: error("归档元信息缺失。")
        require(first.messages.size == 1 && first.selectIndex == 0) { "归档说明结构无效。" }
        val message = first.currentMessage
        val part = message.parts.singleOrNull() as? UIMessagePart.Text ?: error("归档说明不是纯文本。")
        val text = batchArchiveNotice(affected.size)
        val expectedMetadata = buildJsonObject {
            put(COMPACTION_SUMMARY_MARKER, true); put(ORBIS_MANUAL_CONTEXT_MARKER, true)
            put(BATCH_ARCHIVE_MARKER, true); put("human_authored", true); put("generated_summary", false)
        }
        require(message.role == MessageRole.ASSISTANT && message.usage == null && !message.usageContextInvalidated && message.orbisEvent == null &&
            message.orbisQuote == null && message.orbisUserMessageTime == null &&
            message.translation == null && message.modelId == null && message.finishedAt == null &&
            !message.isSynthetic && message.toolRecordRevision == 0L && message.toolRecordsUpdatedAt == null &&
            message.orbisVoiceCallId == null && message.orbisVoiceCallKind == null &&
            message.deletedToolRecords.isEmpty() && message.annotations.isEmpty() &&
            part.text == text && part.metadata == expectedMetadata &&
            expected.messageNodes.none { it.id == first.id || it.messages.any { old -> old.id == message.id } } &&
            preview.replacementNodes.drop(1) == retained && metadata.summaryMessageId == message.id &&
            metadata.summaryText == text && metadata.keepRecent == retained.size &&
            metadata.beforeTokens == preview.beforeTokens && metadata.afterTokens == preview.afterTokens &&
            metadata.beforeBasis == "manual_selection_original_text_estimate" &&
            metadata.afterBasis == "manual_selection_projected_text_estimate") { "归档预览不一致，原记录未改变。" }
    }
    require(preview.beforeTokens == estimateCompactionTokens(expected.currentMessages) &&
        preview.afterTokens == estimateCompactionTokens(preview.replacementNodes.map { it.currentMessage })) { "预览用量已变化。" }
}

/** Selecting all means the current filter, never invisible rows or stale IDs from another page. */
internal fun selectAllMessagePreviewRows(filteredIds: List<Uuid>): Set<Uuid> = filteredIds.toSet()
