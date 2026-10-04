package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** The complete read/modify/write is serialized, including across Android entry points. */
interface ContextPruningStorage {
    fun <T> locked(write: Boolean = false, block: () -> T): T
    fun read(): String?
    /** Atomic replacement, with old contents intact on failures before publication. */
    fun write(value: String)
}

/** Only an explicit business refusal before publication may become a completed refusal receipt.
 * Storage, cancellation and read-back failures must retain their uncertain-outcome semantics. */
internal enum class ContextPruningRefusal(val reason: String) {
    PLAN_RESTORED("context_pruning_plan_already_restored"),
    PREVIEW_CHANGED("context_pruning_preview_changed"),
    NOTHING_ELIGIBLE("context_pruning_nothing_eligible"),
    CAPACITY_REACHED("context_pruning_capacity_reached"),
    BATCH_NOT_FOUND("context_pruning_batch_not_found"),
}

internal class ContextPruningRejected(val refusal: ContextPruningRefusal) : IllegalStateException(refusal.reason)
private fun requirePruning(condition: Boolean, refusal: ContextPruningRefusal) {
    if (!condition) throw ContextPruningRejected(refusal)
}

data class ContextPruningApplyResult(val batch: ContextPruningBatch, val reused: Boolean)

class ContextPruningRepository(
    val assistantId: String,
    val conversationId: String,
    private val storage: ContextPruningStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { requireUuid(assistantId); requireUuid(conversationId) }
    private val mutable = MutableStateFlow(ContextPruningState(assistantId = assistantId, conversationId = conversationId))
    /** Consumers call snapshot on IO before interpreting initial empty state as verified. */
    val states: StateFlow<ContextPruningState> = mutable.asStateFlow()

    fun snapshot(): ContextPruningState = storage.locked { readState().also { mutable.value = it } }

    fun preview(messages: List<UIMessage>, mode: ContextPruningMode, maxMessages: Int): ContextPruningPlan = storage.locked {
        val current = readState().also { mutable.value = it }
        planContextPruning(messages, current, mode, maxMessages)
    }

    /** Exact preview target, not a broad durable command that silently expands to future rounds. */
    fun apply(messages: List<UIMessage>, mode: ContextPruningMode, maxMessages: Int, planId: String): ContextPruningBatch =
        applyDetailed(messages, mode, maxMessages, planId).batch

    fun applyDetailed(messages: List<UIMessage>, mode: ContextPruningMode, maxMessages: Int, planId: String): ContextPruningApplyResult = storage.locked(write = true) {
        requireHash(planId)
        val current = readState()
        // Re-delivered successful tool calls are idempotent, not a second batch or another delete.
        current.batches.singleOrNull { it.planId == planId }?.let {
            requirePruning(!it.restored, ContextPruningRefusal.PLAN_RESTORED)
            mutable.value = current
            return@locked ContextPruningApplyResult(it, reused = true)
        }
        val plan = planContextPruning(messages, current, mode, maxMessages)
        requirePruning(plan.planId == planId, ContextPruningRefusal.PREVIEW_CHANGED)
        requirePruning(plan.marks.isNotEmpty(), ContextPruningRefusal.NOTHING_ELIGIBLE)
        requirePruning(current.batches.size < MAX_PRUNING_BATCHES, ContextPruningRefusal.CAPACITY_REACHED)
        requirePruning(current.batches.sumOf { it.marks.size } + plan.marks.size <= MAX_PRUNING_MARKS, ContextPruningRefusal.CAPACITY_REACHED)
        val batch = ContextPruningBatch(Uuid.random().toString(), plan.planId, plan.marks, now())
        commit(current.copy(batches = current.batches + batch))
        ContextPruningApplyResult(batch, reused = false)
    }

    /** Restore the sending/display policy, never execute any historical tool again. */
    fun restore(batchId: String): ContextPruningBatch = storage.locked(write = true) {
        requireUuid(batchId)
        val current = readState()
        val batch = current.batches.singleOrNull { it.id == batchId }
            ?: throw ContextPruningRejected(ContextPruningRefusal.BATCH_NOT_FOUND)
        if (batch.restored) {
            mutable.value = current
            return@locked batch
        }
        val restored = batch.copy(restored = true)
        commit(current.copy(batches = current.batches.map { if (it.id == batchId) restored else it }))
        restored
    }

    private fun commit(state: ContextPruningState) {
        validateContextPruningState(state, assistantId, conversationId)
        val text = contextPruningJson.encodeToString(state)
        requirePruning(text.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_PRUNING_BYTES, ContextPruningRefusal.CAPACITY_REACHED)
        storage.write(text)
        // A provider fallback/retry must not turn an uncertain local publication into success.
        check(storage.read() == text) { "context_pruning_commit_not_verified" }
        mutable.value = state
    }

    private fun readState(): ContextPruningState {
        val raw = storage.read() ?: return ContextPruningState(assistantId = assistantId, conversationId = conversationId)
        check(raw.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_PRUNING_BYTES && contextPruningJsonDepthAllowed(raw)) {
            "context_pruning_unreadable"
        }
        return try {
            contextPruningJson.decodeFromString<ContextPruningState>(raw).also {
                validateContextPruningState(it, assistantId, conversationId)
            }
        } catch (_: Exception) { error("context_pruning_unreadable") }
    }
}

internal const val MAX_CONTEXT_PRUNING_BYTES = 2 * 1024 * 1024

internal fun validateContextPruningState(state: ContextPruningState, assistantId: String, conversationId: String) {
    check(state.version == 1 && state.assistantId == assistantId && state.conversationId == conversationId) { "context_pruning_owner_mismatch" }
    check(state.batches.size <= MAX_PRUNING_BATCHES && state.batches.sumOf { it.marks.size } <= MAX_PRUNING_MARKS)
    check(state.batches.map { it.id }.distinct().size == state.batches.size)
    check(state.batches.map { it.planId }.distinct().size == state.batches.size)
    state.batches.forEach { batch ->
        requireUuid(batch.id); requireHash(batch.planId)
        check(batch.createdAtMs >= 0 && batch.marks.isNotEmpty() && batch.marks.size <= MAX_PRUNING_MESSAGES)
        check(batch.marks.map { it.messageId }.distinct().size == batch.marks.size)
        batch.marks.forEach { mark ->
            requireUuid(mark.messageId); requireHash(mark.contentHash)
            val indexes = mark.reasoningIndexes + mark.toolIndexes
            check(indexes.isNotEmpty() && indexes.size <= 16_384 && indexes.distinct().size == indexes.size &&
                indexes.all { it in 0 until 16_384 })
        }
    }
}

private fun requireUuid(value: String) { require(value.length == 36 && Uuid.parse(value).toString() == value) { "context_pruning_invalid_id" } }
private fun requireHash(value: String) { require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "context_pruning_invalid_digest" } }

/** Reject hostile imported nesting before the JSON parser allocates a recursive object tree. */
internal fun contextPruningJsonDepthAllowed(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 12) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return depth == 0 && !quoted
}
