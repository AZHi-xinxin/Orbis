package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMixerPreviewTest {
    private val audio = TTSResponse(byteArrayOf(1, 2), AudioFormat.MP3)

    @Test fun `each explicit preview requests new synthesis then plays through its own output`() = runTest {
        val events = mutableListOf<String>()
        val preview = VoiceMixerPreview(this, { events += "tts:$it"; audio },
            { assertEquals(audio, it); events += "play" }, {}, { true })
        assertTrue(preview.start("  hello  "))
        assertEquals(VoicePreviewStage.REQUESTING, preview.state.value.stage)
        advanceUntilIdle()
        assertEquals(listOf("tts:hello", "play"), events)
        assertTrue(preview.start("second")); advanceUntilIdle()
        assertEquals(listOf("tts:hello", "play", "tts:second", "play"), events)
        assertEquals(VoicePreviewStage.IDLE, preview.state.value.stage)
    }

    @Test fun `duplicate start does not issue another paid request`() = runTest {
        val response = CompletableDeferred<TTSResponse>()
        var requests = 0
        val preview = VoiceMixerPreview(this, { requests++; response.await() }, {}, {}, { true })
        assertTrue(preview.start("one")); runCurrent()
        assertFalse(preview.start("two")); assertEquals(1, requests)
        response.complete(audio); advanceUntilIdle()
    }

    @Test fun `stopping request invalidates late results even from noncooperative synthesis`() = runTest {
        val response = CompletableDeferred<TTSResponse>()
        var plays = 0
        var stopped = 0
        val preview = VoiceMixerPreview(this, { withContext(NonCancellable) { response.await() } },
            { plays++ }, { stopped++ }, { true })
        preview.start("one"); runCurrent(); preview.stop()
        response.complete(audio); advanceUntilIdle()
        assertEquals(0, plays); assertEquals(1, stopped)
        assertEquals(VoicePreviewStage.IDLE, preview.state.value.stage)
    }

    @Test fun `new call prevents request and later playback without touching call`() = runTest {
        var allowed = false
        var requests = 0
        var plays = 0
        val response = CompletableDeferred<TTSResponse>()
        val preview = VoiceMixerPreview(this, { requests++; response.await() }, { plays++ }, {}, { allowed })
        assertFalse(preview.start("blocked")); assertEquals(0, requests)
        allowed = true; preview.start("pending"); runCurrent()
        allowed = false; response.complete(audio); advanceUntilIdle()
        assertEquals(1, requests); assertEquals(0, plays)
        assertEquals(VoicePreviewStage.IDLE, preview.state.value.stage)
    }

    @Test fun `failure is bounded sanitized and never retried`() = runTest {
        var requests = 0
        val preview = VoiceMixerPreview(this, { requests++; error("Bearer private-fixture") },
            { fail("must not play") }, {}, { true })
        preview.start("hello"); advanceUntilIdle()
        assertEquals(1, requests)
        assertEquals(VoicePreviewStage.FAILED, preview.state.value.stage)
        assertFalse(preview.state.value.notice.orEmpty().contains("private-fixture"))
    }

    @Test fun `timeout permits explicit retry but never starts one automatically`() = runTest {
        var requests = 0
        val preview = VoiceMixerPreview(this, { requests++; awaitCancellation() }, {}, {}, { true }, 1000)
        preview.start("hello"); advanceUntilIdle()
        assertEquals(1, requests); assertTrue(preview.state.value.notice.orEmpty().contains("超时"))
        assertTrue(preview.start("retry")); advanceUntilIdle(); assertEquals(2, requests)
    }

    @Test fun `closing cancels only owned playback and prevents another request`() = runTest {
        var stopped = 0
        val preview = VoiceMixerPreview(this, { audio }, { awaitCancellation() }, { stopped++ }, { true })
        preview.start("hello"); runCurrent()
        assertEquals(VoicePreviewStage.PLAYING, preview.state.value.stage)
        preview.close(); advanceUntilIdle()
        assertEquals(1, stopped); assertFalse(preview.start("late"))
    }

    @Test fun `old completion cannot clear newer request state`() = runTest {
        val first = CompletableDeferred<TTSResponse>()
        val second = CompletableDeferred<TTSResponse>()
        var requests = 0
        val preview = VoiceMixerPreview(this, {
            if (++requests == 1) withContext(NonCancellable) { first.await() } else second.await()
        }, {}, {}, { true })
        preview.start("first"); runCurrent(); preview.stop()
        preview.start("second"); runCurrent(); first.complete(audio); runCurrent()
        assertEquals(VoicePreviewStage.REQUESTING, preview.state.value.stage)
        second.complete(audio); advanceUntilIdle()
        assertEquals(VoicePreviewStage.IDLE, preview.state.value.stage)
    }

    @Test fun `empty input never sends requests`() = runTest {
        val preview = VoiceMixerPreview(this, { error("must not request") }, {}, {}, { true })
        assertFalse(preview.start(" "))
    }

    @Test fun `preview text is not silently truncated or rejected by an arbitrary local character cap`() = runTest {
        val text = "字".repeat(2001)
        var received: String? = null
        val preview = VoiceMixerPreview(this, { received = it; audio }, {}, {}, { true })
        assertTrue(preview.start(text)); advanceUntilIdle()
        assertEquals(text, received)
    }

    @Test fun `empty audio never plays`() = runTest {
        val preview = VoiceMixerPreview(this, { audio.copy(audioData = byteArrayOf()) },
            { fail("must not play") }, {}, { true })
        preview.start("hello"); advanceUntilIdle()
        assertEquals(VoicePreviewStage.FAILED, preview.state.value.stage)
    }
}
