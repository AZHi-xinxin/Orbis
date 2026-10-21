package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.data.model.*
import me.rerere.ai.ui.UIMessagePart

class OrbisSendAudioToolTest {
    @Test fun acceptsOnlyMatchingBoundedAudioSignatures() {
        val wav = pcm16MonoWav(ByteArray(16000), 8000)
        assertEquals("wav", existingAudioExtension("/workspace/test.WAV", wav))
        assertTrue(runCatching { existingAudioExtension("/workspace/test.mp3", wav) }.isFailure)
        assertTrue(runCatching { existingAudioExtension("/workspace/test.html", wav) }.isFailure)
        assertTrue(runCatching { existingAudioExtension("test.wav", ByteArray(2)) }.isFailure)
        assertTrue(runCatching { existingAudioExtension("test.mp3", ByteArray(32*1024*1024+1)) }.isFailure)
    }
    @Test fun localAudioUsesNativeBubbleAndSafeTextOnlyProjection() {
        val audio = UIMessagePart.Audio("file:///synthetic/upload/test.wav", orbisVoiceNoteMetadata("", durationMs = 1200))
        val tool = UIMessagePart.Tool("call", "orbis_send_audio", "{}", listOf(audio))
        assertEquals(listOf(audio), tool.successfulOrbisVoiceNotes())
        assertEquals(emptyList<UIMessagePart.Audio>(), tool.copy(output = listOf(audio, UIMessagePart.Text("failed"))).successfulOrbisVoiceNotes())
    }
}
