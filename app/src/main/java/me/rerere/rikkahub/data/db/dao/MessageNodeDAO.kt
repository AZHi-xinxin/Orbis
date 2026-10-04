package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.MAX_LEGACY_NODE_COMPARISON_BYTES
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.db.measureEncodedMessageNode

@Dao
interface MessageNodeDAO {
    /** Scalar-only admission: never materialize a possibly oversized/invalid JSON cell in Java. */
    @Query("SELECT COUNT(*) AS nodeCount, COALESCE(SUM(length(CAST(messages AS BLOB))), 0) AS totalBytes, " +
        "COALESCE(MAX(length(CAST(messages AS BLOB))), 0) AS largestRowBytes " +
        "FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getRescueRawSize(conversationId: String): RescueRawSize

    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE conversation_id != :excludedConversationId AND instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReferenceOutsideConversation(encodedFileUrl: String, excludedConversationId: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId AND id = :nodeId LIMIT 1")
    suspend fun getNodeOfConversation(conversationId: String, nodeId: String): MessageNodeEntity?

    @Query("SELECT id, conversation_id AS conversationId, node_index AS nodeIndex, select_index AS selectIndex, " +
        "length(CAST(messages AS BLOB)) AS messagesBytes FROM message_node WHERE id = :nodeId LIMIT 1")
    suspend fun getNodeStorageMetadata(nodeId: String): MessageNodeStorageMetadata?

    @Query("SELECT id, conversation_id AS conversationId, node_index AS nodeIndex, select_index AS selectIndex, " +
        "length(CAST(messages AS BLOB)) AS messagesBytes FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodeStorageMetadataOfConversation(conversationId: String): List<MessageNodeStorageMetadata>

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId AND id = :nodeId " +
        "AND length(CAST(messages AS BLOB)) <= 786432 LIMIT 1")
    suspend fun getBoundedNodeOfConversation(conversationId: String, nodeId: String): MessageNodeEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE conversation_id = :conversationId AND id = :nodeId " +
        "AND CAST(messages AS BLOB) = CAST(:messages AS BLOB))")
    suspend fun hasExactMessages(conversationId: String, nodeId: String, messages: String): Boolean

    /** Existing large messages are never bound to an UPDATE column, even when unchanged. */
    @Query("UPDATE message_node SET node_index = :nodeIndex, select_index = :selectIndex " +
        "WHERE conversation_id = :conversationId AND id = :nodeId AND CAST(messages AS BLOB) = CAST(:messages AS BLOB)")
    suspend fun updatePlacementIfExact(conversationId: String, nodeId: String, messages: String, nodeIndex: Int, selectIndex: Int): Int

    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE id IN (:nodeIds) AND conversation_id != :conversationId)")
    suspend fun hasNodesOwnedByAnotherConversation(conversationId: String, nodeIds: List<String>): Boolean

    @Transaction
    suspend fun updateMessages(conversationId: String, nodeId: String, messages: String): Int {
        if (measureEncodedMessageNode(messages, MAX_LEGACY_NODE_COMPARISON_BYTES) > MessageNodeBudget.MAX_NODE_BYTES) {
            if (hasExactMessages(conversationId, nodeId, messages)) return 1
            throw MessageNodeCapacityException("message_node_storage_limit")
        }
        return updateMessagesWithinBudget(conversationId, nodeId, messages)
    }

    @Query("UPDATE message_node SET messages = :messages WHERE conversation_id = :conversationId AND id = :nodeId " +
        "AND length(CAST(:messages AS BLOB)) <= 786432")
    suspend fun updateMessagesWithinBudget(conversationId: String, nodeId: String, messages: String): Int

    @Transaction
    suspend fun updateMessagesIfUnchanged(conversationId: String, nodeId: String, expected: String, replacement: String): Int {
        measureEncodedMessageNode(expected, MAX_LEGACY_NODE_COMPARISON_BYTES)
        if (measureEncodedMessageNode(replacement, MAX_LEGACY_NODE_COMPARISON_BYTES) > MessageNodeBudget.MAX_NODE_BYTES) {
            if (replacement == expected && hasExactMessages(conversationId, nodeId, expected)) return 1
            throw MessageNodeCapacityException("message_node_storage_limit")
        }
        return updateMessagesIfUnchangedWithinBudget(conversationId, nodeId, expected, replacement)
    }

    @Query("UPDATE message_node SET messages = :replacement WHERE conversation_id = :conversationId AND id = :nodeId " +
        "AND CAST(messages AS BLOB) = CAST(:expected AS BLOB) AND length(CAST(:replacement AS BLOB)) <= 786432")
    suspend fun updateMessagesIfUnchangedWithinBudget(conversationId: String, nodeId: String, expected: String, replacement: String): Int

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Transaction
    suspend fun insertAll(nodes: List<MessageNodeEntity>) {
        nodes.forEach { insert(it) }
    }

    @Transaction
    suspend fun insert(node: MessageNodeEntity) {
        val previous = getNodeStorageMetadata(node.id)
        check(previous == null || previous.conversationId == node.conversationId) { "message_node_owner_changed" }
        if (measureEncodedMessageNode(node.messages, MAX_LEGACY_NODE_COMPARISON_BYTES) > MessageNodeBudget.MAX_NODE_BYTES) {
            if (updatePlacementIfExact(node.conversationId, node.id, node.messages, node.nodeIndex, node.selectIndex) == 1) return
            throw MessageNodeCapacityException("message_node_storage_limit")
        }
        // INSERT ... SELECT may report an older last_insert_rowid when its WHERE rejects a row.
        // Confirm the actual scalar identity and exact body instead of trusting that return value.
        insertWithinBudget(node.id, node.conversationId, node.nodeIndex, node.messages, node.selectIndex)
        val stored = getNodeStorageMetadata(node.id)
        check(stored != null && stored.conversationId == node.conversationId &&
            stored.nodeIndex == node.nodeIndex && stored.selectIndex == node.selectIndex &&
            hasExactMessages(node.conversationId, node.id, node.messages)) {
            "message_node_storage_write_failed"
        }
    }

    @Query("INSERT OR REPLACE INTO message_node(id, conversation_id, node_index, messages, select_index) " +
        "SELECT :nodeId, :conversationId, :nodeIndex, :messages, :selectIndex WHERE length(CAST(:messages AS BLOB)) <= 786432")
    suspend fun insertWithinBudget(nodeId: String, conversationId: String, nodeIndex: Int, messages: String, selectIndex: Int): Long

    @Transaction
    suspend fun update(node: MessageNodeEntity) {
        val previous = getNodeStorageMetadata(node.id) ?: return
        check(previous.conversationId == node.conversationId) { "message_node_owner_changed" }
        if (measureEncodedMessageNode(node.messages, MAX_LEGACY_NODE_COMPARISON_BYTES) > MessageNodeBudget.MAX_NODE_BYTES) {
            if (updatePlacementIfExact(node.conversationId, node.id, node.messages, node.nodeIndex, node.selectIndex) == 1) return
            throw MessageNodeCapacityException("message_node_storage_limit")
        }
        check(updateWithinBudget(node.id, node.conversationId, node.nodeIndex, node.messages, node.selectIndex) == 1)
    }

    @Query("UPDATE message_node SET node_index = :nodeIndex, messages = :messages, select_index = :selectIndex " +
        "WHERE id = :nodeId AND conversation_id = :conversationId AND length(CAST(:messages AS BLOB)) <= 786432")
    suspend fun updateWithinBudget(nodeId: String, conversationId: String, nodeIndex: Int, messages: String, selectIndex: Int): Int

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageNodeStorageMetadata(val id: String, val conversationId: String, val nodeIndex: Int,
    val selectIndex: Int, val messagesBytes: Long)

data class RescueRawSize(val nodeCount: Long, val totalBytes: Long, val largestRowBytes: Long)

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )
