package me.rerere.rikkahub.data.orbis.diagnostics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisSelfCheckTool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.boundary.*
import org.junit.Assert.*
import org.junit.Test

class SelfCheckTest {
    private val model = Model(modelId = "synthetic")
    private val provider = ProviderSetting.OpenAI(apiKey = "SECRET_SHOULD_NOT_APPEAR", baseUrl = "https://user:SECRET@private.example", models = listOf(model))
    private val a = Assistant(chatModelId = model.id, systemPrompt = "PRIVATE_PROMPT", name = "PRIVATE_NAME")
    private val settings = Settings(init = false, providers = listOf(provider), assistants = listOf(a))
    @Test fun `checks real owner opt in and model capabilities without returning secrets`() {
        assertFalse(LocalToolOption.SelfCheck in a.localTools)
        val result = selfCheckConfiguration(settings, a.id.toString(), "tools", AssistantBoundary())
        assertEquals("blocked", result.single { it.code == "model_tools" }.status)
        assertTrue(result.single { it.code == "tool_orbis_theme" }.evidence.contains("尚未"))
        val text = Json.encodeToString(result)
        listOf("SECRET", "private.example", "PRIVATE_PROMPT", "PRIVATE_NAME").forEach { assertFalse(text.contains(it)) }
        assertEquals(settings, settings.copy())
    }
    @Test fun `human grant and AI own disabling are distinct and assistant scoped`() {
        val own = a.copy(localTools = listOf(LocalToolOption.ThemeDesign))
        val other = Assistant(localTools = emptyList())
        val snapshot = settings.copy(assistants = listOf(own, other))
        val boundary = AssistantBoundary(grants = setOf("tool"), tools = mapOf("orbis_theme" to BoundaryChoice(false)))
        val item = selfCheckConfiguration(snapshot, own.id.toString(), "theme", boundary).single()
        assertEquals("ai_with_existing_tools", item.repairBy)
        assertTrue(item.evidence.contains("自行停用"))
        assertTrue(selfCheckConfiguration(snapshot, other.id.toString(), "theme", AssistantBoundary()).single().evidence.contains("尚未"))
        assertThrows(IllegalStateException::class.java) { selfCheckConfiguration(snapshot, "missing", "theme", boundary) }
    }
    @Test fun `classifier preserves only closed codes and does not claim all errors are permissions`() {
        assertEquals("model_schema_rejected", diagnosticCode("400 Invalid JSON payload tools[0].function_declarations[3].parameters.properties[4] SECRET"))
        assertEquals("model_schema_rejected", diagnosticCode("functionDeclarations keywords.items: missing field"))
        assertEquals("model_auth_rejected", diagnosticCode("Failed to get response: 401 SECRET"))
        assertEquals("model_rate_limited", diagnosticCode("HTTP 429"))
        assertEquals("unclassified_failure", diagnosticCode("unfamiliar SECRET private/path"))
        assertEquals("theme_invalid_parameters", diagnosticCode("theme_invalid_parameters"))
    }
    @Test fun `self check is lazy read only gate rechecked and cannot choose another owner`() = runTest {
        var calls = 0; var enabled = true
        val tool = createOrbisSelfCheckTool({ enabled }) { focus -> calls++; SelfCheckReport("test", 1, focus, emptyList()) }
        assertEquals(0, calls)
        assertEquals("", tool.systemPrompt(model, emptyList()))
        fun result(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertFalse(result(tool.execute(buildJsonObject { put("focus", "theme"); put("owner", "other") }))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, calls)
        assertTrue(result(tool.execute(buildJsonObject { put("focus", "theme") }))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls)
        enabled = false
        assertFalse(result(tool.execute(buildJsonObject {}))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls)
    }
    @Test fun `deauthorization during inspection discards report`() = runTest {
        var enabled = true
        val tool = createOrbisSelfCheckTool({ enabled }) { enabled = false; SelfCheckReport("test", 1, it, emptyList()) }
        val result = (tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text
        assertTrue(result.contains("self_check_disabled")); assertFalse(result.contains("report"))
    }
}
