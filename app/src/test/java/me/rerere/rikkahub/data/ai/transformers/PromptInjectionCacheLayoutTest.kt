package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.GenerationInputSnapshot
import me.rerere.rikkahub.data.model.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Real request serialization, entirely offline; no model/key/paid calls. */
class PromptInjectionCacheLayoutTest {
    private val book = Lorebook(entries = listOf(PromptInjection.RegexInjection(
        content = "DYNAMIC_SYSTEM_RULE", keywords = listOf("用量", "压缩"), scanDepth = 2, priority = 8,
    )))
    private val mode = PromptInjection.ModeInjection(content = "FIXED_MODE_RULE", priority = 1)
    private val owner = Assistant(lorebookIds = setOf(book.id), modeInjectionIds = setOf(mode.id))
    private val prefix = listOf(UIMessage.system("FIXED_MAIN ".repeat(1800))) + (1..8).flatMap {
        listOf(UIMessage.user("old question $it"), UIMessage.assistant("old answer $it"))
    }
    private fun project(messages: List<UIMessage>, defer: Boolean = true, assistant: Assistant = owner,
        books: List<Lorebook> = listOf(book), modes: List<PromptInjection.ModeInjection> = listOf(mode)) =
        transformMessages(messages, assistant, modes, books, deferConditionalSystemInjections = defer)

    private fun wire(messages: List<UIMessage>): JsonArray {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return (method.invoke(api, messages, TextGenerationParams(model = Model(modelId = "deepseek-v4-flash")),
            ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/v1"), false) as JsonObject)["messages"]!!.jsonArray
    }

    @Test fun `keyword entry and removal preserve entire fixed system and historical wire prefix`() {
        val original = prefix + UIMessage.user("看看用量")
        val active = wire(project(original))
        val inactive = wire(project(prefix + UIMessage.user("换个话题")))
        val expected = wire(prefix)
        assertEquals(expected.toList(), active.take(expected.size))
        assertEquals(expected.toList(), inactive.take(expected.size))
        assertEquals("system", active[expected.size].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("DYNAMIC_SYSTEM_RULE\nFIXED_MODE_RULE", active[expected.size].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("FIXED_MODE_RULE", inactive[expected.size].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("看看用量", active.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(active[expected.size].jsonObject.containsKey("name")) // Not low-authority host data.
        assertEquals("FIXED_MAIN ".repeat(1800), original.first().toText())
        assertFalse(original.any { it.toText().contains("DYNAMIC_SYSTEM_RULE") })
    }

    @Test fun `assistant text triggers and aging out removes the rule using original scan depth`() {
        val withKeyword = prefix + UIMessage.assistant("查看用量") + UIMessage.user("继续")
        assertTrue(project(withKeyword).any { it.role == MessageRole.SYSTEM && it.toText().contains("DYNAMIC_SYSTEM_RULE") })
        val after = project(withKeyword + UIMessage.assistant("好了") + UIMessage.user("说说天气"))
        assertFalse(after.any { it.toText().contains("DYNAMIC_SYSTEM_RULE") })
        assertEquals("FIXED_MODE_RULE", after[after.lastIndex - 1].toText())
    }

    @Test fun `no conditional entries no carrier and incompatible protocols retain legacy behavior`() {
        val input = prefix + UIMessage.user("用量")
        val legacy = project(input, defer = false)
        assertEquals(prefix.first().toText() + "\nDYNAMIC_SYSTEM_RULE\nFIXED_MODE_RULE", legacy.first().toText())
        val constant = book.copy(entries = book.entries.map { it.copy(constantActive = true) })
        assertEquals(project(input, false, books = listOf(constant)).map { it.toText() },
            project(input, true, books = listOf(constant)).map { it.toText() })
        val noCarrier = listOf(UIMessage.system("only system"), UIMessage.assistant("用量"))
        assertEquals(project(noCarrier, false).map { it.toText() }, project(noCarrier).map { it.toText() })
        assertTrue(supportsLateSystemInjections(ProviderSetting.OpenAI(), "deepseek-v4-flash"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "deepseek-v4-pro"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "claude-opus-5-5"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "福利kiro-0.08-opus-5-5"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "gemini-flash"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "gpt"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(), "unknown-model"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.OpenAI(useResponseApi = true), "gpt"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.Claude(), "claude"))
        assertFalse(supportsLateSystemInjections(ProviderSetting.Google(), "gemini"))
        assertFalse(supportsLateSystemInjections(null, "anything"))
    }

    @Test fun `before system placement and conversation binding are not silently changed`() {
        val before = book.copy(entries = book.entries.map { it.copy(position = InjectionPosition.BEFORE_SYSTEM_PROMPT) })
        val input = prefix + UIMessage.user("用量")
        assertEquals(project(input, false, books = listOf(before)).map { it.toText() },
            project(input, books = listOf(before)).map { it.toText() })
        val scoped = owner.copy(allowConversationPromptInjection = true)
        assertFalse(project(input, assistant = scoped).any { it.toText().contains("DYNAMIC_SYSTEM_RULE") })
        val enabled = transformMessages(input, scoped, listOf(mode), listOf(book),
            conversationModeInjectionIds = setOf(mode.id), conversationLorebookIds = setOf(book.id),
            deferConditionalSystemInjections = true)
        assertEquals(prefix.first().toText(), enabled.first().toText())
        assertTrue(enabled[enabled.lastIndex - 1].toText().contains("DYNAMIC_SYSTEM_RULE"))
    }

    @Test fun `tool continuation freezes rule selection and keeps the previous request intact`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        var transformations = 0
        val prepared = snapshot.input { transformations++; project(prefix + UIMessage.user("用量")) }
        val first = wire(prepared)
        val call = UIMessagePart.Tool("test-call", "read", "{}")
        val response = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))
        snapshot.appendCompletedResponse(response, listOf(call.copy(output = listOf(UIMessagePart.Text("没有触发词的工具结果")))), response.id)
        val continued = snapshot.input { transformations++; error("must not rerun keyword evaluation") }
        val next = wire(continued)
        assertEquals(1, transformations)
        assertEquals(first.toList(), next.take(first.size))
        assertEquals("assistant", next[first.size].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("tool", next[first.size + 1].jsonObject["role"]!!.jsonPrimitive.content)
    }
}
