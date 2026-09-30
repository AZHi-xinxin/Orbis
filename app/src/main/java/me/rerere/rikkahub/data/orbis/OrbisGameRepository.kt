package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Atomic storage is deliberately independent of conversation databases and assistant settings. */
interface OrbisGameStorage {
    fun read(): String?
    /** An exception does NOT prove that nothing was committed; the caller must reload first. */
    fun write(value: String)
}

@Serializable
data class OrbisGameMatch(
    val id: String,
    val startedAt: Long,
    val moves: List<Int> = emptyList(),
    val opponent: String = GomokuRules.BOT_VERSION,
    val opponentLabel: String = "本地规则程序",
    val opponentAssistantId: String? = null,
    val modelCalls: Int = 0,
    val modelCallLimit: Int = 40,
)

@Serializable
data class OrbisGameRecord(
    val match: OrbisGameMatch,
    val finishedAt: Long,
    val result: String,
    val ruleset: String = GomokuRules.RULESET,
    val opponent: String = GomokuRules.BOT_VERSION,
)

@Serializable
data class OrbisGameState(
    val version: Int = 1,
    val collected: Boolean = false,
    val active: OrbisGameMatch? = null,
    val records: List<OrbisGameRecord> = emptyList(),
)

/** All mutations are serialized, validated and durably written before the UI observes success. */
class OrbisGameRepository(
    private val storage: OrbisGameStorage,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true }
    private var storageExpected = false
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private val mutableWriteBlocked = MutableStateFlow(false)
    val writeBlocked = mutableWriteBlocked.asStateFlow()

    /** Read-only tool entry point: validates a snapshot, never repairs or rewrites storage. */
    @Synchronized
    fun readSnapshot(): OrbisGameState {
        ensureWritable() // A stale in-memory snapshot must not be reported as current verified data.
        return state.value.also(::validate)
    }

    /** Explicit user recovery: adopt only a fresh, fully verified disk state; never write/reset it. */
    @Synchronized
    fun reloadFromStorage() {
        mutableWriteBlocked.value = true
        val restored = load() // A failed read or validation leaves the write lock in place.
        mutableState.value = restored
        mutableWriteBlocked.value = false
    }

    private fun ensureWritable() {
        check(!mutableWriteBlocked.value) { "game_storage_reload_required" }
    }

    private fun load(): OrbisGameState {
        val text = storage.read()
        if (text == null) {
            check(!storageExpected) { "game_storage_missing" }
            return OrbisGameState()
        }
        require(text.length <= MAX_STORAGE_CHARS) { "game_storage_too_large" }
        return json.decodeFromString<OrbisGameState>(text).also {
            validate(it)
            storageExpected = true
        }
    }

    private fun validateMatch(match: OrbisGameMatch) {
        require(match.id.isNotBlank() && match.id.length <= 100 && match.startedAt >= 0) { "invalid_match" }
        require(match.opponent in setOf(GomokuRules.BOT_VERSION, GomokuRules.MODEL_OPPONENT, GomokuRules.MIXED_OPPONENT)) {
            "unsupported_opponent"
        }
        require(match.opponentLabel.isNotBlank() && match.opponentLabel.length <= 200) { "invalid_opponent_label" }
        require(match.modelCallLimit in 1..40 && match.modelCalls in 0..match.modelCallLimit) { "invalid_model_call_count" }
        if (match.opponent == GomokuRules.BOT_VERSION) {
            require(match.modelCalls == 0 && match.opponentAssistantId == null) { "invalid_local_opponent" }
        } else {
            require(!match.opponentAssistantId.isNullOrBlank() && match.opponentAssistantId.length <= 100) {
                "invalid_opponent_assistant"
            }
        }
    }

    private fun result(position: GomokuRules.Position): String = when {
        position.winner == 1 -> "win"
        position.winner == 2 -> "loss"
        position.draw -> "draw"
        else -> "abandoned"
    }

    private fun validate(value: OrbisGameState) {
        require(value.version == 1) { "unsupported_game_storage" }
        require(value.records.map { it.match.id }.distinct().size == value.records.size) { "duplicate_record" }
        value.records.forEach { record ->
            validateMatch(record.match)
            require(record.ruleset == GomokuRules.RULESET && record.opponent == record.match.opponent) { "unsupported_ruleset" }
            require(record.finishedAt >= record.match.startedAt) { "invalid_record_time" }
            val position = GomokuRules.verifyMatch(record.match.moves, record.opponent)
            require(record.result == result(position)) { "result_mismatch" }
        }
        value.active?.let { active ->
            validateMatch(active)
            require(value.records.none { it.match.id == active.id }) { "active_match_already_finished" }
            val position = GomokuRules.verifyMatch(active.moves, active.opponent)
            require(!position.finished && (position.humanTurn || active.opponent == GomokuRules.MODEL_OPPONENT)) {
                "invalid_active_match"
            }
        }
    }

    private fun commit(value: OrbisGameState) {
        ensureWritable()
        // State transitions are host constructed. Reload validates every recorded move independently.
        val text = json.encodeToString(value)
        require(text.length <= MAX_STORAGE_CHARS) { "game_storage_too_large" }
        try {
            storage.write(text)
        } catch (error: Throwable) {
            // finishWrite may already have committed. Reusing the old state could destroy newer
            // disk moves/results, so ALL subsequent mutations and tool reads require explicit reload.
            mutableWriteBlocked.value = true
            throw error
        }
        storageExpected = true
        mutableState.value = value
    }

    @Synchronized
    fun collect() {
        ensureWritable()
        if (!state.value.collected) commit(state.value.copy(collected = true))
    }

    @Synchronized
    fun start(
        opponent: String = GomokuRules.BOT_VERSION,
        opponentLabel: String = "本地规则程序",
        opponentAssistantId: String? = null,
        modelCallLimit: Int = 40,
    ): OrbisGameMatch {
        ensureWritable()
        // Starting never silently discards an existing game; the UI offers continue or explicit exit.
        state.value.active?.let { return it }
        require(opponent == GomokuRules.BOT_VERSION || opponent == GomokuRules.MODEL_OPPONENT) { "invalid_start_opponent" }
        val match = OrbisGameMatch(newId(), now(), opponent = opponent, opponentLabel = opponentLabel,
            opponentAssistantId = opponentAssistantId, modelCallLimit = modelCallLimit)
        validateMatch(match)
        require(state.value.records.none { it.match.id == match.id }) { "duplicate_match_id" }
        commit(state.value.copy(collected = true, active = match))
        return match
    }

    @Synchronized
    fun play(matchId: String, cell: Int) {
        ensureWritable()
        val before = state.value
        val active = requireNotNull(before.active) { "match_not_active" }
        require(active.id == matchId) { "stale_match" }
        val position = GomokuRules.replay(active.moves)
        require(position.humanTurn && !position.finished) { "not_human_turn" }
        var moves = active.moves + cell
        var after = GomokuRules.replay(moves)
        if (!after.finished && active.opponent != GomokuRules.MODEL_OPPONENT) {
            moves = moves + GomokuRules.botMove(after)
            after = GomokuRules.replay(moves)
        }
        val updated = active.copy(moves = moves)
        if (after.finished) {
            val record = OrbisGameRecord(updated, maxOf(now(), active.startedAt), result(after), opponent = updated.opponent)
            commit(before.copy(active = null, records = before.records + record))
        } else commit(before.copy(active = updated))
    }

    /** Count the attempt durably before the network call, including failures and explicit retries. */
    @Synchronized
    fun reserveModelTurn(matchId: String): OrbisGameMatch {
        ensureWritable()
        val before = state.value
        val active = requireNotNull(before.active) { "match_not_active" }
        require(active.id == matchId) { "stale_match" }
        require(active.opponent == GomokuRules.MODEL_OPPONENT) { "not_model_opponent" }
        val position = GomokuRules.replay(active.moves)
        require(!position.finished && !position.humanTurn) { "not_model_turn" }
        check(active.modelCalls < active.modelCallLimit) { "game_model_call_limit_reached" }
        val ticket = active.copy(modelCalls = active.modelCalls + 1)
        commit(before.copy(active = ticket))
        return ticket
    }

    /** Match identity, exact board and persisted request counter form the stale-result fence. */
    @Synchronized
    fun applyModelMove(matchId: String, expectedMoves: List<Int>, requestNumber: Int, cell: Int) {
        ensureWritable()
        val before = state.value
        val active = requireNotNull(before.active) { "match_not_active" }
        require(active.id == matchId) { "stale_match" }
        require(active.opponent == GomokuRules.MODEL_OPPONENT) { "not_model_opponent" }
        require(active.moves == expectedMoves && requestNumber > 0 && active.modelCalls == requestNumber) { "stale_model_turn" }
        val position = GomokuRules.replay(active.moves)
        require(!position.finished && !position.humanTurn) { "not_model_turn" }
        val updated = active.copy(moves = active.moves + cell)
        val after = GomokuRules.replay(updated.moves)
        if (after.finished) {
            val record = OrbisGameRecord(updated, maxOf(now(), active.startedAt), result(after), opponent = updated.opponent)
            commit(before.copy(active = null, records = before.records + record))
        } else commit(before.copy(active = updated))
    }

    /** Explicit local fallback never silently pretends a whole game was played by the model. */
    @Synchronized
    fun switchToLocal(matchId: String) {
        ensureWritable()
        val before = state.value
        val active = requireNotNull(before.active) { "match_not_active" }
        require(active.id == matchId) { "stale_match" }
        if (active.opponent != GomokuRules.MODEL_OPPONENT) return
        var updated = active.copy(opponent = GomokuRules.MIXED_OPPONENT, opponentLabel = "模型后转本地规则程序")
        var position = GomokuRules.replay(updated.moves)
        if (!position.finished && !position.humanTurn) {
            updated = updated.copy(moves = updated.moves + GomokuRules.botMove(position))
            position = GomokuRules.replay(updated.moves)
        }
        if (position.finished) {
            val record = OrbisGameRecord(updated, maxOf(now(), active.startedAt), result(position), opponent = updated.opponent)
            commit(before.copy(active = null, records = before.records + record))
        } else commit(before.copy(active = updated))
    }

    @Synchronized
    fun abandon(matchId: String) {
        ensureWritable()
        val before = state.value
        // A second dismissal of the already finished match is a harmless no-op.
        if (before.records.any { it.match.id == matchId }) return
        val active = requireNotNull(before.active) { "match_not_active" }
        require(active.id == matchId) { "stale_match" }
        val record = OrbisGameRecord(active, maxOf(now(), active.startedAt), "abandoned", opponent = active.opponent)
        commit(before.copy(active = null, records = before.records + record))
    }

    companion object { private const val MAX_STORAGE_CHARS = 8 * 1024 * 1024 }
}
