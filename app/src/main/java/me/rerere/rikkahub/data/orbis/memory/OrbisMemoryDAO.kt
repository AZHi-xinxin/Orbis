package me.rerere.rikkahub.data.orbis.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OrbisMemoryDAO {
    @Insert suspend fun insert(note: OrbisMemoryEntity)
    @Update suspend fun update(note: OrbisMemoryEntity): Int
    @Insert suspend fun insertRevision(note: OrbisMemoryRevisionEntity)
    @Insert suspend fun insertOperation(operation: OrbisMemoryOperationEntity)

    @Query("SELECT * FROM orbis_memory_note WHERE assistant_id=:assistantId AND id=:id")
    suspend fun get(assistantId: String, id: String): OrbisMemoryEntity?

    @Query("SELECT * FROM orbis_memory_operation WHERE assistant_id=:assistantId AND operation_key=:key")
    suspend fun operation(assistantId: String, key: String): OrbisMemoryOperationEntity?

    @Query("SELECT * FROM orbis_memory_note WHERE assistant_id=:assistantId AND (:deleted OR deleted=0) AND (:query='' OR instr(lower(body),lower(:query))>0 OR instr(lower(summary),lower(:query))>0 OR instr(lower(tags),lower(:query))>0 OR instr(lower(keywords),lower(:query))>0) ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
    suspend fun search(assistantId: String, query: String, deleted: Boolean, limit: Int, offset: Int): List<OrbisMemoryEntity>

    @Query("SELECT * FROM orbis_memory_revision WHERE assistant_id=:assistantId AND id=:id AND revision=:revision")
    suspend fun revision(assistantId: String, id: String, revision: Int): OrbisMemoryRevisionEntity?

    @Query("SELECT * FROM orbis_memory_revision WHERE assistant_id=:assistantId AND id=:id ORDER BY revision DESC LIMIT :limit OFFSET :offset")
    suspend fun history(assistantId: String, id: String, limit: Int, offset: Int): List<OrbisMemoryRevisionEntity>

    @Query("SELECT id,state,tags,created_at,updated_at,revision,deleted FROM orbis_memory_note WHERE assistant_id=:assistantId AND (:includeDeleted OR deleted=0) ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
    suspend fun metadata(assistantId: String, includeDeleted: Boolean, limit: Int, offset: Int): List<OrbisMemoryMetadataRow>

    @Query("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN deleted=0 AND state!='paused' THEN 1 ELSE 0 END),0) AS active,COALESCE(SUM(CASE WHEN deleted=0 AND state='paused' THEN 1 ELSE 0 END),0) AS paused,COALESCE(SUM(CASE WHEN deleted=0 AND state='pinned' THEN 1 ELSE 0 END),0) AS pinned,COALESCE(SUM(CASE WHEN deleted=1 THEN 1 ELSE 0 END),0) AS deleted FROM orbis_memory_note WHERE assistant_id=:assistantId")
    suspend fun stats(assistantId: String): OrbisMemoryStats

    @Query("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN deleted=0 AND state!='paused' THEN 1 ELSE 0 END),0) AS active,COALESCE(SUM(CASE WHEN deleted=0 AND state='paused' THEN 1 ELSE 0 END),0) AS paused,COALESCE(SUM(CASE WHEN deleted=0 AND state='pinned' THEN 1 ELSE 0 END),0) AS pinned,COALESCE(SUM(CASE WHEN deleted=1 THEN 1 ELSE 0 END),0) AS deleted FROM orbis_memory_note WHERE assistant_id=:assistantId")
    fun observeStats(assistantId: String): Flow<OrbisMemoryStats>

    @Query("SELECT id,CASE WHEN summary!='' THEN summary ELSE body END AS text FROM orbis_memory_note WHERE assistant_id=:assistantId AND deleted=0 AND state='pinned' ORDER BY id LIMIT 8")
    suspend fun pins(assistantId: String): List<OrbisMemoryPinRow>

    @Query("SELECT id,state,CASE WHEN summary_chars<=2000 THEN summary ELSE '' END AS summary,CASE WHEN body_chars<=300 THEN body ELSE NULL END AS body,CASE WHEN state='pinned' THEN CASE WHEN summary!='' THEN summary ELSE body END ELSE NULL END AS pin_text,tags,keywords,important,min_interval_turns,revision,created_at,updated_at FROM orbis_memory_note WHERE assistant_id=:assistantId AND deleted=0 AND (state='pinned' OR (state='conditional' AND (body_chars<=300 OR (summary!='' AND summary_chars<=2000)))) ORDER BY CASE WHEN state='pinned' THEN 0 ELSE 1 END,important DESC,updated_at DESC,id LIMIT :limit OFFSET :offset")
    suspend fun candidates(assistantId: String, limit: Int, offset: Int): List<OrbisMemoryCandidateRow>

    @Query("SELECT id FROM orbis_memory_note WHERE assistant_id=:assistantId AND id IN (:ids) AND deleted=0 AND state IN ('conditional','pinned')")
    suspend fun eligibleInjectionIds(assistantId: String, ids: List<String>): List<String>

    @Query("SELECT id,state,tags,created_at,updated_at,revision,deleted FROM orbis_memory_note WHERE assistant_id=:assistantId AND id!=:excludeId AND deleted=0 AND (instr(lower(body),lower(:term))>0 OR instr(lower(summary),lower(:term))>0 OR instr(lower(tags),lower(:term))>0) ORDER BY updated_at DESC,id LIMIT 5")
    suspend fun similar(assistantId: String, excludeId: String, term: String): List<OrbisMemoryMetadataRow>

    @Query("SELECT * FROM orbis_memory_note WHERE assistant_id=:assistantId AND id>:afterId ORDER BY id LIMIT :limit")
    suspend fun exportNotes(assistantId: String, afterId: String, limit: Int): List<OrbisMemoryEntity>

    @Query("SELECT * FROM orbis_memory_revision WHERE assistant_id=:assistantId AND (id>:afterId OR (id=:afterId AND revision>:afterRevision)) ORDER BY id,revision LIMIT :limit")
    suspend fun exportRevisions(assistantId: String, afterId: String, afterRevision: Int, limit: Int): List<OrbisMemoryRevisionEntity>
}
