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
