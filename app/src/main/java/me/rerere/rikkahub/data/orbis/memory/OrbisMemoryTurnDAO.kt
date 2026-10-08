package me.rerere.rikkahub.data.orbis.memory

import androidx.room.*

/** Private request snapshots, never exposed through the human metadata / atlas APIs. */
@Entity(tableName = "orbis_memory_turn", primaryKeys = ["assistantId", "turnKey"])
data class OrbisMemoryTurnEntity(
    val assistantId: String, val turnKey: String, val ordinal: Long,
    val payload: String, val createdAt: Long,
)

@Entity(tableName = "orbis_memory_surfacing", primaryKeys = ["assistantId", "noteId"])
data class OrbisMemorySurfacingEntity(val assistantId: String, val noteId: String, val lastOrdinal: Long)

@Dao
interface OrbisMemoryTurnDAO {
    @Query("SELECT * FROM orbis_memory_turn WHERE assistantId=:owner AND turnKey=:key")
    suspend fun get(owner: String, key: String): OrbisMemoryTurnEntity?

    @Query("SELECT COALESCE(MAX(ordinal),0) FROM orbis_memory_turn WHERE assistantId=:owner")
    suspend fun latestOrdinal(owner: String): Long

    @Query("SELECT * FROM orbis_memory_surfacing WHERE assistantId=:owner AND noteId IN (:ids)")
    suspend fun lastSurfaced(owner: String, ids: List<String>): List<OrbisMemorySurfacingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(turn: OrbisMemoryTurnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordSurfacing(entries: List<OrbisMemorySurfacingEntity>)

    @Query("DELETE FROM orbis_memory_turn WHERE assistantId=:owner AND ordinal<:minimum")
    suspend fun prune(owner: String, minimum: Long)
}
