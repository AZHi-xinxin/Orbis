package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.OrbisCompactionBackupNodeEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionEventEntity
import me.rerere.rikkahub.data.db.entity.OrbisCompactionRollbackEntity

@Dao
interface OrbisCompactionDAO {
    @Insert suspend fun insertOriginalArchive(archive: me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveEntity)
    @Insert suspend fun insertOriginalChunks(chunks: List<me.rerere.rikkahub.data.db.entity.OrbisOriginalChunkEntity>)

    @Query("SELECT a.event_id AS eventId, e.window_title AS windowTitle, e.created_at AS createdAt, a.node_count AS nodeCount, a.byte_size AS byteSize FROM orbis_original_archive a JOIN orbis_compaction_event e ON a.event_id = e.id JOIN conversationentity c ON e.conversation_id = c.id WHERE e.assistant_id = :owner AND c.assistant_id = :owner ORDER BY e.created_at DESC, e.id DESC LIMIT :limit OFFSET :offset")
    suspend fun listOriginalArchives(owner: String, limit: Int, offset: Int): List<me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveInfo>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(a.byte_size), 0) AS bytes FROM orbis_original_archive a JOIN orbis_compaction_event e ON a.event_id = e.id JOIN conversationentity c ON e.conversation_id = c.id WHERE e.assistant_id = :owner AND c.assistant_id = :owner")
    suspend fun originalStats(owner: String): me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveStats

    @Query("SELECT * FROM orbis_original_archive WHERE event_id = :eventId")
    suspend fun getOriginalArchive(eventId: String): me.rerere.rikkahub.data.db.entity.OrbisOriginalArchiveEntity?

    @Query("SELECT * FROM orbis_original_chunk WHERE event_id = :eventId AND node_index = :node ORDER BY chunk_index ASC LIMIT :limit OFFSET :offset")
    suspend fun originalChunks(eventId: String, node: Int, limit: Int, offset: Int): List<me.rerere.rikkahub.data.db.entity.OrbisOriginalChunkEntity>

    @Query("DELETE FROM orbis_original_archive WHERE event_id IN (SELECT e.id FROM orbis_compaction_event e JOIN conversationentity c ON c.id = e.conversation_id WHERE e.assistant_id = :owner AND c.assistant_id = :owner) AND (:all OR event_id IN (:ids))")
    suspend fun deleteOriginalArchives(owner: String, ids: List<String>, all: Boolean): Int
    @Insert suspend fun insertEvent(event: OrbisCompactionEventEntity)
    @Insert suspend fun insertRollback(slot: OrbisCompactionRollbackEntity)
    @Insert suspend fun insertBackupNodes(nodes: List<OrbisCompactionBackupNodeEntity>)

    @Query("SELECT * FROM orbis_compaction_event WHERE assistant_id = :assistantId AND (:conversationId IS NULL OR conversation_id = :conversationId) AND EXISTS(SELECT 1 FROM conversationentity c WHERE c.id = conversation_id AND c.assistant_id = :assistantId) ORDER BY created_at DESC, id DESC")
    suspend fun listEvents(assistantId: String, conversationId: String?): List<OrbisCompactionEventEntity>

    @Query("SELECT * FROM orbis_compaction_event WHERE id = :eventId AND assistant_id = :assistantId AND EXISTS(SELECT 1 FROM conversationentity c WHERE c.id = conversation_id AND c.assistant_id = :assistantId)")
    suspend fun getEvent(eventId: String, assistantId: String): OrbisCompactionEventEntity?

    @Query("SELECT * FROM orbis_compaction_rollback WHERE conversation_id = :conversationId")
    suspend fun getRollback(conversationId: String): OrbisCompactionRollbackEntity?

    /** A manual deletion changes the active page epoch, but not the retained rollback contents. */
    @Query("UPDATE orbis_compaction_rollback SET compaction_epoch = :nextEpoch WHERE conversation_id = :conversationId AND compaction_epoch = :expectedEpoch")
    suspend fun updateRollbackEpoch(conversationId: String, expectedEpoch: Long, nextEpoch: Long): Int

    @Query("SELECT * FROM orbis_compaction_backup_node WHERE conversation_id = :conversationId ORDER BY node_index ASC LIMIT :limit OFFSET :offset")
    suspend fun getBackupNodes(conversationId: String, limit: Int, offset: Int): List<OrbisCompactionBackupNodeEntity>

    @Query("DELETE FROM orbis_compaction_rollback WHERE conversation_id = :conversationId")
    suspend fun deleteRollback(conversationId: String)

    @Query("DELETE FROM orbis_compaction_event WHERE id = :eventId AND assistant_id = :assistantId")
    suspend fun deleteEvent(eventId: String, assistantId: String): Int

    @Query("UPDATE orbis_compaction_event SET status = 'rolled_back' WHERE id = :eventId")
    suspend fun markRolledBack(eventId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM orbis_compaction_backup_node WHERE instr(messages, :encodedFileUrl) > 0) OR EXISTS(SELECT 1 FROM orbis_original_archive WHERE instr(file_references, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean
}
