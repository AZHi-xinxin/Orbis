package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** Local undo data, deliberately outside parts/annotations consumed by provider adapters. */
@Serializable
data class DeletedToolRecord(
    val tool: UIMessagePart.Tool,
    /** Position in the conceptual parts list with every still-deleted tool reinserted. */
    val originalPartIndex: Int,
    val deletedAt: LocalDateTime,
    /** Integrity anchor only; after prose/order edits undo must not guess an insertion slot. */
    val undoContextHash: String = "",
)

/** Request/estimation boundary: local undo history must never become model input. */
fun UIMessage.withoutDeletedToolRecordData(): UIMessage =
    if (deletedToolRecords.isEmpty() && toolRecordRevision == 0L && toolRecordsUpdatedAt == null) this
    else copy(deletedToolRecords = emptyList(), toolRecordRevision = 0L, toolRecordsUpdatedAt = null)
