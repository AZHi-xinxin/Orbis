package me.rerere.rikkahub.data.orbis.soup

import java.net.URI
import kotlinx.serialization.json.*

/** Offline presentation only. The WebView never receives a host selection, a key or a private solution. */
internal object SoupWebPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/orbis-soup/index.html"
    private val assets = setOf(
        "/assets/orbis-soup/index.html", "/assets/orbis-soup/soup.js",
        "/assets/orbis-soup/soup.css", "/assets/orbis-garden/original.css",
    )
    fun asset(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" && uri.rawAuthority == "appassets.androidplatform.net" &&
            uri.rawPath in assets && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
    fun mainFrame(raw: String?): Boolean = raw == PAGE

    data class Command(val action: String, val puzzle: String? = null, val mode: SoupMode? = null, val session: String? = null)
    private val simple = setOf("ready", "close", "host", "ask", "submit", "hint", "turn", "reveal", "abandon",
        "accept_proposal", "decline_proposal", "retry", "reload", "license", "source", "content_license")

    fun command(raw: String): Command {
        require(raw.toByteArray(Charsets.UTF_8).size <= 1024)
        val obj = Json.parseToJsonElement(raw).jsonObject
        val action = obj["action"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("soup_invalid_action")
        require(obj.keys == setOf("action", "params"))
        val params = obj.getValue("params").jsonObject
        return when {
            action in simple -> { require(params.isEmpty()); Command(action) }
            action == "start" -> {
                require(params.keys == setOf("puzzle", "mode"))
                val puzzle = params.getValue("puzzle").jsonPrimitive.takeIf { it.isString }?.content ?: error("soup_invalid_action")
                val mode = params.getValue("mode").jsonPrimitive.takeIf { it.isString }?.content ?: error("soup_invalid_action")
                require(SoupCatalogue.puzzles.any { it.id == puzzle })
                Command(action, puzzle, SoupMode.valueOf(mode))
            }
            action == "archive" -> {
                require(params.keys == setOf("session"))
                val id = params.getValue("session").jsonPrimitive.takeIf { it.isString }?.content ?: error("soup_invalid_action")
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
                Command(action, session = id)
            }
            else -> error("soup_invalid_action")
        }
    }

    fun snapshot(state: SoupState, busy: Boolean, blocked: Boolean, notice: String?, hostConfigured: Boolean,
                 archiveId: String? = null): JsonObject = buildJsonObject {
        put("revision", state.revision); put("busy", busy); put("blocked", blocked)
        put("host_configured", hostConfigured); put("notice", notice ?: "")
        put("active", state.active?.let(::soupPublicSession) ?: JsonNull)
        put("archive", state.sessions.firstOrNull { it.id == archiveId && it.id != state.activeId }
            ?.let(::soupPublicSession) ?: JsonNull)
        put("puzzles", buildJsonArray { SoupCatalogue.puzzles.forEach { puzzle -> add(buildJsonObject {
            put("id", puzzle.id); put("title", puzzle.title); put("difficulty", puzzle.difficulty)
            put("note", SoupCatalogue.note(puzzle.id) ?: "")
        }) } })
        put("history", buildJsonArray { state.sessions.filter { it.id != state.activeId }.asReversed().forEach { past ->
            add(buildJsonObject { put("id", past.id); put("title", SoupCatalogue.get(past.puzzleId).title)
                put("mode", past.mode.label); put("revealed", past.revealed); put("abandoned", past.abandoned) })
        } })
    }
}
