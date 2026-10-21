package me.rerere.rikkahub.ui.pages.orbis

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A fresh, non-routable origin for one private game frame, never an asset/file proxy. */
internal class OrbisMiniGamePolicy(nonce: String = UUID.randomUUID().toString()) {
    init { require(nonce.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    val pageUrl = "https://game-$nonce.orbis-game.invalid/play.html"

    fun isDocument(url: String?, mainFrame: Boolean, method: String): Boolean =
        mainFrame && method == "GET" && url == pageUrl

    companion object {
        const val CSP = "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; " +
            "connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
            "manifest-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; webrtc 'block'"

        // Defense in depth for WebView versions where CSP WebRTC is not implemented. Installed
        // source/hash remain untouched. Also installed at document-start for every child realm.
        val BOOTSTRAP = """
            (function(){'use strict';
              var blocked=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection',
                'RTCIceTransport','WebTransport','Worker','SharedWorker','EventSource','WebSocket'];
              blocked.forEach(function(name){
                try{Object.defineProperty(window,name,{value:undefined,writable:false,configurable:false});}catch(e){}
              });
              try{Object.defineProperty(window,'open',{value:function(){return null;},writable:false,configurable:false});}catch(e){}
            })();
        """.trimIndent()

        fun hostedHtml(source: String) = "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"x-dns-prefetch-control\" content=\"off\">" +
            "<script>$BOOTSTRAP</script>" + source
    }
}

internal data class OrbisMiniGameResult(val result: String, val moveCount: Int,
    val score: Long? = null, val maxTile: Int? = null, val scoringVersion: String? = null)

internal fun parseOrbisMiniGameResult(payload: String): OrbisMiniGameResult {
    require(payload.length <= 4096) { "game_result_too_large" }
    val value = JSONObject(payload)
    val keys = value.keys().asSequence().toSet()
    require(keys.containsAll(setOf("result", "move_count")) && keys.all { it in setOf("result", "move_count", "score", "max_tile", "scoring_version") }) { "game_result_fields_invalid" }
    val result = value.get("result") as? String ?: error("game_result_invalid")
    require(result in setOf("win", "loss", "draw", "completed")) { "game_result_invalid" }
    val raw = value.get("move_count")
    require(raw is Int || raw is Long) { "game_move_count_invalid" }
    val moves = (raw as Number).toLong()
    require(moves in 0..1_000_000L) { "game_move_count_invalid" }
    fun optionalInteger(key: String): Long? {
        if(!value.has(key) || value.isNull(key)) return null
        val number = value.get(key); require(number is Int || number is Long) { "game_score_invalid" }
        return (number as Number).toLong()
    }
    val score = optionalInteger("score"); val tile = optionalInteger("max_tile")
    require(tile == null || tile in 1..1_073_741_824L)
    val version = if(value.has("scoring_version") && !value.isNull("scoring_version")) value.get("scoring_version") as? String ?: error("invalid_scoring_version") else null
    me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository.validateScore(score, tile?.toInt(), version)
    return OrbisMiniGameResult(result, moves.toInt(), score, tile?.toInt(), version)
}

/** Only session-bound result writes; callers cannot pick a session, game, path, URL or tool. */
internal class OrbisMiniGameResultGate(private val save: (OrbisMiniGameResult) -> Unit) {
    private val open = AtomicBoolean(true)
    fun close() { open.set(false) }

    @Synchronized fun finish(payload: String): String {
        if (!open.get()) return "{\"ok\":false,\"error\":\"game_frame_closed\"}"
        val parsed = try { parseOrbisMiniGameResult(payload) }
            catch (_: Exception) { return "{\"ok\":false,\"error\":\"invalid_game_result\"}" }
        if (!open.get()) return "{\"ok\":false,\"error\":\"game_frame_closed\"}"
        return try {
            save(parsed)
            "{\"ok\":true,\"source\":\"game_reported_host_timed\"}"
        } catch (_: Exception) {
            // Never leak a file path, a repository error payload or credentials into imported JS.
            "{\"ok\":false,\"error\":\"game_result_not_saved\"}"
        }
    }
}
