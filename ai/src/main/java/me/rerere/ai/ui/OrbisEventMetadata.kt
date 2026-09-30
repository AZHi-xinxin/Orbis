package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Host provenance, not an instruction role. Text remains unchanged in persisted message parts. */
@Serializable
data class OrbisEventMetadata(
    val recordId: String,
    val source: String,
    val eventId: String,
    val receivedAt: Long,
    val read: Boolean = false,
    val collapsed: Boolean = true,
    /** Sender-reported rule trigger time in epoch milliseconds; absent in older records. */
    val occurredAt: Long? = null,
)
