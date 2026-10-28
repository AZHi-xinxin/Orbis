package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisMiniGameCheckpointTest {
    private class Storage : OrbisGameStorage {
        var text: String? = null; var fail = false
        override fun read() = text
        override fun write(value: String) { check(!fail); text = value }
    }
    @Test fun resumeAfterRepositoryRestartPreservesSessionBoardAndRecordsCount() {
        val storage = Storage(); val repo = OrbisMiniGameRepository(storage)
        val game = repo.install("owner", "AI", "Game", "", "<p>synthetic</p>")
        val session = repo.openSession(game.id)
        val board = "{\"moves\":2,\"board\":[1,0,2]}"
        assertEquals(1L, repo.saveCheckpoint(session.id, 0, board))
        val resumed = OrbisMiniGameRepository(storage).openSession(game.id)
        assertEquals(session.id, resumed.id); assertEquals(board, resumed.checkpoint)
        assertEquals(session.startedAt, resumed.startedAt)
        assertEquals(1, repo.readSnapshot().sessions.size)
        assertNull(resumed.result)
    }
    @Test fun legacySourceDoesNotSilentlyCreateAnotherMatch() {
        val repo = OrbisMiniGameRepository(Storage())
        val game = repo.install("owner", "AI", "Legacy", "", "<p>legacy</p>")
        val original = repo.start(game.id)
        assertThrows(IllegalStateException::class.java) { repo.openSession(game.id) }
        assertEquals(listOf(original), repo.readSnapshot().sessions)
        repo.abandon(original.id)
        assertNotEquals(original.id, repo.openSession(game.id).id)
    }
    @Test fun explicitHistoryEntryResumesThatSessionWithoutSelectingLatestOrCreatingAnother() {
        val storage = Storage(); val repo = OrbisMiniGameRepository(storage)
        val game = repo.install("owner", "AI", "G", "", "html")
        val first = repo.start(game.id); repo.saveCheckpoint(first.id, 0, "{\"turn\":3}")
        val later = repo.start(game.id); repo.saveCheckpoint(later.id, 0, "{\"turn\":7}")
        val reopened = OrbisMiniGameRepository(storage)
        assertEquals(first.id, reopened.openSession(game.id, first.id).id)
        assertEquals("{\"turn\":3}", reopened.openSession(game.id, first.id).checkpoint)
        assertEquals(later.id, reopened.openSession(game.id).id)
        val other = reopened.install("owner", "AI", "Other", "", "html")
        assertThrows(NoSuchElementException::class.java) { reopened.openSession(other.id, first.id) }
        assertThrows(NoSuchElementException::class.java) { reopened.openSession(game.id, "missing") }
        reopened.abandon(first.id)
        assertThrows(NoSuchElementException::class.java) { reopened.openSession(game.id, first.id) }
        assertEquals(2, reopened.readSnapshot().sessions.size)
        assertEquals("{\"turn\":7}", reopened.checkpoint(later.id).checkpoint)
    }
    @Test fun staleFrameCannotOverwriteNewerCheckpointAndDuplicateDoesNotIncrement() {
        val repo = OrbisMiniGameRepository(Storage()); val game = repo.install("owner", "AI", "G", "", "x")
        val session = repo.start(game.id)
        repo.saveCheckpoint(session.id, 0, "{\"turn\":1}")
        assertEquals(1L, repo.saveCheckpoint(session.id, 0, "{\"turn\":1}"))
        assertThrows(IllegalStateException::class.java) { repo.saveCheckpoint(session.id, 0, "{\"turn\":2}") }
        repo.saveCheckpoint(session.id, 1, "{\"turn\":2}")
        assertEquals("{\"turn\":2}", repo.checkpoint(session.id).checkpoint)
    }
    @Test fun changedSourceCannotReuseOldStateAndFinishingReleasesCheckpoint() {
        val repo = OrbisMiniGameRepository(Storage()); val game = repo.install("owner", "AI", "G", "", "old")
        val session = repo.start(game.id); repo.saveCheckpoint(session.id, 0, "{}")
        repo.install("owner", "AI", "G", "", "new", game.id, game.sha256)
        assertThrows(IllegalStateException::class.java) { repo.openSession(game.id) }
        assertThrows(IllegalStateException::class.java) { repo.saveCheckpoint(session.id, 1, "{\"x\":1}") }
        repo.abandon(session.id)
        assertNull(repo.readSnapshot().sessions.single().checkpoint)
        assertNotEquals(session.id, repo.openSession(game.id).id)
    }
    @Test fun failedWriteDoesNotPublishSuccessOrEraseOldProgress() {
        val storage = Storage(); val repo = OrbisMiniGameRepository(storage)
        val session = repo.start(repo.install("owner", "AI", "G", "", "html").id)
        repo.saveCheckpoint(session.id, 0, "{\"turn\":1}")
        val previous = repo.state.value; storage.fail = true
        assertThrows(IllegalStateException::class.java) { repo.saveCheckpoint(session.id, 1, "{\"turn\":2}") }
        assertTrue(repo.writeBlocked.value)
        assertEquals(previous, repo.state.value)
        storage.fail = false; repo.reloadFromStorage()
        assertFalse(repo.writeBlocked.value)
        assertEquals("{\"turn\":1}", repo.checkpoint(session.id).checkpoint)
    }
    @Test fun malformedOversizedAndDeepStateCannotReachPersistence() {
        listOf("[]", "x", "{\"a\":", "{\"x\":\"" + "a".repeat(65536) + "\"}",
            "{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}").forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) { OrbisMiniGameRepository.validateCheckpoint(payload) }
        }
        OrbisMiniGameRepository.validateCheckpoint("{\"text\":\"[[[\\\"}\"}")
    }
}
