package me.rerere.tts.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.common.http.SseEvent
import org.junit.Assert.*
import org.junit.Test

class TtsStreamingFramesTest {
    private fun event(data: String) = SseEvent.Event(null, null, data)
    private val part = """{"data":{"audio":"0102","status":1},"base_resp":{"status_code":0}}"""
    private val done = """{"data":{"audio":"","status":2},"base_resp":{"status_code":0}}"""

    @Test fun `minimax requires provider completion not just a clean socket close`(): Unit = runBlocking {
        val chunks = miniMaxAudioChunks(flowOf(event(part), event(done), SseEvent.Closed), "model", "voice").toList()
        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2), chunks[0].data)
        assertFalse(chunks[0].isLast)
        assertTrue(chunks[1].isLast)
        assertTrue(chunks[1].data.isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking {
            miniMaxAudioChunks(flowOf(event(part), SseEvent.Closed), "model", "voice").toList()
        } }
    }

    @Test fun `minimax propagates downstream cancellation and storage failure`() {
        for (failure in listOf(CancellationException("cancel"), IllegalStateException("collector failure"))) {
            try {
                runBlocking { miniMaxAudioChunks(flowOf(event(part), event(done)), "model", "voice").collect { throw failure } }
                fail("Expected downstream failure")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
    }

    @Test fun `minimax rejects malformed hex and provider errors without reflecting payload`() {
        for (payload in listOf("private malformed payload", """{"data":{"audio":"0","status":1}}""",
            """{"data":{"audio":"zz","status":1}}""", """{"base_resp":{"status_code":1004,"status_msg":"private"}}""")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { parseMiniMaxAudioFrame(payload, "model", "voice") }
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test fun `qwen empty audio final frame is preserved`() {
        val part = parseQwenAudioFrame("""{"output":{"audio":{"data":"AQI="},"finish_reason":null}}""", "pcm", 24000, "model", "voice")!!
        assertArrayEquals(byteArrayOf(1, 2), part.data)
        assertFalse(part.isLast)
        val final = parseQwenAudioFrame("""{"output":{"audio":{"data":""},"finish_reason":"stop"}}""", "pcm", 24000, "model", "voice")!!
        assertTrue(final.data.isEmpty())
        assertTrue(final.isLast)
        assertEquals(24000, final.sampleRate)
    }

    @Test fun `qwen sentence metadata is skipped but final with omitted audio remains meaningful`() {
        assertNull(parseQwenAudioFrame("""{"output":{"type":"sentence-begin","finish_reason":"null"}}""", "mp3", 24000, "model", "voice"))
        assertTrue(parseQwenAudioFrame("""{"output":{"finish_reason":"stop"}}""", "mp3", 24000, "model", "voice")!!.isLast)
    }

    @Test fun `qwen malformed and provider errors fail rather than silently lose audio`() {
        for (payload in listOf("private malformed payload", """{"code":"InvalidApiKey","message":"private"}""",
            """{"output":{"audio":{"data":"!bad!"}}}""", """{"output":{"finish_reason":"length"}}""")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { parseQwenAudioFrame(payload, "mp3", 24000, "model", "voice") }
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }
}
