package me.rerere.rikkahub.data.orbis.integration

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.pcm16MonoWav
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production HTTP provider + independent queue; synthetic loopback, no playback or user data. */
@RunWith(AndroidJUnit4::class)
class AiConfigurationChecksDeviceTest {
    @Test fun customConnectionHeadersReachCatalogAndProbeWithoutBorrowingInteractiveSession() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val model=Model(modelId="direct",customHeaders=listOf(
                me.rerere.ai.provider.CustomHeader("x-opencode-session","private-conversation"),
                me.rerere.ai.provider.CustomHeader("X-ST-Thread-ID","private-thread"),
                me.rerere.ai.provider.CustomHeader("X-Connection-Key","fixture-connection")))
            val owner=Assistant(customHeaders=listOf(me.rerere.ai.provider.CustomHeader("X-Assistant-Connection","fixture-assistant")))
            val store=store(owner,ProviderSetting.OpenAI(baseUrl=server.url("/v1"),models=listOf(model)))
            server.enqueue(catalog("direct"))
            server.enqueue(PrivateRoomLoopbackResponse().setBody("""{"id":"fixture","choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""))
            AiConfigurationChecks.request(context,store,null,owner.id.toString(),"check","model",model.id.toString())
            val catalog=server.takeRequest();val response=server.takeRequest()
            for(request in listOf(catalog,response)) {
                assertEquals("fixture-connection",request.getHeader("X-Connection-Key"))
                assertEquals("fixture-assistant",request.getHeader("X-Assistant-Connection"))
                assertNull(request.getHeader("X-ST-Thread-ID"))
                assertNotEquals("private-conversation",request.getHeader("x-opencode-session"))
                assertNotNull(request.getHeader("x-opencode-session"))
            }
            assertEquals(catalog.getHeader("x-opencode-session"),response.getHeader("x-opencode-session"))
            val receipt=finish(store,owner)
            assertEquals("passed",receipt["status"]!!.jsonPrimitive.content)
            assertFalse(receipt.toString().contains("fixture-connection"))
            assertEquals("private-conversation",store.settingsFlow.value.providers.single().models.single().customHeaders.first().value)
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(owner: Assistant, provider: ProviderSetting? = null, tts: TTSProviderSetting? = null): SettingsStore {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        check(context.applicationContext.javaClass == Application::class.java)
        return SettingsStore(context, AppScope().also { it.cancel() }).also {
            it.settingsFlow.value = Settings(assistants = listOf(owner), providers = listOfNotNull(provider),
                ttsProviders = listOfNotNull(tts), mcpServers = emptyList())
        }
    }
    private suspend fun finish(store: SettingsStore, owner: Assistant): JsonObject = withTimeout(15_000) {
        var receipt: JsonObject
        do {
            delay(30)
            receipt = AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check_status", "", "")
        } while (receipt["status"]?.jsonPrimitive?.content in setOf("queued", "running"))
        receipt
    }

    private fun catalog(vararg names: String) = PrivateRoomLoopbackResponse().setBody(
        buildJsonObject { putJsonArray("data") { names.forEach { name -> addJsonObject { put("id", name) } } } }.toString())

    @Test fun shortModelCheckIsQueuedUsesOnlySyntheticTextAndReportsRealResponse() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val model = Model(modelId = "synthetic-model")
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "fixture-not-a-secret", models = listOf(model))
            val owner = Assistant()
            val store = store(owner, provider)
            server.enqueue(catalog(model.modelId))
            server.enqueue(PrivateRoomLoopbackResponse().setBody(
                """{"id":"fixture","object":"chat.completion","model":"synthetic-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""))
            val queued = AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", model.id.toString())
            assertEquals("queued", queued["status"]!!.jsonPrimitive.content)
            assertEquals("/v1/models", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("POST", request.method)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("synthetic-model", body["model"]!!.jsonPrimitive.content)
            assertEquals(1, body["messages"]!!.jsonArray.size)
            assertTrue(body["messages"].toString().contains("Reply with OK."))
            val receipt = finish(store, owner)
            assertEquals("passed", receipt["status"]!!.jsonPrimitive.content)
            assertEquals("short_text_response", receipt["probe"]!!.jsonPrimitive.content)
            assertFalse(receipt.toString().contains("fixture-not-a-secret"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun ttsCheckUsesNativeSpeechEndpointWithoutPlayingOrReturningAudio() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val owner = Assistant()
            val provider = TTSProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "tts-fixture-secret",
                model = "fixture-tts", voice = "fixture-voice", strictEndpoint = true)
            val store = store(owner, tts = provider)
            val bytes = pcm16MonoWav(ByteArray(1600), 16000)
            server.enqueue(PrivateRoomLoopbackResponse().setBody(bytes).addHeader("Content-Type", "audio/wav"))
            assertEquals("queued", AiConfigurationChecks.request(context, store, null, owner.id.toString(),
                "check", "tts", provider.id.toString())["status"]!!.jsonPrimitive.content)
            val request = server.takeRequest()
            assertEquals("/v1/audio/speech", request.path)
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("Orbis connection test.", body["input"]!!.jsonPrimitive.content)
            assertEquals("fixture-tts", body["model"]!!.jsonPrimitive.content)
            val receipt = finish(store, owner)
            assertEquals("passed", receipt["status"]!!.jsonPrimitive.content)
            assertTrue(receipt["audio_bytes"]!!.jsonPrimitive.long > 0)
            assertFalse(receipt["playback_tested"]!!.jsonPrimitive.boolean)
            assertFalse(receipt.toString().contains("tts-fixture-secret"))
        }
    }

    @Test fun serverFailureProducesAClosedReceiptAndDoesNotChangeNativeConfig() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val owner = Assistant()
            val model = Model(modelId = "fixture")
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "fixture-key", models = listOf(model))
            val store = store(owner, provider)
            val before = store.settingsFlow.value
            server.enqueue(PrivateRoomLoopbackResponse().setResponseCode(401).setBody(
                """{"error":{"message":"do not repeat this private server text","type":"auth_error","code":"wrong_key"}}"""))
            AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", model.id.toString())
            server.takeRequest()
            val receipt = finish(store, owner)
            assertEquals("failed", receipt["status"]!!.jsonPrimitive.content)
            assertFalse(receipt.toString().contains("private server text"))
            assertFalse(receipt.toString().contains("fixture-key"))
            assertEquals(before, store.settingsFlow.value)
        }
    }

    @Test fun realCheckCompletesProposalAndFailedRecheckRevokesItsSuccess() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val owner = Assistant()
            val draft = ConnectionDraft("model_openai", "Fixture ST", server.url("/v1"), model = "synthetic-model")
            val store = store(owner)
            val pending = ConnectionProposalRules.propose(store.settingsFlow.value, owner.id.toString(), draft)
            store.settingsFlow.value = ConnectionProposalRules.approve(pending, draft.id(owner.id.toString()), "fixture-key", true)
            val proposal = store.settingsFlow.value.connectionProposals.single()
            val id = ConnectionProposalRules.connectionModelId(proposal.id).toString()
            assertFalse(AiConfigurationChecks.proposalVerified(store.settingsFlow.value, proposal))
            server.enqueue(catalog("synthetic-model"))
            server.enqueue(PrivateRoomLoopbackResponse().setBody(
                """{"id":"fixture","object":"chat.completion","model":"synthetic-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""))
            AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", id)
            server.takeRequest(); server.takeRequest(); assertEquals("passed", finish(store, owner)["status"]!!.jsonPrimitive.content)
            assertTrue(AiConfigurationChecks.proposalVerified(store.settingsFlow.value, proposal))
            val completed = ConnectionProposalRules.completeSetup(store.settingsFlow.value, owner.id.toString(), proposal.id) {
                AiConfigurationChecks.proposalVerified(store.settingsFlow.value, it)
            }
            assertTrue(completed.connectionProposals.single().setupCompleted)
            assertEquals(store.settingsFlow.value.providers, completed.providers)
            server.enqueue(PrivateRoomLoopbackResponse().setResponseCode(401).setBody("{}"))
            AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", id)
            assertFalse(AiConfigurationChecks.proposalVerified(store.settingsFlow.value, proposal))
            server.takeRequest(); assertEquals("failed", finish(store, owner)["status"]!!.jsonPrimitive.content)
            assertFalse(AiConfigurationChecks.proposalVerified(store.settingsFlow.value, proposal))
        }
    }

    @Test fun gatewayProbeDiscoversAuxiliaryEvenWhenOnlyMainModelWasSaved() = runBlocking {
        PrivateRoomLoopbackServer().use { server ->
            val model = Model(modelId = "chat")
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "synthetic", models = listOf(model))
            val owner = Assistant(); val store = store(owner, provider); val before = store.settingsFlow.value
            server.enqueue(catalog("chat", "chat--auxiliary-no-memory"))
            server.enqueue(PrivateRoomLoopbackResponse().setBody(
                """{"id":"synthetic","model":"chat--auxiliary-no-memory","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""))
            AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", model.id.toString())
            val discovery = server.takeRequest(); assertEquals("GET", discovery.method); assertEquals("/v1/models", discovery.path)
            val request = server.takeRequest(); val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("chat--auxiliary-no-memory", body["model"]!!.jsonPrimitive.content)
            assertEquals(1, body["messages"]!!.jsonArray.size)
            assertFalse(body.containsKey("tools"))
            val receipt = finish(store, owner)
            assertEquals("passed", receipt["status"]!!.jsonPrimitive.content)
            assertTrue(receipt["auxiliary_route"]!!.jsonPrimitive.boolean)
            assertFalse(receipt["chat_tool_continuation_tested"]!!.jsonPrimitive.boolean)
            assertEquals(before, store.settingsFlow.value)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedOrIncompleteDiscoveryNeverFallsBackToChatProbe() = runBlocking {
        for (catalog in listOf(catalog("chat", "other--auxiliary-no-memory"),
            PrivateRoomLoopbackResponse().setResponseCode(503).setBody("private upstream details"))) {
            PrivateRoomLoopbackServer().use { server ->
                val model = Model(modelId = "chat"); val owner = Assistant()
                val store = store(owner, ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "synthetic", models = listOf(model)))
                server.enqueue(catalog)
                AiConfigurationChecks.request(context, store, null, owner.id.toString(), "check", "model", model.id.toString())
                assertEquals("GET", server.takeRequest().method)
                val receipt = finish(store, owner)
                assertEquals("failed", receipt["status"]!!.jsonPrimitive.content)
                assertTrue(receipt["reason"]!!.jsonPrimitive.content in setOf("probe_auxiliary_alias_missing", "probe_catalog_unavailable"))
                assertFalse(receipt.toString().contains("private upstream details"))
                assertEquals(1, server.requestCount)
            }
        }
    }
}
