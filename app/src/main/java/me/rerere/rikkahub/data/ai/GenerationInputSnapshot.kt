package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.compactionReminder
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTextTokens
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import me.rerere.rikkahub.data.ai.compaction.isCompactionReminder
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import me.rerere.rikkahub.data.ai.transformers.markOrbisEvents
import kotlin.uuid.Uuid

/**
 * Request-local, never persisted and never shared between human wakes.
 *
 * The first request evaluates context selection, templates, lorebooks and device placeholders.
 * Later automatic tool steps append the actual provider response and its tool results. They do
 * not rerender either the initial history or earlier responses. Each response stays a separate
 * wire message: joining consecutive tool-only responses would change an earlier tool_calls array.
 * UI history can still merge these responses into one bubble independently.
 *
 * This is not an approval-resume/after-restart compatibility claim. Such a new invocation starts
 * a fresh snapshot using the existing approval path. No model-compatibility header is emitted.
 */
internal class GenerationInputSnapshot(private val enableUserMessageTime: Boolean = false) {
    private var accepted: List<UIMessage>? = null
    private val continuationToolIds = mutableSetOf<String>()
    private val segmentOwners = mutableMapOf<Uuid, Uuid>()
    private var requestEstimateFloor: Long? = null
    private var reminderAddedThisWake = false
    private var preparedMessageCount = 0
    val isPrepared: Boolean get() = accepted != null

    suspend fun initializeInput(buildInitial: suspend () -> List<UIMessage>) {
        if (accepted == null) {
            // Publish only after successful transformation; cancellation cannot leave a half input.
            accepted = buildInitial().map(::copyGenerationMessage)
            reminderAddedThisWake = accepted!!.any { it.isCompactionReminder() }
        }
    }

    suspend fun input(buildInitial: suspend () -> List<UIMessage>): List<UIMessage> {
        initializeInput(buildInitial)
        return accepted!!.map(::copyGenerationMessage)
    }

    /**
     * Refresh the advisory estimate for every model step without rerendering the frozen prefix.
     * A notice is request-local host data, inserted only on this wake's first crossing. Preparing
     * a request is not evidence that a provider received it or that the model noticed the text.
     */
    fun prepareRequest(
        schemaText: String = "",
        thresholdTokens: Int,
        includeCompactionReminder: Boolean,
        initialEstimate: Long? = null,
    ): PreparedGenerationInput {
        val frozen = checkNotNull(accepted) { "Generation input has not been prepared." }
        val approximate = estimateCompactionTokens(frozen) + estimateCompactionTextTokens(schemaText)
        val estimate = maxOf(approximate, requestEstimateFloor ?: initialEstimate ?: 0L)
        val notice = if (includeCompactionReminder && !reminderAddedThisWake) {
            compactionReminder(estimate, thresholdTokens)
        } else null
        if (notice != null) {
            // A tail USER would become ST's recall query and turn a tool continuation into a new
            // human wake. A tail SYSTEM after a tool result also violates ST's complete A/T tail.
            // Insert before the new, still-unprepared protocol unit instead. Earlier requests,
            // original systems, human content and authored tool arguments/results stay untouched.
            val insertion = reminderInsertionIndex(frozen)
            check(insertion >= preparedMessageCount) { "Host notice cannot rewrite a prepared request prefix." }
            accepted = frozen.take(insertion) + copyGenerationMessage(notice) + frozen.drop(insertion)
            reminderAddedThisWake = true
        }
        val includingNotice = estimate + (notice?.let { estimateCompactionTokens(listOf(it)) } ?: 0L)
        requestEstimateFloor = includingNotice
        preparedMessageCount = accepted!!.size
        return PreparedGenerationInput(accepted!!.map(::copyGenerationMessage), includingNotice)
    }

    /** The initial approval-resume input may already end in a complete tool unit. */
    @Suppress("DEPRECATION")
    private fun reminderInsertionIndex(messages: List<UIMessage>): Int {
        var index = messages.indexOfLast { it.isValidToUpload() }
        if (index < 0) return messages.size
        if (messages[index].role == MessageRole.ASSISTANT && messages[index].getTools().isNotEmpty()) {
            return index
        }
        if (messages[index].role == MessageRole.TOOL) {
            while (index >= 0 && messages[index].role == MessageRole.TOOL) index--
            if (index >= 0 && messages[index].role == MessageRole.ASSISTANT &&
                messages[index].parts.any { it is UIMessagePart.Tool || it is UIMessagePart.ToolCall }) {
                return index
            }
        }
        return messages.size
    }

    /** Validate the correspondence before executing tools, not after a possible side effect. */
    fun validateResponseTools(response: UIMessage, calls: List<UIMessagePart.Tool>) {
        check(response.role == MessageRole.ASSISTANT) { SNAPSHOT_TOOL_MISMATCH }
        val responseCalls = response.getTools().filterNot { it.isExecuted }
        val ids = responseCalls.map { it.toolCallId }
        check(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { SNAPSHOT_TOOL_MISMATCH }
        check(ids.none { it in continuationToolIds }) { SNAPSHOT_TOOL_MISMATCH }
        check(calls.size == responseCalls.size && calls.map { it.toolCallId }.toSet() == ids.toSet()) {
            SNAPSHOT_TOOL_MISMATCH
        }
        responseCalls.forEach { original ->
            val call = calls.single { it.toolCallId == original.toolCallId }
            check(sameCall(original, call)) { SNAPSHOT_TOOL_MISMATCH }
        }
    }

    fun appendCompletedResponse(response: UIMessage, results: List<UIMessagePart.Tool>, uiMessageId: Uuid = response.id) {
        check(accepted != null) { "Generation input has not been prepared." }
        val completed = completedResponse(response, results)
        val responseEstimate = estimateCompactionTokens(listOf(response))
        val completedEstimate = estimateCompactionTokens(listOf(completed))
        // This response is an independent provider segment, never the UI bubble's merged usage.
        // Prompt + completion already includes the authored tool call, but not its new result.
        val usage = response.usage?.takeIf {
            !response.usageContextInvalidated && it.promptTokens > 0 && it.completionTokens >= 0 && it.cachedTokens in 0..it.promptTokens
        }
        requestEstimateFloor = if (usage != null) {
            usage.promptTokens.toLong() +
                (usage.completionTokens.takeIf { it > 0 }?.toLong() ?: responseEstimate) +
                (completedEstimate - responseEstimate).coerceAtLeast(0L)
        } else {
            requestEstimateFloor?.plus(completedEstimate)
        }
        accepted = accepted!! + copyGenerationMessage(completed)
        segmentOwners[completed.id] = uiMessageId
        continuationToolIds.addAll(results.map { it.toolCallId })
    }

    private fun completedResponse(response: UIMessage, results: List<UIMessagePart.Tool>): UIMessage {
        validateResponseTools(response, results)
        check(results.all { it.isExecuted }) { "Tool returned no result; automatic continuation stopped." }
        val byId = results.associateBy { it.toolCallId }
        val completed = response.copy(parts = response.parts.map { part ->
            if (part is UIMessagePart.Tool && !part.isExecuted) {
                val result = byId.getValue(part.toolCallId)
                // Identity and authored arguments were checked above. Preserve real result metadata
                // too, including the existing host-side unknown-tool failure marker.
                part.copy(output = result.output, approvalState = result.approvalState, metadata = result.metadata)
            } else part
        })
        return completed
    }

    /** Prepare without mutation; the durable commit may still fail and must leave this input intact. */
    fun prepareCompactionInput(
        replacement: List<UIMessage>,
        response: UIMessage,
        results: List<UIMessagePart.Tool>,
        uiMessageId: Uuid,
    ): List<UIMessage> {
        val frozen = checkNotNull(accepted) { "Generation input has not been prepared." }
        val completed = completedResponse(response, results)
        val segments = frozen + completed
        val owners = segmentOwners + (completed.id to uiMessageId)
        val systems = frozen.filter { it.role == MessageRole.SYSTEM && !it.isCompactionReminder() }
        val retained = replacement.drop(1)
        val rebased = buildList {
            addAll(systems)
            add(replacement.first())
            retained.forEach { original ->
                val matching = segments.filter {
                    (owners[it.id] ?: it.id) == original.id && it.role != MessageRole.SYSTEM && !it.isCompactionReminder()
                }
                if (matching.isNotEmpty()) addAll(matching)
                else {
                    // A retained message can predate the old contextMessageLimit. It was not part
                    // of the frozen request; retain authored parts without rerunning templates or
                    // dynamic injectors. External events still receive their non-human provenance.
                    addAll(me.rerere.rikkahub.data.ai.transformers.applyOrbisUserMessageTimes(
                        me.rerere.rikkahub.data.ai.transformers.applyOrbisQuotes(markOrbisEvents(listOf(original))),
                        enabled = enableUserMessageTime,
                    ))
                }
            }
        }
        return rebased.map(::copyGenerationMessage)
    }

    /** Called only inside the same non-cancellable commit/rebase critical section. */
    fun acceptCompactionInput(input: List<UIMessage>, response: UIMessage, results: List<UIMessagePart.Tool>, uiMessageId: Uuid) {
        // Already validated and deep-copied by prepareCompactionInput before the DB transaction.
        accepted = input
        // The old request's usage measured the discarded context. Never resurrect it after rebase.
        requestEstimateFloor = null
        preparedMessageCount = 0
        segmentOwners[response.id] = uiMessageId
        continuationToolIds.addAll(results.map { it.toolCallId })
    }

    private fun sameCall(a: UIMessagePart.Tool, b: UIMessagePart.Tool): Boolean =
        a.toolCallId == b.toolCallId && a.toolName == b.toolName && a.input == b.input
}

internal data class PreparedGenerationInput(val messages: List<UIMessage>, val estimatedTokens: Long)

private const val SNAPSHOT_TOOL_MISMATCH =
    "Tool continuation no longer matches the model response; automatic continuation stopped."

private val generationSnapshotJson = Json { encodeDefaults = true }

/** Part metadata and tool output lists are mutable; a shallow list copy is not a snapshot. */
internal fun copyGenerationMessage(message: UIMessage): UIMessage = generationSnapshotJson.decodeFromString(
    UIMessage.serializer(),
    generationSnapshotJson.encodeToString(UIMessage.serializer(), message),
).copy(isSynthetic = message.isSynthetic)

/** Retain the original executors/approval checks, but evaluate schema suppliers only once per run. */
internal fun snapshotGenerationTools(tools: List<Tool>): List<Tool> = tools.map { tool ->
    val schema = tool.parameters()?.let { generationSnapshotJson.encodeToString(InputSchema.serializer(), it) }
    tool.copy(parameters = {
        schema?.let { generationSnapshotJson.decodeFromString(InputSchema.serializer(), it) }
    })
}

/** A returned empty list is a completed invocation, not proof of business success or an error. */
internal fun UIMessagePart.Tool.withGenerationToolOutput(output: List<UIMessagePart>): UIMessagePart.Tool = copy(
    output = if (output.isEmpty()) {
        listOf(UIMessagePart.Text(
            text = "[Host receipt: tool invocation returned no content.]",
            metadata = JsonObject(mapOf("host_empty_tool_result" to JsonPrimitive(true))),
        ))
    } else output,
)
