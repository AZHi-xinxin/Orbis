package me.rerere.rikkahub.data.orbis.diagnostics

import kotlinx.serialization.json.Json
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFailureTest {
    @Test fun `structured HTTP code survives translated wrapper without leaking messages`() {
        val original = HttpException("PRIVATE_CHAT TOKEN url", code = "tool_continuation_lineage_mismatch", httpStatus = 409)
        val event = diagnosticFailure(IllegalStateException("wrapped", original), 123)
        assertEquals("tool_continuation_lineage_mismatch", event.code)
        assertEquals(409, event.httpStatus)
        assertEquals("http_response", event.phase)
        val saved = Json.encodeToString(event)
        listOf("PRIVATE_CHAT", "TOKEN", "wrapped", "url").forEach { assertFalse(saved.contains(it)) }
    }
    @Test fun `machine code takes precedence over misleading prose and only safe parameters survive`() {
        val e = diagnosticFailure(HttpException("timeout", code = "tool_continuation_context_lost", httpStatus = 409, rejectedParameter = "SECRET"))
        assertEquals("tool_continuation_context_lost", e.code)
        assertNull(e.parameter)
        assertEquals("tools", diagnosticFailure(HttpException("invalid", httpStatus = 400, rejectedParameter = "tools")).parameter)
    }
    @Test fun `transport statuses classify without exposing unknown business code`() {
        mapOf(401 to "model_auth_rejected", 429 to "model_rate_limited", 502 to "model_server_failed", 422 to "model_request_rejected").forEach { (status, code) ->
            assertEquals(code, diagnosticFailure(HttpException("private", code = "SECRET", httpStatus = status)).code)
        }
        assertEquals("unclassified_failure", diagnosticFailure(HttpException("private", code = "SECRET", httpStatus = 409)).code)
    }
    @Test fun `local history guard never implies remote network failure`() {
        val event = diagnosticFailure(IllegalStateException("旧轮恢复记录尚未核对，原历史仍保留；新消息仍可发送。"))
        assertEquals("history_recovery_pending", event.code)
        assertEquals("local_recovery", event.phase)
        assertNull(event.httpStatus)
    }
    @Test fun `old records read and arbitrary new metadata is discarded`() {
        val old = Json.decodeFromString<DiagnosticEvent>("""{"at":1,"source":"model","code":"unclassified_failure"}""")
        assertNull(old.phase)
        assertEquals(old, old.copy(phase = "SECRET", httpStatus = -1, parameter = "SECRET").sanitized())
    }
}
