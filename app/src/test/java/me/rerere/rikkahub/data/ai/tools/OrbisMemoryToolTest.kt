package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext
import org.junit.Assert.*
import org.junit.Test

class OrbisMemoryToolTest {
    @Test fun realMemoryToolOpenAiDeclarationHasOnlyNonemptyStringEmotionOptions() {
        val tool = buildOrbisMemoryTool("synthetic-owner", { true }) { _, _, _ -> error("Serialization must not execute") }
        val api = me.rerere.ai.provider.providers.openai.ChatCompletionsAPI(
            okhttp3.OkHttpClient(), me.rerere.ai.util.KeyRoulette.default())
        val method = api.javaClass.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            me.rerere.ai.provider.TextGenerationParams::class.java,
            me.rerere.ai.provider.ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val request = method.invoke(api, listOf(me.rerere.ai.ui.UIMessage.user("synthetic query")),
            me.rerere.ai.provider.TextGenerationParams(
                model = me.rerere.ai.provider.Model(modelId = "gemini-4-hydrogen",
                    abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)), tools = listOf(tool)),
            me.rerere.ai.provider.ProviderSetting.OpenAI(), true) as JsonObject
        val function = request["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("orbis_memory", function["name"]!!.jsonPrimitive.content)
        val parameters = function["parameters"]!!.jsonObject
        val emotion = parameters["properties"]!!.jsonObject["emotion"]!!.jsonObject
        // This is the OpenAI wire schema handed to the relay, before conversion to Google's
        // string-only enum. JSON null at enum[7] was the regression missed by native tests.
        val options = emotion["enum"]!!.jsonArray
        assertTrue("Relay enum entries must all be nonempty strings", options.all {
            it is JsonPrimitive && it.isString && it.content.isNotBlank()
        })
        assertEquals(JsonPrimitive("string"), emotion["type"])
        assertEquals(me.rerere.rikkahub.data.orbis.memory.OrbisMemoryEmotions.labels + "clear",
            options.map { it.jsonPrimitive.content }.toSet())
        assertFalse(parameters["required"]?.jsonArray.orEmpty().contains(JsonPrimitive("emotion")))
    }

    @Test fun emotionClearAndLegacyNullKeepTheSameHostBoundOperation() = runBlocking {
        val received = mutableListOf<JsonObject>()
        val tool = buildOrbisMemoryTool("owner", { true }) { owner, request, key ->
            assertEquals("owner", owner)
            assertEquals("conversation:message:call", key)
            received += request
            buildJsonObject { put("ok", true) }
        }
        withContext(CloudToolInvocationContext("call", "message", "conversation")) {
            tool.execute(args("""{"action":"update","id":"note","emotion":"clear"}"""))
            tool.execute(args("""{"action":"update","id":"note","emotion":null}"""))
            tool.execute(args("""{"action":"update","id":"note","body":"updated"}"""))
            tool.execute(args("""{"action":"update","id":"note","emotion":"暖"}"""))
        }
        assertEquals(4, received.size)
        assertEquals(JsonNull, received[0]["emotion"])
        assertEquals(received[0], received[1])
        assertFalse(received[2].containsKey("emotion")) // Omitted updates must not erase a label.
        assertEquals(JsonPrimitive("暖"), received[3]["emotion"])
    }

    @Test fun realMemoryToolEmotionCanBeSerializedForGeminiWithoutChangingItsSchema() {
        val tool = buildOrbisMemoryTool("synthetic-owner", { true }) { _, _, _ -> error("Serialization must not execute") }
        val original = Json.encodeToJsonElement(tool.parameters()).jsonObject
        // Native Google and OpenAI-compatible relays must both accept the real tool schema.
        assertEquals("emotion", original["properties"]!!.jsonObject.keys.elementAt(4))
        val provider = me.rerere.ai.provider.providers.google.GoogleProvider(okhttp3.OkHttpClient())
        val method = provider.javaClass.getDeclaredMethod("buildCompletionRequestBody", List::class.java,
            me.rerere.ai.provider.TextGenerationParams::class.java).apply { isAccessible = true }
        val request = method.invoke(provider, listOf(me.rerere.ai.ui.UIMessage.user("hello")),
            me.rerere.ai.provider.TextGenerationParams(
                model = me.rerere.ai.provider.Model(modelId = "gemini-3.1-pro", abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)),
                tools = listOf(tool),
            )) as JsonObject
        val declaration = request["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
        assertEquals("orbis_memory", declaration["name"]!!.jsonPrimitive.content)
        val emotion = declaration["parameters"]!!.jsonObject["properties"]!!.jsonObject["emotion"]!!.jsonObject
        assertEquals(JsonPrimitive("string"), emotion["type"])
        assertFalse(emotion.containsKey("nullable"))
        assertEquals(original, Json.encodeToJsonElement(tool.parameters()))
        assertEquals(JsonPrimitive("string"),
            original["properties"]!!.jsonObject["emotion"]!!.jsonObject["type"])
    }

    private fun args(text: String) = Json.parseToJsonElement(text)
    @Test fun aliasesAndOptionalTagsAreForgiving() {
        val p = normalizeOrbisMemoryArguments(args("""{"op":"save","content":"whole text","tags":"tea，咖啡","assistantId":"wrong","operation_key":"forged"}"""))
        assertEquals("store", p["action"]!!.jsonPrimitive.content)
        assertEquals("whole text", p["body"]!!.jsonPrimitive.content)
        assertEquals(2, p["tags"]!!.jsonArray.size)
        assertFalse(p.containsKey("assistantId")); assertFalse(p.containsKey("operation_key"))
    }
    @Test fun bodyOnlyIsEnoughAndEmptyRequestReads() {
        assertEquals("store", normalizeOrbisMemoryArguments(args("""{"body":"memo"}"""))["action"]!!.jsonPrimitive.content)
        assertEquals("read", normalizeOrbisMemoryArguments(args("{}"))["action"]!!.jsonPrimitive.content)
    }
    @Test fun hostBindsOwnerAndReplayKey() = runBlocking {
        var seen = ""
        val tool = buildOrbisMemoryTool("owner", { true }) { owner, _, key ->
            seen = "$owner/$key"; buildJsonObject { put("ok", true) }
        }
        withContext(CloudToolInvocationContext("call", "message", "conversation")) {
            tool.execute(args("""{"body":"memo","assistantId":"victim"}"""))
        }
        assertEquals("owner/conversation:message:call", seen)
    }
    @Test fun missingHostIdentityDoesNotWriteAndReferenceOnlyCannotMutate() = runBlocking {
        var writes = 0
        val normal = buildOrbisMemoryTool("owner", { true }) { _, _, _ -> writes++; buildJsonObject { put("ok", true) } }
        normal.execute(args("""{"body":"memo"}"""))
        val reference = buildOrbisMemoryTool("owner", { true }, true) { _, _, _ -> writes++; buildJsonObject { put("ok", true) } }
        withContext(CloudToolInvocationContext("call", "m", "c")) { reference.execute(args("""{"body":"memo"}""")) }
        assertEquals(0, writes)
    }
    @Test fun injectionOffDoesNotDisableReadOrStoreBySchema() {
        val tool = buildOrbisMemoryTool("owner", { true }) { _, _, _ -> buildJsonObject { put("ok", true) } }
        assertEquals("orbis_memory", tool.name)
        assertFalse(tool.description.contains("先申请"))
    }
}
