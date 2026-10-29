package me.rerere.rikkahub.web

import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.toWebSettingsDto
import org.junit.Assert.*
import org.junit.Test

class WebSettingsDtoTest {
    @Test fun boundaryProjectionScopesDisabledAndHiddenIdsWithoutExportingPauseReasonOrAudit() {
        val owner = Assistant(); val other = Assistant()
        val model = Model(modelId = "synthetic")
        val choice = me.rerere.rikkahub.data.orbis.boundary.AssistantBoundary(
            grants = setOf("model", "mcp"),
            models = mapOf(model.id.toString() to me.rerere.rikkahub.data.orbis.boundary.BoundaryChoice(false, true)),
            mcps = mapOf("mcp-visible" to me.rerere.rikkahub.data.orbis.boundary.BoundaryChoice(false, false)),
            modelOverride = model.id.toString(),
            conversations = mapOf("private-chat" to me.rerere.rikkahub.data.orbis.boundary.ConversationBoundary(reason="private-reason")))
        val settings = Settings(assistants = listOf(owner, other))
        val dto = settings.toWebSettingsDto(mapOf(owner.id.toString() to choice))
        assertEquals(listOf(model.id.toString()),dto.boundaryChoices.getValue(owner.id.toString()).hiddenModels)
        assertEquals(emptyList<String>(),dto.boundaryChoices.getValue(owner.id.toString()).hiddenMcp)
        assertEquals(listOf("mcp-visible"),dto.boundaryChoices.getValue(owner.id.toString()).disabledMcp)
        assertFalse(dto.boundaryChoices.containsKey(other.id.toString()))
        assertEquals(model.id.toString(),dto.assistants.first().chatModelId)
        assertFalse(JsonInstant.encodeToString(dto).contains("private-reason"))
        assertFalse(JsonInstant.encodeToString(dto).contains("private-chat"))
        assertNull(settings.assistants.first().chatModelId)
        val off=settings.toWebSettingsDto(mapOf(owner.id.toString() to choice.copy(grants=emptySet())))
        assertTrue(off.boundaryChoices.getValue(owner.id.toString()).disabledModels.isEmpty())
        assertNull(off.assistants.first().chatModelId)
    }

    @Test fun credentialsAndPrivatePromptGraphNeverLeaveNativeSettings() {
        val secret = "synthetic-private-canary"
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(apiKey = secret, models = listOf(
                Model(providerOverwrite = ProviderSetting.OpenAI(apiKey = secret)),
            ))),
            assistants = listOf(Assistant(name = "Visible assistant", systemPrompt = secret)),
            mcpServers = listOf(McpServerConfig.SseTransportServer(url = secret,
                commonOptions = McpCommonOptions(name = "Visible tools", headers = listOf("Authorization" to secret),
                    oauth = McpOAuthState(accessToken = secret, refreshToken = secret, clientSecret = secret)))),
            webDavConfig = WebDavConfig(password = secret),
            webServerAccessPassword = secret,
        )
        val payload = JsonInstant.encodeToString(settings.toWebSettingsDto())
        assertFalse(payload.contains(secret))
        for (key in listOf("apiKey", "providerOverwrite", "customHeaders", "oauth", "webServerAccessPassword", "webDavConfig", "s3Config", "systemPrompt")) {
            assertFalse("Private field must not be present: $key", payload.contains("\"$key\""))
        }
        assertTrue(payload.contains("Visible assistant"))
        assertTrue(payload.contains("Visible tools"))
    }
    @Test fun importCapabilityRequiresConfiguredAuthentication() {
        assertTrue(Settings().toWebSettingsDto().webImportEnabled)
        assertFalse(Settings(webServerJwtEnabled = true).toWebSettingsDto().webImportEnabled)
        assertTrue(Settings(webServerJwtEnabled = true, webServerAccessPassword = "synthetic").toWebSettingsDto().webImportEnabled)
    }
    @Test fun projectionDoesNotMutateNativeSettings() {
        val settings = Settings(webServerAccessPassword = "synthetic")
        val before = JsonInstant.encodeToString(settings)
        settings.toWebSettingsDto()
        assertEquals(before, JsonInstant.encodeToString(settings))
    }
}
