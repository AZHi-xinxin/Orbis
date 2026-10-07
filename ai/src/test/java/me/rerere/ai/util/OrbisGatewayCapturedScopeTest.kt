package me.rerere.ai.util

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayCapturedScopeTest {
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/nested/v1", apiKey = "synthetic-key")
    private val model = Model(modelId = "synthetic-model")
    private val cid = "synthetic-conversation"
    private fun request(conversation: String = cid, wireModel: String = model.modelId,
        endpoint: String = provider.baseUrl + provider.chatCompletionsPath,
        auth: String = "Bearer synthetic-key", thread: String = "orbis:$cid") =
        OrbisGatewayRequest(conversation, "a".repeat(32), wireModel, endpoint.toHttpUrl(), auth, thread)

    @Test fun exactScopeCanMatchWithoutMintingAutomaticFinishPermission() {
        val captured = request()
        assertTrue(OrbisGatewayThreadControl.matchesRequestScope(captured, provider, model, cid))
        assertFalse(captured.supportsAutomaticFinish)
    }

    @Test fun pathCredentialsModelAndThreadMustAllMatchTheCapturedRequest() {
        for (captured in listOf(request(conversation = "other"), request(wireModel = "other"),
            request(endpoint = "https://synthetic.invalid/other/v1/chat/completions"),
            request(endpoint = "https://another.invalid/nested/v1/chat/completions"),
            request(auth = "Bearer other-synthetic-key"), request(thread = "orbis:other"))) {
            assertFalse(OrbisGatewayThreadControl.matchesRequestScope(captured, provider, model, cid))
        }
    }

    @Test fun ambiguousOrOverriddenCurrentScopeNeverMatches() {
        assertFalse(OrbisGatewayThreadControl.matchesRequestScope(request(), provider.copy(apiKey = "one,two"), model, cid))
        assertFalse(OrbisGatewayThreadControl.matchesRequestScope(request(), provider, model.copy(
            customHeaders = listOf(CustomHeader("X-ST-Thread-ID", "orbis:other"))), cid))
        assertFalse(OrbisGatewayThreadControl.matchesRequestScope(request(), provider, model.copy(
            customBodies = listOf(CustomBody("model", JsonPrimitive("other")))), cid))
        assertFalse(OrbisGatewayThreadControl.matchesRequestScope(request(), ProviderSetting.Google(), model, cid))
    }
}
