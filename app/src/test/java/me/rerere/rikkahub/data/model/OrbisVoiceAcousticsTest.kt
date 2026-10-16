package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.estimateActiveMessageBodies

class OrbisVoiceAcousticsTest {
    private fun tone(rate: Int, seconds: Double, hz: Double): ByteArray = ByteArray((rate*seconds).toInt()*2).also { out ->
        for (i in 0 until out.size/2) { val s=(sin(2*PI*i*hz/rate)*16384).toInt();out[i*2]=s.toByte();out[i*2+1]=(s shr 8).toByte() }
    }
    @Test fun silenceHasNoPitch() {
        val result=measureOrbisVoiceAcoustics(ByteArray(32000),16000)
        assertEquals(1000L,result.durationMs);assertEquals(1,result.lowEnergyPauseCount)
        assertEquals(1000L,result.lowEnergyPauseMs);assertTrue(result.pitchHz.all { it==null })
        assertEquals(-100.0,result.rmsDbfs,0.01)
    }
    @Test fun knownSineMeasuresAmplitudeAndPitch() {
        for(rate in listOf(8000,16000,24000,44100,48000)) {
            val result=measureOrbisVoiceAcoustics(tone(rate,1.0,200.0),rate)
            assertEquals(-9.03,result.rmsDbfs,.15);assertEquals(-6.02,result.peakDbfs,.15)
            assertEquals(0,result.lowEnergyPauseCount)
            assertTrue(result.pitchHz.filterNotNull().size>=3)
            result.pitchHz.filterNotNull().forEach { assertEquals(200.0,it,12.0) }
        }
    }
    @Test fun pausesHonorLengthAndTrailingSilence() {
        val result=measureOrbisVoiceAcoustics(ByteArray(12800)+tone(16000,1.0,200.0)+ByteArray(12800),16000)
        assertEquals(2,result.lowEnergyPauseCount);assertEquals(800L,result.lowEnergyPauseMs)
    }
    @Test fun sharingIsExplicitAndProjectionNeverSendsAudioUrl() {
        val audio=UIMessagePart.Audio("file:///private.wav",orbisVoiceNoteMetadata("你好"))
        val initial=UIMessage.user("").copy(parts=listOf(audio))
        assertFalse(initial.withVoiceNoteTranscripts().toText().contains("声学"))
        val shared=initial.copy(parts=listOf(audio.withSharedAcoustics(measureOrbisVoiceAcoustics(tone(16000,1.0,200.0),16000))))
        val projected=shared.withVoiceNoteTranscripts().toText()
        assertTrue(projected.contains("本机声学测量"));assertFalse(projected.contains("file:///"))
    }
    @Test fun bodyReferenceDoesNotSwitchToUsageAnchor() {
        val one=UIMessage.user("a".repeat(5000));val two=UIMessage.assistant("b".repeat(5000))
        assertTrue(estimateActiveMessageBodies(listOf(one)).tokens<estimateActiveMessageBodies(listOf(one,two)).tokens)
        assertEquals(estimateActiveMessageBodies(listOf(two)).tokens,
            estimateActiveMessageBodies(listOf(two.copy(usage=me.rerere.ai.core.TokenUsage(promptTokens=999999,completionTokens=99)))).tokens)
    }
}
