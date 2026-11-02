package me.rerere.rikkahub.data.orbis.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MusicPlayback(
    val trackId: String? = null, val playing: Boolean = false, val loading: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val queue: List<String> = emptyList(),
    val index: Int = 0, val repeat: String = "all", val revision: Long = 0, val error: String? = null,
    val selectedBy: String = "我", val volume: Float = 1f,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicRuntime private constructor(private val context: Context) {
    val store = MusicRepository(File(context.filesDir, "orbis-music"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var controller: MediaController? = null
    private val mutable = MutableStateFlow(store.state.value.let { s ->
        val id = s.queue.getOrNull(s.current)
        MusicPlayback(trackId = id, position = s.position, duration = s.tracks.firstOrNull { it.id == id }?.durationMs ?: 0,
            queue = s.queue, index = s.current, repeat = s.repeat, selectedBy = s.selectedBy)
    })
    val playback = mutable.asStateFlow()
    private val uiError = MutableStateFlow<String?>(null)
    val error = uiError.asStateFlow()
    private val libraryVisible = MutableStateFlow(false)
    val showLibrary = libraryVisible.asStateFlow()
    fun library(show: Boolean) { libraryVisible.value = show }
    fun dismissError() { uiError.value = null }
    fun human(block: suspend () -> Unit) { scope.launch { try { block() } catch(e: CancellationException) { throw e }
        catch(e: Exception) { uiError.value = friendlyMusicError(e) } } }
    fun appearance(block: (MusicAppearance) -> MusicAppearance) = human { withContext(Dispatchers.IO) { store.appearance(block) }; Unit }
    private suspend fun connected(): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, OrbisMusicService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if(this@MusicRuntime.controller === controller) this@MusicRuntime.controller = null
                }
            }).buildAsync()
        val c = withTimeout(10000) { suspendCancellableCoroutine<MediaController> { cont ->
            future.addListener({ try { val value = future.get(); if(cont.isActive) cont.resume(value) else value.release() }
                catch(e: Exception) { if(cont.isActive) cont.resumeWithException(e) } }, androidx.core.content.ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(true) }
        } }
        controller = c
        // Only connect on explicit playback/read actions. Entering a chat never starts a service.
        return c
    }
    /** Service events (including lock-screen controls) are the source of playback truth. */
    fun publish(p: Player, changed: Boolean) {
        val old = mutable.value
        mutable.value = MusicPlayback(p.currentMediaItem?.mediaId, p.isPlaying,
            p.playbackState == Player.STATE_BUFFERING, p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0),
            (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex.coerceAtLeast(0),
            if(p.shuffleModeEnabled) "shuffle" else if(p.repeatMode == Player.REPEAT_MODE_ONE) "one" else if(p.repeatMode == Player.REPEAT_MODE_ALL) "all" else "off",
            old.revision + if(changed) 1 else 0, p.playerError?.errorCodeName, store.state.value.selectedBy, p.volume)
    }
    suspend fun command(action: String, ids: List<String> = emptyList(), index: Int = 0,
                        position: Long = 0, repeat: String = "all", actor: String = "我",
                        expected: Long? = null, enabled: () -> Boolean = { true }, actorId: String = "human"): MusicPlayback = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(enabled()) { "music_disabled" }
            check(store.loadError == null) { "music_library_unreadable" }
            val c = connected()
            check(enabled()) { "music_disabled" }
            if(expected != null) check(expected == playback.value.revision) { "music_revision_conflict" }
            when(action) {
                "play" -> {
                    require(ids.isNotEmpty() && ids.size <= 2000 && index in ids.indices) { "music_queue_invalid" }
                    val items = withContext(Dispatchers.IO) { ids.map { id -> mediaItem(store, store.track(id)) } }
                    check(enabled()) { "music_disabled" }
                    if(expected != null) check(expected == playback.value.revision) { "music_revision_conflict" }
                    withContext(Dispatchers.IO) { store.selectedBy(actor, actorId) }
                    c.setMediaItems(items, index, 0); c.prepare(); c.play()
                }
                "queue" -> {
                    require(ids.size <= 2000) { "music_queue_invalid" }
                    val current = c.currentMediaItem?.mediaId; val pos = c.currentPosition; val playing = c.playWhenReady
                    val items = withContext(Dispatchers.IO) { ids.map { mediaItem(store, store.track(it)) } }
                    check(enabled()) { "music_disabled" }
                    if(expected != null) check(expected == playback.value.revision) { "music_revision_conflict" }
                    if(items.isEmpty()) { c.pause(); c.clearMediaItems() } else {
                        val idx = ids.indexOf(current).takeIf { it >= 0 } ?: 0
                        c.setMediaItems(items, idx, if(current == ids[idx]) pos else 0); c.prepare(); c.playWhenReady = playing
                    }
                }
                "resume" -> { check(c.mediaItemCount > 0) { "music_queue_empty" }; if(c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(); c.prepare(); c.play() }
                "pause" -> c.pause()
                "stop" -> { c.pause(); c.seekTo(0) }
                "next" -> { check(c.hasNextMediaItem()) { "music_queue_end" }; c.seekToNextMediaItem() }
                "previous" -> c.seekToPrevious()
                "seek" -> { check(c.mediaItemCount > 0) { "music_queue_empty" }; c.seekTo(position.coerceIn(0, c.duration.coerceAtLeast(0))) }
                "mode" -> {
                    require(repeat in setOf("all", "one", "shuffle", "off")) { "music_mode_invalid" }
                    c.shuffleModeEnabled = repeat == "shuffle"
                    c.repeatMode = when(repeat) { "one" -> Player.REPEAT_MODE_ONE; "all", "shuffle" -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }
                }
                "state" -> Unit
                else -> error("music_action_invalid")
            }
            // A controller command is asynchronous; report its acceptance separately from actual playback.
            if(action != "state") mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
            playback.value
        }
    }
    companion object {
        @Volatile private var instance: MusicRuntime? = null
        fun get(context: Context): MusicRuntime = instance ?: synchronized(this) {
            instance ?: MusicRuntime(context.applicationContext).also { instance = it }
        }
    }
}
internal fun mediaItem(store: MusicRepository, track: MusicTrack): MediaItem {
    val file = store.audio(track); check(file.isFile) { "music_audio_missing" }
    return MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(file)).setMediaMetadata(
        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setArtworkUri(if(track.cover) Uri.fromFile(store.cover(track)) else null).build()).build()
}
internal fun friendlyMusicError(e: Exception): String = when(e.message) {
    "music_library_too_large" -> "曲库文字记录已达容量上限，原记录保持不变。可先缩短过长的歌词后再保存。"
    "music_folder_too_large" -> "所选文件夹内容较多，请分批选择较小的文件夹导入。"
    "music_revision_conflict" -> "播放或歌单刚刚有变化，请刷新后再操作。"
    "music_library_unreadable" -> "曲库记录暂时无法读取，原文件已保留。请保留现场并反馈，避免重新导入覆盖记录。"
    "music_disabled" -> "当前助手的音乐工具已关闭，可在本地工具中开启。"
    "music_audio_missing" -> "本地音频文件已不可用，请重新导入。"
    "music_queue_empty" -> "先从曲库选择一首歌。"
    "music_queue_end" -> "已经是队列最后一首。"
    "music_playlist_owner_conflict" -> "这份歌单由另一位创建者管理。"
    else -> "操作未完成：${e.message?.take(150) ?: "请稍后重试"}"
}
