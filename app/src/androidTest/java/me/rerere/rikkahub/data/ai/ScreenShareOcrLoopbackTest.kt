package me.rerere.rikkahub.data.ai

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.ScreenShareTurn
import me.rerere.rikkahub.data.ai.transformers.projectScreenShareTurn
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.screenshare.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Synthetic pixels and loopback HTTP only; no Koin, account, phone chat or capture permission. */
@RunWith(AndroidJUnit4::class)
class ScreenShareOcrLoopbackTest {
    @Test fun openAiOcrFeedsTextIntoOriginalConversation() = verify("openai")
    @Test fun claudeOcrFeedsTextIntoOriginalConversation() = verify("claude")
    @Test fun googleOcrFeedsTextIntoOriginalConversation() = verify("google")

    private fun verify(kind: String) = runBlocking {
        val responses = listOf(when (kind) {
            "claude" -> """{"id":"test-ocr","type":"message","role":"assistant","model":"synthetic-vision","content":[{"type":"text","text":"SYNTHETIC SCREEN READY"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}"""
            "google" -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"SYNTHETIC SCREEN READY"}]},"finishReason":"STOP"}]}"""
            else -> completion("SYNTHETIC SCREEN READY")
        }, completion("original assistant answer"), completion("continued answer"))
        Loopback(responses).use { server ->
            val main = Model(modelId = "synthetic-text-main")
            val vision = Model(modelId = "synthetic-vision", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
            val mainProvider = ProviderSetting.OpenAI(baseUrl = "${server.url}/v1", models = listOf(main))
            val ocrProvider = when (kind) {
                "claude" -> ProviderSetting.Claude(baseUrl = server.url, models = listOf(vision))
                "google" -> ProviderSetting.Google(baseUrl = "${server.url}/v1beta", models = listOf(vision))
                else -> ProviderSetting.OpenAI(baseUrl = "${server.url}/v1", models = listOf(vision))
            }
            val settings = Settings(ocrModelId = vision.id, providers = listOf(mainProvider, ocrProvider))
            validateScreenShareModel(main, settings)
            val route = screenShareHelperRoute(main, settings)
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(10, TimeUnit.SECONDS).build()
            try {
                val manager = ProviderManager(client, InstrumentationRegistry.getInstrumentation().targetContext)
                val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bytes); bitmap.recycle()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
                val turn = ScreenShareTurn("session", "original-owner", "original-conversation", "frame", "", true)
                val original = listOf(UIMessage.user("EARLIER PRIVATE CONTEXT"), UIMessage.assistant("earlier reply"), UIMessage.user("current question"))
                val cache = ScreenShareObservationCache<String>()
                var allowed = true
                var observed = 0
                suspend fun description(): String = cache.get("frame/config", { check(allowed) }) {
                    val request = screenShareHelperRequest(route, "describe current screen", image)
                    manager.getProviderByType(route.provider) { observed++ }.generateText(route.provider, request.messages, request.params).message.toText()
                }
                val first = projectScreenShareTurn(turn, original, false, describe = { _, _ -> description() }) { _, _ -> error("text main cannot take images") }
                assertEquals(original.take(2), first.take(2))
                assertTrue(first.last().toText().contains("SYNTHETIC SCREEN READY"))
                assertFalse(original.last().toText().contains("SYNTHETIC"))
                assertEquals("original assistant answer", manager.getProviderByType(mainProvider).generateText(mainProvider, first, TextGenerationParams(model = main)).message.toText())
                val continued = projectScreenShareTurn(turn, original, false, describe = { _, _ -> description() }) { _, _ -> error("no image") }
                assertEquals(first, continued)
                manager.getProviderByType(mainProvider).generateText(mainProvider, continued, TextGenerationParams(model = main))
                allowed = false
                try { description(); fail("cached text must respect revoked access") } catch (_: IllegalStateException) { }
                server.verify()
                val ocr = server.requests[0]
                assertFalse(ocr.contains("EARLIER PRIVATE CONTEXT"))
                assertFalse(ocr.contains("current question"))
                assertTrue(ocr.contains(when (kind) { "claude" -> "base64"; "google" -> "inline"; else -> "image_url" }))
                assertFalse(Json.parseToJsonElement(ocr).jsonObject.containsKey("tools"))
                for (raw in server.requests.drop(1)) {
                    assertTrue(raw.contains("EARLIER PRIVATE CONTEXT")); assertTrue(raw.contains("current question"))
                    assertTrue(raw.contains("SYNTHETIC SCREEN READY"))
                    assertFalse(raw.contains("image_url")); assertFalse(raw.contains("base64"))
                }
                assertTrue(observed > 0)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    private fun completion(text: String) = """{"id":"synthetic","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}"""

    private class Loopback(private val responses: List<String>) : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)); soTimeout = 15000 }
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = Collections.synchronizedList(mutableListOf<String>())
        @Volatile private var failure: Throwable? = null
        private val worker = thread(isDaemon = true) {
            try { responses.forEach { response -> server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = BufferedInputStream(socket.getInputStream())
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val ch = input.read(); check(ch >= 0 && bytes.size() < 16384); if (ch == 10) break; bytes.write(ch) }
                    return bytes.toString("UTF-8").trimEnd('\r')
                }
                check(line().startsWith("POST "))
                val headers = mutableMapOf<String, String>()
                while (true) { val line = line(); if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                val length = headers.getValue("content-length").toInt(); check(length in 1..131072)
                val bytes = ByteArray(length); var count = 0
                while (count < length) { val read = input.read(bytes, count, length - count); check(read > 0); count += read }
                requests.add(bytes.toString(Charsets.UTF_8))
                val body = response.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } } catch (error: Throwable) { failure = error }
        }
        fun verify() { worker.join(1000); failure?.let { throw AssertionError(it) }; assertEquals(responses.size, requests.size) }
        override fun close() { server.close(); worker.join(1000) }
    }
}
