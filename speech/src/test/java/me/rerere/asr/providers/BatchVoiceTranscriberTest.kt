package me.rerere.asr.providers

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.BatchVoiceRecognitionException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class BatchVoiceTranscriberTest {
    @Test fun mimoPreservesModelLanguageAndPcmWav() {
        val provider = ASRProviderSetting.MiMo(apiKey = "synthetic", model = "custom-asr", language = "zh")
        val pcm = ByteArray(640) { it.toByte() }
        val request = batchVoiceRequest(provider, pcm)
        assertEquals("synthetic", request.header("api-key"))
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("custom-asr", json["model"]!!.jsonPrimitive.content)
        val data = json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0]
            .jsonObject["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content.substringAfter(',')
        val wav = Base64.getDecoder().decode(data)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(16_000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun stepPreservesAudioFormatAndOptions() {
        val provider = ASRProviderSetting.Step(apiKey = "synthetic", enableItn = false,
            enableTimestamp = true, hotwords = listOf("Orbis"), sampleRate = 24_000)
        val request = batchVoiceRequest(provider, ByteArray(960))
        assertEquals("Bearer synthetic", request.header("Authorization"))
        assertEquals("/v1/audio/asr/sse", request.url.encodedPath)
        val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        val input = body["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("24000", input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
        assertEquals("false", input["transcription"]!!.jsonObject["enable_itn"]!!.jsonPrimitive.content)
        assertEquals("Orbis", input["transcription"]!!.jsonObject["hotwords"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun privateCompatibilityEndpointsMayOmitCredentials() {
        assertNull(batchVoiceRequest(ASRProviderSetting.MiMo(apiKey = ""), ByteArray(640)).header("api-key"))
        assertNull(batchVoiceRequest(ASRProviderSetting.Step(apiKey = ""), ByteArray(640)).header("Authorization"))
    }

    @Test fun mimoValidEmptyAndNormalResults() {
        assertEquals("hello", parseMiMoVoiceResponse("""{"choices":[{"finish_reason":"stop","message":{"content":" hello "}}]}"""))
        assertEquals("", parseMiMoVoiceResponse("""{"choices":[{"message":{"content":""}}]}"""))
    }

    @Test fun malformedOrTruncatedMimoIsNotAnEmptySuccessfulTurn() {
        listOf("private response", "{}", """{"choices":[{"message":{"content":null}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""").forEach { body ->
            val error = assertThrows(BatchVoiceRecognitionException::class.java) { parseMiMoVoiceResponse(body) }
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test fun stepFinalAuthoritativeTextReplacesDeltas() {
        val data = "data: {\"type\":\"transcript.text.delta\",\"delta\":\"first draft\"}\n\n" +
            "event: transcript.text.done\ndata: {\"text\":\"final corrected\"}\n\n"
        assertEquals("final corrected", parseStepVoiceResponse(Buffer().writeUtf8(data)))
        assertEquals("", parseStepVoiceResponse(Buffer().writeUtf8("data: {\"type\":\"transcript.text.done\",\"text\":\"\"}\n\n")))
    }

    @Test fun stepEofDeltaAndProviderErrorNeverBecomeFinal() {
        listOf("data: {\"type\":\"transcript.text.delta\",\"delta\":\"partial\"}\n\n",
            "data: [DONE]\n\n", "data: {\"type\":\"error\",\"message\":\"private error\"}\n\n").forEach { body ->
            val error = assertThrows(BatchVoiceRecognitionException::class.java) { parseStepVoiceResponse(Buffer().writeUtf8(body)) }
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test(timeout = 10_000) fun realLoopbackMimoAndStepSuccess(): Unit = runBlocking<Unit> {
        for (step in listOf(false, true)) {
            val response = if (step) "data: {\"type\":\"transcript.text.done\",\"text\":\"synthetic step\"}\n\n"
                else "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"synthetic mimo\"}}]}"
            withServer(response) { endpoint, client, _ ->
                val provider = if (step) ASRProviderSetting.Step(baseUrl = endpoint, apiKey = "synthetic")
                    else ASRProviderSetting.MiMo(baseUrl = endpoint, apiKey = "synthetic")
                assertEquals(if (step) "synthetic step" else "synthetic mimo", BatchVoiceTranscriber(client, provider).transcribe(ByteArray(640)))
            }
        }
    }

    @Test(timeout = 10_000) fun cancellationClosesRealHttpBodyWaitBeforeTransportDeadline(): Unit = runBlocking<Unit> {
        withServer(null) { endpoint, client, closed ->
            val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                withTimeout(600) {
                    BatchVoiceTranscriber(client, ASRProviderSetting.MiMo(baseUrl = endpoint, apiKey = "synthetic"))
                        .transcribe(ByteArray(640))
                }
            } }
            assertTrue(task.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun httpFailureIsNotRetriedAndResponseBodyIsNotExposed() = runBlocking<Unit> {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503)
                .message("Synthetic unavailable").body("private error payload".toResponseBody()).build()
        }.build()
        try {
            val error = runCatching { BatchVoiceTranscriber(client, ASRProviderSetting.MiMo(apiKey = "synthetic"))
                .transcribe(ByteArray(640)) }.exceptionOrNull()
            assertTrue(error is BatchVoiceRecognitionException)
            assertTrue(error!!.message.orEmpty().contains("503"))
            assertFalse(error.message.orEmpty().contains("private"))
            assertEquals(1, count.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    private suspend fun withServer(response: String?, block: suspend (String, OkHttpClient, CountDownLatch) -> Unit) {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 3_000 }
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "batch-asr-synthetic") {
            try { server.accept().use { socket ->
                check(socket.inetAddress.isLoopbackAddress)
                socket.soTimeout = 3_000
                val reader = socket.getInputStream().bufferedReader()
                check(reader.readLine().startsWith("POST /"))
                var length = 0
                while (true) {
                    val line = checkNotNull(reader.readLine())
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                check(length in 1..16_384)
                repeat(length) { check(reader.read() >= 0) }
                val payload = response?.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 Synthetic\r\nContent-Length: ${payload?.size ?: 100}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (payload != null) write(payload)
                    flush()
                }
                if (payload == null) { check(reader.read() == -1); closed.countDown() }
            } } catch (error: Throwable) { failure.set(error) }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {
            block("http://127.0.0.1:${server.localPort}", client, closed)
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Synthetic ASR fixture failed", it) }
        } finally { server.close(); worker.join(1_000); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
