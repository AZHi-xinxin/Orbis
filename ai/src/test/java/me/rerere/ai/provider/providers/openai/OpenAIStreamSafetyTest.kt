package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real OkHttp SSE callbacks against a finite localhost server, no Android, real API or secrets. */
class OpenAIStreamSafetyTest {
    private data class Result(val chunks: List<StreamChunk>, val failure: Throwable?, val completions: Int)

    private fun replay(responses: Boolean, status: Int = 200, contentType: String? = "text/event-stream", body: String): Result {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket().apply { bind(InetSocketAddress(loopback, 0)); soTimeout = 3_000 }
        val port = server.localPort
        val serverFailure = AtomicReference<Throwable?>()
        val requests = AtomicInteger()
        val worker = thread(name = "synthetic-openai-sse-$port", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    check(socket.inetAddress.isLoopbackAddress)
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(reader.readLine() == "GET /synthetic-stream HTTP/1.1")
                    var total = 0
                    while (true) {
                        val line = checkNotNull(reader.readLine())
                        total += line.length
                        check(total <= 8_192)
                        if (line.isEmpty()) break
                    }
                    requests.incrementAndGet()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 $status Synthetic\r\n" +
                        (contentType?.let { "Content-Type: $it\r\n" } ?: "") +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
                    }
                }
            } catch (error: Throwable) { serverFailure.set(error) }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .callTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                check(chain.request().url.host == "127.0.0.1" && chain.request().url.port == port)
                check(chain.request().url.encodedPath == "/synthetic-stream")
                check(chain.request().header("Authorization") == null)
                chain.proceed(chain.request())
            }.build()
        val chunks = Collections.synchronizedList(mutableListOf<StreamChunk>())
        val failure = AtomicReference<Throwable?>()
        val completions = AtomicInteger()
        val done = CountDownLatch(1)
        val decoder: StreamChunkDecoder = if (responses) ResponseApiStreamDecoder() else ChatCompletionsStreamDecoder()
        val listener = OpenAIStreamListener(decoder, { chunks.addAll(it) }) {
            failure.set(it); completions.incrementAndGet(); done.countDown()
        }
        val source = EventSources.createFactory(client).newEventSource(
            Request.Builder().url("http://127.0.0.1:$port/synthetic-stream").build(), listener)
        try {
            assertTrue("Synthetic SSE completion timed out", done.await(4, TimeUnit.SECONDS))
            worker.join(1_000)
            assertFalse("Synthetic server did not finish", worker.isAlive)
            serverFailure.get()?.let { throw AssertionError("Synthetic HTTP server failed", it) }
            assertEquals(1, requests.get())
            return Result(chunks.toList(), failure.get(), completions.get())
        } finally {
            source.cancel()
            server.close()
            worker.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun both(block: (Boolean) -> Unit) = listOf(false, true).forEach(block)
    private fun data(json: String) = "data: $json\n\n"
    private fun assertEmptyFailure(result: Result) {
        assertTrue(result.failure is HttpException)
        assertEquals("upstream_empty_stream", (result.failure as HttpException).code)
        assertEquals("openai_stream_error", result.failure.errorType)
        assertFalse(IOException::class.java.isInstance(result.failure)) // GenerationLoop retries only IOException.
        assertEquals(1, result.completions)
        assertTrue(result.chunks.none { it is StreamChunk.Finish })
    }

    @Test(timeout = 10_000) fun `502 without content type or body fails with preserved HTTP status in both protocols`() = both {
        val result = replay(it, status = 502, contentType = null, body = "")
        assertTrue(result.failure is HttpException)
        assertEquals("502", (result.failure as HttpException).code)
        assertTrue(result.failure.message.orEmpty().contains("502"))
        assertTrue(result.chunks.isEmpty()); assertEquals(1, result.completions)
    }

    @Test(timeout = 10_000) fun `502 HTML becomes fixed safe error not successful empty flow or leaked proxy body`() = both {
        val secret = "synthetic-sensitive-marker-not-for-errors"
        val result = replay(it, 502, "text/html", "<html>$secret</html>")
        assertEquals("502", (result.failure as HttpException).code)
        assertFalse(result.failure.toString().contains(secret))
        assertNull(result.failure.cause)
        assertTrue(result.chunks.isEmpty()); assertEquals(1, result.completions)
    }

    @Test(timeout = 10_000) fun `blank non success response remains failure`() = both {
        val result = replay(it, 503, "application/json", " \r\n ")
        assertEquals("503", (result.failure as HttpException).code)
        assertTrue(result.chunks.isEmpty())
    }

    @Test(timeout = 10_000) fun `unknown JSON diagnostic objects never leak through generic error stringification`() {
        val secret = "synthetic-json-secret-not-for-errors"
        listOf("""{"diagnostic_secret":"$secret"}""",
            """{"error":{"diagnostic_secret":"$secret"}}""",
            """{"message":{"diagnostic_secret":"$secret"}}""").forEach { body ->
            both { responses ->
                val result = replay(responses, 502, "application/json", body)
                val failure = result.failure as HttpException
                assertEquals("502", failure.code)
                assertEquals("连接返回 HTTP 502，本轮未完成。", failure.message)
                assertFalse(failure.toString().contains(secret))
                assertNull(failure.cause)
                assertTrue(result.chunks.isEmpty()); assertEquals(1, result.completions)
            }
        }
    }

    @Test(timeout = 10_000) fun `200 empty event stream cannot report successful completion`() = both {
        assertEmptyFailure(replay(it, body = ""))
    }

    @Test(timeout = 10_000) fun `200 only DONE cannot report successful completion`() = both {
        assertEmptyFailure(replay(it, body = data("[DONE]")))
    }

    @Test(timeout = 10_000) fun `heartbeat only SSE is not model output`() = both {
        assertEmptyFailure(replay(it, body = ": synthetic heartbeat\n\n: synthetic heartbeat\n\n"))
    }

    @Test(timeout = 10_000) fun `role usage and lifecycle metadata cannot stand in for an output delta`() = both { responses ->
        val metadata = if (responses) """{"type":"response.created","response":{"id":"synthetic"}}"""
        else """{"choices":[{"delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""
        assertEmptyFailure(replay(responses, body = data(metadata) + data("[DONE]")))
    }

    @Test(timeout = 10_000) fun `real text delta completes once and is preserved`() = both { responses ->
        val payload = if (responses) """{"type":"response.output_text.delta","item_id":"synthetic","delta":"synthetic answer"}"""
        else """{"choices":[{"delta":{"content":"synthetic answer"}}]}"""
        val result = replay(responses, body = data(payload) + data("[DONE]"))
        assertNull(result.failure); assertEquals(1, result.completions)
        assertEquals("synthetic answer", result.chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, result.chunks.filterIsInstance<StreamChunk.Finish>().size)
    }

    @Test(timeout = 10_000) fun `real reasoning only delta is not misclassified as an empty transport`() = both { responses ->
        val payload = if (responses) """{"type":"response.reasoning_summary_text.delta","item_id":"synthetic","delta":"synthetic reasoning"}"""
        else """{"choices":[{"delta":{"reasoning_content":"synthetic reasoning"}}]}"""
        val result = replay(responses, body = data(payload) + data("[DONE]"))
        assertNull(result.failure)
        assertEquals("synthetic reasoning", result.chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text })
        assertEquals(1, result.completions)
    }

    @Test(timeout = 10_000) fun `tool only output is preserved without executing anything`() = both { responses ->
        val payload = if (responses) """{"type":"response.output_item.added","item":{"type":"function_call","id":"synthetic","call_id":"synthetic-call","name":"noop","arguments":"{}"}}"""
        else """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"synthetic-call","type":"function","function":{"name":"noop","arguments":"{}"}}]}}]}"""
        val result = replay(responses, body = data(payload) + data("[DONE]"))
        assertNull(result.failure)
        assertEquals("synthetic-call", result.chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().id)
        assertEquals(1, result.completions)
    }

    @Test(timeout = 10_000) fun `structured gateway failure retains original classification and HTTP status`() = both {
        val result = replay(it, 502, "application/json",
            """{"error":{"message":"synthetic empty reasoning completion","code":"upstream_empty_completion","type":"stiller_gateway_error"}}""")
        val error = result.failure as HttpException
        assertEquals("upstream_empty_completion", error.code)
        assertEquals("stiller_gateway_error", error.errorType)
        assertTrue(error.message.orEmpty().contains("502"))
        assertEquals(1, result.completions)
    }

    @Test fun `real transport exception remains available to existing network retry policy`() {
        val refusal = IOException("synthetic connect refusal")
        assertSame(refusal, openAIStreamFailure(refusal, null))
        assertTrue(openAIStreamFailure(null, null) is HttpException)
    }
}
