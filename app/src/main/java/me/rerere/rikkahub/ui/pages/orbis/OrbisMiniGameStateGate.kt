package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import java.util.concurrent.atomic.AtomicBoolean

/** One mounted frame, one session. JSON data only; no path, network or arbitrary native access. */
internal class OrbisMiniGameStateGate(
    private val read: () -> Pair<Long, String?>,
    private val save: (Long, String) -> Long,
) {
    private val open = AtomicBoolean(true)
    @Synchronized fun close() { open.set(false) }
    @Synchronized fun load(): String = respond {
        val (revision, state) = read()
        buildJsonObject { put("ok", true); put("revision", revision)
            put("state", state?.let(Json::parseToJsonElement) ?: JsonNull) }
    }
    @Synchronized fun save(payload: String): String = respond {
        require(payload.length <= OrbisMiniGameRepository.MAX_CHECKPOINT_CHARS + 128)
        OrbisMiniGameRepository.validateCheckpoint(payload)
        val value = Json.parseToJsonElement(payload).jsonObject
        require(value.keys == setOf("revision", "state"))
        val revision = (value["revision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        require(revision != null && revision >= 0 && value["state"] is JsonObject)
        val next = save(revision, value.getValue("state").toString())
        buildJsonObject { put("ok", true); put("revision", next) }
    }
    private fun respond(action: () -> JsonObject): String {
        if (!open.get()) return "{\"ok\":false,\"error\":\"game_frame_closed\"}"
        return try { action().toString() } catch (_: Exception) {
            "{\"ok\":false,\"error\":\"game_checkpoint_unavailable\"}"
        }
    }
}
