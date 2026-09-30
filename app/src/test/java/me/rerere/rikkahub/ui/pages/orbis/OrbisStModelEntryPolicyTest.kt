package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class OrbisStModelEntryPolicyTest {
    @Test fun choicesAreEnabledCompatibleConnectionsWithoutInferringStFromName() {
        val ordinary = ProviderSetting.OpenAI(name = "普通兼容服务")
        val disabled = ProviderSetting.OpenAI(name = "ST disabled", enabled = false)
        val result = orbisStModelEntryChoices(listOf(ordinary, disabled, ProviderSetting.Google(name = "ST named")))
        assertEquals(listOf(ordinary.id.toString()), result.map { it.providerId })
    }

    @Test fun duplicateProviderIdentityIsRejectedInsteadOfSelectingFirst() {
        val provider = ProviderSetting.OpenAI(name = "first")
        val duplicate = provider.copy(name = "second")
        assertTrue(orbisStModelEntryChoices(listOf(provider, duplicate)).isEmpty())
        assertTrue(orbisStModelEntryChoices(listOf(provider, ProviderSetting.Google(id = provider.id))).isEmpty())
    }

    @Test fun entryMetadataDoesNotIncludeCredentialEndpointOrModelBody() {
        val provider = ProviderSetting.OpenAI(name = "连接", apiKey = "SECRET_KEY_SENTINEL",
            baseUrl = "https://private-endpoint.invalid/private", models = listOf(Model(modelId = "PRIVATE_MODEL_SENTINEL")))
        val original = provider.copy()
        val result = orbisStModelEntryChoices(listOf(provider))
        assertEquals(1, result.single().configuredModels)
        assertFalse(result.toString().contains("SECRET_KEY_SENTINEL"))
        assertFalse(result.toString().contains("private-endpoint"))
        assertFalse(result.toString().contains("PRIVATE_MODEL_SENTINEL"))
        assertEquals(original, provider)
    }

    @Test fun onlyExplicitExistingSelectionResolvesAndDoesNotChooseDefault() {
        val first = ProviderSetting.OpenAI()
        val second = ProviderSetting.OpenAI()
        val choices = orbisStModelEntryChoices(listOf(first, second))
        assertNull(orbisStModelEntryTarget(choices, null))
        assertNull(orbisStModelEntryTarget(choices, "unknown"))
        assertEquals(second.id.toString(), orbisStModelEntryTarget(choices, second.id.toString()))
        assertNull(orbisStModelEntryTarget(choices.take(1), second.id.toString()))
    }

    @Test fun labelsAreBoundedAndDoNotContainControlCharacters() {
        val choice = orbisStModelEntryChoices(listOf(ProviderSetting.OpenAI(name = " \n\t" + "长".repeat(500)))).single()
        assertTrue(choice.name.length <= 80)
        assertTrue(choice.name.none { it.isISOControl() })
    }

    @Test fun guidanceSeparatesCatalogRefreshFromSelectionAndServerRouteConfiguration() {
        assertTrue(ORBIS_ST_MODEL_DIRECTORY_NOTE.contains("不会自动更改"))
        assertTrue(ORBIS_ST_MODEL_DIRECTORY_NOTE.contains("当前所选 AI"))
        assertTrue(ORBIS_ST_MODEL_ROUTE_NOTE.contains("服务端已配置"))
        assertTrue(ORBIS_ST_MODEL_ROUTE_NOTE.contains("不会创建新线路"))
        assertTrue(ORBIS_ST_MODEL_ROUTE_NOTE.contains("--auxiliary-no-memory"))
    }
}
