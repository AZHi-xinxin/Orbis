package me.rerere.ai.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorParserTest {
    @Test fun `structured error identity survives message parsing`() {
        val error = Json.parseToJsonElement("""{"error":{"message":"Only reasoning returned","code":"upstream_empty_completion","type":"upstream_error"}}""").parseErrorDetail()
        assertEquals("Only reasoning returned", error.message)
        assertEquals("upstream_empty_completion", error.code)
        assertEquals("upstream_error", error.errorType)
    }

    @Test fun `a display message is not a machine readable error code`() {
        val error = Json.parseToJsonElement("""{"error":{"message":"upstream_empty_completion"}}""").parseErrorDetail()
        assertEquals("upstream_empty_completion", error.message)
        assertNull(error.code)
    }

    @Test fun `inner structured error takes precedence over envelope code`() {
        val error = Json.parseToJsonElement("""{"code":"500","detail":{"code":"upstream_error","message":"Failed"}}""").parseErrorDetail()
        assertEquals("upstream_error", error.code)
        assertEquals("Failed", error.message)
    }

    @Test fun `array envelopes and primitive messages still work`() {
        assertEquals("rate_limit", Json.parseToJsonElement("""[{"code":"rate_limit","message":"Wait"}]""").parseErrorDetail().code)
        assertEquals("Failed", Json.parseToJsonElement("\"Failed\"").parseErrorDetail().message)
    }
}
