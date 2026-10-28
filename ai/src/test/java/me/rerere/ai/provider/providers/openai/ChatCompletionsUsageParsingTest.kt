package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI token usage parsing.
 */
class ChatCompletionsUsageParsingTest {

    @Test fun `stream cache explicit zero overrides previous usage but omission does not`() {
        val decoder = ChatCompletionsStreamDecoder()
        fun parse(raw: String) = (decoder.accept(SseEvent(data = """{"usage":$raw,"choices":[]}"""))
            .chunks.single() as StreamChunk.Usage).usage
        val previous = TokenUsage(promptTokens = 100, cachedTokens = 90)
        assertEquals(0, previous.merge(parse("""{"prompt_cache_hit_tokens":0}""")).cachedTokens)
        assertEquals(90, previous.merge(parse("""{"completion_tokens":1}""")).cachedTokens)
    }

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private parseTokenUsage via reflection
    private fun parseTokenUsage(usage: JsonObject): TokenUsage? {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseTokenUsage",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(api, usage) as TokenUsage?
    }

    @Test
    fun `explicit cache zero is distinct from omitted cache across dialects`() {
        val previous = TokenUsage(promptTokens = 200, cachedTokens = 100)
        listOf(
            """{"prompt_tokens_details":{"cached_tokens":0},"cached_tokens":99}""",
            """{"cached_tokens":0}""",
            """{"prompt_cache_hit_tokens":0}""",
        ).forEach { raw ->
            val usage = requireNotNull(parseTokenUsage(Json.parseToJsonElement(raw).jsonObject))
            assertTrue(usage.cachedTokensReported)
            assertEquals(0, previous.merge(usage).cachedTokens)
        }
        val partial = requireNotNull(parseTokenUsage(Json.parseToJsonElement("""{"completion_tokens":12}""").jsonObject))
        assertFalse(partial.cachedTokensReported)
        assertEquals(100, previous.merge(partial).cachedTokens)
    }

    // #1576: cached tokens 按 provider 方言兜底解析
    @Test
    fun `cached tokens fall back across provider dialects`() {
        fun usage(jsonStr: String) = parseTokenUsage(Json.parseToJsonElement(jsonStr).jsonObject)

        // OpenAI 官方嵌套格式
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}""")?.cachedTokens
        )
        // Moonshot 顶层 cached_tokens
        assertEquals(
            7,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7}""")?.cachedTokens
        )
        // DeepSeek 顶层 prompt_cache_hit_tokens
        assertEquals(
            5,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3}""")?.cachedTokens
        )
        // 嵌套字段优先于顶层兜底
        assertEquals(
            12,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12},"cached_tokens":7}""")?.cachedTokens
        )
        // 都没有时为 0
        assertEquals(
            0,
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")?.cachedTokens
        )
    }
}
