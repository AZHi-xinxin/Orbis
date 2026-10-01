package me.rerere.rikkahub.data.ai.compaction

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutDeletedToolRecordData
import me.rerere.rikkahub.data.ai.copyGenerationMessage
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.model.orbisCompactionSummaryHash
import me.rerere.rikkahub.data.model.withVoiceNoteTranscripts
import kotlin.time.Clock
import kotlin.uuid.Uuid

const val COMPACT_TOOL_NAME = "compact"
const val COMPACTION_SUMMARY_MARKER = "orbis_compaction_summary"
const val COMPACTION_REMINDER_MARKER = "orbis_compaction_reminder"

// UIMessage/Reasoning timestamp defaults evaluate Clock.System.now() inside their generated
// serializers. Omitting defaults can therefore add/remove a field when the clock advances,
// even for the exact same immutable message. Estimation must encode a stable full snapshot;
// this private encoder does not change provider requests or persisted message serialization.
private val compactionEstimateJson = Json { encodeDefaults = true }

/** Conservative text reference, not a tokenizer: ASCII ~4 chars/token, other codepoints ~1/token. */
fun estimateCompactionTokens(messages: List<UIMessage>): Long = messages.sumOf { message ->
    // Use the same reversible voice-note projection as the provider request, including nested
    // tool results. Audio paths, original ASR and playback metadata are not model context.
    val encoded = compactionEstimateJson.encodeToString(UIMessage.serializer(),
        message.withoutDeletedToolRecordData().withVoiceNoteTranscripts())
    estimateCompactionTextTokens(encoded) + 8L
}

fun estimateCompactionTextTokens(text: String): Long {
    var ascii = 0L
    var nonAscii = 0L
    var offset = 0
    while (offset < text.length) {
        val codepoint = Character.codePointAt(text, offset)
        if (codepoint < 128) ascii++ else nonAscii++
        offset += Character.charCount(codepoint)
    }
    return (ascii + 3) / 4 + nonAscii
}

data class CompactionContextEstimate(val tokens: Long, val basis: String)

/** Old retained usage cannot survive a compaction boundary as the new context's measurement. */
fun estimateCurrentContext(
    messages: List<UIMessage>,
    modelId: Uuid? = null,
    fixedOverheadTokens: Long = 0,
): CompactionContextEstimate {
    val summary = messages.firstOrNull()?.takeIf { it.isCompactionSummary() }
    val latestIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT && !it.isSynthetic }
    val latest = messages.getOrNull(latestIndex)
    val usage = latest?.usage
    val newEnough = summary == null || latest?.finishedAt?.let { it >= summary.createdAt } == true
    val lastToolEdit = messages.mapNotNull { it.toolRecordsUpdatedAt }.maxOrNull()
    val afterToolEdits = lastToolEdit == null || latest?.finishedAt?.let { it >= lastToolEdit } == true
    if (latest != null && latest.finishedAt != null && latest.modelId != null &&
        (modelId == null || modelId == latest.modelId) && newEnough && afterToolEdits && usage != null &&
        usage.promptTokens > 0 && usage.completionTokens >= 0 && usage.cachedTokens in 0..usage.promptTokens) {
        val generated = usage.completionTokens.takeIf { it > 0 }?.toLong() ?: estimateCompactionTokens(listOf(latest))
        val added = estimateCompactionTokens(messages.drop(latestIndex + 1))
        return CompactionContextEstimate(
            usage.promptTokens.toLong() + generated + added,
            "previous_provider_prompt_usage_plus_reply_and_new_messages_estimate; not exact next request",
        )
    }
    return CompactionContextEstimate(estimateCompactionTokens(messages) + fixedOverheadTokens,
        "estimated_text_and_tool_payload; media/provider tokenization may differ")
}

fun UIMessage.isCompactionSummary(): Boolean = role == MessageRole.ASSISTANT &&
    parts.any { it.metadata?.get(COMPACTION_SUMMARY_MARKER) == JsonPrimitive(true) }

fun UIMessage.isCompactionReminder(): Boolean = parts.any {
    it.metadata?.get(COMPACTION_REMINDER_MARKER) == JsonPrimitive(true)
}

data class PreparedCompaction(
    val summary: UIMessage,
    val keepRecent: Int,
    val available: Int,
    val sourceMessageId: Uuid?,
)

data class CompactionReplacement(
    val messages: List<UIMessage>,
    val summary: UIMessage,
    val keepRecent: Int,
    val additionalProtocolMessages: Int,
    val beforeTokens: Long,
    val afterTokens: Long,
    val eventId: Uuid,
    val createdAtEpochMillis: Long,
    val summaryHash: String,
)

data class AppliedCompaction(val messages: List<UIMessage>, val epoch: Long)

/** The executor is scoped to one collected generation, never a global tool or arbitrary chat ID. */
class ConversationCompactionControl(
    val thresholdTokens: Int,
    private val readMessages: () -> List<UIMessage>,
    private val readHistory: suspend () -> String,
    val commit: suspend (before: List<UIMessage>, replacement: CompactionReplacement) -> AppliedCompaction,
) {
    var lastInputEstimate: Long? = null
        private set

    fun observeInput(messages: List<UIMessage>, schemaText: String = "", modelId: Uuid? = null): Long {
        val approximate = estimateCompactionTokens(messages) + estimateCompactionTextTokens(schemaText)
        val anchored = estimateCurrentContext(readMessages(), modelId)
        return maxOf(approximate, anchored.tokens).also { lastInputEstimate = it }
    }

    fun observeCompactedInput(messages: List<UIMessage>, schemaText: String): Long =
        (estimateCompactionTokens(messages) + estimateCompactionTextTokens(schemaText)).also { lastInputEstimate = it }

    /** Called for each prepared provider request, including automatic tool continuation steps. */
    fun observeRequestEstimate(estimatedTokens: Long) {
        require(estimatedTokens >= 0)
        lastInputEstimate = estimatedTokens
    }

    fun tools(): List<Tool> = listOf(
        Tool(
            name = COMPACT_TOOL_NAME,
            description = "整理本对话活动上下文。你先写自己的普通可见摘要，再调用 use_last_message=true；也可显式提供 summary。不会找其他模型代写。默认保留最近 min(32,现有条数) 条原文，keep_recent 可为0，显式超过现有条数会报错。可选调用context_compaction_status查询现有条数和用量，但不是整理前置。工具调用与结果成组保留，必要协议尾段额外计数。成功后同一轮继续，失败原文不变；旧上下文有可撤销记录。无 ST/锚点/人工确认前置。",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("use_last_message", buildJsonObject { put("type", "boolean") })
                put("summary", buildJsonObject { put("type", "string") })
                put("keep_recent", buildJsonObject { put("type", "integer"); put("minimum", 0) })
            }) },
            // No resident state prompt: ordinary wakes stay quiet, and rebasing cannot
            // retain stale message counts inside the frozen system message. Live state
            // remains opt-in through context_compaction_status; threshold reminders
            // are separate, tagged messages emitted only by compactionReminder.
            execute = { listOf(UIMessagePart.Text(compactionError("只能在当前对话的生成循环中执行，本次未整理。"))) },
        ),
        Tool(
            name = "context_compaction_status",
            description = "查看本对话活动上下文条数、近似用量和整理提醒阈值。0仅关闭提醒，不禁用compact。用量是估算而非当前请求精确token。",
            parameters = { InputSchema.Obj(JsonObject(emptyMap())) },
            execute = { listOf(UIMessagePart.Text(buildJsonObject {
                put("active_messages", readMessages().size)
                put("estimated_tokens", lastInputEstimate ?: estimateCompactionTokens(readMessages()))
                put("basis", "estimate; includes frozen request and tool schemas when available; not exact provider usage")
                put("reminder_threshold_tokens", thresholdTokens)
                put("reminder_starts_at_ratio", 0.9)
                put("automatic_compaction", false)
                put("keep_recent_unit", "original UI messages; paired protocol tail may add messages")
            }.toString())) },
        ),
        Tool(
            name = "context_compaction_history",
            description = "查看当前AI的上下文整理历史元信息，不返回被归档的原文；没有隐式整理、恢复或删除副作用。",
            parameters = { InputSchema.Obj(JsonObject(emptyMap())) },
            execute = { listOf(UIMessagePart.Text(readHistory())) },
        ),
    )
}

/** Text only, never reasoning or nested tool output. Raw <think> prefixes are excluded too. */
private fun ordinaryText(message: UIMessage?): String? {
    if (message == null || message.role != MessageRole.ASSISTANT || message.isSynthetic) return null
    return listOf(message).transformThinkTags(Clock.System.now(), generationFinished = true).single()
        .parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }
}

fun prepareCompaction(
    arguments: JsonElement,
    messages: List<UIMessage>,
    currentResponse: UIMessage?,
): PreparedCompaction {
    val args = arguments as? JsonObject ?: error("compact参数必须是对象。")
    val explicit = args["summary"]?.let {
        require(it is JsonPrimitive && it.isString) { "summary必须是文本。" }
        it.contentOrNull?.takeIf(String::isNotBlank)
    }
    val useLast = args["use_last_message"]?.let {
        (it as? JsonPrimitive)?.booleanOrNull ?: error("use_last_message必须是布尔值。")
    } ?: (explicit == null)
    val source = if (useLast) {
        currentResponse?.takeIf { ordinaryText(it) != null }
            ?: messages.lastOrNull { ordinaryText(it) != null }
    } else null
    val summaryText = source?.let(::ordinaryText) ?: explicit
        ?: error("没有可用的AI普通正文摘要。请先写摘要，或提供summary；本次未修改原文。")
    val keep = args["keep_recent"]?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.intOrNull
            ?: error("keep_recent必须是非负整数。")
    } ?: minOf(32, messages.size)
    require(keep in 0..messages.size) { "keep_recent=$keep，现有${messages.size}条；请使用0..${messages.size}，本次未修改原文。" }
    val summary = UIMessage.assistant(summaryText).copy(
        modelId = source?.modelId ?: messages.lastOrNull()?.modelId,
        parts = listOf(UIMessagePart.Text(summaryText, buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })),
    )
    return PreparedCompaction(summary, keep, messages.size, source?.id)
}

/** Keep whole UI messages. Legacy split call/result formats expand the boundary, never split it. */
@Suppress("DEPRECATION")
fun buildCompactionReplacement(
    request: PreparedCompaction,
    completedMessages: List<UIMessage>,
    compactCallId: String,
): CompactionReplacement {
    require(request.keepRecent <= completedMessages.size) { "活动上下文已改变，请重试。" }
    var start = completedMessages.size - request.keepRecent
    val invocationIndex = completedMessages.indexOfLast { message -> message.getTools().any { it.toolCallId == compactCallId } }
    require(invocationIndex >= 0) { "整理调用回执缺失，本次未修改。" }
    start = minOf(start, invocationIndex)
    start = wholeCompactionToolTailStart(completedMessages, start)
    val kept = completedMessages.drop(start)
    require(kept.flatMap { it.getTools() }.all { it.isExecuted }) { "还有未完成工具，暂不整理；已完成结果仍保留。" }
    val additional = kept.size - request.keepRecent
    val eventId = Uuid.random()
    val createdAtEpochMillis = System.currentTimeMillis()
    val summaryHash = orbisCompactionSummaryHash(request.summary.toText())
    val beforeTokens = estimateCurrentContext(completedMessages).tokens
    val approximateAfter = estimateCompactionTokens(listOf(request.summary) + kept)
    fun receipt(afterTokens: Long) = buildJsonObject {
        put("status", "compacted")
        put("event_id", eventId.toString())
        put("new_start_message_id", request.summary.id.toString())
        put("created_at_ms", createdAtEpochMillis)
        put("summary_sha256", summaryHash)
        put("before_tokens", beforeTokens)
        put("after_tokens_estimate", afterTokens)
        put("usage_basis", "before: previous provider usage when valid plus estimate; after: text/tool estimate including this receipt")
        put("keep_recent", request.keepRecent)
        put("available_messages", request.available)
        put("additional_protocol_messages", additional)
        put("summary_role", "assistant")
        put("continuation", "continue this same run using the summary, retained original messages, and this complete tool receipt")
        put("note", "摘要直接来自你写的普通正文；未调用其他模型。原文整理记录可查看或撤销。若摘要与工具共处一条保留原文，正文可能同时出现在摘要与原文中。")
    }.toString()
    fun withReceipt(afterTokens: Long) = kept.mapIndexed { index, message -> message.copy(
        // The next model response merges into this same bubble. A no-usage response must not
        // inherit pre-compaction prompt usage and resurrect the old budget measurement.
        usage = if (index == kept.lastIndex) null else message.usage,
        parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool && part.toolCallId == compactCallId) {
                part.copy(output = listOf(UIMessagePart.Text(receipt(afterTokens))))
            } else part
        },
    ) }
    var afterTokens = approximateAfter
    var replacement = listOf(request.summary) + withReceipt(afterTokens)
    repeat(8) {
        val measured = estimateCompactionTokens(replacement)
        if (measured != afterTokens) {
            afterTokens = measured
            replacement = listOf(request.summary) + withReceipt(afterTokens)
        }
    }
    check(estimateCompactionTokens(replacement) == afterTokens) { "整理回执估算未稳定，本次未修改。" }
    return CompactionReplacement(
        replacement.map(::copyGenerationMessage), request.summary, request.keepRecent, additional,
        beforeTokens, afterTokens, eventId, createdAtEpochMillis, summaryHash,
    )
}

/** Legacy separate call/result messages expand the retained suffix; never split a protocol unit. */
@Suppress("DEPRECATION")
fun wholeCompactionToolTailStart(messages: List<UIMessage>, requestedStart: Int): Int {
    require(requestedStart in 0..messages.size) { "invalid_compaction_tail_start" }
    var start = requestedStart
    var changed: Boolean
    do {
        changed = false
        val requiredIds = messages.drop(start).flatMap { it.parts }
            .filterIsInstance<UIMessagePart.ToolResult>().map { it.toolCallId }.toSet()
        messages.take(start).forEachIndexed { index, message ->
            if (message.parts.any { part ->
                    part is UIMessagePart.ToolCall && part.toolCallId in requiredIds ||
                        part is UIMessagePart.Tool && part.toolCallId in requiredIds
                }) { start = minOf(start, index); changed = true }
        }
    } while (changed)
    return start
}

fun compactionError(message: String): String = buildJsonObject {
    put("status", "not_compacted"); put("error", message); put("original_context_preserved", true)
}.toString()

fun compactionReminder(estimatedTokens: Long, thresholdTokens: Int): UIMessage? {
    if (thresholdTokens <= 0 || estimatedTokens < (thresholdTokens.toLong() * 9 + 9) / 10) return null
    val percentage = (estimatedTokens.toDouble() / thresholdTokens * 100).toLong()
    val distance = when {
        estimatedTokens < thresholdTokens -> "距阈值还有 ${thresholdTokens.toLong() - estimatedTokens} token。"
        estimatedTokens > thresholdTokens -> "已超过阈值 ${estimatedTokens - thresholdTokens.toLong()} token。"
        else -> "已达到提醒阈值。"
    }
    val risk = if (estimatedTokens >= thresholdTokens) {
        "已达到或超过当前对话配置的整理提醒阈值。"
    } else "已接近当前对话配置的整理提醒阈值（90%起）。"
    // SYSTEM identifies the host, not another human turn. Provider adapters preserve the original
    // system and explicitly carry this tagged notice on protocols with one system field. This
    // stays in the request snapshot, never in the visible/persisted chat or a tool result.
    return UIMessage.system("").copy(isSynthetic = true, parts = listOf(UIMessagePart.Text(
        "[Orbis 上下文用量参考；宿主状态数据，非人类发言] 当前上下文估算 $estimatedTokens / 提醒阈值 $thresholdTokens token（$percentage%）。$distance$risk " +
            "这是宿主中性状态，不是用户发言或压缩命令；估算并非服务商精确计数，提醒阈值也不是模型硬上限。你可先自行记忆存档，再写自己的摘要并调用compact，也可直接写摘要并调用compact，或继续对话；是否及何时整理由你决定。记忆存档不是整理前置条件，也不要求先查询状态或由人类确认。宿主不会自动压缩，也不会代写摘要。",
        buildJsonObject {
            put(COMPACTION_REMINDER_MARKER, true)
            put("source", "orbis_host")
            put("human_authored", false)
            put("instruction_authority", "none")
        },
    )))
}
