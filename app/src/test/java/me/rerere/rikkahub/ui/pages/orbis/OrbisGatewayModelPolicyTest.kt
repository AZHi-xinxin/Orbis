package me.rerere.rikkahub.ui.pages.orbis

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisGatewayModelPolicyTest {
    private class Fixture {
        val old = Model(modelId = "route-old")
        val otherModel = Model(modelId = "other-model")
        val provider = ProviderSetting.OpenAI(name = "网关", models = listOf(old), apiKey = "SYNTHETIC_SECRET")
        val otherProvider = ProviderSetting.OpenAI(name = "其他", models = listOf(otherModel))
        val assistant = Assistant(name = "当前 AI", chatModelId = old.id, systemPrompt = "identity", enableMemory = true)
        val other = Assistant(name = "其他 AI", chatModelId = old.id)
        val settings = Settings(assistantId = assistant.id, chatModelId = otherModel.id,
            assistants = listOf(assistant, other), providers = listOf(provider, otherProvider))
        fun binding() = OrbisGatewayBinding(assistant, provider, listOf("route-old", "route-new"))
        fun preferences() = mutablePreferencesOf(
            SettingsStore.SELECT_ASSISTANT to assistant.id.toString(),
            SettingsStore.ASSISTANTS to JsonInstant.encodeToString(settings.assistants),
            SettingsStore.PROVIDERS to JsonInstant.encodeToString(settings.providers),
            stringPreferencesKey("unrelated_sentinel") to "KEEP_BYTES_UNCHANGED",
        )
    }
    private fun code(expected: String, action: () -> Unit) {
        val failure = assertThrows(OrbisGatewayModelException::class.java) { action() }
        assertEquals(expected, failure.code)
        assertFalse(failure.message.orEmpty().contains("SYNTHETIC_SECRET"))
    }
    @Test fun catalogKeepsOnlyPublishedMainAliasesAndDoesNotChooseOne() {
        val aliases = parseOrbisGatewayDirectory("""{"data":[
            {"id":"route-z","owned_by":"stiller-gateway"},
            {"id":"route-a","owned_by":"stiller-gateway"},
            {"id":"route-a--auxiliary-no-memory","owned_by":"stiller-auxiliary-no-memory"},
            {"id":"ordinary","owned_by":"other"}]}""")
        assertEquals(listOf("route-a", "route-z"), aliases)
    }
    @Test fun duplicateMalformedOversizeAndOrdinaryDirectoriesReject() {
        code("not_gateway") { parseOrbisGatewayDirectory("""{"data":[{"id":"x","owned_by":"ordinary"}]}""") }
        code("invalid_directory") { parseOrbisGatewayDirectory("not json") }
        code("invalid_directory") { parseOrbisGatewayDirectory(" ".repeat(GATEWAY_MODELS_MAX_BYTES + 1)) }
        code("invalid_directory") { parseOrbisGatewayDirectory("""{"data":[{"id":"x","owned_by":"stiller-gateway"},{"id":"x","owned_by":"stiller-gateway"}]}""") }
        code("invalid_directory") { parseOrbisGatewayDirectory("""{"data":[{"id":"x\nsecret","owned_by":"stiller-gateway"}]}""") }
    }
    @Test fun bindingNewAliasChangesOnlyNamedAssistantAndAddsOneLocalModel() {
        val f = Fixture(); val result = f.binding().apply(f.settings, "route-new")
        val provider = result.providers.single { it.id == f.provider.id }
        assertEquals(2, provider.models.size)
        assertEquals("route-new", provider.models.last().modelId)
        assertEquals(provider.models.last().id, result.assistants.first().chatModelId)
        assertEquals(f.assistant.copy(chatModelId = provider.models.last().id), result.assistants.first())
        assertEquals(f.other, result.assistants.last())
        assertEquals(f.otherProvider, result.providers.last())
        assertEquals(f.settings.chatModelId, result.chatModelId)
        assertEquals(f.provider, f.settings.providers.first())
    }
    @Test fun existingAliasReusesLocalIdWithoutChangingProviderOrOtherBindings() {
        val f = Fixture(); val next = f.binding().apply(f.settings, "route-old")
        assertEquals(f.settings, next)
    }
    @Test fun unknownAndAuxiliaryAliasNeverFallsBack() {
        val f = Fixture()
        code("changed") { f.binding().apply(f.settings, "missing") }
        code("changed") { f.binding().apply(f.settings, "route-old--auxiliary-no-memory") }
    }
    @Test fun selectedAssistantAndProviderRevisionChangesRejectBeforeMutation() {
        val f = Fixture(); val binding = f.binding()
        code("changed") { binding.apply(f.settings.copy(assistantId = f.other.id), "route-new") }
        code("changed") { binding.apply(f.settings.copy(assistants = listOf(f.assistant.copy(systemPrompt = "new identity"), f.other)), "route-new") }
        code("changed") { binding.apply(f.settings.copy(providers = listOf(f.provider.copy(apiKey = "CHANGED_SYNTHETIC_SECRET"), f.otherProvider)), "route-new") }
        code("changed") { binding.apply(f.settings.copy(providers = listOf(f.provider.copy(enabled = false), f.otherProvider)), "route-new") }
    }
    @Test fun persistedCompareAndSwapPreservesUnrelatedKeysAndOtherAssistantByteValues() {
        val f = Fixture(); val preferences = f.preferences()
        val before = preferences.asMap().toMap()
        applyOrbisGatewayBinding(preferences, f.binding(), "route-new")
        assertEquals("KEEP_BYTES_UNCHANGED", preferences[stringPreferencesKey("unrelated_sentinel")])
        assertEquals(before[SettingsStore.SELECT_ASSISTANT], preferences[SettingsStore.SELECT_ASSISTANT])
        val assistants = JsonInstant.decodeFromString<List<Assistant>>(preferences[SettingsStore.ASSISTANTS]!!)
        assertEquals(f.other, assistants.last())
        assertEquals(before.keys, preferences.asMap().keys)
        val changed = preferences.asMap().filter { before[it.key] != it.value }.keys
        assertEquals(setOf(SettingsStore.ASSISTANTS, SettingsStore.PROVIDERS), changed)
    }
    @Test fun persistedRevisionChangedAfterUiReadRejectsWithoutAnyFieldWrite() {
        val f = Fixture(); val preferences = f.preferences()
        preferences[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(listOf(f.assistant.copy(chatModelId = f.otherModel.id), f.other))
        val before = preferences.asMap().toMap()
        code("changed") { applyOrbisGatewayBinding(preferences, f.binding(), "route-new") }
        assertEquals(before, preferences.asMap())
    }
    @Test fun persistedProviderChangedAfterDirectoryReadRejectsWithoutAnyFieldWrite() {
        val f = Fixture(); val preferences = f.preferences()
        preferences[SettingsStore.PROVIDERS] = JsonInstant.encodeToString<List<ProviderSetting>>(
            listOf(f.provider.copy(baseUrl = "https://changed.example/v1"), f.otherProvider))
        val before = preferences.asMap().toMap()
        code("changed") { applyOrbisGatewayBinding(preferences, f.binding(), "route-new") }
        assertEquals(before, preferences.asMap())
    }
    @Test fun persistedCurrentAssistantChangedCannotBindAnUnselectedAssistant() {
        val f = Fixture(); val preferences = f.preferences()
        preferences[SettingsStore.SELECT_ASSISTANT] = f.other.id.toString()
        val before = preferences.asMap().toMap()
        code("changed") { applyOrbisGatewayBinding(preferences, f.binding(), "route-new") }
        assertEquals(before, preferences.asMap())
    }
    @Test fun concurrentUnrelatedAssistantEditIsPreservedByPersistedMerge() {
        val f = Fixture(); val preferences = f.preferences()
        val changedOther = f.other.copy(name = "New other name", systemPrompt = "Other identity remains")
        preferences[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(listOf(f.assistant, changedOther))
        applyOrbisGatewayBinding(preferences, f.binding(), "route-new")
        val after = JsonInstant.decodeFromString<List<Assistant>>(preferences[SettingsStore.ASSISTANTS]!!)
        assertEquals(changedOther, after.last())
        assertEquals(f.assistant.copy(chatModelId = after.first().chatModelId), after.first())
    }
    @Test fun ambiguousIdentityAndRoutingOverridesRejectWithoutChoosingAnother() {
        val f = Fixture()
        code("changed") { orbisGatewayRevision(f.settings.copy(providers = listOf(f.provider, f.provider)), f.assistant.id, f.provider.id) }
        code("changed") { orbisGatewayRevision(f.settings, Uuid.random(), f.provider.id) }
        code("overridden") { orbisGatewayRevision(f.settings.copy(providers = listOf(f.provider.copy(useResponseApi = true))), f.assistant.id, f.provider.id) }
        val modified = f.assistant.copy(customBodies = listOf(CustomBody("model", JsonPrimitive("other-route"))))
        code("overridden") { OrbisGatewayBinding(modified, f.provider, listOf("route-new"))
            .apply(f.settings.copy(assistants = listOf(modified, f.other)), "route-new") }
    }
    @Test fun directoryUrlStaysOnExactExistingOriginAndRejectsEmbeddedCredentialOrQuery() {
        val url = orbisGatewayModelsUrl("https://gateway.example:8443/st/v1/")
        assertEquals("gateway.example", url.host); assertEquals(8443, url.port)
        assertEquals("/st/v1/models", url.encodedPath)
        assertEquals("http://100.64.0.1:8080/v1/models", orbisGatewayModelsUrl("http://100.64.0.1:8080/v1").toString())
        code("connection") { orbisGatewayModelsUrl("https://user:pass@gateway.example/v1") }
        code("connection") { orbisGatewayModelsUrl("https://gateway.example/v1?token=never-copy") }
        code("connection") { orbisGatewayModelsUrl("https://gateway.example/v1#fragment") }
    }
}
