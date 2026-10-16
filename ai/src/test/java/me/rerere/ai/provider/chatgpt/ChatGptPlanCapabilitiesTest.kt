package me.rerere.ai.provider.chatgpt

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ChatGptPlanCapabilitiesTest {
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-test-account")
    private val api = ProviderSetting.OpenAI()

    @Test fun onlySupportedNativeSearchCanBeEnabledForPlan() {
        assertTrue(ChatGptPlanCapabilities.supportsBuiltInTool(BuiltInTools.Search))
        assertFalse(ChatGptPlanCapabilities.supportsBuiltInTool(BuiltInTools.ImageGeneration))
        assertFalse(ChatGptPlanCapabilities.supportsBuiltInTool(BuiltInTools.UrlContext))
    }

    @Test fun requestProjectionDoesNotMutateOrEraseSavedModelSettings() {
        val originalTools = linkedSetOf(BuiltInTools.Search, BuiltInTools.ImageGeneration, BuiltInTools.UrlContext)
        val model = Model(
            modelId = "gpt-6.1-sol",
            displayName = "My unchanged model",
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            providerOverwrite = plan,
            tools = originalTools,
        )
        val projected = ChatGptPlanCapabilities.projectModelForRequest(model)

        assertEquals(model.copy(tools = setOf(BuiltInTools.Search)), projected)
        assertSame(originalTools, model.tools)
        assertEquals(setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration, BuiltInTools.UrlContext), model.tools)
        assertSame(plan, projected.providerOverwrite)
        assertEquals(projected, ChatGptPlanCapabilities.projectModelForRequest(projected))
    }

    @Test fun effectiveProviderOverrideDeterminesSettingsUiCapabilities() {
        assertTrue(ChatGptPlanCapabilities.usesPlan(Model(), plan))
        assertTrue(ChatGptPlanCapabilities.usesPlan(Model(providerOverwrite = plan), api))
        assertFalse(ChatGptPlanCapabilities.usesPlan(Model(providerOverwrite = api), plan))
        assertFalse(ChatGptPlanCapabilities.usesPlan(Model(), api))
    }

    @Test fun modelOrProviderDisplayNameIsNotAnAccountConnection() {
        val model = Model(modelId = "gpt-6.1-sol", displayName = "ChatGPT 套餐")
        assertFalse(ChatGptPlanCapabilities.usesPlan(model, ProviderSetting.OpenAI(name = "ChatGPT 套餐")))
        assertFalse(ChatGptPlanCapabilities.usesPlan(model, null))
        assertTrue(ChatGptPlanCapabilities.usesPlan(model, plan.copy(name = "Custom label")))
    }
}
