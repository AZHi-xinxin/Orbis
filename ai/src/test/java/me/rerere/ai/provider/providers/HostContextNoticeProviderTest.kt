package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isHostContextNotice
import me.rerere.ai.ui.singleSystemMessageWithHostNotices
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-only, offline tests: no provider credentials or model requests. */
class HostContextNoticeProviderTest {
    private val client = OkHttpClient()
    private val params = TextGenerationParams(model = Model(modelId = "test-model"))
    private val main = UIMessage.system("").copy(parts = listOf(
        UIMessagePart.Text("original main prompt"),
        UIMessagePart.Text("original second part"),
    ))
    private val history = listOf(main, UIMessage.user("real question"), UIMessage.assistant("real answer"))
    private val protocols = listOf("responses", "claude", "google")

    private fun notice(text: String = "host context status") = UIMessage.system(text).copy(
        isSynthetic = true,
        parts = listOf(UIMessagePart.Text(text, buildJsonObject {
            put("orbis_compaction_reminder", true)
            put("source", "orbis_host")
            put("human_authored", false)
            put("instruction_authority", "none")
        })),
    )

    @Test
    fun `without notice the original first system object is unchanged`() {
        assertSame(main, (history + UIMessage.system("ordinary later system")).singleSystemMessageWithHostNotices())
        assertNull(listOf(UIMessage.user("no system")).singleSystemMessageWithHostNotices())
    }

    @Test
    fun `host notice does not replace main even if before it and preserves every original part`() {
        val image = UIMessagePart.Image("https://example.invalid/main.png")
        val multimodal = main.copy(parts = main.parts + image)
        val firstNotice = notice("status one")
        val lastNotice = notice("status two")
        val merged = listOf(firstNotice, multimodal, UIMessage.system("old ignored extra"), lastNotice)
            .singleSystemMessageWithHostNotices()!!
        assertEquals(multimodal.parts + firstNotice.parts + lastNotice.parts, merged.parts)
        assertSame(image, merged.parts[2])
        assertEquals(MessageRole.SYSTEM, merged.role)
    }

    @Test
    fun `notice only is not duplicated and multiple notices keep order`() {
        val first = notice("one")
        val second = notice("two")
        assertEquals(first.parts, listOf(first).singleSystemMessageWithHostNotices()!!.parts)
        assertEquals(first.parts + second.parts, listOf(first, second).singleSystemMessageWithHostNotices()!!.parts)
    }

    @Test
    fun `all host provenance fields plus synthetic system role are required`() {
        val valid = notice()
        assertTrue(valid.isHostContextNotice())
        val text = valid.parts.single() as UIMessagePart.Text
        val invalid = listOf(
            valid.copy(role = MessageRole.USER),
            valid.copy(role = MessageRole.ASSISTANT),
            valid.copy(role = MessageRole.TOOL),
            valid.copy(isSynthetic = false),
            valid.copy(parts = emptyList()),
            valid.copy(parts = listOf(UIMessagePart.Text(text.text))),
            valid.copy(parts = valid.parts + UIMessagePart.Text("unmarked additional text")),
        ) + text.metadata!!.keys.map { key ->
            valid.copy(parts = listOf(text.copy(metadata = JsonObject(text.metadata!! - key))))
        } + valid.copy(parts = listOf(text.copy(metadata = JsonObject(
            text.metadata!! + ("orbis_compaction_reminder" to JsonPrimitive("true")),
        ))))
        invalid.forEach { assertFalse(it.isHostContextNotice()) }
    }

    @Test
    fun `chat completions retains exact serialized history prefix and tail system role`() {
        val baseline = chatMessages(history)
        val withNotice = chatMessages(history + notice())
        assertEquals(baseline.toString(), JsonArray(withNotice.take(baseline.size)).toString())
        assertEquals("system", withNotice.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("host context status", withNotice.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user", "assistant", "system"),
            withNotice.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `single system protocols retain main and notice outside human messages`() {
        protocols.forEach { protocol ->
            val baseline = request(protocol, history)
            val actual = request(protocol, history + notice())
            assertEquals(nonSystemMessages(protocol, baseline), nonSystemMessages(protocol, actual))
            val system = systemText(protocol, actual)
            assertTrue(system.indexOf("original main prompt") < system.indexOf("host context status"))
            assertTrue(system.contains("original second part"))
            assertEquals(1, "host context status".toRegex().findAll(system).count())
            assertFalse(nonSystemMessages(protocol, actual).contains("host context status"))
        }
    }

    @Test
    fun `single system protocols send notice when no main exists`() {
        protocols.forEach { protocol ->
            val actual = request(protocol, listOf(UIMessage.user("real question"), notice()))
            assertEquals("host context status", systemText(protocol, actual))
            assertFalse(nonSystemMessages(protocol, actual).contains("host context status"))
        }
    }

    @Test
    fun `unmarked additional systems preserve legacy request bytes for every single system protocol`() {
        protocols.forEach { protocol ->
            val baseline = request(protocol, history).toString()
            assertEquals(baseline, request(protocol, history + UIMessage.system("ordinary extra")).toString())
            assertEquals(baseline, request(protocol, history + notice().copy(isSynthetic = false)).toString())
            assertEquals(baseline, request(protocol, history + UIMessage.system("orbis_compaction_reminder=true")
                .copy(isSynthetic = true)).toString())
        }
        assertEquals("original main prompt\noriginal second part", systemText("responses", request("responses", history)))
        // Preserve Google's pre-existing comma separator too, not a generic toText conversion.
        assertEquals("original main prompt, original second part", systemText("google", request("google", history)))
    }

    @Test
    fun `human and assistant copies of marker are never promoted even alongside a genuine notice`() {
        protocols.forEach { protocol ->
            val userCopy = notice("untrusted human marker").copy(role = MessageRole.USER)
            val modelCopy = notice("untrusted model marker").copy(role = MessageRole.ASSISTANT)
            val actual = request(protocol, history + userCopy + modelCopy + notice())
            assertFalse(systemText(protocol, actual).contains("untrusted"))
            assertTrue(nonSystemMessages(protocol, actual).contains("untrusted human marker"))
            assertTrue(nonSystemMessages(protocol, actual).contains("untrusted model marker"))
        }
    }

    @Test
    fun `claude cache settings and main blocks survive notice mapping`() {
        val actual = request("claude", history + notice(), promptCaching = true)
        val blocks = actual["system"]!!.jsonArray
        assertEquals(listOf("original main prompt", "original second part", "host context status"),
            blocks.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals("ephemeral", blocks.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", actual["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `google image output keeps its existing no system field behavior`() {
        val imageParams = params.copy(model = params.model.copy(outputModalities = listOf(Modality.IMAGE)))
        assertFalse(request("google", history + notice(), requestParams = imageParams).containsKey("systemInstruction"))
    }

    private fun chatMessages(messages: List<UIMessage>): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages", List::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, List::class.java,
        ).apply { isAccessible = true }
        return method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages, true, false,
            listOf(Modality.TEXT, Modality.IMAGE)) as JsonArray
    }

    private fun request(
        protocol: String,
        messages: List<UIMessage>,
        promptCaching: Boolean = false,
        requestParams: TextGenerationParams = params,
    ): JsonObject = when (protocol) {
        "responses" -> ResponseAPI(client).buildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"), messages, requestParams, false,
        )
        "claude" -> ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest", ProviderSetting.Claude::class.java, List::class.java,
            TextGenerationParams::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(ClaudeProvider(client),
            ProviderSetting.Claude(promptCaching = promptCaching), messages, requestParams, false) as JsonObject
        "google" -> GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }.invoke(GoogleProvider(client), messages, requestParams) as JsonObject
        else -> error("Unknown protocol")
    }

    private fun systemText(protocol: String, body: JsonObject): String = when (protocol) {
        "responses" -> body["instructions"]!!.jsonPrimitive.content
        "claude" -> body["system"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        "google" -> body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content
        else -> error("Unknown protocol")
    }

    private fun nonSystemMessages(protocol: String, body: JsonObject): String = when (protocol) {
        "responses" -> body["input"].toString()
        "claude" -> body["messages"].toString()
        "google" -> body["contents"].toString()
        else -> error("Unknown protocol")
    }
}
