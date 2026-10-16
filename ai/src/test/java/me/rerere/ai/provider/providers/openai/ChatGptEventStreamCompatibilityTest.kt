package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.stream.SseEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test

class ChatGptEventStreamCompatibilityTest {
    private val created = "data: {\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\"}}\n\n"
    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"id\":\"synthetic\"}}\n\n"
    private fun response(text: String, type: String? = null, code: Int = 200,
                         url: String = "https://api.openai.com/v1/responses", isPost: Boolean = true): Response {
        val request = Request.Builder().url(url).apply { if (isPost) post("{}".toRequestBody()) }.build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(code).message("Synthetic")
            .apply { type?.let { header("Content-Type", it) } }
            .body(text.toResponseBody(type?.toMediaType())).build()
    }

    @Test fun missingTypeIsRepairedWithoutConsumingOrRewritingBytes() {
        val text = ": comment\n\nevent: response.created\n$created$completed"
        val original = response(text)
        val normalized = withChatGptEventStreamType(original)
        assertEquals("text/event-stream", normalized.body.contentType().toString())
        assertEquals("text/event-stream", normalized.header("Content-Type"))
        assertEquals(text, normalized.body.string())
    }

    @Test fun actualSseLibraryReceivesBothEventsAndRealTerminalMarker() {
        val decoder = ChatGptPlanDecoder()
        val events = mutableListOf<String>()
        var failure: Throwable? = null
        var closed = false
        EventSources.processResponse(withChatGptEventStreamType(response(created + completed)), object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                events += data
                decoder.accept(SseEvent(data = data, event = type))
            }
            override fun onClosed(eventSource: EventSource) { decoder.onClosed(); closed = true }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t }
        })
        assertNull(failure)
        assertTrue(closed)
        assertEquals(2, events.size)
    }

    @Test fun largeCreatedEnvelopeWithEchoedToolsReachesTheRealSseDecoder() {
        val tools = (1..40).joinToString(",") {
            """{"type":"function","name":"synthetic_$it","description":"${"description ".repeat(100)}","parameters":{"type":"object","properties":{}}}"""
        }
        val largeCreated = "data: " +
            """{"type":"response.created","response":{"status":"in_progress","instructions":"${"instruction ".repeat(2000)}","tools":[$tools]}}""" + "\n\n"
        assertTrue(largeCreated.toByteArray().size > 16_384)
        val decoder = ChatGptPlanDecoder()
        val events = mutableListOf<String>()
        var failure: Throwable? = null
        var closed = false
        val original = response(largeCreated + completed)
        val normalized = withChatGptEventStreamType(original)
        assertEquals("text/event-stream", normalized.header("Content-Type"))
        EventSources.processResponse(normalized, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                events += data
                decoder.accept(SseEvent(data = data, event = type))
            }
            override fun onClosed(eventSource: EventSource) { decoder.onClosed(); closed = true }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t }
        })
        assertNull(failure)
        assertTrue(closed)
        assertEquals(2, events.size)
        assertEquals(largeCreated.removePrefix("data: ").trimEnd(), events.first())
    }

    @Test fun legitimateDataAboveBothOldLimitsIsPreservedByteForByte() {
        for (size in listOf(8192, 16_384, 65_536)) {
            val firstEvent = "data: " +
                """{"response":{"instructions":"${"x".repeat(size)}","status":"in_progress"},"type":"response.created"}""" + "\n\n"
            val text = firstEvent + completed
            val normalized = withChatGptEventStreamType(response(text))
            assertEquals("text/event-stream", normalized.header("Content-Type"))
            assertEquals(text, normalized.body.string())
        }
    }

    @Test fun multilineDataAndUtf8WithCrLfAreValidatedAsOneCompleteEvent() {
        val text = "event: response.created\r\n" +
            "data: {\r\n" +
            "data:   \"response\": {\"instructions\": \"${"合成测试".repeat(5000)}\", \"status\": \"in_progress\"},\r\n" +
            "data:   \"type\": \"response.created\"\r\n" +
            "data: }\r\n\r\n" + completed
        val normalized = withChatGptEventStreamType(response(text))
        assertEquals("text/event-stream", normalized.header("Content-Type"))
        assertEquals(text, normalized.body.string())
    }

    @Test fun completeFirstEventHasAnExactFiniteWireByteBudget() {
        val cap = 1024 * 1024
        val prefix = "data: {\"type\":\"response.created\",\"response\":{\"instructions\":\""
        val suffix = "\",\"status\":\"in_progress\"}}\r\n\r\n"
        for (extraBytes in listOf(-1, 0, 1)) {
            val firstEvent = prefix + "x".repeat(cap - prefix.length - suffix.length + extraBytes) + suffix
            val text = firstEvent + completed
            val original = response(text)
            val normalized = withChatGptEventStreamType(original)
            if (extraBytes <= 0) assertEquals("text/event-stream", normalized.header("Content-Type"))
            else assertSame(original, normalized)
            assertEquals(text, normalized.body.string())
        }
    }

    @Test fun largeMalformedNestedTypeOrUnterminatedDataCannotClaimToBeAStream() {
        val padding = "x".repeat(20_000)
        listOf(
            "data: {\"response\":{\"type\":\"response.created\",\"instructions\":\"$padding\"}}\n\n",
            "data: {\"type\":\"unknown\",\"instructions\":\"$padding\"}\n\n",
            "data: {\"type\":\"response.created\",\"response\":{\"instructions\":\"$padding\"}\n\n",
            "data: {\"type\":\"response.created\",\"instructions\":\"$padding\"}\n",
            "data: {\"type\":\"response.created\",\"instructions\":\"$padding\"}",
            "<html>$padding</html>\n\n",
            "{\"type\":\"response.created\",\"instructions\":\"$padding\"}\n\n",
        ).forEach { text ->
            val original = response(text)
            assertSame(original, withChatGptEventStreamType(original))
            assertEquals(text, original.body.string())
        }
    }

    @Test fun largeFirstEventNeverSynthesizesTheMissingCompletedEvent() {
        val text = "data: " +
            """{"type":"response.created","response":{"status":"in_progress","instructions":"${"x".repeat(32_768)}"}}""" + "\n\n"
        val decoder = ChatGptPlanDecoder()
        var failure: Throwable? = null
        EventSources.processResponse(withChatGptEventStreamType(response(text)), object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                decoder.accept(SseEvent(data = data, event = type))
            }
            override fun onClosed(eventSource: EventSource) {
                failure = runCatching { decoder.onClosed() }.exceptionOrNull()
            }
        })
        assertNotNull(failure)
    }

    @Test fun absentTypeDoesNotInventCompletionForTruncatedStream() {
        val decoder = ChatGptPlanDecoder()
        var failure: Throwable? = null
        EventSources.processResponse(withChatGptEventStreamType(response(created)), object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                decoder.accept(SseEvent(data = data, event = type))
            }
            override fun onClosed(eventSource: EventSource) {
                failure = runCatching { decoder.onClosed() }.exceptionOrNull()
            }
        })
        assertNotNull(failure)
    }

    @Test fun htmlJsonDoneAndUntypedEventsNeverBecomeSuccessfulStreams() {
        listOf("<html>hello</html>\n", "{\"status\":\"completed\"}\n", "data: [DONE]\n\n",
            "data: {\"type\":123}\n\n", "data: {\"message\":\"private detail\"}\n\n",
            "event: response.completed\ndata: not-json\n\n", "", "data: {\"type\":\"unknown\"}\n\n").forEach { text ->
            val original = response(text)
            assertSame(original, withChatGptEventStreamType(original))
            assertEquals(text, original.body.string())
        }
    }

    @Test fun explicitTypesOtherRoutesMethodsAndHttpErrorsAreUntouched() {
        listOf(response(created, "text/html"), response(created, "text/event-stream"),
            response(created, code = 403), response(created, url = "https://example.invalid/v1/responses"),
            response(created, url = "https://api.openai.com/v1/models"), response(created, isPost = false)).forEach { original ->
            assertSame(original, withChatGptEventStreamType(original))
            assertEquals(created, original.body.string())
        }
    }

    @Test fun prefixInspectionIsByteAndLineBounded() {
        listOf(":${"x".repeat(9000)}\n$created", ": keepalive\n".repeat(33) + created,
            ":${"x".repeat(8000)}\n".repeat(3) + created).forEach { text ->
            val original = response(text)
            assertSame(original, withChatGptEventStreamType(original))
            assertEquals(text, original.body.string())
        }
    }

    @Test fun structuredErrorRemainsFailureNotFalseSuccess() {
        val text = "data: {\"type\":\"error\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}\n\n"
        val normalized = withChatGptEventStreamType(response(text))
        assertEquals(text, normalized.body.string())
        assertThrows(IllegalStateException::class.java) {
            ChatGptPlanDecoder().accept(SseEvent(data = text.removePrefix("data: ").trim()))
        }
    }
}
