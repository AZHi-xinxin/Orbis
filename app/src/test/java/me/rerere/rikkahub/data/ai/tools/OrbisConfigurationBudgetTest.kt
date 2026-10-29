package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.integration.AiConfigurationRules
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class OrbisConfigurationBudgetTest {
    private val models = (1..31).map { Model(modelId = "fixture-$it", displayName = "Fixture $it") }
    private val provider = ProviderSetting.OpenAI(name = "Primary", models = models, apiKey = "PRIVATE-KEY", baseUrl = "https://api.example/v1")
    private val other = ProviderSetting.OpenAI(name = "Other", models = listOf(Model(modelId = "other-model")))
    private val ownedMcp = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Owned", headers = listOf("Authorization" to "MCP-SECRET")), url = "https://mcp.example")
    private val foreignMcp = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "UNSELECTED-MCP"), url = "https://unused.example")
    private val owner = Assistant(chatModelId = models.first().id, mcpServers = setOf(ownedMcp.id))
    private val voice = TTSProviderSetting.OpenAI(apiKey = "VOICE-SECRET", model = "tts", voice = "first")
    private var settings = Settings(assistants = listOf(owner), providers = listOf(provider, other), ttsProviders = listOf(voice), mcpServers = listOf(ownedMcp, foreignMcp))
    private val tool get() = createOrbisSelfConfigurationTool(owner.id.toString(), { settings },
        { transform -> settings = transform(settings); true }, { _, _, _ -> error("network_not_expected") })
    private fun request(action: String, vararg pairs: Pair<String, Any>) = buildJsonObject {
        put("action", action); pairs.forEach { (k, v) -> when(v) { is Int -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    }
    private suspend fun call(args: JsonObject): JsonObject = tool.execute(args).filterIsInstance<UIMessagePart.Text>().single().text.let(Json::parseToJsonElement).jsonObject

    @Test fun overviewIsBoundedAndEveryFilteredModelIsReachableWithoutSettingsWrites() = runBlocking {
        val before = settings
        val current = call(request("inspect"))
        assertEquals(models.first().id.toString(), current["selected_models"]!!.jsonObject["chat"]!!.jsonPrimitive.content)
        assertFalse(current.containsKey("providers")); assertFalse(current.containsKey("mcp")); assertFalse(current.containsKey("tts"))
        assertEquals(32, current["catalog_counts"]!!.jsonObject["models"]!!.jsonPrimitive.int)
        var args = request("inspect", "section" to "models", "provider_id" to provider.id.toString(), "query" to "FIXTURE", "limit" to 7)
        val ids = mutableListOf<String>()
        do {
            val result = call(args)
            assertTrue(result["ok"]!!.jsonPrimitive.boolean)
            val entries = result["items"]!!.jsonArray
            assertTrue(entries.size <= 7)
            assertEquals(31, result["page"]!!.jsonObject["total"]!!.jsonPrimitive.int)
            ids += entries.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            val next = result["next_call"] as? JsonObject ?: break
            assertEquals("FIXTURE", next["query"]!!.jsonPrimitive.content)
            assertEquals(provider.id.toString(), next["provider_id"]!!.jsonPrimitive.content)
            args = next
        } while (true)
        assertEquals(models.map { it.id.toString() }, ids); assertEquals(before, settings)
        for (section in listOf("providers", "models", "tts", "mcp")) {
            val page = call(request("inspect", "section" to section)).toString()
            listOf("PRIVATE-KEY", "VOICE-SECRET", "MCP-SECRET", "UNSELECTED-MCP").forEach { assertFalse(page.contains(it)) }
        }
        val empty = call(request("inspect", "section" to "models", "offset" to Int.MAX_VALUE))
        assertTrue(empty["items"]!!.jsonArray.isEmpty()); assertEquals(JsonNull, empty["page"]!!.jsonObject["next_offset"])
    }

    @Test fun invalidPagingAndFiltersFailWithoutWrites() = runBlocking {
        val before = settings
        for (args in listOf(request("inspect", "section" to "models", "limit" to 0), request("inspect", "section" to "models", "offset" to -1),
            request("inspect", "section" to "models", "limit" to "10"), request("inspect", "query" to "x"),
            request("inspect", "section" to "tts", "provider_id" to provider.id.toString()), request("inspect", "section" to "unknown"),
            request("memory", "mode" to "light", "offset" to 0))) {
            assertFalse(call(args)["ok"]!!.jsonPrimitive.boolean)
        }
        assertEquals(before, settings)
    }

    @Test fun mutationReceiptsKeepTargetAndScopeWithoutRepeatingCatalogue() = runBlocking {
        val actions = listOf(
            request("register_model", "provider_id" to provider.id.toString(), "model" to "new-model"),
            request("select_model", "role" to "fast", "id" to models.last().id.toString()),
            request("select_model", "role" to "chat", "id" to models.last().id.toString()),
            request("select_tts", "id" to voice.id.toString()), request("update_tts", "id" to voice.id.toString(), "voice" to "second"),
            request("tts_from_provider", "provider_id" to provider.id.toString(), "model" to "new-tts", "voice" to "new-voice"),
            request("memory", "mode" to "standard", "auto_inject" to true))
        for (args in actions) {
            val receipt = call(args)
            assertEquals("saved", receipt["status"]!!.jsonPrimitive.content)
            assertEquals(args["action"], receipt["action"]); assertFalse(receipt["connection_verified"]!!.jsonPrimitive.boolean)
            assertFalse(receipt.containsKey("configuration")); assertFalse(receipt.containsKey("providers"))
            assertFalse(receipt.toString().contains("PRIVATE-KEY")); assertFalse(receipt.toString().contains("MCP-SECRET"))
            assertTrue(receipt.toString().length < 800)
        }
        assertEquals(models.last().id, settings.assistants.single().chatModelId)
        assertEquals(models.last().id, settings.fastModelId)
        assertEquals("second", (settings.ttsProviders.first() as TTSProviderSetting.OpenAI).voice)
        val again = call(actions.first())
        assertEquals(1, settings.providers.first().models.count { it.modelId == "new-model" })
        assertTrue(again["ok"]!!.jsonPrimitive.boolean)
        val receipt = call(actions.last()).toString()
        val previous = buildJsonObject { put("ok", true); put("status", "saved"); put("connection_verified", false); put("configuration", AiConfigurationRules.inspect(settings, owner.id.toString())) }.toString()
        assertTrue(receipt.length < previous.length / 5)
        println("CONFIGURATION_RECEIPT_CHARACTERS before=${previous.length} after=${receipt.length}")
    }
}
