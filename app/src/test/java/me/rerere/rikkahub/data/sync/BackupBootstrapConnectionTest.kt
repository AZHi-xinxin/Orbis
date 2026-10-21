package me.rerere.rikkahub.data.sync

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class BackupBootstrapConnectionTest {
    private fun settings(provider: ProviderSetting, model: Model = provider.models.single()): Settings {
        val assistant = Assistant(chatModelId = model.id)
        return Settings(init = false, providers = listOf(provider), chatModelId = model.id,
            assistants = listOf(assistant), assistantId = assistant.id)
    }
    private fun source(url: String = "https://bootstrap.invalid/v1", key: String = "synthetic-bootstrap") =
        settings(ProviderSetting.OpenAI(apiKey = key, baseUrl = url, models = listOf(Model(modelId = "bootstrap-model"))))
    private fun archive(key: String = "") =
        settings(ProviderSetting.OpenAI(apiKey = key, baseUrl = "https://archive.invalid/v1",
            models = listOf(Model(modelId = "archive-model"))))

    @Test fun strippedArchiveReceivesIndependentConnectionWithoutTransplantingKeys() {
        val old = archive()
        val current = source()
        val merged = BackupBootstrapConnection.preserve(old, current)
        assertEquals(old.providers.first(), merged.providers.first())
        val active = merged.findModelById(merged.chatModelId)!!
        val connection = active.findProvider(merged.providers) as ProviderSetting.OpenAI
        assertEquals("https://bootstrap.invalid/v1", connection.baseUrl)
        assertEquals("synthetic-bootstrap", connection.apiKey)
        assertEquals(active.id, merged.assistants.single().chatModelId)
        assertEquals("", (merged.providers.first() as ProviderSetting.OpenAI).apiKey)
    }

    @Test fun collidingIdsAreReassignedAndRepeatedMergeIsIdempotent() {
        val current = source()
        val sourceProvider = current.providers.single() as ProviderSetting.OpenAI
        val old = settings(sourceProvider.copy(apiKey = "", baseUrl = "https://archive.invalid/v1",
            models = listOf(sourceProvider.models.single().copy(modelId = "archive-model"))))
        val merged = BackupBootstrapConnection.preserve(old, current)
        assertEquals(2, merged.providers.map { it.id }.toSet().size)
        assertEquals(2, merged.providers.flatMap { it.models }.map { it.id }.toSet().size)
        assertNotEquals(old.chatModelId, merged.chatModelId)
        assertEquals(merged, BackupBootstrapConnection.preserve(merged, current))
    }

    @Test fun effectiveOverrideCarriesItsOwnOriginCredentialsAndProtocolOptions() {
        val effective = ProviderSetting.OpenAI(apiKey = "override-only", baseUrl = "https://override.invalid/api",
            useResponseApi = true, responsesPath = "/custom-responses")
        val model = Model(modelId = "override-model", providerOverwrite = effective)
        val current = settings(ProviderSetting.OpenAI(apiKey = "parent-only", baseUrl = "https://parent.invalid/v1",
            models = listOf(model)))
        val merged = BackupBootstrapConnection.preserve(archive(), current)
        val retained = merged.findModelById(merged.chatModelId)!!
        val provider = retained.findProvider(merged.providers) as ProviderSetting.OpenAI
        assertNull(retained.providerOverwrite)
        assertEquals(effective.baseUrl, provider.baseUrl)
        assertEquals(effective.apiKey, provider.apiKey)
        assertTrue(provider.useResponseApi)
        assertEquals("/custom-responses", provider.responsesPath)
        assertEquals(model.modelId, retained.modelId)
    }

    @Test fun completeArchiveKeepsItsDefaultAssistantAndConnection() {
        val old = archive("archive-own-key")
        val merged = BackupBootstrapConnection.preserve(old, source())
        assertEquals(old.chatModelId, merged.chatModelId)
        assertEquals(old.assistants, merged.assistants)
        assertEquals(old.providers.first(), merged.providers.first())
    }

    @Test fun absentUninitializedEmptyOrInvalidBootstrapDoesNotAlterArchive() {
        val old = archive()
        for (current in listOf(null, source().copy(init = true), source(key = ""),
            source(url = "file:///private"), source().copy(providers = emptyList()))) {
            assertEquals(old, BackupBootstrapConnection.preserve(old, current))
        }
    }

    @Test fun sourceLocalKeylessIsRetainedButAnUnrelatedArchiveLocalIsNotAssumedReady() {
        val local = source("http://127.0.0.1:11434/v1", "")
        val merged = BackupBootstrapConnection.preserve(archive(), local)
        assertEquals(local.chatModelId, merged.chatModelId)
        val restoredLocal = source("http://192.168.1.8:8080/v1", "")
        val kept = BackupBootstrapConnection.preserve(restoredLocal, source())
        assertNotEquals(restoredLocal.chatModelId, kept.chatModelId)
        assertEquals(restoredLocal.providers.first(), kept.providers.first())
        assertEquals(local, BackupBootstrapConnection.preserve(local, local))
        assertEquals(merged, BackupBootstrapConnection.preserve(merged, local))
    }

    @Test fun googleServiceAccountIsCarriedWholeAndIncompleteOneIsRejected() {
        val model = Model(modelId = "gemini-test")
        val provider = ProviderSetting.Google(vertexAI = true, useServiceAccount = true,
            privateKey = "synthetic-private-key", serviceAccountEmail = "synthetic@example.invalid",
            projectId = "synthetic-project", models = listOf(model))
        val old = archive()
        val merged = BackupBootstrapConnection.preserve(old, settings(provider))
        val carried = merged.findModelById(merged.chatModelId)!!.findProvider(merged.providers) as ProviderSetting.Google
        assertEquals(provider.privateKey, carried.privateKey)
        assertEquals(provider.serviceAccountEmail, carried.serviceAccountEmail)
        assertEquals(old, BackupBootstrapConnection.preserve(old, settings(provider.copy(privateKey = ""))))
    }

    @Test fun assistantHeaderCredentialsDoNotMoveToAnotherOrigin() {
        val old = archive().let { it.copy(assistants = it.assistants.map { assistant ->
            assistant.copy(chatModelId = null, customHeaders = listOf(CustomHeader("Authorization", "Bearer archive-only")))
        }) }
        val merged = BackupBootstrapConnection.preserve(old, source())
        assertEquals(old.chatModelId, merged.assistants.single().chatModelId)
        assertNotEquals(old.chatModelId, merged.chatModelId)
        assertEquals(old.providers.first(), merged.providers.first())
    }

    @Test fun unknownOldOriginHeadersAndBodiesAreNeverAttachedToBootstrap() {
        val old = archive().let { it.copy(assistants = it.assistants.map { assistant ->
            assistant.copy(customHeaders = listOf(CustomHeader("X-Custom-Key", "old-origin-only")),
                customBodies = listOf(CustomBody("access_token", JsonPrimitive("old-body-secret"))))
        }) }
        val merged = BackupBootstrapConnection.preserve(old, source())
        assertNotEquals(old.chatModelId, merged.assistants.single().chatModelId)
        assertTrue(merged.assistants.single().customHeaders.isEmpty())
        assertTrue(merged.assistants.single().customBodies.isEmpty())
        val active = merged.findModelById(merged.assistants.single().chatModelId)!!
        assertTrue(active.customHeaders.isEmpty())
        assertTrue(active.customBodies.isEmpty())
        assertEquals(old.providers.first(), merged.providers.first())
    }

    @Test fun sourceAssistantThenModelOrderIsPreservedAndDoesNotDuplicateOnRetry() {
        val assistantHeader = CustomHeader("X-Synthetic", "assistant-value")
        val modelHeader = CustomHeader("X-Synthetic", "model-value")
        val assistantBody = CustomBody("synthetic_option", JsonPrimitive("assistant-value"))
        val modelBody = CustomBody("synthetic_option", JsonPrimitive("model-value"))
        val current = source().let { original ->
            val provider = original.providers.single() as ProviderSetting.OpenAI
            original.copy(providers = listOf(provider.copy(models = listOf(provider.models.single().copy(
                customHeaders = listOf(modelHeader), customBodies = listOf(modelBody))))),
                assistants = original.assistants.map { it.copy(customHeaders = listOf(assistantHeader),
                    customBodies = listOf(assistantBody)) })
        }
        val merged = BackupBootstrapConnection.preserve(archive(), current)
        val active = merged.findModelById(merged.chatModelId)!!
        assertEquals(listOf(assistantHeader, modelHeader), active.customHeaders)
        assertEquals(listOf(assistantBody, modelBody), active.customBodies)
        assertTrue(merged.assistants.single().customHeaders.isEmpty())
        assertTrue(merged.assistants.single().customBodies.isEmpty())
        assertEquals(merged, BackupBootstrapConnection.preserve(merged, current))
    }

    @Test fun bootstrapNetworkRouteAndCredentialsAreCarriedTogether() {
        val old = archive().copy(networkSetting = NetworkSetting(
            userAgent = "old-agent", proxyUrl = "http://archive-proxy.invalid:8080",
            proxyUsername = "old-user", proxyPassword = "", enableAutoRetry = true))
        val current = source().copy(networkSetting = NetworkSetting(
            userAgent = "new-agent", proxyUrl = "http://bootstrap-proxy.invalid:9090",
            proxyUsername = "synthetic-user", proxyPassword = "synthetic-password", enableAutoRetry = false))
        val merged = BackupBootstrapConnection.preserve(old, current)
        assertEquals(current.networkSetting, merged.networkSetting)
        assertNotEquals(old.networkSetting.proxyUrl, merged.networkSetting.proxyUrl)
        assertEquals(old.providers.first(), merged.providers.first())
        assertEquals(merged, BackupBootstrapConnection.preserve(merged, current))
    }

    @Test fun directBootstrapDoesNotInheritAnArchivedProxy() {
        val old = archive().copy(networkSetting = NetworkSetting(
            proxyUrl = "http://archive-proxy.invalid:8080", proxyUsername = "old-user",
            proxyPassword = "old-password"))
        val current = source()
        val merged = BackupBootstrapConnection.preserve(old, current)
        assertEquals(current.networkSetting, merged.networkSetting)
        assertEquals("", merged.networkSetting.proxyUrl)
        assertEquals("", merged.networkSetting.proxyUsername)
        assertEquals("", merged.networkSetting.proxyPassword)
    }

    @Test fun restoreWithoutBootstrapKeepsArchiveNetworkUnchanged() {
        val old = archive().copy(networkSetting = NetworkSetting(
            userAgent = "archive-agent", proxyUrl = "http://archive-proxy.invalid:8080",
            proxyUsername = "archive-user", proxyPassword = "archive-password", enableAutoRetry = false))
        assertEquals(old, BackupBootstrapConnection.preserve(old, null))
        assertEquals(old, BackupBootstrapConnection.preserve(old, source().copy(init = true)))
    }
}
