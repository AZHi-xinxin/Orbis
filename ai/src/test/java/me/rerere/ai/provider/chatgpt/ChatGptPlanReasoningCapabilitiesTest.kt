package me.rerere.ai.provider.chatgpt

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ChatGptPlanReasoningCapabilitiesTest {
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-test-account")
    private val api = ProviderSetting.OpenAI()
    private val restricted = setOf("gpt-6.1-sol", "gpt-6-astra")
    private val known = restricted + setOf("gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")

    @Test fun exactSevenModelMatrixIncludesAutoButNeverUnsupportedOff() {
        for (slug in known) {
            val expected = ReasoningLevel.entries.filter { it != ReasoningLevel.OFF || slug !in restricted }
            assertEquals(slug, expected, ChatGptPlanCapabilities.reasoningLevels(slug))
            for (requested in ReasoningLevel.entries) {
                val actual = ChatGptPlanCapabilities.reasoningLevelForRequest(slug, requested)
                val expectedLevel = if (slug in restricted && requested == ReasoningLevel.OFF) ReasoningLevel.LOW else requested
                assertEquals("$slug $requested", expectedLevel, actual)
                assertTrue(actual in expected)
            }
        }
    }

    @Test fun importAndLegacyProjectionRecoverReasoningWithoutMutatingSettings() {
        for (slug in known) {
            val original = Model(modelId = slug, abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search, BuiltInTools.ImageGeneration))
            val imported = ChatGptPlanCapabilities.withKnownModelAbilities(original)
            assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), imported.abilities)
            assertEquals(original.tools, imported.tools)
            assertEquals(original.id, imported.id)
            val request = ChatGptPlanCapabilities.projectModelForRequest(original)
            assertEquals(imported.abilities, request.abilities)
            assertEquals(setOf(BuiltInTools.Search), request.tools)
            assertEquals(listOf(ModelAbility.TOOL), original.abilities)
            assertTrue(BuiltInTools.ImageGeneration in original.tools)
            assertTrue(ChatGptPlanCapabilities.supportsReasoning(original, plan))
            assertEquals(imported, ChatGptPlanCapabilities.withKnownModelAbilities(imported))
        }
    }

    @Test fun unknownSlugsAreNotGuessedFromNamesOrPrefixes() {
        for (slug in listOf("gpt-6.1-sol-future", "GPT-6.1-Sol", "another-model", "")) {
            val original = Model(modelId = slug, displayName = "GPT-6.1-Sol", abilities = listOf(ModelAbility.TOOL))
            assertNull(ChatGptPlanCapabilities.reasoningLevels(slug))
            assertSame(original, ChatGptPlanCapabilities.withKnownModelAbilities(original))
            assertFalse(ChatGptPlanCapabilities.supportsReasoning(original, plan))
            for (requested in ReasoningLevel.entries) {
                assertEquals(requested, ChatGptPlanCapabilities.reasoningLevelForRequest(slug, requested))
            }
        }
    }

    @Test fun ordinaryApiAndProviderOverridesKeepTheirOwnCapabilities() {
        val legacy = Model(modelId = "gpt-6.1-sol")
        assertNull(ChatGptPlanCapabilities.reasoningLevels(legacy, api))
        assertFalse(ChatGptPlanCapabilities.supportsReasoning(legacy, api))
        assertTrue(ChatGptPlanCapabilities.supportsReasoning(legacy.copy(abilities = listOf(ModelAbility.REASONING)), api))
        assertNull(ChatGptPlanCapabilities.reasoningLevels(legacy.copy(providerOverwrite = api), plan))
        assertNotNull(ChatGptPlanCapabilities.reasoningLevels(legacy.copy(providerOverwrite = plan), api))
        assertNull(ChatGptPlanCapabilities.reasoningLevels(legacy, null))
    }
}
