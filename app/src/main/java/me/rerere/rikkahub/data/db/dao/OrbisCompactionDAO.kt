package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.OrbisCompactionBackupNodeEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionEventEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionRollbackEntity

@Dao
interface OrbisCompactionDAO {
    @Insert suspend fun insertEvent(event: OrbisCompactionEventEntity)
    @Insert suspend fun insertRollback(slot: OrbisCompactionRollbackEntity)
    @Insert suspend fun insertBackupNodes(nodes: List<OrbisCompactionBackupNodeEntity>)

    @Query("SELECT * FROM orbis_compaction_event WHERE assistant_id = :assistantId AND (:conversationId IS NULL OR conversation_id = :conversationId) AND EXISTS(SELECT 1 FROM conversationentity c WHERE c.id = conversation_id AND c.assistant_id = :assistantId) ORDER BY created_at DESC, id DESC")
    suspend fun listEvents(assistantId: String, conversationId: String?): List<OrbisCompactionEventEntity>

    @Query("SELECT * FROM orbis_compaction_event WHERE id = :eventId AND assistant_id = :assistantId AND EXISTS(SELECT 1 FROM conversationentity c WHERE c.id = conversation_id AND c.assistant_id = :assistantId)")
    suspend fun getEvent(eventId: String, assistantId: String): OrbisCompactionEventEntity?

    @Query("SELECT * FROM orbis_compaction_rollback WHERE conversation_id = :conversationId")
    suspend fun getRollback(conversationId: String): OrbisCompactionRollbackEntity?

    @Query("SELECT * FROM orbis_compaction_backup_node WHERE conversation_id = :conversationId ORDER BY node_index ASC LIMIT :limit OFFSET :offset")
    suspend fun getBackupNodes(conversationId: String, limit: Int, offset: Int): List<OrbisCompactionBackupNodeEntity>

    @Query("DELETE FROM orbis_compaction_rollback WHERE conversation_id = :conversationId")
    suspend fun deleteRollback(conversationId: String)

    @Query("DELETE FROM orbis_compaction_event WHERE id = :eventId AND assistant_id = :assistantId")
    suspend fun deleteEvent(eventId: String, assistantId: String): Int

    @Query("UPDATE orbis_compaction_event SET status = 'rolled_back' WHERE id = :eventId")
    suspend fun markRolledBack(eventId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM orbis_compaction_backup_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean
}
