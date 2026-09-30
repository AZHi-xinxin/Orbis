package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisMiniGameRepositoryTest {
    private class Storage : OrbisGameStorage {
        var raw: String? = null
        var fail = false
        override fun read() = raw
        override fun write(value: String) { raw = value; if (fail) error("synthetic ambiguous commit") }
    }
    private class Fixture {
        val storage = Storage()
        var clock = 1_000L
        var sequence = 0
        val repo = OrbisMiniGameRepository(storage, { clock }, { "id-${sequence++}" })
        fun install(html: String = "<!doctype html><button>井字棋</button>") =
            repo.install("assistant-1", "synthetic AI", "井字棋", "synthetic game", html)
    }

    @Test fun `install stores exact source without starting a game`() {
        val f = Fixture()
        val source = "<html><script>window.privateSynthetic = 'not executed';</script></html>"
        val game = f.install(source)
        assertEquals(source, game.html)
        assertEquals(OrbisMiniGameRepository.hash(source), game.sha256)
        assertTrue(f.repo.readSnapshot().sessions.isEmpty())
        assertEquals(f.repo.readSnapshot(), OrbisMiniGameRepository(f.storage).readSnapshot())
    }

    @Test fun `host started game result survives restart with host elapsed time`() {
        val f = Fixture()
        val game = f.install()
        val session = f.repo.start(game.id)
        f.clock += 7_500
        val completed = f.repo.finish(session.id, "win", 5)
        assertEquals(7_500L, completed.finishedAt!! - completed.startedAt)
        assertEquals(5, completed.moveCount)
        assertEquals(game.sha256, completed.gameSha256)
        assertEquals(completed, OrbisMiniGameRepository(f.storage).readSnapshot().sessions.single())
    }

    @Test fun `duplicate same result is idempotent but conflicting result is rejected`() {
        val f = Fixture(); val game = f.install(); val session = f.repo.start(game.id)
        val completed = f.repo.finish(session.id, "draw", 9)
        val raw = f.storage.raw
        f.clock += 9_000
        assertEquals(completed, f.repo.finish(session.id, "draw", 9))
        assertEquals(raw, f.storage.raw)
        assertThrows(IllegalArgumentException::class.java) { f.repo.finish(session.id, "win", 9) }
        assertEquals(completed, f.repo.readSnapshot().sessions.single())
    }

    @Test fun `cannot fabricate session or invalid result`() {
        val f = Fixture(); val game = f.install(); val session = f.repo.start(game.id)
        assertThrows(IllegalStateException::class.java) { f.repo.finish("unknown", "win", 3) }
        assertThrows(IllegalArgumentException::class.java) { f.repo.finish(session.id, "invented", 3) }
        assertThrows(IllegalArgumentException::class.java) { f.repo.finish(session.id, "win", -1) }
        assertNull(f.repo.readSnapshot().sessions.single().finishedAt)
    }

    @Test fun `update retains original session identity and requires matching author and revision`() {
        val f = Fixture(); val original = f.install(); val session = f.repo.start(original.id)
        assertThrows(IllegalArgumentException::class.java) {
            f.repo.install("other-ai", "other", "other", "", "<p>new</p>", original.id, original.sha256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.repo.install(original.authorId, "same", "new", "", "<p>new</p>", original.id, "stale")
        }
        f.clock++
        val updated = f.repo.install(original.authorId, "same", "new", "", "<p>new</p>", original.id, original.sha256)
        assertNotEquals(original.sha256, updated.sha256)
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(session, f.repo.readSnapshot().sessions.single())
        f.repo.finish(session.id, "completed", 1)
        assertEquals(original.sha256, OrbisMiniGameRepository(f.storage).readSnapshot().sessions.single().gameSha256)
    }

    @Test fun `failed write blocks further reads and mutations until explicit reload`() {
        val f = Fixture(); f.storage.fail = true
        assertThrows(IllegalStateException::class.java) { f.install() }
        assertThrows(IllegalStateException::class.java) { f.repo.readSnapshot() }
        assertThrows(IllegalStateException::class.java) { f.install() }
        f.storage.fail = false
        f.repo.reloadFromStorage()
        assertEquals(1, f.repo.readSnapshot().games.size)
    }

    @Test fun `corrupt source hash is not silently repaired or replaced`() {
        val f = Fixture(); val game = f.install()
        val corrupted = f.storage.raw!!.replace(game.sha256, "0".repeat(64))
        f.storage.raw = corrupted
        assertThrows(IllegalArgumentException::class.java) { f.repo.reloadFromStorage() }
        assertEquals(corrupted, f.storage.raw)
        assertThrows(IllegalStateException::class.java) { f.repo.readSnapshot() }
    }

    @Test fun `leaving unfinished session does not become a loss and explicit abandon is idempotent`() {
        val f = Fixture(); val game = f.install(); val session = f.repo.start(game.id)
        assertNull(OrbisMiniGameRepository(f.storage).readSnapshot().sessions.single().result)
        val abandoned = f.repo.abandon(session.id)
        assertEquals("abandoned", abandoned.result)
        assertEquals(abandoned, f.repo.abandon(session.id))
    }

    @Test fun `backwards clock cannot create negative completed duration`() {
        val f = Fixture(); val game = f.install(); val session = f.repo.start(game.id)
        f.clock = 0
        assertEquals(session.startedAt, f.repo.finish(session.id, "completed", 0).finishedAt)
    }

    @Test fun `oversized or empty HTML leaves original store intact`() {
        val f = Fixture(); f.install(); val raw = f.storage.raw
        listOf("", " ", "a".repeat(OrbisMiniGameRepository.MAX_HTML_CHARS + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { f.install(it) }
            assertEquals(raw, f.storage.raw)
        }
    }
}
