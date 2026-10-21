package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.encodeMessageNodeMessages
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionBackupNodeEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionEventEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionRollbackEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** Only changes this Room database. No model calls, ST, cloud writes or workspace operations.
 * Callers serialize live conversation edits and update their state only after the transaction commits.
 */
class OrbisCompactionRepository(
    private val database: AppDatabase,
    private val messageFtsManager: MessageFtsManager,
) {
    private val dao get() = database.orbisCompactionDao()
    private val historyCache = me.rerere.rikkahub.data.orbis.history.ConversationHistoryCache(database)

    /** One confirmed whole-node edit. Durable state and FTS commit before the caller publishes RAM.
     * Archive keeps an independent complete source plus the existing reversible context slot;
     * delete can make an empty page and never dispatches, executes tools, or deletes attachment files.
     */
    suspend fun commitMessageBatch(expected: Conversation, preview: OrbisMessageBatchPreview): OrbisMessageBatchCommit =
        database.withTransaction {
            validateOrbisMessageBatch(expected, preview)
            val stored = database.conversationDao().getConversationById(expected.id.toString())
                ?: error("原窗口已不存在，未修改消息。")
            val baseline = decodeConversationEntity(stored, readCurrent(stored.id))
            if (manualContextFingerprint(baseline) != preview.expectedFingerprint) throw OrbisCompactionConflictException()
            validateCompactionNodes(preview.replacementNodes)
            if (preview.operation == OrbisMessageBatchOperation.ARCHIVE) {
                val metadata = checkNotNull(preview.archiveMetadata)
                val archive = createManualContextArchive(expected, Instant.ofEpochMilli(metadata.createdAtEpochMillis))
                insertIndependentArchive(archive)
                val committed = commit(expected, preview.replacementNodes, metadata, persistedBaseline = baseline)
                OrbisMessageBatchCommit(committed.conversation,
                    OrbisMessageBatchResult(preview.operation, preview.affectedCount, archive.id))
            } else {
                val nextEpoch = Math.addExact(stored.compactionEpoch, 1L)
                val slot = dao.getRollback(stored.id)
                if (slot != null) {
                    requireCompactionEpoch(stored.compactionEpoch, slot.compactionEpoch)
                    check(dao.updateRollbackEpoch(stored.id, stored.compactionEpoch, nextEpoch) == 1) {
                        "批量删除的撤销点已变化，未修改消息。"
                    }
                }
                val updated = baseline.copy(messageNodes = preview.replacementNodes,
                    compactionEpoch = nextEpoch, chatSuggestions = emptyList(), updateAt = Instant.now())
                val remaining = updated.messageNodes.flatMap { it.messages }.map { it.id }.toSet()
                historyCache.recordInTransaction(stored.id, stored.assistantId, stored.compactionEpoch, nextEpoch,
                    baseline.messageNodes.map { it.id.toString() }, baseline.messageNodes.flatMap { node ->
                        node.messages.filterNot { it.id in remaining }.map {
                            me.rerere.rikkahub.data.orbis.history.RemovedHistoryMessage(node.id.toString(), it)
                        }
                    })
                replacePage(updated)
                OrbisMessageBatchCommit(updated, OrbisMessageBatchResult(preview.operation, preview.affectedCount))
            }
        }

    /** Manual rescue has a strict persisted snapshot and a durable independent original window.
     * Both the archive and normal compaction commit share this outer transaction. No delivery,
     * network, file deletion, or queue dispatch is performed here.
     */
    suspend fun commitManual(
        expected: Conversation,
        replacementNodes: List<MessageNode>,
        metadata: OrbisCompactionMetadata,
        expectedFingerprint: String,
    ): OrbisManualContextCommit = database.withTransaction {
        if (manualContextFingerprint(expected) != expectedFingerprint) throw OrbisCompactionConflictException()
        val stored = database.conversationDao().getConversationById(expected.id.toString())
            ?: error("人工整理的原窗口已不存在。")
        val baseline = decodeConversationEntity(stored, readCurrent(stored.id))
        if (manualContextFingerprint(baseline) != expectedFingerprint) throw OrbisCompactionConflictException()
        validateManualContextReplacement(expected, replacementNodes, metadata)
        val archive = createManualContextArchive(expected, Instant.ofEpochMilli(metadata.createdAtEpochMillis))
        // Insert-only. New conversation, node and message IDs retain every alternative and file URL.
        insertIndependentArchive(archive)
        val committed = commit(expected, replacementNodes, metadata, persistedBaseline = baseline)
        OrbisManualContextCommit(archive.id, committed)
    }

    /** [expected] may include the current completed tool round, not yet persisted.
     * [persistedBaseline] must be the exact database snapshot read under the caller's session lock.
     * Its fingerprint is checked again INSIDE the transaction; no stale writer can replace new history.
     */
    suspend fun commit(
        expected: Conversation,
        replacementNodes: List<MessageNode>,
        metadata: OrbisCompactionMetadata,
        persistedBaseline: Conversation = expected,
    ): OrbisCompactionCommit = database.withTransaction {
        val stored = requireBaseline(expected, persistedBaseline)
        validateCompactionNodes(expected.messageNodes)
        validateCompactionNodes(replacementNodes)
        require(expected.messageNodes.isNotEmpty() && replacementNodes.isNotEmpty()) { "compaction_empty_page" }
        require(metadata.keepRecent in 0..expected.messageNodes.size) { "compaction_invalid_keep_recent" }
        require(metadata.beforeTokens >= 0 && metadata.afterTokens >= 0) { "compaction_invalid_token_measurement" }
        require(metadata.beforeBasis.isNotBlank() && metadata.afterBasis.isNotBlank()) { "compaction_missing_token_basis" }
        val summary = replacementNodes.first().currentMessage
        require(summary.role == MessageRole.ASSISTANT && summary.id == metadata.summaryMessageId &&
            metadata.summaryText.isNotBlank() && summary.toText() == metadata.summaryText) { "compaction_summary_mismatch" }
        require(expected.messageNodes.none { it.messages.any(UIMessage::hasBase64Part) }) { "compaction_unpersisted_attachment" }
        val nextEpoch = Math.addExact(stored.compactionEpoch, 1L)
        require(metadata.createdAtEpochMillis > 0) { "compaction_invalid_event_time" }
        val now = Instant.ofEpochMilli(metadata.createdAtEpochMillis)
        // Saving a branch is part of this same transaction, not another tool/model round.
        // Use current persisted header settings plus the exact live completed message snapshot.
        // Any later write failure rolls back the branch too; repeated old commits fail baseline
        // validation (or the unique event ID) instead of creating duplicate windows.
        val branch = if (metadata.preserveBranch && stored.consultationBinding.isBlank()) {
            createAiCompactionBranch(decodeConversationEntity(stored, expected.messageNodes), now)
                .also { insertIndependentArchive(it) }
        } else null
        val updated = decodeConversationEntity(stored, replacementNodes).copy(
            compactionEpoch = nextEpoch, chatSuggestions = emptyList(), updateAt = now,
        )
        val event = OrbisCompactionEventEntity(
            id = metadata.eventId.toString(), conversationId = stored.id, assistantId = stored.assistantId,
            windowTitle = stored.title, createdAt = now.toEpochMilli(),
            beforeTokens = metadata.beforeTokens, afterTokens = metadata.afterTokens,
            beforeBasis = metadata.beforeBasis, afterBasis = metadata.afterBasis,
            summaryHash = orbisCompactionSummaryHash(metadata.summaryText),
            summaryMessageId = metadata.summaryMessageId.toString(), keptRecent = metadata.keepRecent, status = "committed",
        )
        // All four changes are one WAL transaction. Failure also restores the previous rollback slot.
        dao.deleteRollback(stored.id)
        dao.insertRollback(OrbisCompactionRollbackEntity(
            conversationId = stored.id, eventId = event.id, summaryNodeId = replacementNodes.first().id.toString(),
            compactedNodeIds = JsonInstant.encodeToString(replacementNodes.map { it.id.toString() }), compactionEpoch = nextEpoch,
        ))
        dao.insertBackupNodes(expected.messageNodes.mapIndexed { index, node ->
            OrbisCompactionBackupNodeEntity(stored.id, node.id.toString(), index,
                encodeMessageNodeMessages(node.messages), node.selectIndex)
        })
        dao.insertEvent(event)
        if (metadata.retainOriginals && stored.consultationBinding.isBlank()) {
            val serialized = expected.messageNodes.map { JsonInstant.encodeToString(it) }
            require(serialized.all { it.length <= 8_000_000 }) { "单条原文超过档案读取上限；压缩未提交，原文保留。" }
            val references = JsonInstant.encodeToString(expected.messageNodes.flatMap { it.messages }
                .flatMap { it.partsWithToolUndoAttachments() }.localFileUrls())
            require(references.toByteArray().size <= 256 * 1024) { "附件引用过多；压缩未提交，原文保留。" }
            dao.insertOriginalArchive(me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveEntity(event.id,
                serialized.size, serialized.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + references.toByteArray().size, references))
            serialized.forEachIndexed { node, body ->
                originalArchiveChunks(body).mapIndexed { index, chunk ->
                    me.rerere.rikkahub.data.db.entity.OrbisOriginalChunkEntity(event.id, node, index, chunk)
                }.chunked(32).forEach { dao.insertOriginalChunks(it) }
            }
        }
        if (metadata.captureLocalMemory) {
            val memories = me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository(database)
            // Capacity refusal does not prevent compaction; the complete summary remains on the
            // conversation page. A real DB failure rolls back BOTH writes and preserves originals.
            if (memories.compactionCapacityError(metadata.summaryText) == null) {
                val receipt = memories.storeCompaction(stored.assistantId, event.id, metadata.summaryText)
                check(receipt["ok"] == kotlinx.serialization.json.JsonPrimitive(true)) {
                    "compaction_memory_save_unconfirmed_originals_preserved"
                }
            }
        }
        historyCache.compactInTransaction(expected, updated)
        replacePage(updated)
        OrbisCompactionCommit(updated, event.toModel(rollbackAvailable = true), branch?.id)
    }

    /** No event ID is accepted: historical events can never be selected for restore. */
    suspend fun rollbackLatest(
        expected: Conversation,
        thresholdTokens: Int,
        estimateTokens: (List<MessageNode>) -> Long,
        persistedBaseline: Conversation = expected,
    ): OrbisCompactionCommit = database.withTransaction {
        val stored = requireBaseline(expected, persistedBaseline)
        val slot = dao.getRollback(stored.id) ?: error("compaction_no_latest_rollback")
        requireCompactionEpoch(slot.compactionEpoch, stored.compactionEpoch)
        val event = dao.getEvent(slot.eventId, stored.assistantId) ?: error("compaction_event_missing")
        check(event.status == "committed") { "compaction_already_rolled_back" }
        val original = readBackup(stored.id)
        val restored = restoreCompactionNodes(original,
            JsonInstant.decodeFromString<List<String>>(slot.compactedNodeIds).map { Uuid.parse(it) }.toSet(),
            Uuid.parse(slot.summaryNodeId), expected.messageNodes)
        validateCompactionNodes(restored)
        requireCompactionRollbackWithinLimit(estimateTokens(restored), thresholdTokens)
        val updated = decodeConversationEntity(stored, restored).copy(
            compactionEpoch = Math.addExact(stored.compactionEpoch, 1L), chatSuggestions = emptyList(), updateAt = Instant.now(),
        )
        // Rollback restores visible originals only; discarded deleted caches never resurrect.
        historyCache.clearInTransaction(stored.id)
        replacePage(updated)
        dao.markRolledBack(event.id)
        dao.deleteRollback(stored.id) // Consumed. Older slots do not reappear.
        OrbisCompactionCommit(updated, event.copy(status = "rolled_back").toModel(false))
    }

    suspend fun listHistory(assistantId: Uuid, conversationId: Uuid? = null): List<OrbisCompactionEvent> =
        database.withTransaction {
            dao.listEvents(assistantId.toString(), conversationId?.toString()).map { event ->
                event.toModel(dao.getRollback(event.conversationId)?.eventId == event.id && event.status == "committed")
            }
        }

    suspend fun latestRollback(conversationId: Uuid, assistantId: Uuid): OrbisCompactionEvent? = database.withTransaction {
        val owner = database.conversationDao().getConversationById(conversationId.toString())
        if (owner?.assistantId != assistantId.toString()) return@withTransaction null
        val slot = dao.getRollback(conversationId.toString()) ?: return@withTransaction null
        dao.getEvent(slot.eventId, assistantId.toString())?.takeIf { it.status == "committed" }?.toModel(true)
    }

    /** Preview only; rollbackLatest recalculates its limit inside the committing transaction. */
    suspend fun projectedRollbackNodes(expected: Conversation): List<MessageNode>? = database.withTransaction {
        val owner = database.conversationDao().getConversationById(expected.id.toString()) ?: return@withTransaction null
        if (owner.assistantId != expected.assistantId.toString()) return@withTransaction null
        requireCompactionEpoch(expected.compactionEpoch, owner.compactionEpoch)
        val slot = dao.getRollback(expected.id.toString()) ?: return@withTransaction null
        restoreCompactionNodes(readBackup(expected.id.toString()),
            JsonInstant.decodeFromString<List<String>>(slot.compactedNodeIds).map { Uuid.parse(it) }.toSet(),
            Uuid.parse(slot.summaryNodeId), expected.messageNodes)
    }

    /** History is immutable except deletion. Deleting the latest event also discards its rollback slot. */
    suspend fun deleteHistory(assistantId: Uuid, eventId: Uuid): Boolean = database.withTransaction {
        val event = dao.getEvent(eventId.toString(), assistantId.toString()) ?: return@withTransaction false
        if (dao.getRollback(event.conversationId)?.eventId == event.id) dao.deleteRollback(event.conversationId)
        dao.deleteEvent(event.id, assistantId.toString()) == 1
    }

    suspend fun retainedFileReferences(urls: List<String>): Set<String> = urls.filter {
        dao.hasFileReference(JsonInstant.encodeToString(it))
    }.toSet()

    suspend fun originalArchives(owner: Uuid, offset: Int = 0) = dao.listOriginalArchives(owner.toString(), 30, offset.coerceAtLeast(0))
    suspend fun originalArchiveStats(owner: Uuid) = dao.originalStats(owner.toString())

    /** Human reader only. No generation, executable tools, FTS indexing or active context insertion. */
    suspend fun originalArchiveNode(owner: Uuid, eventId: String, index: Int): MessageNode = database.withTransaction {
        check(dao.getEvent(eventId, owner.toString()) != null) { "档案不存在或不属于当前 AI。" }
        val archive = checkNotNull(dao.getOriginalArchive(eventId)) { "档案已删除。" }
        require(index in 0 until archive.nodeCount)
        val body = StringBuilder()
        var offset = 0
        while (true) {
            val chunks = dao.originalChunks(eventId, index, 16, offset)
            if (chunks.isEmpty()) break
            chunks.forEachIndexed { i, chunk ->
                check(chunk.chunkIndex == offset + i && chunk.body.length <= 16_000) { "档案分块不完整。" }
                body.append(chunk.body)
                check(body.length <= 8_000_000) { "档案超过安全读取上限。" }
            }
            offset += chunks.size
        }
        JsonInstant.decodeFromString<MessageNode>(body.toString())
    }

    suspend fun deleteOriginalArchives(owner: Uuid, ids: Set<String>, all: Boolean = false): Int = database.withTransaction {
        require(ids.size <= 30 && (all || ids.isNotEmpty()))
        // Only this assistant's optional archives, never active chat or the latest rollback slot.
        dao.deleteOriginalArchives(owner.toString(), ids.toList(), all)
    }

    private suspend fun requireBaseline(expected: Conversation, baseline: Conversation): me.rerere.rikkahub.data.db.entity.ConversationEntity {
        require(expected.id == baseline.id && expected.assistantId == baseline.assistantId) { "compaction_baseline_owner_mismatch" }
        requireCompactionEpoch(expected.compactionEpoch, baseline.compactionEpoch)
        val stored = database.conversationDao().getConversationById(expected.id.toString())
            ?: error("compaction_conversation_missing")
        check(stored.assistantId == expected.assistantId.toString()) { "compaction_owner_changed" }
        requireCompactionEpoch(baseline.compactionEpoch, stored.compactionEpoch)
        if (compactionNodeFingerprint(readCurrent(stored.id)) != compactionNodeFingerprint(baseline.messageNodes)) {
            throw OrbisCompactionConflictException()
        }
        // A caller may append/update its live final assistant node, but not omit persisted history.
        check(baseline.messageNodes.size <= expected.messageNodes.size && baseline.messageNodes.indices.all {
            baseline.messageNodes[it].id == expected.messageNodes[it].id
        }) { "compaction_live_snapshot_missing_persisted_nodes" }
        val stablePrefix = (baseline.messageNodes.size - 1).coerceAtLeast(0)
        if (compactionNodeFingerprint(baseline.messageNodes.take(stablePrefix)) !=
            compactionNodeFingerprint(expected.messageNodes.take(stablePrefix))) {
            throw OrbisCompactionConflictException()
        }
        baseline.messageNodes.lastOrNull()?.let { previous ->
            val live = expected.messageNodes[baseline.messageNodes.lastIndex]
            check(previous.messages.all { old -> live.messages.any { it.id == old.id } }) {
                "compaction_live_snapshot_missing_persisted_branch"
            }
        }
        return stored
    }

    private suspend fun readCurrent(id: String): List<MessageNode> = buildList {
        var offset = 0
        while (true) {
            val page = database.messageNodeDao().getNodesOfConversationPaged(id, 32, offset)
            if (page.isEmpty()) break
            addAll(page.map { MessageNode(Uuid.parse(it.id), JsonInstant.decodeFromString<List<UIMessage>>(it.messages), it.selectIndex) })
            offset += page.size
        }
    }

    private suspend fun readBackup(id: String): List<MessageNode> = buildList {
        var offset = 0
        while (true) {
            val page = dao.getBackupNodes(id, 32, offset)
            if (page.isEmpty()) break
            addAll(page.map { MessageNode(Uuid.parse(it.nodeId), JsonInstant.decodeFromString<List<UIMessage>>(it.messages), it.selectIndex) })
            offset += page.size
        }
    }

    /** Insert-only normal conversation; attachment URLs are retained, not copied or removed. */
    private suspend fun insertIndependentArchive(archive: Conversation) {
        database.conversationDao().insert(encodeConversationEntity(archive))
        database.messageNodeDao().insertAll(archive.messageNodes.mapIndexed { index, node ->
            MessageNodeEntity(node.id.toString(), archive.id.toString(), index,
                encodeMessageNodeMessages(node.messages), node.selectIndex)
        })
        messageFtsManager.indexConversationInTransaction(archive)
    }

    private suspend fun replacePage(conversation: Conversation) {
        conversation.messageNodes.map { it.id.toString() }.chunked(500).forEach { ids ->
            check(!database.messageNodeDao().hasNodesOwnedByAnotherConversation(conversation.id.toString(), ids)) {
                "compaction_node_owned_by_another_conversation"
            }
        }
        database.conversationDao().update(encodeConversationEntity(conversation))
        database.messageNodeDao().deleteByConversation(conversation.id.toString())
        database.messageNodeDao().insertAll(conversation.messageNodes.mapIndexed { index, node ->
            MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                encodeMessageNodeMessages(node.messages), node.selectIndex)
        })
        messageFtsManager.indexConversationInTransaction(conversation)
    }

    private fun validateCompactionNodes(nodes: List<MessageNode>) {
        require(nodes.map { it.id }.distinct().size == nodes.size) { "compaction_duplicate_nodes" }
        require(nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices }) { "compaction_invalid_branch" }
    }

    private fun OrbisCompactionEventEntity.toModel(rollbackAvailable: Boolean) = OrbisCompactionEvent(
        id = Uuid.parse(id), conversationId = Uuid.parse(conversationId), assistantId = Uuid.parse(assistantId),
        windowTitle = windowTitle, createdAtEpochMillis = createdAt, beforeTokens = beforeTokens, afterTokens = afterTokens,
        beforeBasis = beforeBasis, afterBasis = afterBasis, summaryHash = summaryHash,
        summaryMessageId = Uuid.parse(summaryMessageId), keptRecent = keptRecent, status = status, rollbackAvailable = rollbackAvailable,
    )
}
