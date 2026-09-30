package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderConnectionVisibilityTest {
    @Test fun untouchedPresetsAreHiddenWithoutChangingSavedData() {
        val stored = DEFAULT_PROVIDERS.toList()
        val before = stored.map { Json.encodeToJsonElement(ProviderSetting.serializer(), it) }
        assertTrue(orbisVisibleModelConnections(stored).isEmpty())
        assertEquals(DEFAULT_PROVIDERS.size, stored.size)
        assertEquals(before, stored.map { Json.encodeToJsonElement(ProviderSetting.serializer(), it) })
    }

    @Test fun customAndUnknownConnectionsRemainVisibleEvenWhenBlankOrDisabled() {
        val custom = ProviderSetting.OpenAI(name = "My connection", enabled = false, baseUrl = "")
        val unknownPreset = custom.copy(id = Uuid.random(), builtIn = true)
        assertEquals(listOf(custom, unknownPreset), orbisVisibleModelConnections(DEFAULT_PROVIDERS + custom + unknownPreset))
    }

    @Test fun everyConfiguredOpenAiPresetRemainsReachable() {
        val preset = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>().first()
        val edited = listOf(
            preset.copy(name = "Renamed"), preset.copy(enabled = !preset.enabled),
            preset.copy(apiKey = "synthetic-not-a-key"), preset.copy(baseUrl = "http://127.0.0.1:9999/v1"),
            preset.copy(chatCompletionsPath = "/different"), preset.copy(responsesPath = "/different"),
            preset.copy(useResponseApi = true), preset.copy(includeHistoryReasoning = false),
            preset.copy(balanceOption = preset.balanceOption.copy(apiPath = "/different")),
            preset.copy(models = listOf(Model(modelId = "synthetic-model"))),
        )
        edited.forEach { assertEquals(listOf(it), orbisVisibleModelConnections(listOf(it))) }
    }

    @Test fun googleServiceAccountAndProtocolChangesRemainReachable() {
        val preset = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Google>().first()
        val edited = listOf(
            preset.copy(vertexAI = true), preset.copy(useServiceAccount = true),
            preset.copy(privateKey = "synthetic-private-field"), preset.copy(serviceAccountEmail = "test@example.invalid"),
            preset.copy(projectId = "synthetic-project"), preset.copy(location = "different-region"),
        )
        edited.forEach { assertEquals(listOf(it), orbisVisibleModelConnections(listOf(it))) }
        val converted = ProviderSetting.Claude(id = preset.id)
        assertEquals(listOf(converted), orbisVisibleModelConnections(listOf(converted)))
    }

    @Test fun visibleConnectionsRetainOriginalOrderAndObjects() {
        val first = ProviderSetting.Claude(name = "First")
        val last = ProviderSetting.OpenAI(name = "Last")
        val result = orbisVisibleModelConnections(listOf(first) + DEFAULT_PROVIDERS + last)
        assertEquals(listOf(first, last), result)
        assertSame(first, result.first())
        assertSame(last, result.last())
    }

    @Test fun draftRequiresNameAndAddressButNotASecretForLocalServices() {
        assertFalse(ProviderSetting.OpenAI(name = "Custom", baseUrl = "").isValidModelConnectionDraft())
        assertFalse(ProviderSetting.Claude(name = " ").isValidModelConnectionDraft())
        assertFalse(ProviderSetting.OpenAI(baseUrl = "not-a-url").isValidModelConnectionDraft())
        assertTrue(ProviderSetting.OpenAI(apiKey = "", baseUrl = "http://127.0.0.1:9999/v1").isValidModelConnectionDraft())
        assertFalse(ProviderSetting.Google(vertexAI = true, projectId = "").isValidModelConnectionDraft())
        assertTrue(ProviderSetting.Google(vertexAI = true, projectId = "test").isValidModelConnectionDraft())
    }

    @Test fun editReplacesOnlyTargetInLatestSettingsAndPreservesOtherSelections() {
        val original = ProviderSetting.OpenAI(name = "Before")
        val unrelated = ProviderSetting.Claude(name = "Other connection", apiKey = "synthetic-other-key")
        val latest = Settings(providers = listOf(unrelated, original), titlePrompt = "New unrelated setting",
            favoriteModels = listOf(Uuid.random()), chatModelId = Uuid.random(), fastModelId = Uuid.random())
        val edited = original.copy(name = "After", apiKey = "synthetic-edited-key")
        val updated = latest.replaceModelConnection(edited)
        assertEquals(listOf(unrelated, edited), updated.providers)
        assertSame(unrelated, updated.providers.first())
        assertEquals(latest, updated.copy(providers = latest.providers))
        assertEquals("Before", original.name)
    }

    @Test fun editCannotRecreateDeletedConnectionOrOverwriteUnloadedSettings() {
        val edited = ProviderSetting.OpenAI(name = "Deleted meanwhile")
        assertThrows(IllegalStateException::class.java) {
            Settings(providers = emptyList()).replaceModelConnection(edited)
        }
        assertThrows(IllegalStateException::class.java) {
            Settings(init = true, providers = listOf(edited)).replaceModelConnection(edited)
        }
    }
}
