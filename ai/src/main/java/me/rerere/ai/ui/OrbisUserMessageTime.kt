package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Captured at acceptance, never inferred from a legacy local date or refreshed on replay. */
@Serializable
data class OrbisUserMessageTime(
    val epochMillis: Long,
    val zoneId: String,
    val offsetSeconds: Int,
)
