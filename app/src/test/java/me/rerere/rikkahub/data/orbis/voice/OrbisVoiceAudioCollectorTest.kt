package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.model.ORBIS_VOICE_NOTE_MAX_TEXT_CHARS
import me.rerere.rikkahub.data.model.pcm16MonoWav
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OrbisVoiceAudioCollectorTest {
    private fun chunk(data: ByteArray = byteArrayOf(1, 2), last: Boolean = false, rate: Int? = 24_000,
        format: AudioFormat = AudioFormat.PCM) = AudioChunk(data, format, rate, last)

    @Test fun commonTextLimitMatchesV4SingleRequestWithoutSilentTruncation() {
        assertEquals(2000, ORBIS_VOICE_NOTE_MAX_TEXT_CHARS)
    }

    @Test fun pcmArbitraryByteChunksAreJoinedBeforeSingleWavHeader() = runTest {
        val result = collectOrbisVoiceAudio(flowOf(chunk(byteArrayOf(1)), chunk(byteArrayOf(2, 3, 4), true)))
        assertEquals("wav", result.extension)
        assertArrayEquals(pcm16MonoWav(byteArrayOf(1, 2, 3, 4), 24_000), result.bytes)
        val view = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, result.bytes.size)
        assertEquals(24_000, view.getInt(24))
        assertEquals(48_000, view.getInt(28))
        assertEquals(1, view.getShort(22).toInt())
        assertEquals(16, view.getShort(34).toInt())
    }

    @Test fun terminalEmptyChunkCompletesPriorAudio() = runTest {
        val result = collectOrbisVoiceAudio(flowOf(chunk(), chunk(byteArrayOf(), true)))
        assertEquals(46, result.bytes.size)
    }

    @Test fun normalEofWithoutTerminalDoesNotPublishPartialAudio() = runTest {
        assertTrue(runCatching { collectOrbisVoiceAudio(flowOf(chunk())) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun upstreamErrorAfterAudioAndAfterTerminalStillFails() = runTest {
        for (terminal in listOf(false, true)) {
            val failure = IllegalStateException("synthetic provider failure")
            val result = runCatching { collectOrbisVoiceAudio(flow { emit(chunk(last = terminal)); throw failure }) }
            assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test fun cancellationIsNeverConvertedToSuccessfulAudio() = runTest {
        val cancelled = CancellationException("synthetic cancel")
        assertSame(cancelled, runCatching {
            collectOrbisVoiceAudio(flow { emit(chunk()); throw cancelled })
        }.exceptionOrNull())
    }

    @Test fun stalledFlowTimesOutWithoutReturningCollectedAudio() = runTest {
        assertTrue(runCatching {
            withTimeout(100) { collectOrbisVoiceAudio(flow { emit(chunk()); delay(200); emit(chunk(last = true)) }) }
        }.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test fun emptyAudioMissingRateAndOddPcmFailClosed() = runTest {
        listOf(chunk(byteArrayOf(), true), chunk(last = true, rate = null), chunk(byteArrayOf(1), true)).forEach {
            assertTrue(runCatching { collectOrbisVoiceAudio(flowOf(it)) }.isFailure)
        }
    }

    @Test fun changingFormatRateOrSendingAfterTerminalIsRejected() {
        listOf(chunk(format = AudioFormat.MP3), chunk(rate = 48_000), chunk(rate = -1)).forEach {
            val collector = OrbisVoiceAudioCollector()
            collector.append(chunk())
            assertThrows(IllegalArgumentException::class.java) { collector.append(it) }
        }
        val completed = OrbisVoiceAudioCollector().apply { append(chunk(last = true)) }
        assertThrows(IllegalArgumentException::class.java) { completed.append(chunk()) }
    }

    @Test fun byteLimitIncludesPcmWavHeaderAndRejectsOverflowBeforeWriting() {
        val collector = OrbisVoiceAudioCollector(46)
        collector.append(chunk())
        assertThrows(IllegalArgumentException::class.java) { collector.append(chunk()) }
        assertThrows(IllegalArgumentException::class.java) { collector.append(chunk(byteArrayOf(), true)) }
        assertThrows(IllegalArgumentException::class.java) { collector.finish() }
    }

    @Test fun declaredNonMonoOrNonPcm16EncodingIsRejected() {
        for (metadata in listOf(mapOf("channels" to "2"), mapOf("bitsPerSample" to "32"))) {
            assertThrows(IllegalArgumentException::class.java) {
                OrbisVoiceAudioCollector().append(chunk().copy(metadata = metadata))
            }
        }
    }

    @Test fun fragmentedSingleWavIsAcceptedButConcatenatedWholeWavsAreRejected() = runTest {
        val wav = pcm16MonoWav(byteArrayOf(1, 2), 24_000)
        val result = collectOrbisVoiceAudio(flowOf(chunk(wav.copyOfRange(0, 10), format = AudioFormat.WAV),
            chunk(wav.copyOfRange(10, wav.size), true, format = AudioFormat.WAV)))
        assertArrayEquals(wav, result.bytes)
        assertTrue(runCatching { collectOrbisVoiceAudio(flowOf(chunk(wav + wav, true, format = AudioFormat.WAV))) }.isFailure)
        assertTrue(runCatching { collectOrbisVoiceAudio(flowOf(chunk(wav.copyOf(wav.size - 1), true, format = AudioFormat.WAV))) }.isFailure)
    }

    @Test fun encodedSingleResultIsKeptExactlyWithoutWavWrapper() = runTest {
        val encoded = byteArrayOf(0x49, 0x44, 0x33, 1, 2)
        val result = collectOrbisVoiceAudio(flowOf(chunk(encoded, true, format = AudioFormat.MP3)))
        assertEquals("mp3", result.extension)
        assertArrayEquals(encoded, result.bytes)
    }
}
