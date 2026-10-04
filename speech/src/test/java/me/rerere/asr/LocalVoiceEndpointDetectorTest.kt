package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceEndpointDetectorTest {
    @Test fun silenceAndLowNoiseNeverStartOrUpload() {
        val detector = LocalVoiceEndpointDetector(16_000)
        repeat(30_000) { assertTrue(detector.accept(pcmFrame(20)).isEmpty()) }
        assertTrue(detector.finish().isEmpty())
    }

    @Test fun isolatedClicksAreNotUtterances() {
        val detector = LocalVoiceEndpointDetector(16_000)
        repeat(30) {
            assertTrue(detector.accept(pcmFrame(2_000)).isEmpty())
            assertTrue(detector.accept(pcmFrame(0)).isEmpty())
        }
        assertTrue(detector.finish().isEmpty())
    }

    @Test fun shortWordKeepsPrerollAndWaitsFullThreeSeconds() {
        val detector = LocalVoiceEndpointDetector(16_000)
        repeat(20) { detector.accept(pcmFrame(0)) }
        val events = mutableListOf<LocalVoiceEndpointDetector.Event>()
        repeat(3) { events += detector.accept(pcmFrame(1_500)) }
        assertEquals(listOf(LocalVoiceEndpointDetector.Event.Started), events)
        repeat(149) { assertTrue(detector.accept(pcmFrame(0)).isEmpty()) }
        val end = detector.accept(pcmFrame(0))
        assertTrue(end[0] is LocalVoiceEndpointDetector.Event.Segment)
        assertEquals(LocalVoiceEndpointDetector.Event.Ended, end[1])
        assertEquals((15 + 150) * 640, (end[0] as LocalVoiceEndpointDetector.Event.Segment).pcm.size)
        assertTrue(detector.accept(pcmFrame(1_500)).isEmpty())
    }

    @Test fun resumedSpeechResetsSilenceCountdown() {
        val detector = LocalVoiceEndpointDetector(16_000)
        repeat(10) { detector.accept(pcmFrame(1_500)) }
        repeat(120) { detector.accept(pcmFrame(0)) }
        repeat(3) { detector.accept(pcmFrame(1_500)) }
        repeat(149) { assertFalse(detector.accept(pcmFrame(0)).contains(LocalVoiceEndpointDetector.Event.Ended)) }
        assertTrue(detector.accept(pcmFrame(0)).contains(LocalVoiceEndpointDetector.Event.Ended))
    }

    @Test fun longSpeechSplitsBoundedAudioWithoutEndingTurn() {
        val detector = LocalVoiceEndpointDetector(16_000)
        val events = mutableListOf<LocalVoiceEndpointDetector.Event>()
        repeat(1_600) { events += detector.accept(pcmFrame(1_500)) }
        assertEquals(2, events.filterIsInstance<LocalVoiceEndpointDetector.Event.Segment>().size)
        assertFalse(events.contains(LocalVoiceEndpointDetector.Event.Ended))
        repeat(150) { events += detector.accept(pcmFrame(0)) }
        assertEquals(1, events.count { it == LocalVoiceEndpointDetector.Event.Ended })
        val segments = events.filterIsInstance<LocalVoiceEndpointDetector.Event.Segment>()
        assertTrue(segments.all { it.pcm.size <= 480_000 })
        assertEquals((1_600 + 150) * 640, segments.sumOf { it.pcm.size })
    }

    @Test fun oddByteReadsHaveIdenticalEndpointingAndPcm() {
        val raw = ByteArray(180 * 640)
        repeat(30) { pcmFrame(1_500).copyInto(raw, it * 640) }
        val whole = LocalVoiceEndpointDetector(16_000).accept(raw)
        val detector = LocalVoiceEndpointDetector(16_000)
        val split = mutableListOf<LocalVoiceEndpointDetector.Event>()
        var offset = 0
        while (offset < raw.size) {
            val end = minOf(offset + 333, raw.size)
            split += detector.accept(raw.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(whole.map { it::class }, split.map { it::class })
        assertArrayEquals((whole[1] as LocalVoiceEndpointDetector.Event.Segment).pcm,
            (split[1] as LocalVoiceEndpointDetector.Event.Segment).pcm)
    }

    @Test fun clearNeverFlushesPendingSpeech() {
        val detector = LocalVoiceEndpointDetector(16_000)
        repeat(10) { detector.accept(pcmFrame(1_500)) }
        detector.clear()
        assertTrue(detector.finish().isEmpty())
        assertTrue(detector.accept(pcmFrame(1_500)).isEmpty())
    }
}

internal fun pcmFrame(amplitude: Int, sampleRate: Int = 16_000): ByteArray =
    ByteArray(sampleRate / 50 * 2).also { bytes ->
        for (index in bytes.indices step 2) {
            val sample = if (index % 4 == 0) amplitude else -amplitude
            bytes[index] = sample.toByte(); bytes[index + 1] = (sample shr 8).toByte()
        }
    }
