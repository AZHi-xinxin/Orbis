package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse as MockResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer as MockWebServer
import org.junit.Assert.*
import org.junit.Test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Application
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.runner.RunWith

/** Real provider adapters, synthetic loopback only; never reads business settings. */
@RunWith(AndroidJUnit4::class)
class PrivateRoomProviderDeviceTest {
    @org.junit.Before fun requireIsolation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    }
    @Test fun truncatedOrPausedProviderResponseIsNotReportedComplete() = runBlocking {
        for (reason in listOf("length", "max_tokens", "pause_turn", "unknown")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"$reason"}]}"""))
                val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "synthetic")
                val engine = PrivateRoomProviderModel(provider, TextGenerationParams(model = Model(modelId = "test-model")), privateRoomHttpClient(OkHttpClient()))
                assertEquals(PrivateRoomOutcome.INCOMPLETE, PrivateRoomLoop(engine).run("", "", emptyList()) { true })
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun actualProviderCompletesPrivateToolRoundWithoutOuterSessionOrNativeTools() = runBlocking {
        verifyPrivateToolRound(direct = false)
    }

    @Test fun ordinaryDirectApiCompletesPrivateToolRoundWithoutCustomGateway() = runBlocking {
        verifyPrivateToolRound(direct = true)
    }

    private suspend fun verifyPrivateToolRound(direct: Boolean) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"one","model":"test-model","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-private","type":"function","function":{"name":"private_write","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""))
            server.enqueue(MockResponse().setBody("""{"id":"two","model":"test-model","choices":[{"message":{"role":"assistant","content":"PRIVATE_FINAL"},"finish_reason":"stop"}]}"""))
            val provider = if (direct) privateRoomRoute(
                ProviderSetting.OpenAI(baseUrl = server.url("/v1/"), apiKey = "synthetic", useResponseApi = true),
                "test-model", privateRoomHttpClient(OkHttpClient()), confirmedDirectApi = true,
            ) else ProviderSetting.OpenAI(baseUrl = server.url("/v1"), apiKey = "synthetic", chatCompletionsPath = "/private-room/chat/completions")
            val params = TextGenerationParams(
                model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL),
                    tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext),
                    customBodies = listOf(CustomBody("ordinary_custom", JsonPrimitive("must-not-leak")))),
                sessionId = "OUTER_SESSION", orbisConversationId = "OUTER_CONVERSATION",
                customHeaders = listOf(CustomHeader("X-ST-Thread-ID", "OUTER_SESSION")),
                customBody = listOf(CustomBody("session_id", JsonPrimitive("OUTER_SESSION"))),
                onGatewayRequest = { error("ordinary gateway tracker must not run") },
            )
            val names = listOf("private_list", "private_read", "private_write", "private_requests", "private_decide", "private_revoke")
            var writes = 0
            val tools = names.map { name -> Tool(name, "synthetic", execute = {
                check(name == "private_write"); writes++; listOf(UIMessagePart.Text("PRIVATE_TOOL_RESULT"))
            }) }
            val loop = PrivateRoomLoop(PrivateRoomProviderModel(provider, params, privateRoomHttpClient(OkHttpClient())))
            assertEquals(PrivateRoomOutcome.COMPLETED, loop.run("synthetic persona", "synthetic public context", tools) { true })
            assertEquals(1, writes)
            val requests = listOf(server.takeRequest(), server.takeRequest())
            requests.forEach { request ->
                assertEquals(if (direct) "/v1/chat/completions" else "/v1/private-room/chat/completions", request.path)
                assertEquals(if (direct) null else "orbis-private-room/1", request.getHeader("X-Orbis-Private-Room"))
                assertNull(request.getHeader("X-ST-Thread-ID"))
                assertFalse(request.body.contains("OUTER_"))
                assertFalse(request.body.contains("ordinary_custom"))
                val body = Json.parseToJsonElement(request.body).jsonObject
                assertEquals(JsonPrimitive(false), body["stream"])
                assertEquals(names.toSet(), body.getValue("tools").jsonArray.map {
                    it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
                }.toSet())
            }
            assertFalse(requests.first().body.contains("PRIVATE_TOOL_RESULT"))
            assertTrue(requests.last().body.contains("PRIVATE_TOOL_RESULT"))
            assertEquals(2, server.requestCount)
        }
    }

}
