package me.rerere.rikkahub.data.orbis.history

import androidx.room.withTransaction
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MAX_LEGACY_NODE_COMPARISON_BYTES
import me.rerere.rikkahub.data.db.entity.OrbisHistoryRemovedEntity
import me.rerere.rikkahub.data.db.entity.OrbisHistoryScopeEntity
import me.rerere.rikkahub.data.db.entity.OrbisHistorySlotEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

internal data class RemovedHistoryMessage(val nodeId: String, val message: UIMessage)

/** Same-WAL lifecycle as the page. No replay, prompt transformer, cross-window lookup or file copy. */
internal class ConversationHistoryCache(private val database: AppDatabase) {
    private val dao get() = database.orbisHistoryCacheDao()

    suspend fun alignInTransaction(window: String, owner: String, epoch: Long, consultation: Boolean = false) {
        check(database.inTransaction())
        val scope = dao.scope(window) ?: return
        if (consultation || scope.assistantId != owner || scope.compactionEpoch != epoch) dao.clear(window)
    }

    suspend fun recordInTransaction(
        window: String, owner: String, previousEpoch: Long, nextEpoch: Long,
        beforeOrder: List<String>, removed: List<RemovedHistoryMessage>,
    ) {
        check(database.inTransaction())
        alignInTransaction(window, owner, previousEpoch)
        val eligible = removed.filterNot { it.message.isCompactionSummary() }
        var scope = dao.scope(window)
        if (scope == null && eligible.isEmpty()) return
        var order = mergeHistoryOrder(dao.slots(window).map { it.nodeId }, beforeOrder)
        if (order == null) {
            // A different tree cannot inherit a previous tree's tombstones.
            dao.clear(window)
            scope = null
            order = beforeOrder
        }
        if (scope == null && eligible.isEmpty()) return
        dao.putScope(OrbisHistoryScopeEntity(window, owner, nextEpoch))
        val resolvedOrder = order
        writeOrder(window, resolvedOrder)
        val now = System.currentTimeMillis()
        eligible.forEach { (node, message) ->
            require(node in resolvedOrder) { "history_cache_source_node_missing" }
            val chunks = historyPayloadChunks(MessageNodeBudget.encodeNode(listOf(message), MAX_LEGACY_NODE_COMPARISON_BYTES))
            dao.clearMessage(window, message.id.toString())
            dao.putRemoved(chunks.mapIndexed { index, body ->
                OrbisHistoryRemovedEntity(window, message.id.toString(), node, message.role.name,
                    message.createdAt.toString(), now, index, chunks.size, body)
            })
        }
    }

    /** Called before replacePage inside the SAME transaction. A crash commits both or neither. */
    suspend fun compactInTransaction(before: Conversation, after: Conversation) {
        check(database.inTransaction())
        val window = before.id.toString()
        alignInTransaction(window, before.assistantId.toString(), before.compactionEpoch, before.isConsultation)
        if (dao.scope(window) == null) return
        val oldOrder = dao.slots(window).map { it.nodeId }
        val merged = mergeHistoryOrder(oldOrder, before.messageNodes.map { it.id.toString() })
        if (merged == null || before.assistantId != after.assistantId) {
            dao.clear(window)
            return
        }
        val originalIds = before.messageNodes.map { it.id.toString() }.toSet()
        val retained = after.messageNodes.filterNot { it.currentMessage.isCompactionSummary() }
            .map { it.id.toString() }.filter { it in originalIds }.toSet()
        val keptOrder = retainedHistoryOrder(merged, originalIds, retained)
        if (keptOrder.isEmpty()) {
            dao.clear(window)
            return
        }
        val keep = keptOrder.toSet()
        merged.filterNot { it in keep }.forEach { dao.clearNode(window, it) }
        dao.putScope(OrbisHistoryScopeEntity(window, after.assistantId.toString(), after.compactionEpoch))
        writeOrder(window, keptOrder)
    }

    suspend fun clearInTransaction(window: String) {
        check(database.inTransaction())
        dao.clear(window)
    }

    private suspend fun writeOrder(window: String, order: List<String>) {
        dao.clearSlots(window)
        order.mapIndexed { index, id -> OrbisHistorySlotEntity(window, id, index) }
            .chunked(200).forEach { dao.putSlots(it) }
    }

    /** Bound by the host, not model arguments. Return no historical body across owner/epoch drift. */
    suspend fun verifyCurrent(current: Conversation) = database.withTransaction { requireCurrent(current) }

    suspend fun listRemoved(current: Conversation, limit: Int, offset: Int) = database.withTransaction {
        requireCurrent(current)
        dao.listRemoved(current.id.toString(), limit, offset)
    }

    suspend fun readRemoved(current: Conversation, messageId: String): UIMessage? = database.withTransaction {
        requireCurrent(current)
        dao.removedChunks(current.id.toString(), messageId).takeIf { it.isNotEmpty() }?.let { rows ->
            try {
                val first = rows.first()
                check(rows.size == first.chunkCount && rows.withIndex().all { (index, row) ->
                    row.chunkIndex == index && row.chunkCount == first.chunkCount &&
                        row.sourceNodeId == first.sourceNodeId && row.role == first.role &&
                        row.createdAt == first.createdAt && row.removedAt == first.removedAt
                })
                JsonInstant.decodeFromString<List<UIMessage>>(joinHistoryPayload(rows.map { it.body })).single()
                    .takeIf { it.id.toString() == first.messageId }
            } catch (_: Exception) {
                // Decoder messages may contain history. Never return their cause to a tool.
                error("history_cache_decode_failed")
            }
        }
    }

    private suspend fun requireCurrent(current: Conversation) {
        check(!current.isConsultation) { "history_cache_scope_unavailable" }
        val stored = database.conversationDao().getConversationById(current.id.toString())
        check(stored != null && stored.assistantId == current.assistantId.toString() &&
            stored.compactionEpoch == current.compactionEpoch && stored.consultationBinding.isBlank()) {
            "history_cache_scope_changed"
        }
        alignInTransaction(stored.id, stored.assistantId, stored.compactionEpoch)
    }
}
