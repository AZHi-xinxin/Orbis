package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ReasoningSelectionPolicyTest {
    private val plan = ProviderSetting.OpenAI(chatGptAccountId = "local-test-account")
    private val api = ProviderSetting.OpenAI()

    @Test fun restrictedLegacyOffShowsActualLowAndAnExplanationWithoutMutatingModel() {
        for (slug in listOf("gpt-6.1-sol", "gpt-6-astra")) {
            val model = Model(modelId = slug)
            val selection = reasoningSelectionPolicy(ReasoningLevel.OFF, model, plan)
            assertEquals(ReasoningLevel.LOW, selection.effectiveLevel)
            assertFalse(ReasoningLevel.OFF in selection.levels)
            assertTrue(ReasoningLevel.AUTO in selection.levels)
            assertTrue(selection.notice.orEmpty().contains("本次按最低的低档执行"))
            assertTrue(model.abilities.isEmpty())
        }
    }

    @Test fun otherFiveModelsAllowOffAndAllModelsRetainAuto() {
        for (slug in listOf("gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            val selection = reasoningSelectionPolicy(ReasoningLevel.OFF, Model(modelId = slug), plan)
            assertEquals(ReasoningLevel.OFF, selection.effectiveLevel)
            assertEquals(ReasoningLevel.entries.toList(), selection.levels)
        }
        for (slug in listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            val selection = reasoningSelectionPolicy(ReasoningLevel.AUTO, Model(modelId = slug), plan)
            assertEquals(ReasoningLevel.AUTO, selection.effectiveLevel)
            assertTrue(selection.notice.orEmpty().contains("自动由模型决定"))
        }
    }

    @Test fun ordinaryApiAndUnknownModelsKeepExistingUiBehavior() {
        for ((model, provider) in listOf(
            Model(modelId = "gpt-6.1-sol") to api,
            Model(modelId = "gpt-6.1-sol-future") to plan,
            Model(modelId = "gpt-6.1-sol", providerOverwrite = api) to plan,
            null to plan,
        )) {
            for (level in ReasoningLevel.entries) {
                val selection = reasoningSelectionPolicy(level, model, provider)
                assertEquals(level, selection.effectiveLevel)
                assertEquals(ReasoningLevel.entries.toList(), selection.levels)
                assertNull(selection.notice)
            }
        }
    }

    @Test fun planOverrideUsesActualConnectionEvenWhenParentIsOrdinaryApi() {
        val selection = reasoningSelectionPolicy(ReasoningLevel.OFF,
            Model(modelId = "gpt-6.1-sol", providerOverwrite = plan), api)
        assertEquals(ReasoningLevel.LOW, selection.effectiveLevel)
        assertFalse(ReasoningLevel.OFF in selection.levels)
    }
}
