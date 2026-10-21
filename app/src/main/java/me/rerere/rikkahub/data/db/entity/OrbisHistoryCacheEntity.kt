package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Current-window cache, never copied into compaction archives, forks or prompt input. */
@Entity(tableName = "orbis_history_scope", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"],
    onDelete = ForeignKey.CASCADE)])
data class OrbisHistoryScopeEntity(
    @PrimaryKey @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("compaction_epoch") val compactionEpoch: Long,
)

@Entity(tableName = "orbis_history_slot", primaryKeys = ["conversation_id", "node_id"],
    foreignKeys = [ForeignKey(entity = OrbisHistoryScopeEntity::class,
        parentColumns = ["conversation_id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)])
data class OrbisHistorySlotEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("node_id") val nodeId: String,
    @ColumnInfo("node_order") val nodeOrder: Int,
)

@Entity(tableName = "orbis_history_removed", primaryKeys = ["conversation_id", "message_id", "chunk_index"],
    foreignKeys = [ForeignKey(entity = OrbisHistoryScopeEntity::class,
        parentColumns = ["conversation_id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversation_id", "source_node_id"])])
data class OrbisHistoryRemovedEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("source_node_id") val sourceNodeId: String,
    val role: String,
    @ColumnInfo("created_at") val createdAt: String,
    @ColumnInfo("removed_at") val removedAt: Long,
    @ColumnInfo("chunk_index") val chunkIndex: Int,
    @ColumnInfo("chunk_count") val chunkCount: Int,
    /** Bounded UTF-8-safe fragment; never a cursor-sized historical message cell. */
    val body: String,
)

data class OrbisHistoryRemovedInfo(
    val messageId: String,
    val sourceNodeId: String,
    val role: String,
    val createdAt: String,
    val removedAt: Long,
)
