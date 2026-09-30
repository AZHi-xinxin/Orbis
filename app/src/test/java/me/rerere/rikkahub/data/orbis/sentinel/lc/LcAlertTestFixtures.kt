package me.rerere.rikkahub.data.orbis.sentinel.lc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val LC_TEST_NOW = 1_800_000_000_000L
internal val LC_TEST_CONFIG = LcAlertPolicyConfig(enabled = true, prompt = "  AI-authored notice\nKeep this exact.  ")
internal const val LC_TEST_TARGET = "assistant-1:conversation-1"

internal fun lcTestPayload(
    id: String = "event-00001",
    type: String = "visual_interaction",
    at: Long = LC_TEST_NOW,
    overrides: Map<String, JsonElement> = emptyMap(),
): String {
    val fields = linkedMapOf<String, JsonElement>(
        "event_id" to JsonPrimitive(id), "type" to JsonPrimitive(type),
        "timestamp" to JsonPrimitive(at.toBigDecimal().movePointLeft(3)),
        "app_package" to JsonPrimitive("com.example.reader"), "app_label" to JsonPrimitive(" Reader "),
    )
    if (type == "visual_interaction") fields.putAll(mapOf(
        "reason" to JsonPrimitive("visual_observation"), "message" to JsonPrimitive(" A visible page. "),
    ))
    if (type == "app_timeout") fields.putAll(mapOf(
        "usage_basis" to JsonPrimitive("continuous_app"), "usage_threshold_minutes" to JsonPrimitive(60),
        "duration_minutes" to JsonPrimitive(65), "usage_snapshot_at" to JsonPrimitive(at.toBigDecimal().movePointLeft(3)),
    ))
    fields.putAll(overrides)
    return JsonObject(fields).toString()
}

internal class LcTestStorage {
    @Volatile var raw: String? = null
    var writes: Int = 0
    fun store() = LcAlertDecisionStore(read = { raw }, write = { raw = it; writes++ })
}
