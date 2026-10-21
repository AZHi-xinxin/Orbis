package me.rerere.rikkahub.data.orbis.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CallAcousticRulesTest {
    private val enabled = CallAcousticConfig(enabled = true, pitch = true)
    @Test fun defaultOffAndSuppressionNeverProduceEvents() {
        val rules = CallAcousticRules("call")
        for(t in 0L..20_000 step 100) {
            assertTrue(rules.accept(t, AcousticSample(0.0, false), CallAcousticConfig(), true).isEmpty())
            assertTrue(rules.accept(t, AcousticSample(0.0, false), enabled, false).isEmpty())
        }
    }
    @Test fun silenceOncePerContinuousWindowAndNoGapAsSilence() {
        val rules = CallAcousticRules("call")
        val events = (0L..20_000 step 100).flatMap { rules.accept(it, AcousticSample(0.0, false), enabled, true) }
        assertEquals(1, events.size); assertEquals("silence", events.single().kind)
        assertTrue(rules.accept(40_000, AcousticSample(0.0, false), enabled, true).isEmpty())
        rules.resetWindow()
        assertTrue(rules.accept(90_000, AcousticSample(0.0, false), enabled, true).isEmpty())
    }
    @Test fun volumeAndPitchNeedBaselineAndSustainedSpeechThenReset() {
        val rules = CallAcousticRules("call")
        for(t in 0L..60_000 step 100) rules.accept(t, AcousticSample(.1, true, 180.0), enabled, true)
        val events = (60_100L..70_000 step 100).flatMap { rules.accept(it, AcousticSample(.04, true, 260.0), enabled, true) }
        assertEquals(setOf("low_volume", "high_pitch"), events.map { it.kind }.toSet())
        assertEquals(2, events.size)
        rules.resetBaseline()
        for(t in 70_100L..80_000 step 100) assertTrue(rules.accept(t, AcousticSample(.04, true, 260.0), enabled, true).isEmpty())
    }
    @Test fun noBaselineMeansNoGuessAndLongCallsRemainBounded() {
        val rules = CallAcousticRules("call")
        for(t in 0L..1_800_000 step 100) {
            val events = rules.accept(t, AcousticSample(.01, false), enabled, true)
            assertTrue(events.all { it.kind == "silence" })
            assertTrue(rules.retainedFrames <= 220)
        }
    }
    @Test fun badFramesAndClippingNeverCountAsQuiet() {
        val rules = CallAcousticRules("call")
        for(t in 0L..10_000 step 100) assertTrue(rules.accept(t, AcousticSample(1.0, false, clipped = true), enabled, true).isEmpty())
        assertTrue(runCatching { CallPcmAnalysis.analyze(byteArrayOf(1), 16000, null, true) }.isFailure)
        assertTrue(runCatching { CallPcmAnalysis.analyze(ByteArray(100), 0, null, true) }.isFailure)
        assertFalse(CallPcmAnalysis.analyze(ByteArray(1600), 16000, false, true).voiced)
    }
    @Test fun pcmEnergyAndPitchAreFiniteWithoutKeepingAudio() {
        val pcm = ByteArray(1600)
        repeat(800) { i ->
            val value = (sin(2 * PI * 200 * i / 16000) * 12000).toInt()
            pcm[i*2] = value.toByte(); pcm[i*2+1] = (value shr 8).toByte()
        }
        val sample = CallPcmAnalysis.analyze(pcm, 16000, true, true)
        assertTrue(sample.voiced); assertTrue(sample.rms in .24.. .28)
        assertNotNull(sample.pitch); assertTrue(sample.pitch!! in 65.0..400.0)
    }
}
