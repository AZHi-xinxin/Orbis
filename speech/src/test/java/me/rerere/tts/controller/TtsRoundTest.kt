package me.rerere.tts.controller

import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class TtsRoundTest {
    private fun plan(text: String, provider: TTSProviderSetting = TTSProviderSetting.OpenAI(),
        playback: VoicePlaybackSnapshot = VoicePlaybackSnapshot.capture(config = VoiceMixerConfig()), limit: Int = 4_000) =
        planTtsRound(text, provider, playback, limit)

    @Test fun completeCloudUtteranceIsOneRequestIncludingParagraphs() {
        val text = ("这是完整的一句话，不应该每160字切开。\n\n".repeat(200)) + "最后一段。"
        val chunks = plan(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single().chunk.text)
    }

    @Test fun streamProviderPacketSizeDoesNotBecomeTextSegments() {
        val text = "一段保留给提供商整体合成的长文本。".repeat(100)
        assertEquals(1, plan(text, TTSProviderSetting.FishAudio()).size)
        assertEquals(1, plan(text, TTSProviderSetting.Volcengine()).size)
    }

    @Test fun systemLimitIsRespectedWithoutLosingWhitespaceOrCharacters() {
        val text = "ab cd。\n\n中文😀".repeat(10)
        val chunks = plan(text, TTSProviderSetting.SystemTTS(), limit = 10)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("") { it.chunk.text })
        assertTrue(chunks.all { it.chunk.text.length <= 10 })
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    @Test fun indivisibleSentenceStillRespectsProviderLimitAndSurrogatePairs() {
        val text = "x😀".repeat(90)
        val chunks = plan(text, TTSProviderSetting.SystemTTS(), limit = 8)
        assertEquals(text, chunks.joinToString("") { it.chunk.text })
        assertTrue(chunks.all { it.chunk.text.length <= 8 && !it.chunk.text.last().isHighSurrogate() && !it.chunk.text.first().isLowSurrogate() })
    }

    @Test fun elevenV4UsesTheAdaptersEstablishedTwoThousandCharacterLimit() {
        val text = "中文".repeat(2_501)
        val chunks = plan(text, TTSProviderSetting.ElevenLabs(model = "eleven_v4"))
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.chunk.text.length <= 2_000 })
        assertEquals(text, chunks.joinToString("") { it.chunk.text })
    }

    @Test fun allSegmentsOfOneRoundShareOneProviderAndPlaybackSnapshot() {
        val snapshot = VoicePlaybackSnapshot.capture(1.2f, VoiceMixerConfig(true,
            VoiceMix(.9f, -3f, .7f, VoiceSpace.HALL, .6f, .8f, VoiceFilter.ROBOT)))
        val chunks = plan("长文本".repeat(100), TTSProviderSetting.SystemTTS(), snapshot, 40)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.round === chunks.first().round })
        assertSame(snapshot, chunks.last().round.playback)
        assertEquals(chunks.size, chunks.map { it.id }.distinct().size)
    }

    @Test fun laterGlobalEditsAndBypassDoNotChangeAnAlreadyCapturedRound() {
        val previous = VoiceMixer.config.value
        try {
            val initial = VoiceMixerConfig(true, VoiceMix(.9f, -3f, .7f, VoiceSpace.FOREST, .4f, .8f, VoiceFilter.SOFT))
            VoiceMixer.apply(initial)
            val snapshot = VoicePlaybackSnapshot.capture(1.3f)
            val round = plan("本轮", playback = snapshot).single().round
            VoiceMixer.apply(VoiceMixerConfig(false, VoiceMix(2f, 12f, 0f, VoiceSpace.TUNNEL, 1f, 1f, VoiceFilter.ROBOT)))
            assertEquals(initial, round.playback.config)
            assertEquals(1.3f, round.playback.baseSpeed, 0f)
            val next = VoicePlaybackSnapshot.capture(.8f)
            assertFalse(next.config.enabled)
            assertNotEquals(snapshot, next)
        } finally { VoiceMixer.apply(previous) }
    }

    @Test fun queuedRoundsDoNotShareMutableProviderIdentityOrLaterVoiceSelection() {
        val provider = TTSProviderSetting.OpenAI(name = "first", voice = "first-voice")
        val first = plan("第一轮", provider).single().round
        provider.name = "edited"
        val second = plan("第二轮", provider.copy(voice = "second-voice")).single().round
        assertEquals("first", first.provider.name)
        assertEquals("first-voice", (first.provider as TTSProviderSetting.OpenAI).voice)
        assertEquals("second-voice", (second.provider as TTSProviderSetting.OpenAI).voice)
    }

    @Test fun emptyInputDoesNotAllocateASynthesisRound() {
        assertTrue(plan(" \n\t ").isEmpty())
    }

    @Test fun presetsAreNotRetainedInPlaybackAndInvalidSpeedsFailBeforeSynthesis() {
        val config = VoiceMixerConfig(presets = listOf(VoicePreset("fixture", VoiceMix())))
        assertTrue(VoicePlaybackSnapshot.capture(config = config).config.presets.isEmpty())
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertTrue(runCatching { VoicePlaybackSnapshot.capture(it) }.isFailure)
        }
    }
}
