package me.rerere.tts.controller

import android.media.AudioAttributes
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Emulator-only, zero-gain synthetic PCM. No microphone, focus request, real TTS or user files. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlayerSegmentDeviceTest {
    private fun requireFixture() {
        assumeTrue("Synthetic playback must not run on a person's phone",
            Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
    }

    private fun pcm(seconds: Int) = TTSResponse(ByteArray(16_000 * 2 * seconds),
        AudioFormat.PCM, sampleRate = 16_000)

    // Inspect/control the actual Media3 engine only in the isolated regression fixture, without
    // adding a production API that could bypass AudioPlayer's generation and cleanup ownership.
    private fun engine(player: AudioPlayer): ExoPlayer = AudioPlayer::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(player) as ExoPlayer

    @Test fun pcmEffectsRemainFrozenAcrossBuffersUntilTheNextOwnedSegmentFlush() {
        requireFixture()
        var pending = VoiceMixerConfig(enabled = true)
        val processor = VoiceMixAudioProcessor { pending }
        processor.configure(AudioProcessor.AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        fun render(): ByteArray {
            val input = ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN)
            repeat(64) { input.putShort(1_000.toShort()) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            return ByteArray(output.remaining()).also { output.get(it) }
        }
        try {
            val original = render()
            pending = VoiceMixerConfig(true, VoiceMix(space = VoiceSpace.HALL, distance = .8f, reverb = .9f, filter = VoiceFilter.TELEPHONE))
            assertArrayEquals(original, render())
            processor.flush()
            assertFalse(original.contentEquals(render()))
        } finally { processor.reset() }
    }

    @Test fun communicationAudioFreezesEverySegmentInARoundAndUsesNewSettingsNextRound() = runBlocking {
        requireFixture()
        val previous = VoiceMixer.config.value
        VoiceMixer.apply(VoiceMixerConfig(enabled = true))
        val player = withContext(Dispatchers.Main) {
            AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext,
                AudioAttributes.USAGE_VOICE_COMMUNICATION).also { it.setOutputMuted(true) }
        }
        val snapshot = VoicePlaybackSnapshot.capture()
        val first = async { player.play(pcm(3), snapshot) }
        try {
            withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            repeat(60) { tick ->
                VoiceMixer.apply(VoiceMixerConfig(true, VoiceMix(.5f + tick / 40f, -12f + tick / 3f)))
                delay(5)
            }
            val latest = VoiceMixerConfig(true, VoiceMix(1.5f, -12f))
            VoiceMixer.apply(latest)
            withContext(Dispatchers.Main) {
                assertEquals(1f, engine(player).playbackParameters.speed, 0f)
                assertEquals(1f, engine(player).playbackParameters.pitch, 0f)
            }
            withTimeout(10_000) { first.await() }
            val second = async { player.play(pcm(1), snapshot) }
            try {
                withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
                withContext(Dispatchers.Main) {
                    assertEquals(1f, engine(player).playbackParameters.speed, 0f)
                    assertEquals(1f, engine(player).playbackParameters.pitch, 0f)
                }
                withTimeout(10_000) { second.await() }
                assertEquals(PlaybackStatus.Ended, player.playbackState.value.status)
            } finally { second.cancelAndJoin() }
            val nextRound = async { player.play(pcm(1)) }
            try {
                withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
                withContext(Dispatchers.Main) {
                    assertEquals(1.5f, engine(player).playbackParameters.speed, 0f)
                    assertEquals(.5f, engine(player).playbackParameters.pitch, 0f)
                }
                withTimeout(10_000) { nextRound.await() }
            } finally { nextRound.cancelAndJoin() }
        } finally {
            first.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            VoiceMixer.apply(previous)
        }
    }

    @Test fun unexpectedEngineIdleUnblocksTheWaiterWithoutReplayAndNextItemGetsANewSink() = runBlocking {
        requireFixture()
        val previous = VoiceMixer.config.value
        VoiceMixer.apply(VoiceMixerConfig())
        val player = withContext(Dispatchers.Main) {
            AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext,
                AudioAttributes.USAGE_VOICE_COMMUNICATION).also { it.setOutputMuted(true) }
        }
        // Capture the exception inside the child so it cannot cancel the enclosing test before
        // assertions about cleanup and a later, explicitly requested item are made.
        val failed = async { runCatching { player.play(pcm(10)) }.exceptionOrNull() }
        try {
            withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            val old = withContext(Dispatchers.Main) { engine(player).also { it.stop() } }
            val error = withTimeout(6_000) { failed.await() }
            assertTrue(error is PlaybackStalledException)
            assertEquals(PlaybackStall.UNEXPECTED_IDLE, (error as PlaybackStalledException).stall)
            assertEquals(PlaybackStatus.Error, player.playbackState.value.status)
            delay(500)
            assertEquals(PlaybackStatus.Error, player.playbackState.value.status)
            withContext(Dispatchers.Main) { assertSame(old, engine(player)) }
            val next = async { player.play(pcm(1)) }
            try {
                withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
                withContext(Dispatchers.Main) { assertNotSame(old, engine(player)) }
                withTimeout(10_000) { next.await() }
                assertEquals(PlaybackStatus.Ended, player.playbackState.value.status)
            } finally { next.cancelAndJoin() }
        } finally {
            failed.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            VoiceMixer.apply(previous)
        }
    }

    @Test fun explicitStopInvalidatesPendingIdleRecoveryAndResumeCannotReplayIt() = runBlocking {
        requireFixture()
        val previous = VoiceMixer.config.value
        VoiceMixer.apply(VoiceMixerConfig())
        val player = withContext(Dispatchers.Main) {
            AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext,
                AudioAttributes.USAGE_VOICE_COMMUNICATION).also { it.setOutputMuted(true) }
        }
        val cancelled = async { runCatching { player.play(pcm(10)) }.exceptionOrNull() }
        try {
            withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            val old = withContext(Dispatchers.Main) {
                engine(player).also { it.stop(); player.stop() }
            }
            assertTrue(withTimeout(5_000) { cancelled.await() } is CancellationException)
            VoiceMixer.apply(VoiceMixerConfig(true, VoiceMix(1.5f, 12f)))
            player.resume()
            delay(2_750)
            assertEquals(PlaybackStatus.Idle, player.playbackState.value.status)
            withContext(Dispatchers.Main) {
                assertSame(old, engine(player))
                assertFalse(engine(player).isPlaying)
            }
        } finally {
            cancelled.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            VoiceMixer.apply(previous)
        }
    }

    @Test fun replacingAnItemDoesNotLetItsCancellationStopTheNewOwner() = runBlocking {
        requireFixture()
        val previous = VoiceMixer.config.value
        VoiceMixer.apply(VoiceMixerConfig())
        val player = withContext(Dispatchers.Main) {
            AudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext,
                AudioAttributes.USAGE_VOICE_COMMUNICATION).also { it.setOutputMuted(true) }
        }
        val old = async { runCatching { player.play(pcm(10)) }.exceptionOrNull() }
        try {
            withTimeout(10_000) { player.playbackState.first { it.status == PlaybackStatus.Playing } }
            val replacement = async { player.play(pcm(1)) }
            try {
                assertTrue(withTimeout(5_000) { old.await() } is CancellationException)
                old.cancel()
                withTimeout(10_000) { replacement.await() }
                assertEquals(PlaybackStatus.Ended, player.playbackState.value.status)
            } finally { replacement.cancelAndJoin() }
        } finally {
            old.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            VoiceMixer.apply(previous)
        }
    }
}
