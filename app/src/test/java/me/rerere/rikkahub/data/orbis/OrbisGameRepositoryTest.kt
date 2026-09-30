package me.rerere.rikkahub.data.orbis

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class OrbisGameRepositoryTest {
    private class MemoryStorage : OrbisGameStorage {
        var text: String? = null
        var fail = false
        var failAfterWrite = false
        var failRead = false
        var writes = 0
        var writeAttempts = 0
        override fun read(): String? {
            if (failRead) error("private-read-path-sentinel")
            return text
        }
        override fun write(value: String) {
            writeAttempts++
            if (fail) error("private-path-sentinel")
            text = value
            writes++
            if (failAfterWrite) error("private-post-commit-read-path-sentinel")
        }
    }
    private fun repository(storage: MemoryStorage, id: String = "synthetic-match") =
        OrbisGameRepository(storage, now = { 123L }, newId = { id })

    @Test fun `empty storage does not invent a collection or records`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        assertEquals(OrbisGameState(), repo.readSnapshot())
        assertEquals(0, storage.writes)
    }

    @Test fun `collect is durable idempotent and not a game`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        repo.collect(); repo.collect()
        assertEquals(1, storage.writes)
        val restored = repository(storage).readSnapshot()
        assertTrue(restored.collected)
        assertNull(restored.active)
        assertTrue(restored.records.isEmpty())
    }

    @Test fun `start repeat and reload preserve same active game without recording a loss`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val match = repo.start()
        repo.play(match.id, 0)
        assertEquals(listOf(0, 10), repo.state.value.active!!.moves)
        assertEquals(repo.state.value.active, repo.start())
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
        assertTrue(repo.state.value.records.isEmpty())
    }

    @Test fun `explicit exit is durable exactly once and does not count as loss`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        repo.play(id, 0)
        repo.abandon(id); repo.abandon(id)
        val restored = repository(storage).readSnapshot()
        assertNull(restored.active)
        assertEquals(1, restored.records.size)
        assertEquals("abandoned", restored.records.single().result)
        assertEquals(listOf(0, 10), restored.records.single().match.moves)
    }

    @Test fun `real local game produces one verified terminal record`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        while (repo.state.value.active != null) {
            val board = GomokuRules.replay(repo.state.value.active!!.moves).board
            repo.play(id, board.indexOfFirst { it == 0 })
        }
        assertEquals("loss", repo.readSnapshot().records.single().result)
        assertEquals(16, repo.state.value.records.single().match.moves.size)
        assertEquals(repo.readSnapshot(), repository(storage).readSnapshot())
        val previous = storage.text
        repo.abandon(id)
        assertEquals(previous, storage.text)
        assertThrows(IllegalArgumentException::class.java) { repo.play(id, 60) }
    }

    @Test fun `precommit write failure blocks stale writes until explicit verified reload`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        val before = repo.readSnapshot()
        val bytes = storage.text
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { repo.play(id, 0) }
        assertEquals(before, repo.state.value)
        assertTrue(repo.writeBlocked.value)
        assertThrows(IllegalStateException::class.java) { repo.readSnapshot() }
        assertEquals(bytes, storage.text)
        assertThrows(IllegalStateException::class.java) { repo.abandon(id) }
        assertEquals(before, repo.state.value)
        assertEquals(2, storage.writeAttempts)
        storage.fail = false
        assertThrows(IllegalStateException::class.java) { repo.play(id, 0) }
        assertEquals(2, storage.writeAttempts)
        repo.reloadFromStorage()
        assertFalse(repo.writeBlocked.value)
        assertEquals(before, repo.readSnapshot())
        repo.play(id, 0)
        assertEquals(listOf(0, 10), repo.state.value.active!!.moves)
    }

    @Test fun `postcommit exception locks every mutation and reload preserves the committed moves`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        val oldState = repo.state.value
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repo.play(id, 0) }
        val committedBytes = storage.text
        assertEquals(oldState, repo.state.value)
        assertTrue(repo.writeBlocked.value)
        assertEquals(listOf(0, 10), repository(storage).readSnapshot().active!!.moves)
        assertThrows(IllegalStateException::class.java) { repo.collect() }
        assertThrows(IllegalStateException::class.java) { repo.start() }
        assertThrows(IllegalStateException::class.java) { repo.play(id, 1) }
        assertThrows(IllegalStateException::class.java) { repo.abandon(id) }
        assertThrows(IllegalStateException::class.java) { repo.readSnapshot() }
        assertEquals(2, storage.writeAttempts)
        assertEquals(committedBytes, storage.text)
        storage.failAfterWrite = false
        repo.reloadFromStorage()
        assertEquals(2, storage.writeAttempts) // Recovery is strictly read-only.
        assertFalse(repo.writeBlocked.value)
        repo.play(id, 1)
        assertEquals(listOf(0, 10, 1, 2), repo.readSnapshot().active!!.moves)
    }

    @Test fun `postcommit terminal exception cannot overwrite a real result with abandoned`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        while (repo.state.value.active!!.moves.size < 14) {
            repo.play(id, GomokuRules.replay(repo.state.value.active!!.moves).board.indexOfFirst { it == 0 })
        }
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repo.play(id, 9) }
        val terminalBytes = storage.text
        val attempts = storage.writeAttempts
        assertTrue(repo.state.value.records.isEmpty()) // Never publish an unconfirmed success.
        assertThrows(IllegalStateException::class.java) { repo.abandon(id) }
        assertEquals(terminalBytes, storage.text)
        assertEquals(attempts, storage.writeAttempts)
        storage.failAfterWrite = false
        repo.reloadFromStorage()
        assertNull(repo.state.value.active)
        assertEquals("loss", repo.readSnapshot().records.single().result)
        repo.abandon(id)
        assertEquals(terminalBytes, storage.text)
    }

    @Test fun `failed corrupt or missing reload keeps lock without resetting or rewriting`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repo.play(id, 0) }
        val actualDisk = storage.text
        val oldState = repo.state.value
        val attempts = storage.writeAttempts
        storage.failRead = true
        assertThrows(IllegalStateException::class.java) { repo.reloadFromStorage() }
        storage.failRead = false
        for (badDisk in listOf(null, "not-json", Json.encodeToString(OrbisGameState(version = 2)))) {
            storage.text = badDisk
            assertThrows(Exception::class.java) { repo.reloadFromStorage() }
            assertTrue(repo.writeBlocked.value)
            assertEquals(oldState, repo.state.value)
            assertEquals(badDisk, storage.text)
            assertThrows(IllegalStateException::class.java) { repo.start() }
        }
        assertEquals(attempts, storage.writeAttempts)
        storage.text = actualDisk
        repo.reloadFromStorage()
        assertFalse(repo.writeBlocked.value)
        assertEquals(listOf(0, 10), repo.readSnapshot().active!!.moves)
        assertEquals(actualDisk, storage.text)
        assertEquals(attempts, storage.writeAttempts)
    }

    @Test fun `first write never committed can explicitly reload verified absence`() {
        val storage = MemoryStorage().also { it.fail = true }
        val repo = repository(storage)
        assertThrows(IllegalStateException::class.java) { repo.start() }
        assertTrue(repo.writeBlocked.value)
        assertNull(storage.text)
        repo.reloadFromStorage()
        assertFalse(repo.writeBlocked.value)
        assertEquals(OrbisGameState(), repo.readSnapshot())
        assertEquals(1, storage.writeAttempts)
    }

    @Test fun `invalid moves and stale callbacks never write`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        repo.play(id, 0)
        val before = storage.text
        for ((match, cell) in listOf(id to 0, id to -1, "stale" to 2)) {
            assertThrows(IllegalArgumentException::class.java) { repo.play(match, cell) }
            assertEquals(before, storage.text)
        }
        assertThrows(IllegalArgumentException::class.java) { repo.abandon("stale") }
        assertEquals(before, storage.text)
    }

    @Test fun `corrupt unsupported and forged storage fail without reset or write`() {
        for (text in listOf("not-json", Json.encodeToString(OrbisGameState(version = 2)),
                Json.encodeToString(OrbisGameState(active = OrbisGameMatch("test", 0, listOf(0, 1)))),
                Json.encodeToString(OrbisGameState(records = listOf(OrbisGameRecord(OrbisGameMatch("test", 0), 1, "win")))))) {
            val storage = MemoryStorage().also { it.text = text }
            assertThrows(Exception::class.java) { repository(storage) }
            assertEquals(text, storage.text)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `concurrent repeated exit adds only one record`() {
        val storage = MemoryStorage()
        val repo = repository(storage)
        val id = repo.start().id
        val start = CountDownLatch(1)
        val workers = List(8) { thread { start.await(); repo.abandon(id) } }
        start.countDown(); workers.forEach { it.join() }
        assertEquals(1, repo.state.value.records.size)
        assertEquals(2, storage.writes)
    }

    @Test fun `new game never overwrites earlier results`() {
        val storage = MemoryStorage()
        val first = repository(storage, "one")
        first.abandon(first.start().id)
        val second = repository(storage, "two")
        second.start()
        assertEquals("one", second.state.value.records.single().match.id)
        assertEquals("two", second.state.value.active!!.id)
        assertEquals(second.readSnapshot(), repository(storage).readSnapshot())
    }
}
