package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "orbis_compaction_event",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversation_id"), Index("assistant_id"), Index("created_at")],
)
data class OrbisCompactionEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("window_title") val windowTitle: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("before_tokens") val beforeTokens: Long,
    @ColumnInfo("after_tokens") val afterTokens: Long,
    @ColumnInfo("before_basis") val beforeBasis: String,
    @ColumnInfo("after_basis") val afterBasis: String,
    @ColumnInfo("summary_hash") val summaryHash: String,
    @ColumnInfo("summary_message_id") val summaryMessageId: String,
    @ColumnInfo("kept_recent") val keptRecent: Int,
    val status: String,
)

/** One slot per conversation. Replaced atomically when another compaction succeeds. */
@Entity(
    tableName = "orbis_compaction_rollback",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("event_id")],
)
data class OrbisCompactionRollbackEntity(
    @PrimaryKey @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("summary_node_id") val summaryNodeId: String,
    @ColumnInfo("compacted_node_ids") val compactedNodeIds: String,
    @ColumnInfo("compaction_epoch") val compactionEpoch: Long,
)

/** Per-node rows avoid placing a 350K+ context into one SQLite cursor-window blob. */
@Entity(
    tableName = "orbis_compaction_backup_node",
    primaryKeys = ["conversation_id", "node_id"],
    foreignKeys = [ForeignKey(entity = OrbisCompactionRollbackEntity::class, parentColumns = ["conversation_id"],
        childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
)
data class OrbisCompactionBackupNodeEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("node_id") val nodeId: String,
    @ColumnInfo("node_index") val nodeIndex: Int,
    val messages: String,
    @ColumnInfo("select_index") val selectIndex: Int,
)
