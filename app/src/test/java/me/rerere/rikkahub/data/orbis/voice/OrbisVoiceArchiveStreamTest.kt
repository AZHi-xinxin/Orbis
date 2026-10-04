package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceArchiveStreamTest {
    private val reply = """{"summary":"约好散步。","transcript":"用户：明天去公园。"}"""
    private fun text(value: String) = StreamChunk.TextDelta("text", value)
    private fun finish(reason: String = "stop") = StreamChunk.Finish(reason)

    @Test fun `keys shells whitespace thinking and escaped blanks are not meaningful content`() {
        listOf("", " ", "{", "{\"summary\":", "{\"summary\":\"", "{\"summary\":\" ",
            "{\"summary\":\"\\n\\r\\t\\u0020", "{\"summary\":\"\",\"transcript\":\"",
            "```json\n{\"summary\":\"", "{\"nested\":{\"summary\":\"fake",
            "{\"summary\":{\"summary\":\"fake").forEach { raw ->
            assertFalse(VoiceArchiveContentProbe().apply { append(raw) }.meaningful)
        }
    }

    @Test fun `actual value survives chunk boundaries escaped field names fences and surrogate pairs`() {
        listOf("{\"summary\":\"真", "{\"transcript\":\"记录", "{\"summ\\u0061ry\":\"真",
            "```json\n{\"summary\":\"真", "{\"summary\":\"\\u4e2d", "{\"summary\":\"🙂",
            "{\"summary\":\"\\ud83d\\ude42").forEach { raw ->
            val probe = VoiceArchiveContentProbe()
            raw.forEach { probe.append(it.toString()) }
            assertTrue(probe.meaningful)
        }
    }

    @Test fun `abnormal attempt with shell and thinking only expires at ten seconds`() = runTest {
        var upstreamCancelled = false
        val result = runCatching {
            collectVoiceArchiveStream(VOICE_ARCHIVE_FIRST_CONTENT_MS) { flow {
                try {
                    emit(StreamChunk.ReasoningDelta("reason", "thinking"))
                    emit(text("{\"summary\":\" "))
                    delay(10_001)
                    emit(text("late\",\"transcript\":\"late\"}")); emit(finish())
                } finally { upstreamCancelled = true }
            } }
        }
        assertEquals("archive_first_reply_timeout", (result.exceptionOrNull() as VoiceArchiveFailure).safeCode)
        assertEquals(10_000L, testScheduler.currentTime)
        assertTrue(upstreamCancelled)
    }

    @Test fun `first useful content before deadline permits slow remainder without early fallback`() = runTest {
        val result = collectVoiceArchiveStream(10_000) { flow {
            delay(9_999); emit(text("{\"summary\":\"约"))
            delay(20_000); emit(text("好散步。\",\"transcript\":\"用户：明天去公园。\"}")); emit(finish())
        } }
        assertEquals("约好散步。", result.summary)
        assertEquals(29_999L, testScheduler.currentTime)
    }

    @Test fun `uncooperative factory must finish cancellation before fallback and cannot win with late valid data`() = runTest {
        var factoryReturned = false
        var lateTextDelivered = false
        val failure = runCatching {
            collectVoiceArchiveStream(10_000) {
                // Synthetic transport intentionally ignores cancellation during setup. Structured
                // cleanup waits for it; this is not a promise of a new dispatch at exactly 10s.
                withContext(NonCancellable) { delay(20_000) }
                factoryReturned = true
                flow {
                    emit(text(reply))
                    lateTextDelivered = true
                    emit(finish())
                }
            }
        }.exceptionOrNull()
        assertEquals("archive_first_reply_timeout", (failure as VoiceArchiveFailure).safeCode)
        assertTrue(factoryReturned)
        assertFalse(lateTextDelivered)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `normal attempt has no ten second first content deadline`() = runTest {
        val result = collectVoiceArchiveStream(null) { flow { delay(10_001); emit(text(reply)); emit(finish()) } }
        assertEquals("约好散步。", result.summary)
    }

    @Test fun `one attempt still times out after useful content if full result never finishes`() = runTest {
        val result = runCatching {
            collectVoiceArchiveStream(10_000) { flow { emit(text("{\"summary\":\"约")); delay(180_001) } }
        }
        assertEquals("archive_timeout", (result.exceptionOrNull() as VoiceArchiveFailure).safeCode)
        assertEquals(180_000L, testScheduler.currentTime)
    }

    @Test fun `complete JSON requires Finish and non truncated final status`() = runTest {
        for (chunks in listOf(listOf(text(reply)), listOf(text(reply), finish("length")),
            listOf(text(reply), finish("pause_turn")), listOf(text("{\"summary\":\"incomplete"), finish()))) {
            val result = runCatching { collectVoiceArchiveStream(null) { flowOf(*chunks.toTypedArray()) } }
            assertEquals("invalid_archive_response", (result.exceptionOrNull() as VoiceArchiveFailure).safeCode)
        }
    }

    @Test fun `only explicit provider success reasons can finish an archive`() = runTest {
        for (reason in listOf("stop", "STOP", "end_turn", "stop_sequence", "completed")) {
            val archive = collectVoiceArchiveStream(null) { flowOf(text(reply), finish(reason)) }
            assertEquals("约好散步。", archive.summary)
        }
    }

    @Test fun `complete looking JSON cannot turn missing unknown blocked or truncated status into success`() = runTest {
        for (reason in listOf(null, "", "error", "content_filter", "SAFETY", "RECITATION", "unknown",
            "length", "max_tokens", "MAX_TOKENS", "tool_calls", "function_call", "pause_turn", "tool_use",
            "incomplete", "incomplete:max_output_tokens", "incomplete:content_filter", "refusal", "cancelled")) {
            val error = runCatching { collectVoiceArchiveStream(null) {
                flowOf(text(reply), StreamChunk.Finish(reason))
            } }.exceptionOrNull()
            assertEquals("invalid_archive_response", (error as VoiceArchiveFailure).safeCode)
        }
    }

    @Test fun `upstream failure after Finish does not turn into success and cancellation stays cancellation`() = runTest {
        val error = IllegalStateException("synthetic transport failure")
        val failed = runCatching { collectVoiceArchiveStream(null) { flow {
            emit(text(reply)); emit(finish()); throw error
        } } }
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertEquals(error.message, failed.exceptionOrNull()?.message)
        val cancelled = CancellationException("synthetic cancellation")
        val cancellation = runCatching { collectVoiceArchiveStream(null) { flow { throw cancelled } } }
        assertTrue(cancellation.isFailure)
        assertTrue(cancellation.exceptionOrNull() is CancellationException)
        assertEquals(cancelled.message, cancellation.exceptionOrNull()?.message)
    }

    @Test fun `tool events and oversized response or summary are rejected before archive persistence`() = runTest {
        val tool = runCatching { collectVoiceArchiveStream(null) { flowOf(StreamChunk.ToolCallStart("tool", "not-executed")) } }
        assertEquals("invalid_archive_response", (tool.exceptionOrNull() as VoiceArchiveFailure).safeCode)
        val tooLarge = runCatching { collectVoiceArchiveStream(null) { flowOf(text("x".repeat(VOICE_ARCHIVE_RESPONSE_CHARS + 1))) } }
        assertEquals("archive_response_too_large", (tooLarge.exceptionOrNull() as VoiceArchiveFailure).safeCode)
        val summary = "文".repeat(22_000)
        val invalidArchive = "{\"summary\":\"$summary\",\"transcript\":\"complete source\"}"
        val result = runCatching { collectVoiceArchiveStream(null) { flowOf(text(invalidArchive), finish()) } }
        assertEquals("archive_response_too_large", (result.exceptionOrNull() as VoiceArchiveFailure).safeCode)
    }
}
