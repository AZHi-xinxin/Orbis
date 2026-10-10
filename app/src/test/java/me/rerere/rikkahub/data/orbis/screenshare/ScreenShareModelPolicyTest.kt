package me.rerere.rikkahub.data.orbis.screenshare

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ScreenShareModelPolicyTest {
    private val model = Model(modelId = "synthetic-vision")
    @Test fun unknownGatewayCannotFallBackToMainConversationModel() {
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:18000/v1", models = listOf(model))
        try { selectScreenShareHelperModel(model, listOf(provider)); fail("No authority for helper lane") }
        catch (_: ScreenShareHelperUnavailable) { }
    }
    @Test fun exactSameProviderAuxiliaryAliasIsUsed() {
        val alias = Model(modelId = model.modelId + "--auxiliary-no-memory")
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:18000/v1", models = listOf(model, alias))
        assertEquals(alias, selectScreenShareHelperModel(model, listOf(provider)))
    }
    @Test fun officialProviderCanUseIsolatedToolFreeRequest() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", models = listOf(model))
        assertEquals(model, selectScreenShareHelperModel(model, listOf(provider)))
    }
    @Test fun LookalikeEndpointCannotClaimOfficialProvider() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com.example.test/v1", models = listOf(model))
        try { selectScreenShareHelperModel(model, listOf(provider)); fail("Not official host") }
        catch (_: ScreenShareHelperUnavailable) { }
    }
}
