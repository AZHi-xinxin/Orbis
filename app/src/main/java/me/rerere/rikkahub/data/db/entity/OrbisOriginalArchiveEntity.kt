package me.rerere.rikkahub.data.db.entity

import androidx.room.*

@Entity(tableName = "orbis_original_archive", foreignKeys = [ForeignKey(entity = OrbisCompactionEventEntity::class,
    parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)])
data class OrbisOriginalArchiveEntity(
    @PrimaryKey @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("node_count") val nodeCount: Int,
    @ColumnInfo("byte_size") val byteSize: Long,
    @ColumnInfo("file_references") val fileReferences: String,
)

/** Small Unicode-safe rows, even when a single original tool message is very large. */
@Entity(tableName = "orbis_original_chunk", primaryKeys = ["event_id", "node_index", "chunk_index"],
    foreignKeys = [ForeignKey(entity = OrbisOriginalArchiveEntity::class, parentColumns = ["event_id"],
        childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)])
data class OrbisOriginalChunkEntity(
    @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("node_index") val nodeIndex: Int,
    @ColumnInfo("chunk_index") val chunkIndex: Int,
    val body: String,
)

data class OrbisOriginalArchiveInfo(val eventId: String, val windowTitle: String, val createdAt: Long,
    val nodeCount: Int, val byteSize: Long)
data class OrbisOriginalArchiveStats(val count: Int, val bytes: Long)
