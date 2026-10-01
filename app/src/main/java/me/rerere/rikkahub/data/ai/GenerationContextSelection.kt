package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary

/** A per-invocation projection, never the new contents of the persisted conversation.
 * Always pass the full active branch, not the previous request's selected suffix. In particular,
 * zero reselects the full branch on the next wake; it does not undo an explicit compact/archive.
 */
internal class GenerationContextSelection(
    source: List<UIMessage>,
    private val limit: Int,
) {
    private val sourceIds = source.map { it.id }
    private val summaryAtStart = source.firstOrNull()?.isCompactionSummary() == true
    private val selected = when {
        limit <= 0 -> source
        summaryAtStart -> source.take(1) + source.drop(1).limitContext(limit)
        else -> source.limitContext(limit)
    }
    val includesWholeBranch: Boolean get() = selected.size == sourceIds.size

    // UIMessagePart metadata/output can be mutable. Input transformers must not receive stored
    // message objects, even though GenerationInputSnapshot also copies their finished output.
    fun requestMessages(): List<UIMessage> = selected.map(::copyGenerationMessage)

    fun receipt(prepared: List<UIMessage>, preparedAtMillis: Long): GenerationContextReceipt {
        val selectedIds = selected.map { it.id }.toSet()
        val preparedIds = prepared.filter { it.isValidToUpload() }.map { it.id }.toSet()
        fun firstPosition(ids: Set<kotlin.uuid.Uuid>): Int? =
            sourceIds.indexOfFirst { it in ids }.takeIf { it >= 0 }?.plus(1)
        return GenerationContextReceipt(
            preparedAtMillis = preparedAtMillis,
            contextMessageLimit = limit,
            sourceMessageCount = sourceIds.size,
            selectedMessageCount = selected.size,
            preparedMessageCount = prepared.count { it.isValidToUpload() },
            firstSelectedPosition = firstPosition(selectedIds),
            firstPreparedSourcePosition = firstPosition(preparedIds),
            firstLocalMessageSelected = sourceIds.firstOrNull()?.let { it in selectedIds } ?: false,
            firstLocalMessagePrepared = sourceIds.firstOrNull()?.let { it in preparedIds } ?: false,
            compactionSummaryAtStart = summaryAtStart,
        )
    }
}

/** Metadata only: no content, message IDs, hashes of private text, credentials or model memory.
 * This describes the first logical provider input before provider-specific wire conversion. It
 * is NOT evidence that the server received it or that a model correctly recalled every message.
 */
internal data class GenerationContextReceipt(
    val preparedAtMillis: Long,
    val contextMessageLimit: Int,
    val sourceMessageCount: Int,
    val selectedMessageCount: Int,
    val preparedMessageCount: Int,
    val firstSelectedPosition: Int?,
    val firstPreparedSourcePosition: Int?,
    val firstLocalMessageSelected: Boolean,
    val firstLocalMessagePrepared: Boolean,
    val compactionSummaryAtStart: Boolean,
)

/** Bounded process-local evidence. Never persist or upload this registry. */
internal class GenerationContextReceiptStore(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    private val mutable = MutableStateFlow<Map<String, GenerationContextReceipt>>(emptyMap())
    val receipts: StateFlow<Map<String, GenerationContextReceipt>> = mutable.asStateFlow()

    fun record(conversationId: String, receipt: GenerationContextReceipt) {
        mutable.update { previous ->
            val next = LinkedHashMap(previous)
            next.remove(conversationId)
            next[conversationId] = receipt
            while (next.size > capacity) next.remove(next.keys.first())
            next
        }
    }
}

internal val generationContextReceipts = GenerationContextReceiptStore()
