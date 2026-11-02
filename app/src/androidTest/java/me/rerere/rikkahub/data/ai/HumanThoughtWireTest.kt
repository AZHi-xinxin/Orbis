package me.rerere.rikkahub.data.ai

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Actual wire payloads against local HTTP only; no accounts or personal conversations. */
class HumanThoughtWireTest {
    @Test fun openAi() = verify("openai")
    @Test fun responses() = verify("responses")
    @Test fun google() = verify("google")
    @Test fun claude() = verify("claude")

    private fun verify(kind: String) = runBlocking {
        val response = when(kind) {
            "claude" -> """{"id":"test","type":"message","role":"assistant","model":"synthetic","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}"""
            "google" -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
            "responses" -> """{"id":"test","status":"completed","output":[{"id":"msg","type":"message","role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}],"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}"""
            else -> """{"id":"test","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        }
        Loopback(listOf(response,response)).use { server ->
            val model = Model(modelId = "synthetic")
            val provider: ProviderSetting = when(kind) {
                "claude" -> ProviderSetting.Claude(baseUrl = server.url)
                "google" -> ProviderSetting.Google(baseUrl = "${server.url}/v1beta")
                else -> ProviderSetting.OpenAI(baseUrl = "${server.url}/v1", useResponseApi = kind == "responses")
            }
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).callTimeout(10, TimeUnit.SECONDS).build()
            try {
                val manager = ProviderManager(client, InstrumentationRegistry.getInstrumentation().targetContext)
                for (thought in listOf("HUMAN ASIDE", "")) {
                    val message = UIMessage.user("MAIN BODY").copy(parts = listOf(humanThoughtPart(thought), UIMessagePart.Text("MAIN BODY")))
                    val stored = message.parts.toList()
                    manager.getProviderByType(provider).generateText(provider, listOf(message), TextGenerationParams(model))
                    assertEquals(stored, message.parts)
                }
                server.verify()
                for ((index, raw) in server.requests.withIndex()) {
                    assertTrue(raw.contains("【人类思考链】")); assertTrue(raw.contains("【正文回复】"))
                    assertTrue(raw.contains(if(index == 0) "HUMAN ASIDE" else "（为空）"))
                    assertTrue(raw.contains("MAIN BODY"))
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    val messages = (obj["messages"] ?: obj["contents"] ?: obj["input"])!!.jsonArray
                    assertEquals(1, messages.size)
                    assertEquals("user", messages.single().jsonObject["role"]!!.jsonPrimitive.content)
                    assertFalse(raw.contains("reasoning_content"))
                    assertFalse(raw.contains("orbis_human_thought"))
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

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
