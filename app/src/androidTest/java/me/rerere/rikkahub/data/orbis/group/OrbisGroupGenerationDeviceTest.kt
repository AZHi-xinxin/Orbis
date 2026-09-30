package me.rerere.rikkahub.data.orbis.group

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real group adapter -> GenerationLoop -> ProviderManager -> on-device loopback HTTP.
 * Uses IsolatedGenerationLoopRunner, a plain Application, blank synthetic API keys and a
 * context that rejects private storage. Persistence callbacks capture synthetic text in
 * memory; the separate storage device suite covers SQLite. No real settings, model or DB.
 */
@RunWith(AndroidJUnit4::class)
class OrbisGroupGenerationDeviceTest {
    @Test fun actualOpenAiTextReachesPersistenceWithoutPrivateInjectionOrTools() = runBlocking<Unit> {
        fixture(claude = false, streaming = false, answer = "synthetic durable group answer") { fixture ->
            val input = fixture.input()
            val saved = mutableListOf<String>()
            val result = fixture.responder.generate(input) { saved += it }
            assertEquals("synthetic durable group answer", result)
            assertTrue(saved.isNotEmpty())
            assertEquals(result, saved.last())
            assertTrue(saved.all { it == result })

            val request = fixture.server.request()
            assertEquals(JsonPrimitive("synthetic-group-model"), request["model"])
            assertToolsAbsent(request)
            val wire = request.toString()
            assertTrue(wire.contains("synthetic public group question"))
            assertTrue(wire.contains("Orbis 群聊范围"))
            assertFalse(wire.contains(PRIVATE_MARKER))
            assertFalse(wire.contains("synthetic_tool_override"))
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test fun actualStreamingRetryDoesNotAppendToItsOwnStoredPartial() = runBlocking<Unit> {
        fixture(claude = false, streaming = true, answer = "fresh group reply") { fixture ->
            val input = fixture.input(ownPartial = "OLD_PARTIAL_MUST_NOT_BE_REEMITTED")
            val priorIds = input.messages.map { it.id }
            assertEquals(MessageRole.USER, input.messages.last().role)
            val saved = mutableListOf<String>()
            val result = fixture.responder.generate(input) { saved += it }
            assertEquals("fresh group reply", result)
            assertEquals(result, saved.last())
            assertTrue(saved.all { !it.contains("OLD_PARTIAL") && "fresh group reply".startsWith(it) })
            // Historical messages remain input only; a new output ID was required by the adapter.
            assertEquals(priorIds, input.messages.map { it.id })
            assertTrue(input.messages.any { it.toText().contains("OLD_PARTIAL_MUST_NOT_BE_REEMITTED") })
            val request = fixture.server.request()
            val messages = request.getValue("messages").jsonArray
            assertEquals(JsonPrimitive("user"), messages.last().jsonObject["role"])
            assertTrue(messages.last().toString().contains("Orbis 群聊调度提示"))
            assertTrue(request.toString().contains("OLD_PARTIAL_MUST_NOT_BE_REEMITTED"))
            assertToolsAbsent(request)
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test fun actualClaudeStreamingPauseTurnHonorsZeroAutomaticContinuations() = runBlocking<Unit> {
        fixture(claude = true, streaming = true, answer = "single paused group answer") { fixture ->
            val saved = mutableListOf<String>()
            val result = fixture.responder.generate(fixture.input()) { saved += it }
            assertEquals("single paused group answer", result)
            assertEquals(result, saved.last())
            assertEquals(JsonPrimitive(true), fixture.server.request()["stream"])
            assertToolsAbsent(fixture.server.request())
            // This assertion observes real transport attempts, not a mocked params object:
            // a lost maxAutomaticContinuations=0 forwarding would attempt POST number two.
            assertEquals(1, fixture.requestBudget.get())
            assertEquals(1, fixture.server.requestCount.get())
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    private fun assertToolsAbsent(request: JsonObject) {
        val tools = request["tools"]
        assertTrue(tools == null || tools == JsonNull || tools == JsonArray(emptyList()))
        assertFalse(request.toString().contains("synthetic_tool_override"))
    }

    private suspend fun fixture(claude: Boolean, streaming: Boolean, answer: String,
        block: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner) { "Select the isolated generation-loop runner." }
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java) {
            "This fixture must not start the production application."
        }
        val context = NoPrivateStorageContext(instrumentation.context)
        val server = LoopbackServer(claude, streaming, answer)
        val requestBudget = AtomicInteger()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .dns { host ->
                if (host != "127.0.0.1") throw IOException("Non-loopback DNS forbidden in fixture.")
                listOf(LOOPBACK)
            }
            .addInterceptor { chain ->
                val request = chain.request(); val url = request.url
                if (url.scheme != "http" || url.host != "127.0.0.1" || url.port != server.port ||
                    url.encodedPath != server.path || url.query != null || request.method != "POST" ||
                    requestBudget.incrementAndGet() > 1
                ) throw IOException("Synthetic group endpoint/request budget violation.")
                chain.proceed(request)
            }.build()
        try {
            val model = Model(modelId = "synthetic-group-model", displayName = "Synthetic only",
                abilities = listOf(ModelAbility.TOOL), tools = setOf(BuiltInTools.Search),
                customBodies = listOf(CustomBody("tools", JsonArray(listOf(JsonPrimitive("synthetic_tool_override"))))))
            val lorebook = Lorebook(entries = listOf(PromptInjection.RegexInjection(
                keywords = listOf("synthetic"), content = PRIVATE_MARKER, scanDepth = 20,
            )))
            val assistant = Assistant(chatModelId = model.id, name = "Synthetic member",
                systemPrompt = "Synthetic isolated group persona.", streamOutput = streaming, maxTokens = 128,
                enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
                presetMessages = listOf(UIMessage.user(PRIVATE_MARKER)),
                messageTemplate = PRIVATE_MARKER + " {{ message }}", lorebookIds = setOf(lorebook.id),
                enableWebSearch = true, enableTimeReminder = true,
                workspaceId = Uuid.random(), mcpServers = setOf(Uuid.random()),
                customBodies = listOf(CustomBody("messages", JsonArray(listOf(JsonPrimitive(PRIVATE_MARKER))))))
            val provider: ProviderSetting = if (claude) ProviderSetting.Claude(
                name = "Synthetic loopback only", models = listOf(model), apiKey = "",
                baseUrl = "http://127.0.0.1:${server.port}/v1",
            ) else ProviderSetting.OpenAI(name = "Synthetic loopback only", models = listOf(model), apiKey = "",
                baseUrl = "http://127.0.0.1:${server.port}/v1")
            // Blank keys make key roulette return before accessing cacheDir. The wrapper also
            // fails loudly if any production setting, DB, memory or private path is requested.
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                chatModelId = model.id, assistantId = assistant.id, enableSuggestion = false,
                networkSetting = NetworkSetting(enableAutoRetry = false),
                modeInjections = emptyList(), lorebooks = listOf(lorebook), searchServices = emptyList(),
                ttsProviders = emptyList(), mcpServers = emptyList())
            val member = OrbisGroupMember(Uuid.random().toString(), assistant.id, model.id, provider.id,
                "Synthetic member", joinedAt = 1)
            val participant = resolveGroupParticipant(settings, member)
            val responder = GenerationLoopGroupResponder(GenerationLoop(context, ProviderManager(client, context), Json))
            withTimeout(15_000) { block(Fixture(context, server, requestBudget, participant, responder)) }
            server.assertHealthy()
            assertEquals(1, requestBudget.get())
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow(); server.close()
        }
    }

    private class Fixture(val context: NoPrivateStorageContext, val server: LoopbackServer,
        val requestBudget: AtomicInteger, val participant: GroupParticipant,
        val responder: GenerationLoopGroupResponder) {
        private val roomId = Uuid.random().toString()
        fun input(ownPartial: String? = null): GroupGenerationInput {
            val roundId = Uuid.random().toString()
            val history = mutableListOf(OrbisGroupMessage(Uuid.random().toString(), roomId, roundId,
                null, "Synthetic human", text = "synthetic public group question", createdAt = 1, updatedAt = 1))
            if (ownPartial != null) history += OrbisGroupMessage(Uuid.random().toString(), roomId, roundId,
                participant.member.id, participant.member.name, participant.member.modelId, ownPartial,
                OrbisGroupMessageStatus.FAILED, createdAt = 2, updatedAt = 2)
            return GroupGenerationInput(roomId, participant,
                buildGroupContext(history, participant, history.size.toLong()).messages)
        }
    }

    private class NoPrivateStorageContext(base: Context) : ContextWrapper(base) {
        val privateStorageAccesses = AtomicInteger()
        private fun forbidden(): Nothing {
            privateStorageAccesses.incrementAndGet()
            throw AssertionError("Synthetic group fixture attempted private storage access.")
        }
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = forbidden()
        override fun getFilesDir(): File = forbidden()
        override fun getNoBackupFilesDir(): File = forbidden()
        override fun getDatabasePath(name: String): File = forbidden()
        override fun getDir(name: String, mode: Int): File = forbidden()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbidden()
    }

    /** Exact random port + single POST + 15s deadline; no disk, external DNS, credentials or proxy. */
    private class LoopbackServer(private val claude: Boolean, private val streaming: Boolean,
        private val answer: String) : AutoCloseable {
        private val listener = ServerSocket().apply { bind(InetSocketAddress(LOOPBACK, 0), 1); soTimeout = 250 }
        val port = listener.localPort
        val path = if (claude) "/v1/messages" else "/v1/chat/completions"
        val requestCount = AtomicInteger()
        private val captured = AtomicReference<JsonObject?>()
        private val failure = AtomicReference<Throwable?>()
        private val closed = AtomicBoolean()
        private val active = AtomicReference<Socket?>()
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        private val worker = thread(name = "synthetic-group-http-$port", isDaemon = true) {
            try {
                while (!closed.get() && requestCount.get() == 0) {
                    check(System.nanoTime() < deadline) { "Synthetic fixture deadline exceeded." }
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    active.set(socket)
                    socket.use {
                        check(it.inetAddress.isLoopbackAddress); it.soTimeout = 3000
                        val input = BufferedInputStream(it.getInputStream())
                        check(readLine(input) == "POST $path HTTP/1.1")
                        val headers = mutableMapOf<String, String>(); var headerBytes = 0
                        while (true) {
                            val line = readLine(input); headerBytes += line.length + 2
                            check(headerBytes <= 16_384)
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':'); check(colon > 0)
                            val key = line.substring(0, colon).lowercase(); check(key !in headers)
                            headers[key] = line.substring(colon + 1).trim()
                        }
                        check(headers["host"] == "127.0.0.1:$port" && "transfer-encoding" !in headers)
                        val size = headers.getValue("content-length").toInt(); check(size in 1..131_072)
                        val bytes = ByteArray(size); var read = 0
                        while (read < size) { val count = input.read(bytes, read, size - read); check(count > 0); read += count }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        check((request["stream"] == JsonPrimitive(true)) == streaming)
                        captured.set(request); requestCount.incrementAndGet()
                        val response = response().toByteArray(Charsets.UTF_8)
                        val type = if (streaming) "text/event-stream" else "application/json"
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(response); flush() }
                    }
                    active.set(null)
                }
            } catch (error: Throwable) { if (!closed.get()) failure.set(error)
            } finally { runCatching { active.getAndSet(null)?.close() }; runCatching { listener.close() } }
        }
        fun request(): JsonObject = requireNotNull(captured.get())
        fun assertHealthy() {
            worker.join(1000); check(!worker.isAlive)
            failure.get()?.let { throw AssertionError("Synthetic group HTTP fixture failed.", it) }
            assertEquals(1, requestCount.get())
        }
        override fun close() {
            closed.set(true); runCatching { active.getAndSet(null)?.close() }; runCatching { listener.close() }
            worker.join(1000); check(!worker.isAlive)
        }
        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) { val next = input.read(); check(next >= 0 && bytes.size() < 8192); if (next == 10) break; bytes.write(next) }
            return bytes.toString("ISO-8859-1").removeSuffix("\r")
        }
        private fun response(): String {
            val text = JsonPrimitive(answer).toString()
            if (!streaming) return """{"id":"synthetic-group","model":"synthetic-group-model","choices":[{"index":0,"message":{"role":"assistant","content":$text},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
            if (!claude) return "data: {\"id\":\"synthetic-group\",\"model\":\"synthetic-group-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":$text},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
            return "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"synthetic-group\",\"model\":\"synthetic-group-model\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n" +
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$text}}\n\n" +
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val PRIVATE_MARKER = "PRIVATE_CONTEXT_MUST_NOT_LEAVE_SYNTHETIC_SETTINGS"
    }
}
