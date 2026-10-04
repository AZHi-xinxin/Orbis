package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse as MockResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer as MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomRouteTest {
    @Test fun explicitlyConfirmedOrdinaryApiNeedsNoGatewayAndNoCapabilityProbe() = runTest {
        MockWebServer().use { server ->
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1/").toString(), apiKey = "synthetic", useResponseApi = true)
            val route = privateRoomRoute(provider, "test-model", privateRoomHttpClient(OkHttpClient()), true) as ProviderSetting.OpenAI
            assertEquals(0, server.requestCount)
            assertEquals("/chat/completions", route.chatCompletionsPath)
            assertFalse(route.useResponseApi)
            assertEquals(server.url("/v1").toString(), route.baseUrl)
        }
    }
    @Test fun explicitChoiceStillRejectsUnsafeTransportAndEmbeddedIdentity() = runTest {
        for (url in listOf("http://not-local.invalid/v1", "https://user:password@example.invalid/v1", "https://example.invalid/v1?session=old")) {
            try { privateRoomRoute(ProviderSetting.OpenAI(baseUrl = url), "model", OkHttpClient(), true); fail("reject") }
            catch (_: IllegalStateException) { }
        }
    }
    private fun contract() = buildJsonObject {
        put("contract", "orbis-private-room/1"); put("stateless", true); put("client_tools_only", true)
        put("server_memory", false); put("chat_completions_path", "/v1/private-room/chat/completions")
        put("models", buildJsonArray { add("test-model") })
    }
    @Test fun strictContractRejectsMemoryCaptureOrCrossOriginPath() {
        requirePrivateRoomContract(contract(), "test-model")
        for (changed in listOf("server_memory" to JsonPrimitive(true), "stateless" to JsonPrimitive(false),
            "chat_completions_path" to JsonPrimitive("https://other.invalid/steal"))) {
            try { requirePrivateRoomContract(JsonObject(contract() + changed), "test-model"); fail("must reject") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun oldGatewayReceivesOnlyEmptyCapabilityProbeNeverPrivateMessages() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString(), apiKey = "synthetic")
            try { privateRoomRoute(provider, "test-model", privateRoomHttpClient(OkHttpClient())); fail("old gateway") }
            catch (_: IllegalStateException) { }
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/private-room/capabilities", request.path)
            assertEquals(0L, request.bodySize)
            assertNull(request.getHeader("X-ST-Thread-ID"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun supportedGatewayUsesSeparateRouteWithoutChangingOuterModelAlias() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(contract().toString()))
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString(), apiKey = "synthetic", useResponseApi = true)
            val route = privateRoomRoute(provider, "test-model", privateRoomHttpClient(OkHttpClient())) as ProviderSetting.OpenAI
            assertEquals("/private-room/chat/completions", route.chatCompletionsPath)
            assertFalse(route.useResponseApi)
            assertEquals(provider.baseUrl, route.baseUrl)
        }
    }
    @Test fun gatewayTrailingSlashIsNormalizedBeforePrivatePost() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(contract().toString()))
            val provider = ProviderSetting.OpenAI(baseUrl = server.url("/v1/").toString(), apiKey = "synthetic")
            val route = privateRoomRoute(provider, "test-model", privateRoomHttpClient(OkHttpClient())) as ProviderSetting.OpenAI
            assertEquals(server.url("/v1").toString(), route.baseUrl)
        }
    }
}
