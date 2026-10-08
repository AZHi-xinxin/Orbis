package me.rerere.rikkahub.data.orbis.memory

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

/** Deliberately unrelated to legacy MemoryEntity or companion/LC storage. */
@Entity(tableName = "orbis_memory_note", primaryKeys = ["assistant_id", "id"],
    indices = [Index(value = ["assistant_id", "deleted", "state", "updated_at"])])
data class OrbisMemoryEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    val id: String,
    val body: String,
    val summary: String,
    @ColumnInfo("body_chars") val bodyChars: Int,
    @ColumnInfo("summary_chars") val summaryChars: Int,
    val state: String,
    @ColumnInfo("previous_state") val previousState: String,
    val tags: String,
    val keywords: String,
    val important: Boolean,
    @ColumnInfo("min_interval_turns") val minIntervalTurns: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    val revision: Int,
    val deleted: Boolean,
)

/** Every revision is a complete immutable original, including paused/deleted transitions. */
@Entity(tableName = "orbis_memory_revision", primaryKeys = ["assistant_id", "id", "revision"])
data class OrbisMemoryRevisionEntity(@Embedded val note: OrbisMemoryEntity)

@Entity(tableName = "orbis_memory_operation", primaryKeys = ["assistant_id", "operation_key"])
data class OrbisMemoryOperationEntity(
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("operation_key") val operationKey: String,
    @ColumnInfo("payload_hash") val payloadHash: String,
    @ColumnInfo("result_json") val resultJson: String,
    @ColumnInfo("created_at") val createdAt: Long,
)

data class OrbisMemoryMetadataRow(
    val id: String, val state: String, val tags: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    val revision: Int, val deleted: Boolean,
)

data class OrbisMemoryCandidateRow(
    val id: String, val state: String, val summary: String, val body: String?,
    @ColumnInfo("pin_text") val pinText: String?,
    val tags: String, val keywords: String, val important: Boolean,
    @ColumnInfo("min_interval_turns") val minIntervalTurns: Int,
    val revision: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

data class OrbisMemoryPinRow(val id: String, val text: String)
