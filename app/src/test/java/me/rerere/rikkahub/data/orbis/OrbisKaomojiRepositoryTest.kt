package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisKaomojiRepositoryTest {
    private class MemoryStorage : OrbisKaomojiStorage {
        var raw: String? = null
        var failAfterWrite = false
        override fun read() = raw
        override fun write(value: String) {
            raw = value
            if (failAfterWrite) error("synthetic private path")
        }
    }

    @Test fun `first use seeds only generic text faces without writing or emoji`() {
        val storage = MemoryStorage()
        val entries = OrbisKaomojiRepository(storage).readSnapshot().entries
        assertTrue(entries.isNotEmpty())
        entries.forEach {
            assertEquals(it, OrbisKaomojiRepository.normalize(it))
            assertFalse(it.text.codePoints().anyMatch { code -> code in 0x1F000..0x1FFFF })
        }
        assertNull(storage.raw)
    }

    @Test fun `human and AI operations persist in same library with revision conflict guard`() {
        val storage = MemoryStorage()
        val human = OrbisKaomojiRepository(storage)
        val first = human.add("  wink  ", " (^_~) ", listOf(" cheerful ", "cheerful"))
        assertEquals("wink", first.label)
        assertEquals(listOf("cheerful"), first.tags)
        val second = human.update(first.id, first.revision, "wink again", "(^_~)", emptyList())
        assertEquals(2L, second.revision)
        assertThrows(IllegalArgumentException::class.java) { human.update(first.id, first.revision, "stale", "(-_-)") }
        assertEquals(second, OrbisKaomojiRepository(storage).readSnapshot().entries.last())
        human.delete(second.id, second.revision)
        assertFalse(OrbisKaomojiRepository(storage).readSnapshot().entries.any { it.id == second.id })
    }

    @Test fun `duplicate add is idempotent and empty library stays empty`() {
        val storage = MemoryStorage()
        val repository = OrbisKaomojiRepository(storage)
        val before = repository.readSnapshot().entries
        assertEquals(before.first(), repository.add("different label", before.first().text))
        assertNull(storage.raw)
        before.forEach { repository.delete(it.id, it.revision) }
        assertTrue(OrbisKaomojiRepository(storage).readSnapshot().entries.isEmpty())
    }

    @Test fun `invalid content and duplicate update leave original storage unchanged`() {
        val storage = MemoryStorage()
        val repository = OrbisKaomojiRepository(storage)
        for (text in listOf("", " \n ", "x".repeat(1201), "\u0000", "\uD83D")) {
            assertThrows(IllegalArgumentException::class.java) { repository.add("label", text) }
        }
        assertThrows(IllegalArgumentException::class.java) { repository.add("label", "(x)", List(9) { "tag$it" }) }
        val entries = repository.readSnapshot().entries
        assertThrows(IllegalArgumentException::class.java) { repository.update(entries[1].id, 1, "duplicate", entries[0].text) }
        assertNull(storage.raw)
    }

    @Test fun `multi line art and emoji preserve indentation through save reload and update`() {
        val storage = MemoryStorage()
        val repository = OrbisKaomojiRepository(storage)
        val art = "  /\\_/\\\n ( o.o ) 🌟\n  > ^ < 👩‍💻"
        val entry = repository.add("文字画", art)
        assertEquals(art, entry.text)
        assertEquals(entry, OrbisKaomojiRepository(storage).readSnapshot().entries.last())
        assertEquals("  x\n    y", repository.update(entry.id, entry.revision, "换行", "  x\r\n\ty").text)
    }

    @Test fun `unicode supplementary characters count once rather than bytes or UTF16 units`() {
        val repository = OrbisKaomojiRepository(MemoryStorage())
        assertEquals(1, OrbisKaomojiRepository.characterCount("😀"))
        assertEquals(1200, OrbisKaomojiRepository.characterCount(repository.add("emoji", "😀".repeat(1200)).text))
        assertThrows(IllegalArgumentException::class.java) { repository.add("too long", "😀".repeat(1201)) }
    }

    @Test fun `uncertain writes block followups until explicit reload`() {
        val storage = MemoryStorage()
        val repository = OrbisKaomojiRepository(storage)
        storage.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) { repository.add("test", "(-_-)z") }
        assertFalse(repository.readSnapshot().entries.any { it.text == "(-_-)z" })
        assertThrows(IllegalStateException::class.java) { repository.add("another", "(o_o)") }
        assertThrows(IllegalStateException::class.java) { repository.snapshotForBackup() }
        storage.failAfterWrite = false
        repository.reload()
        assertTrue(repository.readSnapshot().entries.any { it.text == "(-_-)z" })
        assertEquals(repository.readSnapshot(), repository.snapshotForBackup())
        repository.add("another", "(o_o)")
    }

    @Test fun `corrupt files are never silently replaced by seeds`() {
        val storage = MemoryStorage().apply { raw = "not json" }
        assertThrows(Exception::class.java) { OrbisKaomojiRepository(storage) }
        assertEquals("not json", storage.raw)
        storage.raw = """{"version":2,"entries":[]}"""
        assertThrows(IllegalArgumentException::class.java) { OrbisKaomojiRepository(storage) }
    }
}
