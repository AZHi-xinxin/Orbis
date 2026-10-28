package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Synthetic final wire JSON; never calls an upstream, runs a tool, or rewrites saved messages. */
class ChatCompletionsDeepSeekHistoryTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private fun request(messages: List<UIMessage>, stream: Boolean = false,
        host: String = "api.deepseek.com", modelId: String = "deepseek-flash",
        tools: Boolean = true, history: Boolean = true,
        customBody: List<CustomBody> = emptyList(), catalogue: List<Tool>? = null): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val params = TextGenerationParams(
            Model(modelId = modelId, abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
            tools = catalogue ?: if (tools) listOf(Tool("compact", "synthetic", execute = { error("must not execute") })) else emptyList(),
            customBody = customBody,
        )
        return method.invoke(api, messages, params,
            ProviderSetting.OpenAI(baseUrl = "https://$host/v1", includeHistoryReasoning = history), stream) as JsonObject
    }
    private fun assistantMessages(body: JsonObject) = body.getValue("messages").jsonArray
        .map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "assistant" }

    private fun reminder(tokens: Int) = UIMessage.system("").copy(isSynthetic = true,
        parts = listOf(UIMessagePart.Text("[Orbis 宿主状态，非人类发言] 上下文用量 $tokens / 360000", buildJsonObject {
            put("orbis_compaction_reminder", true); put("source", "orbis_host")
            put("human_authored", false); put("instruction_authority", "none"); put("request_only", true)
        })))

    @Test fun `threshold reminder stays on official Pro human carrier and keeps tool pair intact`() {
        val prefix = listOf(UIMessage.system("stable"), UIMessage.user("old"), UIMessage.assistant("old answer"))
        val human = UIMessage.user("current query")
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("synthetic", "compact", "{}",
            output = listOf(UIMessagePart.Text("unchanged result")))))
        for (stream in listOf(false, true)) {
            val first = request(prefix + human + reminder(324450), stream, modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            val second = request(prefix + human + reminder(326536) + tool, stream, modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), first.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(first.take(3), second.take(3))
            val carrier = second[3].jsonObject["content"]!!.jsonArray
            assertEquals("current query", carrier[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue(carrier[1].jsonObject["text"]!!.jsonPrimitive.content.contains("326536"))
            assertEquals(listOf("assistant", "tool"), second.takeLast(2).map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("unchanged result", second.last().jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("current query", human.toText())
        }
    }

    @Test fun `ST gets explicit reminder source marker without moving original source or tool prefix`() {
        for (stream in listOf(false, true)) {
            val wire = request(listOf(UIMessage.user("human query"), reminder(324450)), stream,
                host = "st.example.invalid", modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            assertEquals("human query", wire[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("system", wire[1].jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive("orbis_host_data_v1"), wire[1].jsonObject["name"])
        }
    }

    @Test fun `no human and untagged or old system history never get rewritten as human data`() {
        val notice = reminder(324450)
        val untrusted = notice.copy(isSynthetic = false)
        val plain = UIMessage.system(notice.toText())
        val human = UIMessage.user("current")
        for (messages in listOf(listOf(notice), listOf(notice, human), listOf(human, untrusted), listOf(human, plain))) {
            val wire = request(messages, modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            assertEquals(messages.map { it.role.name.lowercase() }, wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        }
    }

    @Test fun `compaction summary has an explicit empty reasoning field without fabricating thoughts`() {
        val summary = UIMessage.assistant("Synthetic summary, unchanged.")
        listOf(false, true).forEach { stream ->
            val body = request(listOf(summary, UIMessage.user("continue")), stream)
            val sent = assistantMessages(body).single()
            assertEquals(JsonPrimitive(""), sent["reasoning_content"])
            assertEquals(JsonPrimitive(summary.toText()), sent["content"])
            assertEquals(listOf(UIMessagePart.Text("Synthetic summary, unchanged.")), summary.parts)
        }
    }

    @Test fun `named DeepSeek relay preserves all original reasoning and tool results`() {
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("original reasoning"), UIMessagePart.Text("before tool"),
            UIMessagePart.Tool("call_synthetic", "compact", "{}", output = listOf(UIMessagePart.Text("saved result"))),
            UIMessagePart.Text("after tool without new reasoning"),
        ))
        val original = source.parts.toList()
        listOf(false, true).forEach { stream ->
            val body = request(listOf(UIMessage.assistant("summary"), source), stream,
                host = "synthetic-st.invalid", modelId = "deepseek-v4-flash-vision-exp")
            val sent = assistantMessages(body)
            assertEquals(listOf("", "original reasoning", ""), sent.map { it["reasoning_content"]?.jsonPrimitive?.content })
            val result = body.getValue("messages").jsonArray.map { it.jsonObject }
                .single { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals("saved result", result.getValue("content").jsonPrimitive.content)
            assertEquals("call_synthetic", result.getValue("tool_call_id").jsonPrimitive.content)
            assertEquals(original, source.parts)
        }
    }

    @Test fun `empty history fields are scoped and never override opting out`() {
        val messages = listOf(UIMessage.assistant("old answer"))
        listOf(false, true).forEach { stream ->
            listOf(
                request(messages, stream, host = "generic.invalid", modelId = "generic-model"),
                request(messages, stream, tools = false),
                request(messages, stream, history = false),
                request(messages, stream, host = "openrouter.ai", modelId = "deepseek/deepseek-flash"),
            ).forEach { assertFalse(assistantMessages(it).single().containsKey("reasoning_content")) }
        }
    }

    @Test fun `final custom model and tools determine whether missing fields are added`() {
        val messages = listOf(UIMessage.assistant("summary"))
        listOf(false, true).forEach { stream ->
            val noTools = request(messages, stream, customBody = listOf(CustomBody("tools", JsonArray(emptyList()))))
            assertFalse(assistantMessages(noTools).single().containsKey("reasoning_content"))
            val otherModel = request(messages, stream, host = "synthetic-st.invalid",
                customBody = listOf(CustomBody("model", JsonPrimitive("generic-model"))))
            assertFalse(assistantMessages(otherModel).single().containsKey("reasoning_content"))
            val deepSeekModel = request(messages, stream, host = "synthetic-st.invalid", modelId = "generic-model",
                customBody = listOf(CustomBody("model", JsonPrimitive("deepseek/deepseek-flash"))))
            assertEquals(JsonPrimitive(""), assistantMessages(deepSeekModel).single()["reasoning_content"])
        }
    }

    @Test fun `explicit reasoning fields and non assistant messages are never overwritten`() {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", "system instruction") })
            add(buildJsonObject { put("role", "user"); put("content", "synthetic request") })
            add(buildJsonObject { put("role", "assistant"); put("content", "answer"); put("reasoning_content", "original") })
            add(buildJsonObject { put("role", "assistant"); put("content", "explicit null"); put("reasoning_content", JsonNull) })
            add(buildJsonObject { put("role", "assistant"); put("content", "details"); put("reasoning_details", buildJsonArray { add(JsonPrimitive("opaque")) }) })
            add(buildJsonObject { put("role", "tool"); put("tool_call_id", "synthetic"); put("content", "original result") })
        }
        listOf(false, true).forEach { stream ->
            val body = request(emptyList(), stream, customBody = listOf(CustomBody("messages", messages)))
            assertEquals(messages, body["messages"])
        }
    }
    @Test fun `official catalogue reconnect order does not rewrite the prefix or lose schemas`() {
        fun catalogue(reversed: Boolean) = listOf("z_read", "a_lookup").let { if (reversed) it.reversed() else it }.map { name ->
            Tool(name, "unchanged $name", parameters = {
                InputSchema.Obj(properties = JsonObject(listOf("query", "limit").let { if (reversed) it.reversed() else it }
                    .associateWith { buildJsonObject { put("type", "string") } }), required = listOf("query"))
            }, execute = { error("not executed") })
        }
        listOf(false, true).forEach { stream ->
            val a = request(listOf(UIMessage.user("test")), stream, modelId = "deepseek-v4-pro", catalogue = catalogue(false))
            val b = request(listOf(UIMessage.user("test")), stream, modelId = "deepseek-v4-pro", catalogue = catalogue(true))
            assertEquals(a.toString(), b.toString())
            val names = a.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("a_lookup", "z_read"), names)
            val generic = request(emptyList(), host = "relay.invalid", catalogue = catalogue(false))
            assertEquals("z_read", generic.getValue("tools").jsonArray.first().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            val overridden = request(emptyList(), catalogue = catalogue(false), customBody = listOf(CustomBody("tools", JsonArray(emptyList()))))
            assertTrue(overridden.getValue("tools").jsonArray.isEmpty())
        }
    }

    private fun hostData(body: Boolean, text: String) = UIMessagePart.Text(text, buildJsonObject {
        if (body) {
            put("orbis_body_status_projection", "v1"); put("source", "orbis_body_status")
        } else {
            put("orbis_local_memory_projection", true); put("source", "orbis_local_memory")
        }
        put("human_authored", false); put("instruction_authority", "none"); put("request_only", true)
    })

    @Test fun `official Pro wire matches cache preserving user data layout for both memory and body`() {
        val prefix = listOf(UIMessage.system("stable pinned memory"), UIMessage.user("previous"), UIMessage.assistant("answer"))
        val human = UIMessage.user("literal human text")
        val projected = human.copy(parts = human.parts + listOf(hostData(false, "retrieved memory"), hostData(true, "body 58")))
        val input = prefix + projected
        listOf(false, true).forEach { stream ->
            val wire = request(input, stream, modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("stable pinned memory", wire.first().jsonObject["content"]!!.jsonPrimitive.content)
            val parts = wire.last().jsonObject["content"]!!.jsonArray
            assertEquals(listOf("literal human text", "retrieved memory", "body 58"), parts.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertTrue(parts.all { it.jsonObject["type"] == JsonPrimitive("text") })
            assertEquals(3, projected.parts.size)
            assertEquals("literal human text", human.toText())
        }
    }

    @Test fun `ST and other providers retain host source separation even with a Pro model name`() {
        val human = UIMessage.user("human query")
        val messages = listOf(UIMessage.system("stable"), human.copy(parts = human.parts + hostData(false, "retrieved")))
        listOf(false, true).forEach { stream ->
            listOf(
                request(messages, stream, host = "st.example.invalid", modelId = "deepseek-v4-pro"),
                request(messages, stream, host = "api.deepseek.com.example.invalid", modelId = "deepseek-v4-pro"),
                request(messages, stream, modelId = "deepseek-v4-flash"),
                request(messages, stream, modelId = "deepseek-v4-pro", customBody = listOf(CustomBody("model", JsonPrimitive("other")))),
            ).forEach { body ->
                val wire = body["messages"]!!.jsonArray
                assertEquals(listOf("system", "system", "user"), wire.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                assertEquals("human query", wire.last().jsonObject["content"]!!.jsonPrimitive.content)
                assertEquals("retrieved", wire[1].jsonObject["content"]!!.jsonPrimitive.content)
                assertEquals(JsonPrimitive("orbis_host_data_v1"),wire[1].jsonObject["name"])
                assertFalse(wire.first().jsonObject.containsKey("name"))
                assertFalse(wire.last().jsonObject.containsKey("name"))
            }
            val overridden = request(messages, stream, modelId = "other", customBody = listOf(CustomBody("model", JsonPrimitive("deepseek-v4-pro"))))
            assertEquals(2, overridden["messages"]!!.jsonArray.size)
        }
    }

    @Test fun `Pro changing readings leave preceding messages and tools byte stable through a tool continuation`() {
        val prefix = listOf(UIMessage.system("stable instructions"), UIMessage.user("earlier"), UIMessage.assistant("answer"))
        val human = UIMessage.user("now")
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("same reasoning"),
            UIMessagePart.Tool("call_synthetic", "compact", "{}", output = listOf(UIMessagePart.Text("unchanged result"))),
        ))
        listOf(false, true).forEach { stream ->
            val a = request(prefix + human.copy(parts = human.parts + hostData(true, "value 58")), stream, modelId = "deepseek-v4-pro")
            val b = request(prefix + human.copy(parts = human.parts + hostData(true, "value 59")), stream, modelId = "deepseek-v4-pro")
            assertEquals(a["messages"]!!.jsonArray.dropLast(1), b["messages"]!!.jsonArray.dropLast(1))
            assertEquals(a["tools"], b["tools"])
            val continued = request(prefix + human.copy(parts = human.parts + hostData(true, "value 58")) + tool,
                stream, modelId = "deepseek-v4-pro")
            val wire = continued["messages"]!!.jsonArray
            assertEquals(a["messages"]!!.jsonArray.toList(), wire.take(prefix.size + 1))
            assertEquals("tool", wire.last().jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("call_synthetic", wire.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("unchanged result", wire.last().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `Pro never rewrites explicit message overrides or authored system messages`() {
        val source = listOf(UIMessage.system("authored system"), UIMessage.user("human"))
        val explicit = buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "explicit") }) }
        listOf(false, true).forEach { stream ->
            val custom = request(source, stream, modelId = "deepseek-v4-pro", customBody = listOf(CustomBody("messages", explicit)))
            assertEquals(explicit, custom["messages"])
            val wire = request(source, stream, modelId = "deepseek-v4-pro")["messages"]!!.jsonArray
            assertEquals("authored system", wire.first().jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("human", wire.last().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }
}
