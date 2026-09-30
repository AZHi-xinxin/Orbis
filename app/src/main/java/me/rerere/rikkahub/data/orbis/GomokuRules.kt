package me.rerere.rikkahub.data.orbis

import kotlin.math.abs
import kotlin.math.pow

/** Native host rules. Nothing provided by a game frame, model or imported script is executed. */
object GomokuRules {
    const val SIZE = 9
    const val RULESET = "gomoku-9x9-five/1"
    const val BOT_VERSION = "local-rule-bot/1"
    const val MODEL_OPPONENT = "chat-model/1"
    const val MIXED_OPPONENT = "model-then-local/1"
    private val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)

    data class Position(val board: List<Int>, val winner: Int?, val draw: Boolean, val humanTurn: Boolean) {
        val finished: Boolean get() = winner != null || draw
    }

    fun replay(moves: List<Int>): Position {
        require(moves.size <= SIZE * SIZE) { "invalid_moves" }
        val board = MutableList(SIZE * SIZE) { 0 }
        var winningPiece: Int? = null
        moves.forEachIndexed { index, move ->
            require(winningPiece == null) { "move_after_terminal" }
            require(move in board.indices && board[move] == 0) { "illegal_move" }
            board[move] = if (index % 2 == 0) 1 else 2
            winningPiece = winner(board)
        }
        return Position(board.toList(), winningPiece, winningPiece == null && moves.size == board.size,
            humanTurn = moves.size % 2 == 0)
    }

    /** Validates the local opponent as well as legal cells, so a forged result is not trusted. */
    fun verifyLocalMatch(moves: List<Int>): Position {
        val result = replay(moves)
        for (index in 1 until moves.size step 2) {
            require(botMove(replay(moves.take(index))) == moves[index]) { "opponent_move_mismatch" }
        }
        return result
    }

    /** Model and mixed games validate legal alternating moves, not deterministic bot choices. */
    fun verifyMatch(moves: List<Int>, opponent: String): Position = when (opponent) {
        BOT_VERSION -> verifyLocalMatch(moves)
        MODEL_OPPONENT, MIXED_OPPONENT -> replay(moves)
        else -> throw IllegalArgumentException("unsupported_opponent")
    }

    private fun winner(board: List<Int>): Int? {
        for (row in 0 until SIZE) for (col in 0 until SIZE) {
            val piece = board[row * SIZE + col]
            if (piece == 0) continue
            for ((dr, dc) in directions) {
                if ((1..4).all { n ->
                        val r = row + dr * n
                        val c = col + dc * n
                        r in 0 until SIZE && c in 0 until SIZE && board[r * SIZE + c] == piece
                    }) return piece
            }
        }
        return null
    }

    /** Deterministic v1 opponent, matching the approved prototype's win/block/line scoring. */
    fun botMove(position: Position): Int {
        require(!position.finished && !position.humanTurn) { "not_bot_turn" }
        val board = position.board
        var best = Double.NEGATIVE_INFINITY
        var chosen = -1
        board.forEachIndexed { pos, value ->
            if (value != 0) return@forEachIndexed
            var score = (16 - abs(pos / SIZE - 4) - abs(pos % SIZE - 4)).toDouble()
            for ((piece, weight) in listOf(2 to 1.1, 1 to 1.0)) {
                val proposed = board.toMutableList().also { it[pos] = piece }
                if (winner(proposed) == piece) score += 100000 * weight
                for ((dr, dc) in directions) {
                    var length = 1
                    for (sign in listOf(-1, 1)) for (step in 1..4) {
                        val r = pos / SIZE + dr * step * sign
                        val c = pos % SIZE + dc * step * sign
                        if (r !in 0 until SIZE || c !in 0 until SIZE || proposed[r * SIZE + c] != piece) break
                        length++
                    }
                    score += 7.0.pow(length) * weight
                }
            }
            if (score > best) { best = score; chosen = pos }
        }
        check(chosen >= 0) { "no_legal_move" }
        return chosen
    }
}
