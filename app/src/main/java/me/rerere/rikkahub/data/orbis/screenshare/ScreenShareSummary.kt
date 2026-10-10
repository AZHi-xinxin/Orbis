package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

internal data class SavedScreenShareSummary(val sessionId: String, val owner: String, val conversation: String,
    val endedAt: Long, val reason: String, val summary: String) {
    fun message(): String = "请回顾我们这次屏幕共享；若有值得长期保留的经历，可按现有授权使用记忆工具整理，只有成功回执才说已保存。\n" +
        "以下是我明确选择带回原聊天的已保存文字总结，不是重新开启共享的请求。\n" +
        "session_id=$sessionId；结束原因=$reason。\n\n$summary"
}

/** Only completed saved records belonging to this exact original conversation are sendable. */
internal fun selectCompletedScreenShareSummary(records: List<JsonObject>, owner: String, conversation: String): SavedScreenShareSummary? =
    records.filter { (it["assistant_id"] as? JsonPrimitive)?.contentOrNull == owner &&
        (it["conversation_id"] as? JsonPrimitive)?.contentOrNull == conversation }
        .maxByOrNull { (it["started_at"] as? JsonPrimitive)?.longOrNull ?: (it["ended_at"] as? JsonPrimitive)?.longOrNull ?: 0L }
        ?.let { record ->
        fun value(key: String) = (record[key] as? JsonPrimitive)?.contentOrNull
        val session = value("session_id") ?: return@let null
        if (runCatching { Uuid.parse(session) }.isFailure || value("assistant_id") != owner ||
            value("conversation_id") != conversation || value("status") != "ended" ||
            (record["raw_frames_saved"] as? JsonPrimitive)?.booleanOrNull != false) return@let null
        val ended = (record["ended_at"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@let null
        val summary = value("summary")?.takeIf { it.isNotBlank() && it.length <= 100_000 } ?: return@let null
        SavedScreenShareSummary(session, owner, conversation, ended, value("reason").orEmpty().take(100), summary)
    }
