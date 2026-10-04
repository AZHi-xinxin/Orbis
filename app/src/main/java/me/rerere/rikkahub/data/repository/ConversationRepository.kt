package me.rerere.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.encodeMessageNodeMessages
import me.rerere.rikkahub.data.db.MAX_LEGACY_NODE_COMPARISON_BYTES
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.preserveEventPresentation
import me.rerere.rikkahub.data.model.withEventPresentation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.partsWithToolUndoAttachments
import me.rerere.rikkahub.data.model.preserveToolRecordEdits
import me.rerere.rikkahub.data.model.OrbisToolRecordEdit
import me.rerere.rikkahub.data.model.withToolRecordEdit
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val voiceCalls: me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository? = null,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl)) ||
            database.orbisCompactionDao().hasFileReference(JsonInstant.encodeToString(fileUrl)) || hasVoiceFileReference(fileUrl)

    suspend fun hasCompactionFileReference(fileUrl: String): Boolean =
        database.orbisCompactionDao().hasFileReference(JsonInstant.encodeToString(fileUrl)) || hasVoiceFileReference(fileUrl)

    /** Before a live page is persisted, its old rows still refer to removed files. Check other
     * windows (including independent manual archives), plus rollback/voice ownership instead. */
    suspend fun hasExternalFileReference(fileUrl: String, editedConversationId: Uuid): Boolean =
        messageNodeDAO.hasFileReferenceOutsideConversation(JsonInstant.encodeToString(fileUrl), editedConversationId.toString()) ||
            hasCompactionFileReference(fileUrl)

    private suspend fun hasVoiceFileReference(fileUrl: String): Boolean {
        val archive = voiceCalls ?: return false
        val encoded = JsonInstant.encodeToString(fileUrl)
        var offset = 0
        while (true) {
            val page = archive.list(limit = 100, offset = offset)
            if (page.any { it.sourceNodesJson?.contains(encoded) == true }) return true
            if (page.size < 100) return false
            offset += page.size
        }
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10, includeConsultations: Boolean = false): List<Conversation> {
        return (if (includeConsultations) conversationDAO.getRecentAssistantContext(assistantId.toString(), limit)
        else conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        )).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    /** Human presentation guard; the normal assistant engine still reads the full conversation. */
    suspend fun isConsultationConversation(uuid: Uuid): Boolean =
        conversationDAO.getConversationById(uuid.toString())?.consultationBinding?.isNotEmpty() == true

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            messageFtsManager.indexConversationInTransaction(conversation)
        }
    }

    /** Additive import: all chat rows and search indexes commit together, never REPLACE a conversation. */
    suspend fun insertImportedConversations(conversations: List<Conversation>): Int = database.withTransaction {
        var inserted = 0
        conversations.forEach { conversation ->
            if (!conversationDAO.existsById(conversation.id.toString())) {
                conversationDAO.insert(conversationToConversationEntity(conversation))
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                messageFtsManager.indexConversationInTransaction(conversation)
                inserted++
            }
        }
        inserted
    }

    suspend fun updateConversation(conversation: Conversation, requireExistingOwner: Uuid? = null) {
        database.withTransaction {
            // Dedicated prompt edits own this column. Other full-state callers can hold an old
            // Conversation snapshot (for example title/assistant changes or queued messages).
            val stored = conversationDAO.getConversationById(conversation.id.toString())
            if (stored != null) me.rerere.rikkahub.data.model.requireCompactionEpoch(
                conversation.compactionEpoch, stored.compactionEpoch,
            )
            if (requireExistingOwner != null) {
                check(stored != null && stored.assistantId == requireExistingOwner.toString()) { "event_target_missing_or_changed" }
            }
            if (conversation.isConsultation || stored?.consultationBinding?.isNotEmpty() == true) {
                check(stored != null && stored.assistantId == conversation.assistantId.toString() &&
                    stored.consultationBinding == conversationToConversationEntity(conversation).consultationBinding) {
                    "consultation_target_missing_or_changed"
                }
            }
            // A streaming/full-state snapshot can predate a committed card read/fold edit.
            // Merge only its flags, for the exact event identity AND unchanged payload.
            val nodes = conversation.messageNodes.map { node ->
                val metadata = messageNodeDAO.getNodeStorageMetadata(node.id.toString())
                check(metadata == null || metadata.conversationId == conversation.id.toString()) { "message_node_owner_changed" }
                if (metadata != null && metadata.messagesBytes > MessageNodeBudget.MAX_NODE_BYTES) {
                    // An old oversized cell must not enter a CursorWindow merely to prove it is
                    // unchanged. Bound the candidate encoding too; SQL compares the raw bytes.
                    if (metadata.messagesBytes > MAX_LEGACY_NODE_COMPARISON_BYTES)
                        throw MessageNodeCapacityException("legacy_message_node_comparison_limit")
                    val candidate = MessageNodeBudget.encodeNode(node.messages, MAX_LEGACY_NODE_COMPARISON_BYTES)
                    if (!messageNodeDAO.hasExactMessages(conversation.id.toString(), node.id.toString(), candidate))
                        throw MessageNodeCapacityException("legacy_message_node_changed")
                    node
                } else if (stored?.assistantId == conversation.assistantId.toString()) {
                    val previous = messageNodeDAO.getBoundedNodeOfConversation(conversation.id.toString(), node.id.toString())
                    if (previous != null) {
                        val committedNode = MessageNode(
                        id = Uuid.parse(previous.id),
                        messages = JsonInstant.decodeFromString<List<UIMessage>>(previous.messages),
                        selectIndex = previous.selectIndex,
                        )
                        preserveEventPresentation(preserveToolRecordEdits(node, committedNode), committedNode)
                    } else node
                } else node
            }
            conversationDAO.update(
                conversationToConversationEntity(conversation).let { incoming ->
                    if (stored != null) incoming.copy(orbisPrompt = stored.orbisPrompt) else incoming
                }
            )
            // Preserve byte-identical historical rows instead of deleting and rebinding them.
            saveMessageNodes(conversation.id.toString(), nodes)
            // The search index shares the page transaction: a delayed pre-compaction index update
            // must never re-expose the old page after the compacted page has committed.
            messageFtsManager.indexConversationInTransaction(conversation.copy(messageNodes = nodes))
        }
    }

    /** Called only after the independent call archive has been durably verified. */
    suspend fun commitVoiceCallArchive(
        expected: Conversation, callId: String, archived: List<MessageNode>, summary: UIMessage,
    ): Conversation = database.withTransaction {
        val stored = checkNotNull(conversationDAO.getConversationById(expected.id.toString())) {
            "通话所属聊天已不存在；归档仍在通话记录库。"
        }
        check(stored.assistantId == expected.assistantId.toString()) { "通话所属 AI 已变更，未修改聊天。" }
        me.rerere.rikkahub.data.model.requireCompactionEpoch(expected.compactionEpoch, stored.compactionEpoch)
        val current = conversationEntityToConversation(stored, loadMessageNodes(stored.id))
        val nodes = me.rerere.rikkahub.data.orbis.voice.collapseVoiceCallNodes(current.messageNodes, callId, archived, summary)
        if (nodes == current.messageNodes) return@withTransaction current
        val next = current.copy(messageNodes = nodes, compactionEpoch = current.compactionEpoch + 1,
            chatSuggestions = emptyList(), updateAt = Instant.now())
        conversationDAO.update(conversationToConversationEntity(next))
        saveMessageNodes(stored.id, nodes)
        messageFtsManager.indexConversationInTransaction(next)
        next
    }

    /** Existing-row, metadata-only update. Text, ordering, alternatives and FTS are untouched. */
    suspend fun saveOrbisEventPresentation(
        conversationId: Uuid,
        expectedOwner: Uuid,
        edit: OrbisEventPresentationEdit,
    ) {
        database.withTransaction {
            val stored = conversationDAO.getConversationById(conversationId.toString())
            check(stored != null && stored.assistantId == expectedOwner.toString()) { "event_target_missing_or_changed" }
            val node = boundedNodeForEdit(conversationId.toString(), edit.nodeId.toString())
                ?: error("event_message_missing_or_changed")
            val messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            val target = messages.singleOrNull { it.id == edit.messageId }
                ?: error("event_message_missing_or_changed")
            val updated = target.withEventPresentation(edit)
            val serialized = encodeMessageNodeMessages(messages.map { if (it.id == target.id) updated else it })
            check(messageNodeDAO.updateMessages(conversationId.toString(), node.id, serialized) == 1) {
                "event_message_missing_or_changed"
            }
        }
    }

    /** Existing chats get a single-column update; their message tree and attachments are untouched. */
    suspend fun saveToolRecordEdit(
        expected: Conversation,
        edit: OrbisToolRecordEdit,
        now: kotlinx.datetime.LocalDateTime,
    ): UIMessage = database.withTransaction {
        val stored = conversationDAO.getConversationById(expected.id.toString())
        check(stored != null && stored.assistantId == expected.assistantId.toString()) {
            "工具记录所属聊天已变更，未修改对话。"
        }
        me.rerere.rikkahub.data.model.requireCompactionEpoch(expected.compactionEpoch, stored.compactionEpoch)
        val expectedNode = expected.messageNodes.singleOrNull { it.currentMessage.id == edit.messageId }
            ?: error("这条回复已切换或不存在，请刷新后再操作。")
        val row = boundedNodeForEdit(stored.id, expectedNode.id.toString())
            ?: error("工具记录所属回复不存在。")
        val messages = JsonInstant.decodeFromString<List<UIMessage>>(row.messages)
        val target = messages.getOrNull(row.selectIndex)
        check(target != null && target.id == edit.messageId && target.parts == expectedNode.currentMessage.parts &&
            target.toolRecordRevision == expectedNode.currentMessage.toolRecordRevision &&
            target.deletedToolRecords == expectedNode.currentMessage.deletedToolRecords) {
            "回复或工具记录已变更，请刷新后再操作。"
        }
        val edited = target.withToolRecordEdit(edit, now)
        check(messageNodeDAO.updateMessages(stored.id, row.id, encodeMessageNodeMessages(messages.map {
            if (it.id == edit.messageId) edited else it
        })) == 1) { "工具记录所属回复不存在。" }
        // Normal FTS sees prose only; still commit its refresh with the edited page, never later.
        val current = conversationEntityToConversation(stored, loadMessageNodes(stored.id))
        messageFtsManager.indexConversationInTransaction(current)
        edited
    }

    /** Existing chats get a single-column update; their message tree and attachments are untouched. */
    suspend fun saveOrbisPrompt(conversation: Conversation, prompt: OrbisConversationPrompt) {
        database.withTransaction {
            if (conversationDAO.existsById(conversation.id.toString())) {
                check(conversationDAO.updateOrbisPrompt(
                    conversation.id.toString(), JsonInstant.encodeToString(prompt),
                ) == 1) { "会话已变更，请重新打开后再保存提示词" }
            } else {
                // Explicitly saved prompt settings also persist for a brand-new, empty chat.
                conversationDAO.insert(conversationToConversationEntity(conversation.copy(orbisPrompt = prompt)))
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            }
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        val archivedFiles = mutableSetOf<String>()
        var archiveOffset = 0
        while (true) {
            val page = database.orbisCompactionDao().getBackupNodes(conversation.id.toString(), 32, archiveOffset)
            if (page.isEmpty()) break
            page.forEach { node ->
                archivedFiles += JsonInstant.decodeFromString<List<UIMessage>>(node.messages).flatMap { it.partsWithToolUndoAttachments() }.localFileUrls()
            }
            archiveOffset += page.size
        }
        database.withTransaction {
            database.openHelper.writableDatabase.execSQL("DELETE FROM message_fts WHERE conversation_id = ?",
                arrayOf(conversation.id.toString()))
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        val candidates = (fullConversation.files.map { it.toString() } + archivedFiles).distinct()
        filesManager.deleteChatFiles(candidates.filterNot { hasFileReference(it) }.map { android.net.Uri.parse(it) })
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
        includeConsultations: Boolean = false,
    ) = messageFtsManager.search(keyword, sort, assistantId?.toString(), includeConsultations)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            database.withTransaction {
                val entity = conversationDAO.getConversationById(id) ?: return@withTransaction
                val nodes = loadMessageNodes(entity.id)
                messageFtsManager.indexConversationInTransaction(conversationEntityToConversation(entity, nodes))
            }
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity =
        encodeConversationEntity(conversation)

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation = decodeConversationEntity(conversationEntity, messageNodes)

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /** Title and FTS metadata only: does not load, replace or delete message/attachment rows. */
    suspend fun renameConversation(conversationId: Uuid, value: String): Boolean {
        val title = me.rerere.rikkahub.data.model.normalizeConversationTitle(value)
        return database.withTransaction {
            val updated = conversationDAO.updateTitle(conversationId.toString(), title) > 0
            if (updated) database.openHelper.writableDatabase.execSQL(
                "UPDATE message_fts SET title = ? WHERE conversation_id = ?",
                arrayOf(title, conversationId.toString()),
            )
            updated
        }
    }

    suspend fun getConversationSummaryOfAssistant(conversationId: Uuid, assistantId: Uuid): Conversation? =
        conversationDAO.getSummaryOfAssistant(conversationId.toString(), assistantId.toString())
            ?.let { conversationSummaryToConversation(it) }

    /** Atomic compare-and-set: a model request cannot overwrite a later manual title edit. */
    suspend fun applyGeneratedTitleIfUnchanged(conversationId: Uuid, assistantId: Uuid, expectedTitle: String, title: String): Boolean =
        database.withTransaction {
            val updated = conversationDAO.updateTitleIfUnchanged(conversationId.toString(), assistantId.toString(), expectedTitle, title) > 0
            if (updated) database.openHelper.writableDatabase.execSQL(
                "UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId.toString()),
            )
            updated
        }

    /** Optional, field-only CAS. A delayed suggestion is discarded after ANY durable history
     * change, even one which retains the epoch/count/last message (for example an old branch edit).
     * Page comparisons and the single-column UPDATE share one transaction; no message/FTS writes.
     */
    suspend fun applyGeneratedSuggestionsIfUnchanged(expected: Conversation, suggestions: List<String>): Boolean =
        database.withTransaction {
            val id = expected.id.toString()
            val stored = conversationDAO.getConversationById(id) ?: return@withTransaction false
            if (stored.assistantId != expected.assistantId.toString() ||
                stored.compactionEpoch != expected.compactionEpoch ||
                stored.updateAt != expected.updateAt.toEpochMilli()) return@withTransaction false

            val rows = messageNodeDAO.getNodeStorageMetadataOfConversation(id)
            if (rows.size != expected.messageNodes.size) return@withTransaction false
            rows.forEachIndexed { index, row ->
                val node = expected.messageNodes[index]
                if (row.conversationId != id || row.nodeIndex != index ||
                    row.id != node.id.toString() || row.selectIndex != node.selectIndex) return@withTransaction false
                if (row.messagesBytes > MAX_LEGACY_NODE_COMPARISON_BYTES) return@withTransaction false
                val candidate = try {
                    MessageNodeBudget.encodeNode(node.messages, if (row.messagesBytes > MessageNodeBudget.MAX_NODE_BYTES)
                        MAX_LEGACY_NODE_COMPARISON_BYTES else MessageNodeBudget.MAX_NODE_BYTES)
                } catch (_: MessageNodeCapacityException) { return@withTransaction false }
                if (!messageNodeDAO.hasExactMessages(id, row.id, candidate)) return@withTransaction false
            }
            conversationDAO.updateSuggestionsIfUnchanged(id, expected.assistantId.toString(),
                expected.compactionEpoch, expected.updateAt.toEpochMilli(),
                JsonInstant.encodeToString(suggestions.take(10))) == 1
        }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> = try {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                // A partial history must never become a saveable Conversation. In particular,
                // skipping a failed cursor page would let a later full save delete unseen rows.
                val page = messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    check(messages.isNotEmpty() && entity.selectIndex in messages.indices)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Serializer/SQLite exception text can contain private message data. Do not retain the
        // cause, print it, return an empty history, or publish already-decoded pages.
        throw IllegalStateException("对话加载失败，原记录未改动。请返回，到「数据与本地备份 → 会话自助恢复」检查；不要清除数据或卸载。")
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val ids = nodes.map { it.id.toString() }.toSet()
        check(ids.size == nodes.size) { "duplicate_message_node" }
        val previous = messageNodeDAO.getNodeStorageMetadataOfConversation(conversationId).associateBy { it.id }
        // Encode and persist one bounded cell at a time, not an additional copy of the full page.
        nodes.forEachIndexed { index, node ->
            val old = previous[node.id.toString()]
            if (old != null && old.messagesBytes > MAX_LEGACY_NODE_COMPARISON_BYTES)
                throw MessageNodeCapacityException("legacy_message_node_comparison_limit")
            val encoded = MessageNodeBudget.encodeNode(node.messages, if (old != null && old.messagesBytes > MessageNodeBudget.MAX_NODE_BYTES)
                MAX_LEGACY_NODE_COMPARISON_BYTES else MessageNodeBudget.MAX_NODE_BYTES)
            if (old != null && messageNodeDAO.hasExactMessages(conversationId, old.id, encoded)) {
                if (old.nodeIndex != index || old.selectIndex != node.selectIndex) {
                    check(messageNodeDAO.updatePlacementIfExact(conversationId, old.id, encoded, index, node.selectIndex) == 1)
                }
                return@forEachIndexed
            }
            if (old != null && old.messagesBytes > MessageNodeBudget.MAX_NODE_BYTES)
                throw MessageNodeCapacityException("legacy_message_node_changed")
            messageNodeDAO.insert(MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = encoded,
                selectIndex = node.selectIndex
            ))
        }
        previous.keys.filterNot { it in ids }.forEach { messageNodeDAO.deleteById(it) }
    }

    private suspend fun boundedNodeForEdit(conversationId: String, nodeId: String): MessageNodeEntity? {
        val metadata = messageNodeDAO.getNodeStorageMetadata(nodeId) ?: return null
        if (metadata.conversationId != conversationId) return null
        if (metadata.messagesBytes > MessageNodeBudget.MAX_NODE_BYTES)
            throw MessageNodeCapacityException("legacy_message_node_edit_limit")
        return messageNodeDAO.getBoundedNodeOfConversation(conversationId, nodeId)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/** Exact request-history identity for optional in-memory suggestion publication. */
internal fun matchesGeneratedSuggestionSnapshot(expected: Conversation, current: Conversation): Boolean =
    current.id == expected.id && current.assistantId == expected.assistantId &&
        current.compactionEpoch == expected.compactionEpoch && current.updateAt == expected.updateAt &&
        current.messageNodes == expected.messageNodes
