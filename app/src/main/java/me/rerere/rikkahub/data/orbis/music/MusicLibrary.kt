package me.rerere.rikkahub.data.orbis.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal val musicJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Serializable internal data class MusicTrack(
    val id: String, val title: String, val artist: String = "未知歌手", val album: String = "",
    val durationMs: Long = 0, val extension: String = "audio", val cover: Boolean = false,
    val lyrics: String = "", val favorite: Boolean = false,
)
@Serializable internal data class MusicPlaylist(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val creatorId: String = "human", val creatorName: String = "我", val category: String = "未分类",
    val tracks: List<String> = emptyList(), val revision: Int = 1,
    val description: String = "", val categories: List<String> = listOf(category), val cover: Boolean = false,
)
@Serializable internal data class MusicAppearance(
    val mode: String = "hidden", val opacity: Float = .92f, val lyricOpacity: Float = .65f,
    val lyricSize: Float = 20f, val locked: Boolean = false, val style: String = "galaxy", val x: Float = .5f, val y: Float = .05f,
)
@Serializable internal data class MusicLibrary(
    val schema: Int = 1, val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(), val categories: List<String> = emptyList(),
    val appearance: MusicAppearance = MusicAppearance(),
    val queue: List<String> = emptyList(), val current: Int = 0, val position: Long = 0,
    val repeat: String = "all", val selectedBy: String = "我", val selectedById: String = "human",
)

/** One app-wide library, shared by the native controls and opt-in assistant tools. */
internal class MusicRepository(val root: File) {
    private val loaded = runCatching { read() }
    val loadError: String? = loaded.exceptionOrNull()?.let { "music_library_unreadable" }
    private val mutable = MutableStateFlow(loaded.getOrDefault(MusicLibrary()))
    val state = mutable.asStateFlow()
    private fun read(): MusicLibrary {
        val f = File(root, "library.json")
        if (!f.exists()) return MusicLibrary()
        check(f.length() <= 32L * 1024 * 1024) { "music_library_too_large" }
        return musicJson.decodeFromString<MusicLibrary>(f.readText()).also { s ->
            check(s.schema == 1 && s.tracks.size <= 10000 && s.playlists.size <= 500) { "music_library_invalid" }
            check(s.tracks.all { it.id.matches(Regex("[a-f0-9]{64}")) && it.extension.matches(Regex("[a-z0-9]{1,8}")) }) { "music_library_invalid" }
            check(s.tracks.map { it.id }.distinct().size == s.tracks.size) { "music_library_invalid" }
            check(s.playlists.all { it.id.matches(Regex("[a-f0-9-]{36}")) }) { "music_library_invalid" }
        }
    }
    @Synchronized private fun update(block: (MusicLibrary) -> MusicLibrary): MusicLibrary {
        check(loadError == null) { "music_library_unreadable" }
        val next = block(mutable.value)
        val bytes = musicJson.encodeToString(next).toByteArray()
        check(bytes.size <= 32L * 1024 * 1024) { "music_library_too_large" }
        check(root.isDirectory || root.mkdirs())
        val temp = File(root, "library.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), File(root, "library.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        mutable.value = next
        return next
    }
    fun track(id: String) = state.value.tracks.firstOrNull { it.id == id } ?: error("music_track_missing")
    fun audio(track: MusicTrack): File {
        require(track.id.matches(Regex("[a-f0-9]{64}")) && track.extension.matches(Regex("[a-z0-9]{1,8}")))
        return File(root, "audio/${track.id}.${track.extension}")
    }
    fun cover(track: MusicTrack) = File(root, "art/${track.id}.jpg")
    fun add(track: MusicTrack): Boolean {
        var added = false
        update { if (it.tracks.any { t -> t.id == track.id }) it else {
            check(it.tracks.size < 10000) { "music_library_full" }; added = true; it.copy(tracks = it.tracks + track)
        } }
        return added
    }
    fun metadata(id: String, title: String, artist: String, album: String) = update { s ->
        track(id); s.copy(tracks = s.tracks.map { if(it.id == id) it.copy(title = label(title), artist = label(artist), album = album.take(300)) else it })
    }
    fun reorderCategories(names: List<String>) = update { s ->
        require(names.size == s.categories.size && names.toSet() == s.categories.toSet()); s.copy(categories = names)
    }
    fun playlistCover(id: String) = update { s ->
        check(s.playlists.any { it.id == id }); s.copy(playlists = s.playlists.map { if(it.id == id) it.copy(cover = true, revision = it.revision + 1) else it })
    }
    fun playlistCoverFile(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "art/playlist-$id.jpg") }
    fun selectedBy(name: String, actorId: String) = update { it.copy(selectedBy = name, selectedById = actorId) }
    fun favorite(id: String) = update { it.copy(tracks = it.tracks.map { t -> if(t.id == id) t.copy(favorite = !t.favorite) else t }) }
    fun lyrics(id: String, text: String) = update {
        require(text.length <= 200000) { "music_lyrics_too_large" }
        track(id); it.copy(tracks = it.tracks.map { t -> if(t.id == id) t.copy(lyrics = text) else t })
    }
    fun category(name: String, replacement: String? = null) = update { s ->
        val value = label(name).also { require(',' !in it && '，' !in it) { "music_category_invalid" } }
        if (replacement == null) s.copy(categories = (s.categories + value).distinct()) else {
            val new = label(replacement)
            s.copy(categories = (s.categories.filterNot { it == value } + new).distinct(),
                playlists = s.playlists.map { if(value in it.categories) it.copy(category = if(it.category == value) new else it.category, categories = it.categories.map { c -> if(c == value) new else c }.distinct(), revision = it.revision + 1) else it })
        }
    }
    fun playlist(name: String, category: String, creatorId: String, creatorName: String,
                 ids: List<String>, id: String? = null, expected: Int? = null, description: String? = null): MusicPlaylist {
        var result: MusicPlaylist? = null
        update { s ->
            require(ids.size <= 2000 && ids.all { key -> s.tracks.any { it.id == key } }) { "music_track_missing" }
            val old = id?.let { key -> s.playlists.firstOrNull { it.id == key } ?: error("music_playlist_missing") }
            check(old == null || expected == old.revision) { "music_revision_conflict" }
            check(creatorId == "human" || old == null || old.creatorId == creatorId) { "music_playlist_owner_conflict" }
            check(old != null || s.playlists.size < 500) { "music_playlists_full" }
            val cats = category.split(',', '，').map { label(it) }.distinct().also { require(it.size <= 10) }
            val cat = cats.first()
            val p = (old ?: MusicPlaylist(name = label(name), creatorId = creatorId, creatorName = label(creatorName)))
                .copy(name = label(name), category = cat, categories = cats, description = (description ?: old?.description ?: "").also { require(it.length <= 2000) }, tracks = ids.distinct(), revision = (old?.revision ?: 0) + 1)
            result = p
            s.copy(playlists = s.playlists.filterNot { it.id == p.id } + p, categories = (s.categories + cats).distinct())
        }
        return result!!
    }
    fun deletePlaylist(id: String, actor: String, expected: Int) = update { s ->
        val p = s.playlists.firstOrNull { it.id == id } ?: error("music_playlist_missing")
        check(p.revision == expected) { "music_revision_conflict" }
        check(actor == "human" || actor == p.creatorId) { "music_playlist_owner_conflict" }
        s.copy(playlists = s.playlists.filterNot { it.id == id }) // Audio files remain in the library.
    }
    fun appearance(transform: (MusicAppearance) -> MusicAppearance) = update { s ->
        val a = transform(s.appearance)
        require(a.style in setOf("galaxy", "mist") && a.opacity.isFinite() && a.lyricOpacity.isFinite() && a.lyricSize.isFinite() && a.x.isFinite() && a.y.isFinite())
        require(a.mode in setOf("expanded", "mini", "lyrics", "hidden"))
        s.copy(appearance = a.copy(opacity = a.opacity.coerceIn(.15f, 1f), lyricOpacity = a.lyricOpacity.coerceIn(0f, 1f),
            lyricSize = a.lyricSize.coerceIn(14f, 32f), x = a.x.coerceIn(0f, 1f), y = a.y.coerceIn(0f, 1f)))
    }
    fun checkpoint(queue: List<String>, current: Int, position: Long, repeat: String, selectedBy: String) = update {
        it.copy(queue = queue, current = current.coerceAtLeast(0), position = position.coerceAtLeast(0), repeat = repeat, selectedBy = selectedBy)
    }
    companion object { fun label(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 120) { "music_label_invalid" } } }
}

internal data class LyricLine(val time: Long, val text: String)
internal fun parseLrc(text: String): List<LyricLine> {
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60000, 60000) ?: 0
    return text.lineSequence().flatMap { row ->
        val words = row.replace(stamp, "").trim()
        stamp.findAll(row).mapNotNull { m ->
            val seconds = m.groupValues[2].toLong()
            if (seconds >= 60) null else LyricLine((m.groupValues[1].toLong() * 60000 + seconds * 1000 +
                m.groupValues[3].padEnd(3, '0').toLong() + offset).coerceAtLeast(0), words)
        }
    }.sortedBy { it.time }.take(10000).toList()
}
