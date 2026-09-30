package me.rerere.rikkahub.data.orbis.contact

import kotlinx.serialization.Serializable

/** User choices only. Model tool arguments and imported history cannot change these switches. */
@Serializable
data class OrbisContactPreferences(
    val notificationAutoRead: Boolean = false,
    val notificationReadWhenLocked: Boolean = false,
    val allowIncomingCalls: Boolean = true,
    val rejectCooldownMinutes: Int = 15,
) {
    fun normalized() = copy(rejectCooldownMinutes = rejectCooldownMinutes.coerceIn(1, 120))
}
