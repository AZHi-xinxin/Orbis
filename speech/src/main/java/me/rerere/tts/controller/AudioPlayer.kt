package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class AudioPlayer(context: Context, private val audioUsage: Int = android.media.AudioAttributes.USAGE_MEDIA) {
    private val appContext = context.applicationContext
    @Volatile private var playbackSnapshot = VoicePlaybackSnapshot.capture()
    private fun createPlayer() = ExoPlayer.Builder(appContext, object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(VoiceMixAudioProcessor { playbackSnapshot.config }))
                .build()
    }).build().apply {
        // The call runtime owns communication focus/routing; do not alter global volume or mode here.
        setAudioAttributes(AudioAttributes.Builder().setUsage(audioUsage)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
    }
    private var player = createPlayer()
    private var needsPlayerRebuild = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null
    private var livenessJob: Job? = null
    private var explicitlyPaused = false
    private val generation = PlaybackGeneration()
    private val handler = Handler(Looper.getMainLooper())
    private var activeToken: Long? = null
    private var activeListener: Player.Listener? = null
    private var activeContinuation: CancellableContinuation<Unit>? = null
    @Volatile private var released = false
    private val outputGain = PlaybackOutputGain()
    private var baseSpeed = 1f
    private fun applyMix(atSegmentBoundary: Boolean = false) {
        if (released || needsPlayerRebuild) return
        val (config, speed) = playbackSnapshot
        // The boundary call is made only after stop/clear and before installing the new item.
        // Slider updates (including bypass/presets) never hot-reconfigure a playing AudioSink.
        playbackRateAtBoundary(config, speed, activeToken != null && !atSegmentBoundary)?.let { rate ->
            runCatching { player.playbackParameters = PlaybackParameters(rate.speed, rate.pitch) }
                .onFailure { runCatching { player.playbackParameters = PlaybackParameters(speed) } }
        }
        val mix = config.effective(speed, outputGain.effectiveVolume)
        runCatching { player.volume = mix.volume }
            .onFailure { runCatching { player.volume = outputGain.effectiveVolume } }
    }

    /** Changes this player only; decoding and the media clock continue while inaudible. */
    fun setOutputMuted(muted: Boolean) = onPlayerThread {
        if (!released) {
            outputGain.setMuted(muted)
            applyMix()
        }
    }

    /** Safe from any caller thread. No system/stream volume, focus, seek or playback reset. */
    fun setOutputVolume(volume: Float) {
        require(volume.isFinite()) { "Output volume must be finite" }
        onPlayerThread {
            if (!released) {
                outputGain.setVolume(volume)
                applyMix()
            }
        }
    }

    fun pause() {
        val token = generation.current()
        onPlayerThread { if (!released && generation.isCurrent(token) && activeToken != null) {
            explicitlyPaused = true
            player.pause()
        } }
    }
    fun resume() {
        val token = generation.current()
        onPlayerThread { if (!released && generation.isCurrent(token) && activeToken != null) {
            explicitlyPaused = false
            player.play()
        } }
    }
    fun stop() {
        val token = generation.advance()
        onPlayerThread {
            if (released || !generation.isCurrent(token)) return@onPlayerThread
            cancelCurrent()
            if (!needsPlayerRebuild) runCatching { player.stop() }.onFailure { discardFailedPlayer() }
            _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
        }
    }
    fun clear() {
        val token = generation.advance()
        onPlayerThread {
            if (released || !generation.isCurrent(token)) return@onPlayerThread
            cancelCurrent()
            if (!needsPlayerRebuild) runCatching { player.clearMediaItems() }.onFailure { discardFailedPlayer() }
        }
    }
    fun release() {
        if (released) return
        released = true
        generation.advance()
        onPlayerThread {
            cancelCurrent()
            scope.cancel()
            if (!needsPlayerRebuild) runCatching { player.release() }
        }
    }
    fun seekBy(ms: Long) {
        val token = generation.current()
        onPlayerThread {
            if (!released && generation.isCurrent(token) && activeToken == token) {
                player.seekTo(player.currentPosition + ms)
            }
        }
    }
    fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed > 0f)
        onPlayerThread {
            if (released) return@onPlayerThread
            baseSpeed = speed
            _playbackState.update { it.copy(nextSpeed = speed) }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse, snapshot: VoicePlaybackSnapshot? = null): Unit = withContext(Dispatchers.Main.immediate) {
        check(!released) { "Audio player released" }
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData
        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        playSource(mediaSource, snapshot ?: VoicePlaybackSnapshot.capture(baseSpeed), response.duration?.times(1000)?.toLong())
    }

    /** File-backed voice notes share call/readout effects and the same safe rate-change boundary. */
    suspend fun playFile(file: File, snapshot: VoicePlaybackSnapshot? = null, onStarted: () -> Unit = {}): Unit = withContext(Dispatchers.Main.immediate) {
        check(!released) { "Audio player released" }
        require(file.isFile) { "Local audio file is missing" }
        val source = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(appContext))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        playSource(source, snapshot ?: VoicePlaybackSnapshot.capture(baseSpeed), onStarted = onStarted)
    }

    private suspend fun playSource(mediaSource: MediaSource, snapshot: VoicePlaybackSnapshot, durationMs: Long? = null, onStarted: () -> Unit = {}) {
        currentCoroutineContext().ensureActive()
        val token = generation.advance()
        cancelCurrent()
        // Cancelling the previous waiter may synchronously enter its finally/stop callback.
        // That callback must be able to invalidate this setup before it owns a new item.
        generation.requireCurrent(token)
        currentCoroutineContext().ensureActive()
        check(!released) { "Audio player released" }
        playbackSnapshot = snapshot
        var notifiedStarted = false
        suspendCancellableCoroutine<Unit> { cont ->
            activeToken = token
            activeContinuation = cont
            fun ownsPlayback() = !released && generation.isCurrent(token) && activeToken == token
            fun detach() {
                if (activeToken != token) return
                activeListener?.let { runCatching { player.removeListener(it) } }
                activeListener = null
                activeContinuation = null
                activeToken = null
                explicitlyPaused = false
                stopLivenessMonitor()
                stopPositionUpdates()
            }
            fun failPlayback(failure: Exception) {
                if (!ownsPlayback()) return
                detach()
                // Invalidate the old sink, but create/play nothing here. Only a later owned
                // playSource call may build a replacement; the failed item is never replayed.
                discardFailedPlayer()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = failure.message) }
                if (cont.isActive) cont.resumeWithException(failure)
            }
            fun cancelOwnedPlayback() {
                if (!ownsPlayback()) return
                generation.advance()
                detach()
                if (!needsPlayerRebuild) runCatching { player.stop() }.onFailure { discardFailedPlayer() }
            }
            _playbackState.update {
                it.copy(
                    status = PlaybackStatus.Buffering,
                    positionMs = 0L,
                    durationMs = durationMs ?: 0L,
                    speed = snapshot.baseSpeed,
                    errorMessage = null,
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
                            if (isPlaying) startPositionUpdates(token, ::failPlayback) else stopPositionUpdates()
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
                    failPlayback(error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!ownsPlayback()) return
                    if (isPlaying && !notifiedStarted) {
                        notifiedStarted = true
                        // UI bookkeeping may not take down the decoder or an active call.
                        runCatching(onStarted)
                        if (!ownsPlayback()) return
                    }
                    val status = when {
                        isPlaying -> PlaybackStatus.Playing
                        player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.Buffering
                        player.playbackState == Player.STATE_IDLE -> PlaybackStatus.Idle
                        player.playbackState == Player.STATE_ENDED -> PlaybackStatus.Ended
                        else -> PlaybackStatus.Paused
                    }
                    _playbackState.update { it.copy(status = status) }
                    if (isPlaying) startPositionUpdates(token, ::failPlayback) else stopPositionUpdates()
                }
            }
            activeListener = listener
            cont.invokeOnCancellation {
                onPlayerThread {
                    // Cancellation can arrive after another play() has taken ownership.
                    cancelOwnedPlayback()
                }
            }
            if (!cont.isActive) { detach(); return@suspendCancellableCoroutine }
            try {
                val prepared = runOwnedPlaybackSetup({ ownsPlayback() && cont.isActive },
                    {
                        if (needsPlayerRebuild) {
                            player = createPlayer()
                            needsPlayerRebuild = false
                        }
                    },
                    { player.stop() },
                    { player.clearMediaItems() },
                    { applyMix(atSegmentBoundary = true) },
                    { player.addListener(listener) },
                    { player.setMediaSource(mediaSource) },
                    { player.prepare() },
                    { player.play() },
                )
                if (!prepared || !cont.isActive) {
                    if (ownsPlayback()) cancelOwnedPlayback() else detach()
                    cont.cancel(CancellationException("Audio setup stopped or replaced"))
                    return@suspendCancellableCoroutine
                }
                val liveness = PlaybackLivenessPolicy()
                livenessJob = scope.launch {
                    while (ownsPlayback()) {
                        delay(250)
                        if (!ownsPlayback()) break
                        try {
                            val phase = when (player.playbackState) {
                                Player.STATE_IDLE -> PlaybackProgressPhase.IDLE
                                Player.STATE_BUFFERING -> PlaybackProgressPhase.BUFFERING
                                Player.STATE_ENDED -> PlaybackProgressPhase.ENDED
                                else -> PlaybackProgressPhase.READY
                            }
                            val stalled = liveness.observe(SystemClock.elapsedRealtime(), player.currentPosition,
                                phase, player.playWhenReady, explicitlyPaused,
                                player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                            if (stalled != null) failPlayback(PlaybackStalledException(stalled))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            failPlayback(failure)
                        }
                    }
                }
            } catch (failure: Exception) {
                failPlayback(failure)
            }
        }
    }

    private fun onPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post { block() }
    }

    private fun cancelCurrent() {
        activeListener?.let { runCatching { player.removeListener(it) } }
        activeListener = null
        val previous = activeContinuation
        activeContinuation = null
        activeToken = null
        explicitlyPaused = false
        stopLivenessMonitor()
        stopPositionUpdates()
        previous?.cancel(CancellationException("Audio playback stopped or replaced"))
    }

    private fun discardFailedPlayer() {
        if (needsPlayerRebuild) return
        needsPlayerRebuild = true
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.release() }
    }

    private fun stopLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob = null
    }

    private fun startPositionUpdates(token: Long, onFailure: (Exception) -> Unit) {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (!released && generation.isCurrent(token) && activeToken == token) {
                try {
                    _playbackState.update {
                        it.copy(
                            positionMs = player.currentPosition,
                            durationMs = if (player.duration > 0) player.duration else it.durationMs
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onFailure(failure)
                    break
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
