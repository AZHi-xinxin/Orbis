package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class GomokuRulesTest {
    @Test fun `empty board has exactly 81 legal spaces and human starts`() {
        val state = GomokuRules.replay(emptyList())
        assertEquals(81, state.board.size)
        assertTrue(state.board.all { it == 0 })
        assertTrue(state.humanTurn)
        assertFalse(state.finished)
    }

    @Test fun `all four directions recognize five and never wrap an edge`() {
        for (black in listOf(listOf(0, 1, 2, 3, 4), listOf(0, 9, 18, 27, 36),
                listOf(0, 10, 20, 30, 40), listOf(4, 12, 20, 28, 36))) {
            val moves = black.flatMapIndexed { i, cell -> if (i < 4) listOf(cell, 72 + i) else listOf(cell) }
            assertEquals(1, GomokuRules.replay(moves).winner)
        }
        assertNull(GomokuRules.replay(listOf(7, 70, 8, 72, 9, 74, 10, 76, 11)).winner)
    }

    @Test fun `illegal range occupied cells and post win moves fail`() {
        for (moves in listOf(listOf(-1), listOf(81), listOf(4, 4),
                listOf(0, 72, 1, 73, 2, 74, 3, 75, 4, 76), List(82) { it })) {
            assertThrows(IllegalArgumentException::class.java) { GomokuRules.replay(moves) }
        }
    }

    @Test fun `full no five board is draw`() {
        val black = (0 until 81).filter { (it / 9 + 2 * (it % 9)) % 4 < 2 }
        val white = (0 until 81).filterNot { it in black }
        assertEquals(41, black.size)
        val moves = black.flatMapIndexed { i, cell -> listOfNotNull(cell, white.getOrNull(i)) }
        val state = GomokuRules.replay(moves)
        assertTrue(state.draw)
        assertTrue(state.finished)
        assertNull(state.winner)
    }

    @Test fun `bot matches approved prototype golden game and does not mutate input`() {
        val golden = listOf(0, 10, 1, 2, 3, 18, 4, 5, 6, 11, 7, 12, 8, 13, 9, 14)
        val position = GomokuRules.verifyLocalMatch(golden)
        assertEquals(2, position.winner)
        val initial = GomokuRules.replay(listOf(0))
        val before = initial.board.toList()
        assertEquals(10, GomokuRules.botMove(initial))
        assertEquals(before, initial.board)
    }

    @Test fun `bot requires its turn and verified records reject forged opponent`() {
        assertThrows(IllegalArgumentException::class.java) { GomokuRules.botMove(GomokuRules.replay(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { GomokuRules.verifyLocalMatch(listOf(0, 1)) }
    }

    @Test fun `bot takes winning move before blocking human`() {
        val position = GomokuRules.replay(listOf(9, 0, 10, 1, 11, 2, 12, 3, 30))
        assertEquals(4, GomokuRules.botMove(position))
    }

    @Test fun `bot blocks immediate human five`() {
        val position = GomokuRules.replay(listOf(0, 70, 1, 72, 2, 74, 3))
        assertEquals(4, GomokuRules.botMove(position))
    }
}
