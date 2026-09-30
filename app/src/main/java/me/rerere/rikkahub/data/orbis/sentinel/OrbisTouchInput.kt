package me.rerere.rikkahub.data.orbis.sentinel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** This credential may report a physical touch, never choose its message or recipient. */
@Serializable
internal data class OrbisTouchInput(val event_id: String, val occurred_at: Long)

internal fun parseOrbisTouchInput(body: String): OrbisTouchInput {
    require(body.toByteArray(Charsets.UTF_8).size <= 2048) { "event_request_too_large" }
    return Json.decodeFromString<OrbisTouchInput>(body).also {
        require(it.event_id.matches(Regex("[A-Za-z0-9:._-]{1,180}"))) { "invalid_event_id" }
        require(it.occurred_at > 0) { "invalid_event_time" }
    }
}
