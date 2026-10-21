package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.*

@Dao
interface OrbisHistoryCacheDAO {
    @Query("SELECT * FROM orbis_history_scope WHERE conversation_id=:window")
    suspend fun scope(window: String): OrbisHistoryScopeEntity?
    @Upsert suspend fun putScope(scope: OrbisHistoryScopeEntity)
    @Query("DELETE FROM orbis_history_scope WHERE conversation_id=:window")
    suspend fun clear(window: String)
    @Query("SELECT * FROM orbis_history_slot WHERE conversation_id=:window ORDER BY node_order")
    suspend fun slots(window: String): List<OrbisHistorySlotEntity>
    @Query("DELETE FROM orbis_history_slot WHERE conversation_id=:window")
    suspend fun clearSlots(window: String)
    @Insert suspend fun putSlots(slots: List<OrbisHistorySlotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRemoved(rows: List<OrbisHistoryRemovedEntity>)
    @Query("DELETE FROM orbis_history_removed WHERE conversation_id=:window AND source_node_id=:node")
    suspend fun clearNode(window: String, node: String)
    @Query("DELETE FROM orbis_history_removed WHERE conversation_id=:window AND message_id=:message")
    suspend fun clearMessage(window: String, message: String)
    @Query("SELECT message_id AS messageId,source_node_id AS sourceNodeId,role,created_at AS createdAt,removed_at AS removedAt FROM orbis_history_removed WHERE conversation_id=:window AND chunk_index=0 ORDER BY removed_at DESC,message_id LIMIT :limit OFFSET :offset")
    suspend fun listRemoved(window: String, limit: Int, offset: Int): List<OrbisHistoryRemovedInfo>
    @Query("SELECT * FROM orbis_history_removed WHERE conversation_id=:window AND message_id=:message ORDER BY chunk_index LIMIT 514")
    suspend fun removedChunks(window: String, message: String): List<OrbisHistoryRemovedEntity>
}
