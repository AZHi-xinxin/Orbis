package me.rerere.ai.provider.providers.openai

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.util.OrbisGatewayRequest
import me.rerere.ai.util.OrbisGatewayStopResult
import me.rerere.ai.util.OrbisGatewayTerminalReason
import me.rerere.ai.util.OrbisGatewayTurnControl
import me.rerere.ai.util.json
import me.rerere.ai.util.observeOrbisGatewayRequest
import me.rerere.ai.util.observeOrbisGatewayResponse
import me.rerere.ai.util.orbisSourceHeaders
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test

/** Real finite localhost HTTP/SSE, synthetic tool declaration only. Never executes a tool/model. */
class OrbisGatewayFinishLoopbackTest {
    @Test(timeout = 15_000) fun nonStreamingAcceptedReceiptCanBeFinishedWithoutAnotherGeneration() = replay(null)
    @Test(timeout = 15_000) fun chatStreamingAcceptedReceiptCanBeFinishedWithoutAnotherGeneration() = replay(false)
    @Test(timeout = 15_000) fun responsesStreamingAcceptedReceiptCanBeFinishedWithoutAnotherGeneration() = replay(true)

    private fun replay(responses: Boolean?) = runBlocking {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 5_000 }
        val sentPaths = Collections.synchronizedList(mutableListOf<String>())
        val serverFailure = AtomicReference<Throwable?>()
        val expectedNonce = AtomicReference<String?>()
        val binding = """{"session_id":"${"1".repeat(32)}","generation":1,"revision":0,"batch_id":null}"""
        val worker = thread(name = "synthetic-gateway-finish-${server.localPort}", isDaemon = true) {
            try {
                repeat(3) { index ->
                    server.accept().use { socket ->
                        check(socket.inetAddress.isLoopbackAddress)
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val start = checkNotNull(reader.readLine()).split(' ')
                        check(start.first() == "POST")
                        sentPaths += start[1]
                        val headers = mutableMapOf<String, String>()
                        var headerBytes = 0
                        while (true) {
                            val line = checkNotNull(reader.readLine())
                            headerBytes += line.length
                            check(headerBytes < 8_192)
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        check(headers["authorization"] == "Bearer synthetic-local-key")
                        check(headers["x-st-thread-id"] == "orbis:synthetic-window")
                        val length = checkNotNull(headers["content-length"]).toInt()
                        check(length in 0..8_192)
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) read += reader.read(chars, read, length - read).also { check(it > 0) }
                        val body = chars.concatToString()
                        if (index == 0) {
                            expectedNonce.set(headers["x-st-request-id"])
                            check(expectedNonce.get()?.matches(Regex("[0-9a-f]{32}")) == true)
                        } else {
                            val payload = json.parseToJsonElement(body).jsonObject
                            check(payload.getValue("request_id").jsonPrimitive.content == expectedNonce.get())
                            check(payload.getValue("model").jsonPrimitive.content == "synthetic-model")
                            if (index == 2) {
                                check(payload.getValue("binding") == json.parseToJsonElement(binding))
                                check(payload.getValue("terminal_reason").jsonPrimitive.content == "failed")
                            }
                        }
                        val payload = when (index) {
                            0 -> when (responses) {
                                null -> "{}"
                                false -> "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"synthetic-call\",\"type\":\"function\",\"function\":{\"name\":\"synthetic_noop\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
                                true -> "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"synthetic\",\"call_id\":\"synthetic-call\",\"name\":\"synthetic_noop\",\"arguments\":\"{}\"}}\n\ndata: [DONE]\n\n"
                            }
                            1 -> """{"protocol":"st-turn-control/1","state":"waiting_tool_results","can_stop":true,"external_tool_status":"unknown","binding":$binding}"""
                            else -> """{"protocol":"st-turn-control/1","state":"retired","can_stop":false,"external_tool_status":"unknown"}"""
                        }
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        val capability = if (index == 0) "X-ST-Turn-Control: st-turn-control/1\r\nX-ST-Request-ID: ${expectedNonce.get()}\r\n" else ""
                        val contentType = if (index == 0 && responses != null) "text/event-stream" else "application/json"
                        val wire = "HTTP/1.1 200 Synthetic\r\nContent-Type: $contentType\r\n$capability" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(wire.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    }
                }
            } catch (failure: Throwable) { serverFailure.set(failure) }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        val params = TextGenerationParams(Model(modelId = "synthetic-model"), orbisConversationId = "synthetic-window", onGatewayRequest = {})
        val path = if (responses == true) "/v1/responses" else "/v1/chat/completions"
        val request = Request.Builder().url("http://127.0.0.1:${server.localPort}$path")
            .headers(params.orbisSourceHeaders()).header("Authorization", "Bearer synthetic-local-key")
            .post("{}".toRequestBody()).build().observeOrbisGatewayRequest(params, "synthetic-model")
        val handle = checkNotNull(request.tag(OrbisGatewayRequest::class.java))
        var source: okhttp3.sse.EventSource? = null
        try {
            assertFalse(handle.supportsAutomaticFinish)
            if (responses == null) {
                client.newCall(request).await().use { request.observeOrbisGatewayResponse(it); it.body.string() }
            } else {
                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val decoder: StreamChunkDecoder = if (responses) ResponseApiStreamDecoder() else ChatCompletionsStreamDecoder()
                source = EventSources.createFactory(client).newEventSource(request,
                    OpenAIStreamListener(decoder, {}, request::observeOrbisGatewayResponse) { error -> failure.set(error); done.countDown() })
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(failure.get())
            }
            assertTrue(handle.supportsAutomaticFinish)
            val control = OrbisGatewayTurnControl(client)
            val permit = checkNotNull(control.status(handle).stopPermit)
            assertEquals(OrbisGatewayStopResult.RETIRED, control.finish(permit, OrbisGatewayTerminalReason.FAILED))
            worker.join(1_000)
            serverFailure.get()?.let { throw AssertionError("Synthetic fixture failed", it) }
            assertFalse(worker.isAlive)
            assertEquals(listOf(path, "/v1/turns/status", "/v1/turns/finish"), sentPaths.toList())
        } finally {
            source?.cancel(); server.close(); worker.join(1_000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
