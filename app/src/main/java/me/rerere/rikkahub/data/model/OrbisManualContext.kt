package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import me.rerere.rikkahub.data.ai.compaction.wholeCompactionToolTailStart
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

const val ORBIS_MANUAL_CONTEXT_MARKER = "orbis_manual_context"
const val ORBIS_MANUAL_CONTEXT_SUMMARY_MAX_CHARS = 20_000

/** An immutable local preview. Constructing it does not save, send, or execute anything. */
data class OrbisManualContextPreview(
    val conversationId: Uuid,
    val compactionEpoch: Long,
    val expectedFingerprint: String,
    val requestedArchiveCount: Int,
    val requestedSummary: String,
    val archivedCount: Int,
    val keptCount: Int,
    val beforeTokens: Long,
    val afterTokens: Long,
    val summaryText: String,
    val replacementNodes: List<MessageNode>,
    val metadata: OrbisCompactionMetadata,
) {
    val additionalProtocolMessages: Int get() = requestedArchiveCount - archivedCount
}

data class OrbisManualContextCommit(val archiveId: Uuid, val commit: OrbisCompactionCommit)

/** Persisted timestamps are milliseconds; ignore only sub-millisecond precision and transient UI fields. */
fun manualContextFingerprint(conversation: Conversation): String = orbisCompactionSummaryHash(
    JsonInstant.encodeToString(conversation.copy(
        createAt = Instant.ofEpochMilli(conversation.createAt.toEpochMilli()),
        updateAt = Instant.ofEpochMilli(conversation.updateAt.toEpochMilli()),
        customSystemPrompt = conversation.customSystemPrompt?.takeUnless { it.isEmpty() },
        workspaceCwd = conversation.workspaceCwd?.takeUnless { it.isEmpty() },
    )),
)

/** Select the prefix [1, archiveThroughCount], expanding the retained suffix for tool dependencies. */
fun prepareOrbisManualContext(
    conversation: Conversation,
    archiveThroughCount: Int,
    summary: String = "",
): OrbisManualContextPreview {
    require(summary.length <= ORBIS_MANUAL_CONTEXT_SUMMARY_MAX_CHARS) { "人工整理说明最多 20000 个字符。" }
    require(archiveThroughCount in 1..conversation.messageNodes.size) { "请选择从第 1 条开始的有效归档范围。" }
    requireCompleteManualContextTools(conversation.currentMessages)
    val start = wholeCompactionToolTailStart(conversation.currentMessages, archiveThroughCount)
    require(start > 0) { "完整工具组需要保留全部记录；请选择更靠后的归档终点。" }
    val kept = conversation.messageNodes.drop(start).map { node ->
        node.copy(messages = node.messages.map { it.copy(usage = null) })
    }
    val text = if (summary.isBlank()) {
        "[Orbis 人工上下文整理说明；不是 AI 撰写的摘要]\n" +
            "人类已将本窗口开头的 $start 条记录移出活动上下文，完整原文另存于独立的“原文存档”窗口。" +
            "本段仅说明整理操作，没有生成或补写历史摘要；被归档的细节未包含在当前上下文中。"
    } else {
        "[Orbis 人工上下文整理说明；以下内容由人类提供，不是 AI 自写摘要]\n" + summary
    }
    val summaryMessage = UIMessage.assistant(text).copy(parts = listOf(UIMessagePart.Text(text, buildJsonObject {
        put(COMPACTION_SUMMARY_MARKER, true)
        put(ORBIS_MANUAL_CONTEXT_MARKER, true)
        put("human_authored", true)
        put("generated_summary", false)
    })))
    val replacement = listOf(summaryMessage.toMessageNode()) + kept
    val before = estimateCompactionTokens(conversation.currentMessages)
    val after = estimateCompactionTokens(replacement.map { it.currentMessage })
    return OrbisManualContextPreview(
        conversation.id, conversation.compactionEpoch, manualContextFingerprint(conversation),
        archiveThroughCount, summary.trim(), start, kept.size, before, after, text, replacement,
        OrbisCompactionMetadata(summaryMessage.id, text, kept.size, before, after,
            "manual_prefix_original_text_estimate", "manual_prefix_projected_text_estimate"),
    )
}

/** Recheck the exact source and suffix rather than trusting caller-supplied replacement nodes. */
internal fun validateManualContextReplacement(
    expected: Conversation,
    replacementNodes: List<MessageNode>,
    metadata: OrbisCompactionMetadata,
) {
    val start = expected.messageNodes.size - metadata.keepRecent
    require(start in 1..expected.messageNodes.size &&
        wholeCompactionToolTailStart(expected.currentMessages, start) == start) { "人工整理范围或工具边界已变化。" }
    requireCompleteManualContextTools(expected.currentMessages)
    val first = replacementNodes.firstOrNull() ?: error("缺少人工整理说明。")
    require(first.messages.size == 1 && first.selectIndex == 0) { "人工整理说明格式无效。" }
    val message = first.currentMessage
    val part = message.parts.singleOrNull() as? UIMessagePart.Text ?: error("人工整理说明必须为纯文本。")
    require(message.role == MessageRole.ASSISTANT && message.id == metadata.summaryMessageId && message.usage == null &&
        part.metadata?.get(COMPACTION_SUMMARY_MARKER) == JsonPrimitive(true) &&
        part.metadata?.get(ORBIS_MANUAL_CONTEXT_MARKER) == JsonPrimitive(true) &&
        part.metadata?.get("human_authored") == JsonPrimitive(true) &&
        part.metadata?.get("generated_summary") == JsonPrimitive(false) &&
        part.text == metadata.summaryText && part.text.length <= ORBIS_MANUAL_CONTEXT_SUMMARY_MAX_CHARS + 256) {
        "人工整理说明或来源标记无效。"
    }
    require(expected.messageNodes.none { it.id == first.id || it.messages.any { old -> old.id == message.id } }) {
        "人工整理说明不得复用原文身份。"
    }
    val tail = expected.messageNodes.drop(start).map { node -> node.copy(messages = node.messages.map { it.copy(usage = null) }) }
    require(replacementNodes.drop(1) == tail) { "保留原文或分支已变化，未整理。" }
    require(metadata.beforeTokens == estimateCompactionTokens(expected.currentMessages) &&
        metadata.afterTokens == estimateCompactionTokens(replacementNodes.map { it.currentMessage })) { "人工整理预览已变化。" }
}

@Suppress("DEPRECATION")
private fun requireCompleteManualContextTools(messages: List<UIMessage>) {
    val parts = messages.flatMap { it.parts }
    val tools = parts.filterIsInstance<UIMessagePart.Tool>()
    val calls = parts.filterIsInstance<UIMessagePart.ToolCall>().map { it.toolCallId }.toSet()
    val results = parts.filterIsInstance<UIMessagePart.ToolResult>().map { it.toolCallId }.toSet()
    require(tools.all { it.isExecuted } && calls.all { it in results || tools.any { tool -> tool.toolCallId == it } } &&
        results.all { it in calls || tools.any { tool -> tool.toolCallId == it } }) {
        "仍有未完成、待批准或不完整的工具组，请先处理工具，再整理上下文。"
    }
}

/** Independent identities and removed host bindings prevent archival use from updating old receipts
 * or voice records. Message parts, tool call IDs, original timestamps, usage and branches stay intact.
 */
internal fun createManualContextArchive(original: Conversation, at: Instant): Conversation = original.copy(
    id = Uuid.random(),
    title = original.title.ifBlank { "未命名窗口" } + " · 原文存档",
    messageNodes = original.messageNodes.map { node -> node.copy(
        id = Uuid.random(), messages = node.messages.map { it.copy(id = Uuid.random(),
            orbisEvent = null, orbisVoiceCallId = null, orbisVoiceCallKind = null) }, isFavorite = false,
    ) },
    chatSuggestions = emptyList(), isPinned = false, createAt = at, updateAt = at,
    compactionEpoch = 0, newConversation = false,
)
