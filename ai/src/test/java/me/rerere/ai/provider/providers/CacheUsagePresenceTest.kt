package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheUsagePresenceTest {
    private val client = OkHttpClient()
    private val old = TokenUsage(promptTokens = 200, cachedTokens = 100)
    private fun parse(target: Any, method: String, raw: String): TokenUsage {
        val parser = target.javaClass.getDeclaredMethod(method, JsonObject::class.java)
        parser.isAccessible = true
        return parser.invoke(target, Json.parseToJsonElement(raw).jsonObject) as TokenUsage
    }
    private fun check(target: Any, method: String, zero: String, partial: String) {
        val explicitZero = parse(target, method, zero)
        assertTrue(explicitZero.cachedTokensReported)
        assertEquals(0, old.merge(explicitZero).cachedTokens)
        val omitted = parse(target, method, partial)
        assertFalse(omitted.cachedTokensReported)
        assertEquals(100, old.merge(omitted).cachedTokens)
    }
    @Test fun responsesDistinguishesExplicitZeroFromMissingDetails() {
        check(ResponseAPI(client, KeyRoulette.default()), "parseTokenUsage",
            """{"input_tokens_details":{"cached_tokens":0}}""", """{"output_tokens":10}""")
    }
    @Test fun claudeOutputOnlyDeltaRetainsMessageStartCache() {
        check(ClaudeProvider(client), "parseTokenUsage",
            """{"usage":{"cache_read_input_tokens":0}}""", """{"usage":{"output_tokens":10}}""")
    }
    @Test fun googleDistinguishesExplicitZeroFromMissingMetadata() {
        check(GoogleProvider(client), "parseUsageMeta",
            """{"cachedContentTokenCount":0}""", """{"candidatesTokenCount":10}""")
    }
}
