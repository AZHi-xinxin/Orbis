package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic payloads only. No real screen, key, model request, device or filesystem. */
class HttpLogRedactionTest {
    private val payload = "SYNTHETIC_PRIVATE_SCREEN_BYTES_DO_NOT_RETAIN"

    @Test fun `OpenAI chat and responses images are absent while role and model remain`() {
        val body = """{"model":"vision-test","messages":[{"role":"user","content":[{"type":"text","text":"What is here?"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$payload","detail":"low"}}]}],"input":[{"type":"input_image","image_url":"data:image/png;base64,$payload"}]}"""
        val result = redactHttpLogBody(body)
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertFalse(result.contains(payload))
        assertEquals("vision-test", parsed.getValue("model").jsonPrimitive.content)
        assertEquals("user", parsed.getValue("messages").jsonArray[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(result.contains("What is here?"))
        assertTrue(body.contains(payload)) // The caller's wire-body string is never replaced.
    }

    @Test fun `Anthropic base64 source and Google inlineData are redacted`() {
        val bodies = listOf(
            """{"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"$payload"}}]}]}""",
            """{"contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"image/jpeg","data":"$payload"}},{"text":"Keep this diagnostic text"}]}]}""",
            """{"inline_data":{"mime_type":"audio/pcm","data":"$payload"}}""",
        )
        bodies.forEach { body ->
            val result = redactHttpLogBody(body)
            assertFalse(result.contains(payload))
            assertTrue(result.contains(HTTP_LOG_MEDIA_REDACTED))
        }
    }

    @Test fun `audio output base64 and common alternate media fields are removed`() {
        val body = """{"input_audio":{"data":"$payload","format":"wav"},"output":{"audio":{"data":"$payload"}},"b64_json":"$payload","file_data":"$payload","imageData":"$payload","video_url":"data:video/mp4;base64,$payload","usage":{"total_tokens":18}}"""
        val result = redactHttpLogBody(body)
        assertFalse(result.contains(payload))
        assertEquals(18, Json.parseToJsonElement(result).jsonObject.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.content.toInt())
    }

    @Test fun `JSON escaped and percent encoded data URLs are removed anywhere`() {
        val bodies = listOf(
            """{"message":"bad input data:image/jpeg;base64,$payload"}""",
            """{"message":"data\u003aimage\/png;base64,$payload"}""",
            """{"message":"data%3Aaudio%2Fpcm%3Bbase64%2C$payload"}""",
        )
        bodies.forEach { assertFalse(redactHttpLogBody(it).contains(payload)) }
    }

    @Test fun `unknown bare base64 and nested reflected JSON do not escape`() {
        val encoded = "Q".repeat(256)
        val nested = kotlinx.serialization.json.JsonPrimitive("""{"source":{"type":"base64","data":"$payload"}}""").toString()
        val result = redactHttpLogBody("""{"opaque_vendor_field":"$encoded","error":{"message":$nested}}""")
        assertFalse(result.contains(encoded))
        assertFalse(result.contains(payload))
    }

    @Test fun `malformed multipart stream and primitive bodies are omitted`() {
        listOf("data: {\"inlineData\":{\"data\":\"$payload\"}}\n\n", "--form-boundary\n$payload", "{\"data\":\"$payload", "\"$payload\"")
            .forEach { assertEquals(HTTP_LOG_BODY_OMITTED, redactHttpLogBody(it)) }
    }

    @Test fun `oversized body is not retained and deep bodies stay bounded`() {
        assertEquals(HTTP_LOG_BODY_OMITTED, redactHttpLogBody(" ".repeat(HTTP_LOG_BODY_LIMIT.toInt() + 1)))
        val body = "[".repeat(60) + "\"$payload\"" + "]".repeat(60)
        assertFalse(redactHttpLogBody(body).contains(payload))
        assertEquals(HTTP_LOG_BODY_OMITTED, redactHttpLogBody("[".repeat(10_000) + "0" + "]".repeat(10_000)))
    }

    @Test fun `transport error diagnostics contain only sanitized class identity`() {
        val result = redactHttpLogFailure("IOException")
        assertTrue(result.contains("IOException"))
        assertTrue(result.contains("raw error omitted"))
        assertFalse(result.contains(payload))
    }

    @Test fun `authentication headers are case insensitive but diagnostic headers survive`() {
        val secret = "SYNTHETIC_KEY_NOT_A_REAL_CREDENTIAL"
        val headers = listOf("Authorization", "proxy-authorization", "X-Api-Key", "x-goog-api-key", "Cookie", "Set-Cookie", "X-Access-Token", "Ocp-Apim-Subscription-Key")
            .associateWith { secret } + mapOf("Content-Type" to "application/json", "x-request-id" to "synthetic-request-1")
        val redacted = redactHttpLogHeaders(headers)
        assertFalse(redacted.values.any { it.contains(secret) })
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("synthetic-request-1", redacted["x-request-id"])
        assertEquals(secret, headers["Authorization"])
    }

    @Test fun `query credentials duplicate secrets userinfo and fragments are not logged`() {
        val url = "https://synthetic-user:synthetic-pass@example.invalid/v1/chat?key=synthetic-key&API_KEY=synthetic-api&token=synthetic-token&token=second-token&X-Amz-Credential=synthetic-cred&q=ordinary&model=vision#synthetic-fragment"
        val redacted = redactHttpLogUrl(url)
        listOf("synthetic-user", "synthetic-pass", "synthetic-key", "synthetic-api", "synthetic-token", "second-token", "synthetic-cred", "synthetic-fragment").forEach {
            assertFalse(redacted.contains(it))
        }
        assertTrue(redacted.contains("q=ordinary"))
        assertTrue(redacted.contains("model=vision"))
        assertTrue(url.contains("synthetic-pass"))
        assertEquals("[Unparseable URL omitted]", redactHttpLogUrl("not a URL"))
    }
}
