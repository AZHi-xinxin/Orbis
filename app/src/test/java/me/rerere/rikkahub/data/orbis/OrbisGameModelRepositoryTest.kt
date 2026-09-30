package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class OrbisGameModelRepositoryTest {
    private class MemoryStorage : OrbisGameStorage {
        var text: String? = null
        var failAfterWrite = false
        var writes = 0
        override fun read() = text
        override fun write(value: String) {
            text = value
            writes++
            if (failAfterWrite) error("synthetic_post_commit_failure")
        }
    }
    private fun repository(storage: MemoryStorage) = OrbisGameRepository(storage, { 123L }, { "synthetic-game" })
    private fun OrbisGameRepository.modelGame(limit: Int = 40) = start(
        GomokuRules.MODEL_OPPONENT, "测试模型", "synthetic-assistant", limit,
    )
    private fun OrbisGameRepository.modelMove(id: String, cell: Int) {
        val ticket = reserveModelTurn(id)
        applyModelMove(id, ticket.moves, ticket.modelCalls, cell)
    }

    @Test fun `model human move is persisted alone and pending board survives restart`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val match = repo.modelGame()
        repo.play(match.id, 0)
        assertEquals(listOf(0), repo.readSnapshot().active!!.moves)
        assertFalse(GomokuRules.replay(repo.state.value.active!!.moves).humanTurn)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
        assertThrows(IllegalArgumentException::class.java) { repo.play(match.id, 1) }
    }

    @Test fun `reservation is durable and non deterministic legal model move reloads`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repo.play(id, 0)
        val ticket = repo.reserveModelTurn(id)
        assertEquals(1, ticket.modelCalls)
        assertEquals(ticket, repository(storage).readSnapshot().active)
        repo.applyModelMove(id, ticket.moves, ticket.modelCalls, 1)
        assertEquals(listOf(0, 1), repo.readSnapshot().active!!.moves)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
    }

    @Test fun `later retry fences an older response without changing the board`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repo.play(id, 0)
        val old = repo.reserveModelTurn(id)
        val current = repo.reserveModelTurn(id)
        val saved = storage.text
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, old.moves, old.modelCalls, 1) }
        assertEquals(saved, storage.text)
        repo.applyModelMove(id, current.moves, current.modelCalls, 2)
        val moved = storage.text
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, current.moves, current.modelCalls, 3) }
        assertEquals(moved, storage.text)
    }

    @Test fun `request requires exact board request number identity and legal cell`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        assertThrows(IllegalArgumentException::class.java) { repo.reserveModelTurn(id) }
        repo.play(id, 0)
        val ticket = repo.reserveModelTurn(id)
        val bytes = storage.text
        for (cell in listOf(-1, 0, 81)) assertThrows(IllegalArgumentException::class.java) {
            repo.applyModelMove(id, ticket.moves, ticket.modelCalls, cell)
        }
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove("other", ticket.moves, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, emptyList(), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, ticket.moves, 0, 1) }
        assertEquals(bytes, storage.text)
    }

    @Test fun `failed attempts consume budget and the limit survives process restart`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame(10).id
        repo.play(id, 0)
        repeat(10) { assertEquals(it + 1, repo.reserveModelTurn(id).modelCalls) }
        val restarted = repository(storage)
        val bytes = storage.text
        assertThrows(IllegalStateException::class.java) { restarted.reserveModelTurn(id) }
        assertEquals(bytes, storage.text)
        restarted.switchToLocal(id)
        assertEquals(GomokuRules.MIXED_OPPONENT, restarted.readSnapshot().active!!.opponent)
        assertEquals(10, restarted.state.value.active!!.modelCalls)
    }

    @Test fun `concurrent reservations never exceed maximum budget`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame(10).id
        repo.play(id, 0)
        val gate = CountDownLatch(1)
        val workers = List(20) { thread { gate.await(); runCatching { repo.reserveModelTurn(id) } } }
        gate.countDown()
        workers.forEach { it.join() }
        assertEquals(10, repo.readSnapshot().active!!.modelCalls)
        assertEquals(12, storage.writes) // start + human move + ten reserved attempts
    }

    @Test fun `switching to local fills a pending turn and rejects any late model reply`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repo.play(id, 0)
        val ticket = repo.reserveModelTurn(id)
        repo.switchToLocal(id)
        assertEquals(listOf(0, 10), repo.state.value.active!!.moves)
        assertEquals(GomokuRules.MIXED_OPPONENT, repo.state.value.active!!.opponent)
        val bytes = storage.text
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, ticket.moves, ticket.modelCalls, 1) }
        assertThrows(IllegalArgumentException::class.java) { repo.reserveModelTurn(id) }
        repo.switchToLocal(id)
        assertEquals(bytes, storage.text)
        repo.play(id, 1)
        assertEquals(4, repo.readSnapshot().active!!.moves.size)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
    }

    @Test fun `finished model game records actual opponent and legal history rather than local bot script`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repeat(4) { repo.play(id, it); repo.modelMove(id, 9 + it) }
        repo.play(id, 4)
        val record = repo.readSnapshot().records.single()
        assertNull(repo.state.value.active)
        assertEquals("win", record.result)
        assertEquals(GomokuRules.MODEL_OPPONENT, record.opponent)
        assertEquals(4, record.match.modelCalls)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
    }

    @Test fun `switching on terminal local response archives an honest mixed result`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repeat(4) { repo.play(id, it * 2); repo.modelMove(id, 9 + it) }
        repo.play(id, 8)
        repo.switchToLocal(id) // White's 9,10,11,12 line is completed by the local player.
        val record = repo.readSnapshot().records.single()
        assertEquals(GomokuRules.MIXED_OPPONENT, record.opponent)
        assertEquals("loss", record.result)
        assertNull(repo.state.value.active)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
    }

    @Test fun `model and mixed abandonment retain truthful opponent metadata`() {
        for (mixed in listOf(false, true)) {
            val storage = MemoryStorage()
            val repo = repository(storage)
            val id = repo.modelGame().id
            repo.play(id, 0)
            if (mixed) repo.switchToLocal(id)
            repo.abandon(id)
            val record = repo.readSnapshot().records.single()
            assertEquals("abandoned", record.result)
            assertEquals(if (mixed) GomokuRules.MIXED_OPPONENT else GomokuRules.MODEL_OPPONENT, record.opponent)
            assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
        }
    }

    @Test fun `uncertain reservation write blocks network tickets and further writes until reload`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repo.play(id, 0)
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repo.reserveModelTurn(id) }
        assertTrue(repo.writeBlocked.value)
        assertEquals(0, repo.state.value.active!!.modelCalls)
        assertThrows(IllegalStateException::class.java) { repo.switchToLocal(id) }
        storage.failAfterWrite = false
        repo.reloadFromStorage()
        assertEquals(1, repo.readSnapshot().active!!.modelCalls)
        assertEquals(2, repo.reserveModelTurn(id).modelCalls)
    }

    @Test fun `postcommit model move failure preserves disk result through locked reload`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.modelGame().id
        repo.play(id, 0)
        val ticket = repo.reserveModelTurn(id)
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repo.applyModelMove(id, ticket.moves, ticket.modelCalls, 1) }
        assertTrue(repo.writeBlocked.value)
        assertThrows(IllegalStateException::class.java) { repo.abandon(id) }
        storage.failAfterWrite = false
        repo.reloadFromStorage()
        assertEquals(listOf(0, 1), repo.readSnapshot().active!!.moves)
        assertThrows(IllegalArgumentException::class.java) { repo.applyModelMove(id, ticket.moves, ticket.modelCalls, 2) }
    }

    @Test fun `legacy storage retains local defaults and illegal mixed history is still rejected`() {
        val storage = MemoryStorage().also {
            it.text = """{"version":1,"collected":true,"active":{"id":"legacy","startedAt":0,"moves":[0,10]},"records":[]}"""
        }
        assertEquals(GomokuRules.BOT_VERSION, repository(storage).readSnapshot().active!!.opponent)
        val malformed = OrbisGameMatch("bad", 0, listOf(0, 0), GomokuRules.MIXED_OPPONENT, "mixed", "assistant")
        storage.text = Json.encodeToString(OrbisGameState(active = malformed))
        assertThrows(IllegalArgumentException::class.java) { repository(storage) }
    }

    @Test fun `invalid budgets assistants and opponent types never create a game`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        for (limit in listOf(0, 41)) assertThrows(IllegalArgumentException::class.java) { repo.modelGame(limit) }
        assertThrows(IllegalArgumentException::class.java) { repo.start(GomokuRules.MODEL_OPPONENT) }
        assertThrows(IllegalArgumentException::class.java) { repo.start(GomokuRules.MIXED_OPPONENT, "mixed", "assistant") }
        assertEquals(0, storage.writes)
    }
}
