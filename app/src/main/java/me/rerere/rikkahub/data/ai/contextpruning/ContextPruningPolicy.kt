package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

const val CONTEXT_PRUNING_TOOL_NAME = "orbis_context_prune"
const val CONTEXT_PRUNING_RECEIPT_TYPE = "orbis_context_pruning/v1"
internal const val MAX_PRUNING_MESSAGES = 200
internal const val MAX_PRUNING_BATCHES = 128
internal const val MAX_PRUNING_MARKS = 10_000
internal val contextPruningJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable
enum class ContextPruningMode { REASONING, TOOLS, BOTH }

/** Metadata only. Message content and undo copies remain in their original conversation nodes. */
@Serializable
data class ContextPruningMark(
    val messageId: String,
    val contentHash: String,
    val reasoningIndexes: List<Int> = emptyList(),
    val toolIndexes: List<Int> = emptyList(),
)

@Serializable
data class ContextPruningBatch(
    val id: String,
    val planId: String,
    val marks: List<ContextPruningMark>,
    val createdAtMs: Long,
    val restored: Boolean = false,
) {
    val reasoningCount: Int get() = marks.sumOf { it.reasoningIndexes.size }
    val toolCount: Int get() = marks.sumOf { it.toolIndexes.size }
}

@Serializable
data class ContextPruningState(
    val version: Int = 1,
    val assistantId: String,
    val conversationId: String,
    val batches: List<ContextPruningBatch> = emptyList(),
)

data class ContextPruningPlan(
    val planId: String,
    val marks: List<ContextPruningMark>,
    val eligibleMessages: Int,
    val excludedCharacters: Long,
) {
    val reasoningCount: Int get() = marks.sumOf { it.reasoningIndexes.size }
    val toolCount: Int get() = marks.sumOf { it.toolIndexes.size }
}

/** Presentation only. Do not persist this projection or reinterpret arbitrary tool JSON as a receipt. */
data class ContextPruningDisplayProjection(
    val hiddenReasoningIndexes: Set<Int> = emptySet(),
    val hiddenToolIndexes: Set<Int> = emptySet(),
    val controlToolIndexes: Set<Int> = emptySet(),
    val activeBatchIds: Set<String> = emptySet(),
) {
    val hiddenPartIndexes: Set<Int> get() = hiddenReasoningIndexes + hiddenToolIndexes + controlToolIndexes
}

private data class PruningTurn(val start: Int, val end: Int)

/** A real following USER boundary and a terminal, finished assistant prose reply are required.
 * Provider metadata is deliberately treated conservatively: unknown signatures/encrypted state,
 * legacy split pairs and server-side tools make the entire turn ineligible. */
@Suppress("DEPRECATION")
private fun safePruningTurns(messages: List<UIMessage>): List<PruningTurn> {
    require(messages.map { it.id }.distinct().size == messages.size) { "context_pruning_ambiguous_source" }
    val users = messages.indices.filter { messages[it].role == MessageRole.USER }
    val toolIds = messages.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
        .groupingBy { it.toolCallId }.eachCount()
    return users.zipWithNext().mapNotNull { (start, end) ->
        val turn = messages.subList(start, end)
        val replies = turn.filter { it.role == MessageRole.ASSISTANT }
        val terminal = turn.lastOrNull { it.role != MessageRole.SYSTEM }
        val safe = replies.isNotEmpty() && terminal?.role == MessageRole.ASSISTANT &&
            terminal.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() } &&
            turn.all { message ->
                message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM) &&
                    (message.role != MessageRole.ASSISTANT || message.finishedAt != null) &&
                    message.parts.all { part ->
                        part.metadata.isNullOrEmpty() && when (part) {
                            is UIMessagePart.Tool -> part.toolCallId.isNotBlank() && part.isExecuted &&
                                toolIds[part.toolCallId] == 1 && part.output.all { output ->
                                    output !is UIMessagePart.Tool && output !is UIMessagePart.ToolCall &&
                                        output !is UIMessagePart.ToolResult && output !is UIMessagePart.ServerTool &&
                                        output !is UIMessagePart.Reasoning && output.metadata.isNullOrEmpty()
                                }
                            is UIMessagePart.Reasoning -> part.finishedAt != null
                            is UIMessagePart.ToolCall, is UIMessagePart.ToolResult, is UIMessagePart.ServerTool -> false
                            else -> true
                        }
                    }
            }
        if (safe) PruningTurn(start, end) else null
    }
}

private fun oldEligibleIndexes(messages: List<UIMessage>, turns: List<PruningTurn>): Set<Int> {
    val users = messages.indices.filter { messages[it].role == MessageRole.USER }
    if (users.size < 3) return emptySet()
    val protectedStart = users[users.size - 2]
    return turns.filter { it.end <= protectedStart }.flatMap { (it.start until it.end).toList() }.toSet()
}

internal fun contextPruningContentHash(message: UIMessage): String = pruningDigest(
    contextPruningJson.encodeToString(message.parts),
)

internal fun pruningDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Preview carries counts/identities, never private reasoning, tool inputs or outputs. */
fun planContextPruning(
    messages: List<UIMessage>, state: ContextPruningState,
    mode: ContextPruningMode = ContextPruningMode.BOTH, maxMessages: Int = 100,
): ContextPruningPlan {
    require(maxMessages in 1..MAX_PRUNING_MESSAGES) { "context_pruning_invalid_limit" }
    val eligible = oldEligibleIndexes(messages, safePruningTurns(messages))
    var characters = 0L
    var eligibleCount = 0
    val marks = buildList {
        messages.forEachIndexed { index, message ->
            if (index !in eligible || message.role != MessageRole.ASSISTANT) return@forEachIndexed
            val already = projectContextPruningForDisplay(message, state)
            val tools = message.parts.indices.filter { partIndex ->
                mode != ContextPruningMode.REASONING && partIndex !in already.hiddenPartIndexes &&
                    message.parts[partIndex] is UIMessagePart.Tool &&
                    (message.parts[partIndex] as UIMessagePart.Tool).toolName != CONTEXT_PRUNING_TOOL_NAME
            }
            // Some compatible providers require reasoning_content on a tool-bearing assistant
            // message even without a signature. Do not strip it while keeping that tool unit.
            val hasRetainedTools = message.parts.indices.any { partIndex ->
                message.parts[partIndex] is UIMessagePart.Tool && partIndex !in already.hiddenPartIndexes && partIndex !in tools
            }
            val reasoning = message.parts.indices.filter { partIndex ->
                !hasRetainedTools && mode != ContextPruningMode.TOOLS && partIndex !in already.hiddenPartIndexes &&
                    message.parts[partIndex] is UIMessagePart.Reasoning
            }
            if (reasoning.isEmpty() && tools.isEmpty()) return@forEachIndexed
            eligibleCount++
            if (size >= maxMessages) return@forEachIndexed
            add(ContextPruningMark(message.id.toString(), contextPruningContentHash(message), reasoning, tools))
            (reasoning + tools).forEach { characters += contextPruningJson.encodeToString(message.parts[it]).length.toLong() }
        }
    }
    // The two recent user identities protect the preview boundary without including the changing
    // current tool chain. A normal preview->apply continuation therefore keeps the same plan ID.
    val recentUsers = messages.filter { it.role == MessageRole.USER }.takeLast(2).map { it.id.toString() }
    val identity = listOf(state.assistantId, state.conversationId, mode.name, maxMessages.toString(),
        recentUsers.joinToString(","), state.batches.joinToString(",") { "${it.id}:${it.restored}" },
        contextPruningJson.encodeToString(marks)).joinToString("\n")
    return ContextPruningPlan(pruningDigest(identity), marks, eligibleCount, characters)
}

/** Successful control calls collapse once, without invoking another cleanup action. An output
 * is trusted for this narrow display/projection purpose only if it matches a committed local batch.
 * Preview, restoration and failures never acquire this status. */
internal fun isCommittedPruningControl(tool: UIMessagePart.Tool, state: ContextPruningState): Boolean {
    if (tool.toolName != CONTEXT_PRUNING_TOOL_NAME || !tool.isExecuted || tool.output.size != 1) return false
    val text = (tool.output.single() as? UIMessagePart.Text)?.text ?: return false
    if (text.length > 2048 || !contextPruningJsonDepthAllowed(text) || tool.input.length > 2048 ||
        !contextPruningJsonDepthAllowed(tool.input)) return false
    val value = try { contextPruningJson.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return false
    val requiredKeys = setOf("receipt_type", "status", "batch_id", "excluded_reasoning_parts", "excluded_tool_pairs",
        "source_preserved", "effective_from")
    if (value.keys != requiredKeys && value.keys != requiredKeys + "reused_existing_batch") return false
    if ("reused_existing_batch" in value && (value["reused_existing_batch"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.booleanOrNull == null) return false
    fun string(name: String): String? = (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val batch = state.batches.singleOrNull { it.id == string("batch_id") } ?: return false
    val input = try { contextPruningJson.parseToJsonElement(tool.input) as? JsonObject } catch (_: Exception) { null } ?: return false
    if ((input["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "apply" ||
        (input["plan_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content != batch.planId) return false
    return string("receipt_type") == CONTEXT_PRUNING_RECEIPT_TYPE && string("status") == "applied" &&
        string("effective_from") == "next_independent_generation" &&
        (value["source_preserved"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
        (value["excluded_reasoning_parts"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == batch.reasoningCount &&
        (value["excluded_tool_pairs"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == batch.toolCount
}

fun projectContextPruningForDisplay(message: UIMessage, state: ContextPruningState): ContextPruningDisplayProjection {
    if (message.role != MessageRole.ASSISTANT) return ContextPruningDisplayProjection()
    val possible = state.batches.filterNot { it.restored }.mapNotNull { batch ->
        batch.marks.singleOrNull { it.messageId == message.id.toString() }?.let { batch.id to it }
    }
    val matching = if (possible.isEmpty()) emptyList() else {
        val hash = contextPruningContentHash(message)
        possible.filter { (_, mark) -> mark.contentHash == hash }
    }
    val controls = if (message.parts.any { !it.metadata.isNullOrEmpty() }) emptySet() else
        message.parts.indices.filter { index ->
            (message.parts[index] as? UIMessagePart.Tool)?.let { isCommittedPruningControl(it, state) } == true
        }.toSet()
    val hiddenTools = matching.flatMap { (_, mark) -> mark.toolIndexes }
        .filter { (message.parts.getOrNull(it) as? UIMessagePart.Tool)?.isExecuted == true }.toSet()
    val retainsTools = message.parts.indices.any { message.parts[it] is UIMessagePart.Tool && it !in hiddenTools && it !in controls }
    return ContextPruningDisplayProjection(
        hiddenReasoningIndexes = if (retainsTools) emptySet() else matching.flatMap { (_, mark) -> mark.reasoningIndexes }
            .filter { message.parts.getOrNull(it) is UIMessagePart.Reasoning }.toSet(),
        hiddenToolIndexes = hiddenTools,
        controlToolIndexes = controls,
        activeBatchIds = matching.map { it.first }.toSet(),
    )
}

/** Request copy only, never a replacement database snapshot. Current and recent rounds remain
 * intact. Signed/unknown protocol turns fail closed even if an old policy contains their IDs. */
fun projectContextPruningForRequest(messages: List<UIMessage>, state: ContextPruningState): List<UIMessage> {
    if (state.batches.isEmpty()) return messages
    val turns = safePruningTurns(messages)
    val old = oldEligibleIndexes(messages, turns)
    val finished = turns.flatMap { (it.start until it.end).toList() }.toSet()
    var changed = false
    val projected = messages.mapIndexedNotNull { index, message ->
        val display = projectContextPruningForDisplay(message, state)
        val excluded = (if (index in old) display.hiddenReasoningIndexes + display.hiddenToolIndexes else emptySet()) +
            (if (index in finished) display.controlToolIndexes else emptySet())
        if (excluded.isEmpty()) {
            if (changed) message.copy(usageContextInvalidated = true) else message
        } else {
            changed = true
            val parts = message.parts.filterIndexed { partIndex, _ -> partIndex !in excluded }
            if (parts.isEmpty()) null else message.copy(parts = parts, usageContextInvalidated = true)
        }
    }
    return if (changed) projected else messages
}
