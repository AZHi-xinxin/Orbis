package me.rerere.rikkahub.data.orbis.group

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Real group -> GenerationLoop -> OpenAI serialization, but HTTP intercepted before DNS.
 * Plain test Application, synthetic blank-key settings, no real provider, files or chat DB.
 */
@RunWith(AndroidJUnit4::class)
class OrbisGroupGlmFormatDeviceTest {
    @Test fun firstRoundHasOneUserAndNoFabricatedAssistantOnActualProviderWire() = runBlocking<Unit> {
        fixture(ownPartial = false) { request, saved, result ->
            assertEquals(listOf("system", "user"), roles(request))
            val text = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(text.contains("[群聊记录 · Synthetic Human]"))
            assertTrue(text.contains("[群聊记录 · Synthetic Other]"))
            assertTrue(text.endsWith("不要代写其他成员，不执行任何工具。"))
            assertEquals("FRESH_SYNTHETIC_REPLY", result)
            assertEquals(result, saved.last())
        }
    }

    @Test fun retryKeepsOldPartialFrozenAndWritesOnlyNewReplyWithoutRetryingHttp() = runBlocking<Unit> {
        fixture(ownPartial = true) { request, saved, result ->
            assertEquals(listOf("system", "user", "assistant", "user"), roles(request))
            assertTrue(request["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonPrimitive.content.contains("OLD_PARTIAL"))
            assertTrue(saved.all { !it.contains("OLD_PARTIAL") && "FRESH_SYNTHETIC_REPLY".startsWith(it) })
            assertEquals("FRESH_SYNTHETIC_REPLY", result)
        }
    }

    private fun roles(request: JsonObject) = request["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    private suspend fun fixture(ownPartial: Boolean, checkResult: (JsonObject, List<String>, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner) { "Use isolated runner." }
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        val context = NoStorageContext(instrumentation.context)
        val count = AtomicInteger()
        val dns = AtomicInteger()
        val captured = AtomicReference<JsonObject?>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .dns { dns.incrementAndGet(); throw AssertionError("No DNS or socket is allowed.") }
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.toString() == "https://api.siliconflow.cn/v1/chat/completions")
                check(request.method == "POST" && count.incrementAndGet() == 1)
                val buffer = Buffer()
                requireNotNull(request.body).writeTo(buffer)
                captured.set(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                // Deliberately do not call chain.proceed(): even this official-looking URL
                // is satisfied in-process and cannot make a paid model request.
                val stream = "data: {\"id\":\"synthetic\",\"model\":\"zai-org/GLM-4.5V\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"FRESH_SYNTHETIC_REPLY\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(stream.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
        try {
            val model = Model(modelId = "zai-org/GLM-4.5V", abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL))
            val assistant = Assistant(chatModelId = model.id, systemPrompt = "PUBLIC_SYNTHETIC_PERSONA", streamOutput = true,
                enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
                presetMessages = listOf(UIMessage.user(PRIVATE_MARKER)), messageTemplate = PRIVATE_MARKER,
                workspaceId = Uuid.random(), mcpServers = setOf(Uuid.random()))
            val provider = ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn/v1", apiKey = "", models = listOf(model))
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                chatModelId = model.id, assistantId = assistant.id, enableSuggestion = false,
                networkSetting = NetworkSetting(enableAutoRetry = false), searchServices = emptyList(),
                ttsProviders = emptyList(), mcpServers = emptyList())
            val member = OrbisGroupMember(Uuid.random().toString(), assistant.id, model.id, provider.id, "Synthetic GLM", joinedAt = 1)
            val participant = resolveGroupParticipant(settings, member)
            val room = Uuid.random().toString()
            val round = Uuid.random().toString()
            val rows = mutableListOf(OrbisGroupMessage(Uuid.random().toString(), room, round, null, "Synthetic Human",
                text = "SYNTHETIC_QUESTION", createdAt = 1, updatedAt = 1))
            if (ownPartial) rows += OrbisGroupMessage(Uuid.random().toString(), room, round, member.id, member.name,
                text = "OLD_PARTIAL", status = OrbisGroupMessageStatus.FAILED, createdAt = 2, updatedAt = 2)
            rows += OrbisGroupMessage(Uuid.random().toString(), room, round, "other", "Synthetic Other",
                text = "SYNTHETIC_OTHER_WORDS", createdAt = 3, updatedAt = 3)
            val originals = rows.toList()
            val messages = buildGroupContext(rows, participant, rows.size.toLong()).messages
            val before = messages.toList()
            assertEquals(MessageRole.USER, messages.last().role)
            assertTrue(messages.last().isSynthetic)
            val responder = GenerationLoopGroupResponder(GenerationLoop(context, ProviderManager(client, context), Json))
            val saved = mutableListOf<String>()
            val result = withTimeout(10_000) { responder.generate(GroupGenerationInput(room, participant, messages)) { saved += it } }
            val request = requireNotNull(captured.get())
            assertEquals(before, messages)
            assertEquals(originals, rows)
            assertEquals(1, count.get())
            assertEquals(0, dns.get())
            assertEquals(0, context.storage.get())
            assertEquals(JsonPrimitive("zai-org/GLM-4.5V"), request["model"])
            assertEquals(JsonPrimitive(4096), request["max_tokens"])
            assertEquals(JsonPrimitive(true), request["enable_thinking"])
            assertEquals(JsonPrimitive(true), request["stream_options"]!!.jsonObject["include_usage"])
            assertFalse(request.containsKey("tools"))
            assertFalse(request.containsKey("thinking_budget"))
            assertFalse(request.toString().contains(PRIVATE_MARKER))
            checkResult(request, saved, result)
        } finally {
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }

    private class NoStorageContext(base: Context) : ContextWrapper(base) {
        val storage = AtomicInteger()
        private fun reject(): Nothing { storage.incrementAndGet(); throw AssertionError("Private storage forbidden.") }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = reject()
        override fun getCacheDir(): File = reject()
        override fun getNoBackupFilesDir(): File = reject()
        override fun getDatabasePath(name: String): File = reject()
        override fun getDir(name: String, mode: Int): File = reject()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = reject()
    }

    companion object { private const val PRIVATE_MARKER = "PRIVATE_SYNTHETIC_CONTEXT_MUST_NOT_LEAVE_SETTINGS" }
}
