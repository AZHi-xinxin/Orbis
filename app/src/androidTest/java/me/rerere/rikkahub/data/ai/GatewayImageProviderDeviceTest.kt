package me.rerere.rikkahub.data.ai

import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.*
import me.rerere.ai.util.GatewayImageTurn
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Real provider and HTTP response-body disconnect, synthetic history only, isolated emulator. */
class GatewayImageProviderDeviceTest {
    @Test fun streamingImageContinuationSurvivesOptionalLookupDisconnect() = exercise(true)
    @Test fun nonStreamingImageContinuationSurvivesOptionalLookupDisconnect() = exercise(false)

    private fun exercise(stream: Boolean) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.packageName == "org.orbis.agent.bodylab")
        val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 15_000
        }
        val captured = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "synthetic-image-provider") {
            try {
                var lookups = 0
                repeat(9) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val path = checkNotNull(reader.readLine()).split(' ')[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = checkNotNull(reader.readLine()); if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        check(headers["authorization"] == "Bearer synthetic-local-key")
                        val length = checkNotNull(headers["content-length"]).toInt(); check(length in 1..100_000)
                        val chars = CharArray(length); var count = 0
                        while (count < length) count += reader.read(chars, count, length - count).also { check(it > 0) }
                        val body = Json.parseToJsonElement(chars.concatToString()).jsonObject
                        captured += path to body
                        val isLookup = path == "/v1/image-files"
                        if (isLookup && ++lookups == 2) {
                            // Headers arrive successfully, but the body is interrupted (not a model/tool request).
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray()); flush()
                            }
                            return@use
                        }
                        val payload = if (isLookup) buildJsonObject {
                            put("protocol", "st-image-files/1"); put("supported", true)
                            put("references", JsonObject(body.getValue("digests").jsonArray.associate {
                                it.jsonPrimitive.content to JsonPrimitive("st-image://" + it.jsonPrimitive.content)
                            }))
                        }.toString() else if (stream) "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                        else "{\"id\":\"synthetic\",\"model\":\"model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                        val bytes = payload.toByteArray()
                        val type = if (!isLookup && stream) "text/event-stream" else "application/json"
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nX-ST-Image-Files: st-image-files/1\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).build()
        try {
            val api = ChatCompletionsAPI(client, KeyRoulette.default())
            val setting = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.localPort}/v1", apiKey = "synthetic-local-key")
            val model = Model(modelId = "model", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
            val oldImage = "data:image/png;base64,AQIDBA=="
            val newImage = "data:image/png;base64,BQYHCA=="
            val original = listOf(UIMessage.user("image").copy(parts = listOf(UIMessagePart.Image(oldImage))))
            val completed = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                toolCallId = "already-done", toolName = "save_note", input = "{}", output = listOf(UIMessagePart.Text("already saved")))))
            val continuation = original + completed
            val withNew = continuation + UIMessage.user("new image").copy(parts = listOf(UIMessagePart.Image(newImage)))
            suspend fun generate(messages: List<UIMessage>, turn: GatewayImageTurn) {
                val params = TextGenerationParams(model, gatewayImageTurn = turn)
                if (stream) assertTrue(api.streamText(setting, messages, params).toList().isNotEmpty())
                else assertEquals("stop", api.generateText(setting, messages, params).finishReason)
            }
            generate(original, GatewayImageTurn()) // Learns capability, sends original image once.
            val turn = GatewayImageTurn()
            generate(original, turn) // Confirms the old image reference.
            generate(continuation, turn) // Must not perform another lookup.
            generate(withNew, turn) // The new-image lookup body disconnects.
            generate(withNew, turn) // Fallback remains stable on the following continuation.
            generate(withNew, GatewayImageTurn()) // New generation retries optimization normally.
            worker.join(5_000); assertFalse(worker.isAlive); failure.get()?.let { throw AssertionError(it) }
            assertEquals(3, captured.count { it.first == "/v1/image-files" })
            val chats = captured.filter { it.first == "/v1/chat/completions" }.map { it.second.getValue("messages").jsonArray }
            assertEquals(6, chats.size)
            assertTrue(chats[0].toString().contains(oldImage)); assertFalse(chats[1].toString().contains(oldImage))
            for (index in 2..4) {
                assertEquals(chats[1].first(), chats[index].first())
                assertEquals(chats[2].drop(1).take(2), chats[index].drop(1).take(2))
                assertEquals(1, chats[index].count { it.jsonObject["role"] == JsonPrimitive("tool") })
            }
            assertTrue(chats[3].toString().contains(newImage)); assertEquals(chats[3], chats[4])
            assertFalse(chats[5].toString().contains(newImage))
        } finally {
            server.close(); worker.join(2_000); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
