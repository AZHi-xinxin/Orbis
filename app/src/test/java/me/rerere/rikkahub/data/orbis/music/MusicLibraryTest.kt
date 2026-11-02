package me.rerere.rikkahub.data.orbis.music

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun repo() = MusicRepository(temp.newFolder())
    private fun track(n: Int = 0) = MusicTrack(n.toString(16).padStart(64, '0'), "Song $n", "Artist")
    @Test fun duplicateImportKeepsFavoriteLyricsAndOnlyOneCopy() {
        val r = repo(); assertTrue(r.add(track())); r.favorite(track().id); r.lyrics(track().id, "[00:01.1]test")
        assertFalse(r.add(track())); assertEquals(1, r.state.value.tracks.size)
        val restored = MusicRepository(r.root); assertTrue(restored.track(track().id).favorite); assertTrue(restored.track(track().id).lyrics.contains("test"))
    }
    @Test fun deletingPlaylistNeverDeletesMusicOrOtherPlaylists() {
        val r = repo(); r.add(track()); val file = r.audio(track()); file.parentFile!!.mkdirs(); file.writeText("original")
        val a = r.playlist("A", "晚安", "human", "我", listOf(track().id)); val b = r.playlist("B", "晚安", "ai:1", "AI", listOf(track().id))
        r.deletePlaylist(a.id, "human", a.revision)
        assertEquals("original", file.readText()); assertEquals(b, r.state.value.playlists.single()); assertEquals(1, r.state.value.tracks.size)
    }
    @Test fun aiCannotRewriteHumanOrOtherAssistantPlaylist() {
        val r = repo(); val p = r.playlist("A", "分类", "human", "我", emptyList())
        assertThrows(IllegalStateException::class.java) { r.playlist("B", "分类", "ai:1", "AI", emptyList(), p.id, p.revision) }
        assertThrows(IllegalStateException::class.java) { r.deletePlaylist(p.id, "ai:1", p.revision) }
        assertEquals(p, r.state.value.playlists.single())
    }
    @Test fun concurrentPlaylistEditRequiresFreshRevision() {
        val r = repo(); val p = r.playlist("A", "分类", "ai:1", "AI", emptyList())
        r.playlist("Human edit", "分类", "human", "我", emptyList(), p.id, p.revision)
        assertThrows(IllegalStateException::class.java) { r.playlist("Stale AI", "分类", "ai:1", "AI", emptyList(), p.id, p.revision) }
        assertEquals("Human edit", r.state.value.playlists.single().name)
    }
    @Test fun playlistCannotContainMissingSongs() {
        val r = repo(); assertThrows(IllegalArgumentException::class.java) { r.playlist("A", "分类", "human", "我", listOf("made-up")) }
        assertTrue(r.state.value.playlists.isEmpty())
    }
    @Test fun categoryRenamePreservesPlaylistAndAdvancesRevision() {
        val r = repo(); val p = r.playlist("A", "旧", "human", "我", emptyList()); r.category("旧", "新")
        assertEquals(p.copy(category = "新", categories = listOf("新"), revision = 2), r.state.value.playlists.single())
    }
    @Test fun appearanceDoesNotChangeQueueAndPersistsHiddenState() {
        val r = repo(); r.add(track()); r.checkpoint(listOf(track().id), 0, 8000, "shuffle", "AI")
        r.appearance { it.copy(mode = "hidden", opacity = .3f, lyricOpacity = .1f, locked = true) }
        val saved = MusicRepository(r.root).state.value
        assertEquals(listOf(track().id), saved.queue); assertEquals(8000L, saved.position); assertTrue(saved.appearance.locked); assertEquals("hidden", saved.appearance.mode)
    }
    @Test fun filePathsRejectTraversal() {
        val r = repo(); assertThrows(IllegalArgumentException::class.java) { r.audio(track().copy(id = "../../file")) }
        assertThrows(IllegalArgumentException::class.java) { r.audio(track().copy(extension = "../../file")) }
    }
    @Test fun lrcHandlesRepeatedTimesFractionAndOffset() {
        val lines = parseLrc("[ar:Artist]\n[offset:-100]\n[01:02.30][00:01.2]repeated\n[00:02]second\n[00:99]bad")
        assertEquals(listOf(1100L, 1900L, 62200L), lines.map { it.time }); assertEquals("repeated", lines.first().text)
    }
    @Test fun unreadableLibraryIsPreservedAndCannotBeOverwrittenByImport() {
        val root = temp.newFolder(); val file = java.io.File(root, "library.json"); file.writeText("incomplete existing record")
        val r = MusicRepository(root)
        assertEquals("music_library_unreadable", r.loadError)
        assertThrows(IllegalStateException::class.java) { r.add(track()) }
        assertEquals("incomplete existing record", file.readText())
    }
    @Test fun oversizedWritePreservesLastReadableLibrary() {
        val r = repo(); r.add(track())
        val file = java.io.File(r.root, "library.json"); val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) { r.add(track(1).copy(lyrics = "a".repeat(32 * 1024 * 1024))) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(track()), MusicRepository(r.root).state.value.tracks)
        assertEquals(listOf(track()), r.state.value.tracks)
    }
    @Test fun plainLyricsDontBecomeFakeTimedLyrics() { assertTrue(parseLrc("just words").isEmpty()); assertTrue(parseLrc("").isEmpty()) }
}
