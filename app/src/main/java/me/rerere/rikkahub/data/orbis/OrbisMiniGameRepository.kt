package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class OrbisInstalledGame(
    val id: String, val title: String, val description: String, val html: String,
    val sha256: String, val createdAt: Long, val updatedAt: Long,
    val authorId: String, val authorName: String,
)

@Serializable
data class OrbisMiniGameSession(
    val id: String, val gameId: String, val gameTitle: String, val gameSha256: String,
    val startedAt: Long, val finishedAt: Long? = null, val result: String? = null,
    val moveCount: Int? = null,
)

@Serializable
data class OrbisMiniGameState(
    val version: Int = 1,
    val games: List<OrbisInstalledGame> = emptyList(),
    val sessions: List<OrbisMiniGameSession> = emptyList(),
)

/** Source stays app-private. Installing does not execute HTML or grant network/native capabilities. */
class OrbisMiniGameRepository(
    private val storage: OrbisGameStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true }
    private var expected = false
    private var blocked = false
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    @Synchronized fun readSnapshot(): OrbisMiniGameState {
        check(!blocked) { "game_storage_reload_required" }
        return state.value
    }

    @Synchronized fun reloadFromStorage() {
        blocked = true
        mutableState.value = load()
        blocked = false
    }

    private fun load(): OrbisMiniGameState {
        val raw = storage.read()
        if (raw == null) {
            check(!expected) { "game_storage_missing" }
            return OrbisMiniGameState()
        }
        require(raw.length <= MAX_STORAGE_CHARS) { "game_storage_too_large" }
        return json.decodeFromString<OrbisMiniGameState>(raw).also { validate(it); expected = true }
    }

    private fun validate(value: OrbisMiniGameState) {
        require(value.version == 1) { "unsupported_game_library" }
        require(value.games.map { it.id }.distinct().size == value.games.size) { "duplicate_game" }
        require(value.sessions.map { it.id }.distinct().size == value.sessions.size) { "duplicate_session" }
        value.games.forEach {
            require(validId(it.id) && validId(it.authorId)) { "invalid_game_id" }
            validateSource(it.title, it.description, it.html, it.authorName)
            require(it.sha256 == hash(it.html) && it.createdAt >= 0 && it.updatedAt >= it.createdAt) { "invalid_game_source" }
        }
        value.sessions.forEach {
            require(validId(it.id) && value.games.any { game -> game.id == it.gameId }) { "invalid_game_session" }
            require(it.gameTitle.isNotBlank() && it.gameTitle.length <= 100 && it.gameSha256.matches(Regex("[a-f0-9]{64}"))) { "invalid_game_session" }
            require(it.startedAt >= 0) { "invalid_game_time" }
            if (it.finishedAt == null) require(it.result == null && it.moveCount == null) { "unfinished_game_result" }
            else require(it.finishedAt >= it.startedAt && it.result in RESULTS && it.moveCount != null && it.moveCount in 0..1_000_000) { "invalid_game_result" }
        }
    }

    private fun commit(value: OrbisMiniGameState) {
        check(!blocked) { "game_storage_reload_required" }
        val raw = json.encodeToString(value)
        require(raw.length <= MAX_STORAGE_CHARS) { "game_storage_too_large" }
        try { storage.write(raw) } catch (error: Throwable) { blocked = true; throw error }
        expected = true
        mutableState.value = value
    }

    @Synchronized fun install(
        authorId: String, authorName: String, title: String, description: String, html: String,
        gameId: String? = null, expectedSha256: String? = null,
    ): OrbisInstalledGame {
        val before = readSnapshot()
        require(validId(authorId)) { "invalid_author" }
        validateSource(title, description, html, authorName)
        val old = gameId?.let { id -> before.games.find { it.id == id } ?: error("game_not_found") }
        if (old != null) {
            require(old.authorId == authorId) { "game_belongs_to_another_author" }
            require(expectedSha256 == old.sha256) { "game_revision_changed_query_library_first" }
        } else require(expectedSha256 == null) { "new_game_has_no_revision" }
        val time = maxOf(now(), old?.updatedAt ?: 0)
        val game = OrbisInstalledGame(old?.id ?: newId(), title.trim(), description.trim(), html, hash(html),
            old?.createdAt ?: time, time, authorId, authorName)
        require(validId(game.id)) { "invalid_game_id" }
        require(old != null || before.games.none { it.id == game.id }) { "duplicate_game" }
        commit(before.copy(games = if (old == null) before.games + game else before.games.map { if (it.id == old.id) game else it }))
        return game
    }

    @Synchronized fun start(gameId: String): OrbisMiniGameSession {
        val before = readSnapshot()
        val game = before.games.find { it.id == gameId } ?: error("game_not_found")
        val session = OrbisMiniGameSession(newId(), game.id, game.title, game.sha256, now())
        require(validId(session.id) && session.startedAt >= 0 && before.sessions.none { it.id == session.id }) { "invalid_session_id" }
        commit(before.copy(sessions = before.sessions + session))
        return session
    }

    /** Only the host-created frame's bound session reaches this API, never an arbitrary JS id. */
    @Synchronized fun finish(sessionId: String, result: String, moveCount: Int): OrbisMiniGameSession {
        val before = readSnapshot()
        require(result in RESULTS && moveCount in 0..1_000_000) { "invalid_game_result" }
        val old = before.sessions.find { it.id == sessionId } ?: error("game_session_not_found")
        if (old.finishedAt != null) {
            require(old.result == result && old.moveCount == moveCount) { "game_result_already_recorded" }
            return old
        }
        val finished = old.copy(finishedAt = maxOf(now(), old.startedAt), result = result, moveCount = moveCount)
        commit(before.copy(sessions = before.sessions.map { if (it.id == old.id) finished else it }))
        return finished
    }

    @Synchronized fun abandon(sessionId: String): OrbisMiniGameSession {
        val old = readSnapshot().sessions.find { it.id == sessionId } ?: error("game_session_not_found")
        return if (old.finishedAt != null) old else finish(sessionId, "abandoned", 0)
    }

    companion object {
        const val MAX_HTML_CHARS = 2 * 1024 * 1024
        private const val MAX_STORAGE_CHARS = 32 * 1024 * 1024
        private val RESULTS = setOf("win", "loss", "draw", "completed", "abandoned")
        private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,100}"))
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun validateSource(title: String, description: String, html: String, authorName: String) {
            require(title.isNotBlank() && title.length <= 100 && description.length <= 2000 && authorName.length <= 200) { "invalid_game_metadata" }
            require(html.isNotBlank() && html.length <= MAX_HTML_CHARS && '\u0000' !in html) { "invalid_game_html" }
        }
    }
}
