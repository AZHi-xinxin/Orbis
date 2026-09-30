package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioPlayer(context: Context, audioUsage: Int = android.media.AudioAttributes.USAGE_MEDIA) {
    private val player = ExoPlayer.Builder(context).build().apply {
        // The call runtime owns communication focus/routing; do not alter global volume or mode here.
        setAudioAttributes(AudioAttributes.Builder().setUsage(audioUsage)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null
    private val generation = PlaybackGeneration()
    private val handler = Handler(Looper.getMainLooper())
    private var activeToken: Long? = null
    private var activeListener: Player.Listener? = null
    private var activeContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var released = false
    private val outputGain = PlaybackOutputGain()

    /** Changes this player only; decoding and the media clock continue while inaudible. */
    fun setOutputMuted(muted: Boolean) = onPlayerThread {
        if (!released) {
            outputGain.setMuted(muted)
            player.volume = outputGain.effectiveVolume
        }
    }

    /** Safe from any caller thread. No system/stream volume, focus, seek or playback reset. */
    fun setOutputVolume(volume: Float) {
        require(volume.isFinite()) { "Output volume must be finite" }
        onPlayerThread {
            if (!released) {
                outputGain.setVolume(volume)
                player.volume = outputGain.effectiveVolume
            }
        }
    }

    fun pause() {
        val token = generation.current()
        onPlayerThread { if (!released && generation.isCurrent(token) && activeToken != null) player.pause() }
    }
    fun resume() {
        val token = generation.current()
        onPlayerThread { if (!released && generation.isCurrent(token) && activeToken != null) player.play() }
    }
    fun stop() {
        val token = generation.advance()
        onPlayerThread {
            if (released || !generation.isCurrent(token)) return@onPlayerThread
            cancelCurrent()
            player.stop()
            _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
        }
    }
    fun clear() {
        val token = generation.advance()
        onPlayerThread {
            if (released || !generation.isCurrent(token)) return@onPlayerThread
            cancelCurrent()
            player.clearMediaItems()
        }
    }
    fun release() {
        if (released) return
        released = true
        generation.advance()
        onPlayerThread {
            cancelCurrent()
            scope.cancel()
            player.release()
        }
    }
    fun seekBy(ms: Long) = onPlayerThread { if (!released && activeToken != null) player.seekTo(player.currentPosition + ms) }
    fun setSpeed(speed: Float) {
        onPlayerThread {
            if (released) return@onPlayerThread
            player.playbackParameters = PlaybackParameters(speed)
            _playbackState.update { it.copy(speed = speed) }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse): Unit = withContext(Dispatchers.Main.immediate) {
        check(!released) { "Audio player released" }
        val token = generation.advance()
        cancelCurrent()
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData
        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        suspendCancellableCoroutine<Unit> { cont ->
            activeToken = token
            activeContinuation = cont
            fun ownsPlayback() = !released && generation.isCurrent(token) && activeToken == token
            fun detach() {
                if (activeToken != token) return
                activeListener?.let(player::removeListener)
                activeListener = null
                activeContinuation = null
                activeToken = null
                stopPositionUpdates()
            }
            _playbackState.update {
                it.copy(
                    status = PlaybackStatus.Buffering,
                    positionMs = 0L,
                    durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
                )
            }

            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (!ownsPlayback()) return
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                            stopPositionUpdates()
                        }
                        Player.STATE_READY -> {
                            val isPlaying = player.isPlaying
                            val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                            _playbackState.update {
                                it.copy(
                                    status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                    durationMs = duration,
                                    positionMs = player.currentPosition
                                )
                            }
                            if (isPlaying) startPositionUpdates(token) else stopPositionUpdates()
                        }
                        Player.STATE_ENDED -> {
                            stopPositionUpdates()
                            _playbackState.update {
                                it.copy(
                                    status = PlaybackStatus.Ended,
                                    positionMs = player.duration.coerceAtLeast(it.positionMs),
                                    durationMs = if (player.duration > 0) player.duration else it.durationMs
                                )
                            }
                            detach()
                            if (cont.isActive) cont.resume(Unit)
                        }
                        Player.STATE_IDLE -> {
                            stopPositionUpdates()
                            _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!ownsPlayback()) return
                    detach()
                    _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                    if (cont.isActive) cont.resumeWithException(error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!ownsPlayback()) return
                    val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                    _playbackState.update { it.copy(status = status) }
                    if (isPlaying) startPositionUpdates(token) else stopPositionUpdates()
                }
            }
            activeListener = listener
            cont.invokeOnCancellation {
                onPlayerThread {
                    // Cancellation can arrive after another play() has taken ownership.
                    if (ownsPlayback()) {
                        generation.advance()
                        detach()
                        player.stop()
                    }
                }
            }
            if (!cont.isActive) { detach(); return@suspendCancellableCoroutine }
            try {
                player.addListener(listener)
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
            } catch (failure: Exception) {
                detach()
                if (cont.isActive) cont.resumeWithException(failure)
            }
        }
    }

    private fun onPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post { block() }
    }

    private fun cancelCurrent() {
        activeListener?.let(player::removeListener)
        activeListener = null
        val previous = activeContinuation
        activeContinuation = null
        activeToken = null
        stopPositionUpdates()
        previous?.cancel(CancellationException("Audio playback stopped or replaced"))
    }

    private fun startPositionUpdates(token: Long) {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (!released && generation.isCurrent(token) && activeToken == token) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
