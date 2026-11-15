package me.rerere.rikkahub.data.orbis.diagnostics

import kotlinx.serialization.json.Json
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFailureTest {
    @Test fun `plan failures retain status and structural parameter after journal sanitation`() {
        val original = me.rerere.ai.provider.chatgpt.ChatGptPlanFailure.from(
            HttpException("PRIVATE CHAT TOKEN", "array_above_max_length", httpStatus = 400,
                rejectedParameter = "input[0].content"))
        val event = diagnosticFailure(IllegalStateException("private wrapper", original), 123).sanitized()
        assertEquals("model_request_rejected", event.code)
        assertEquals(400, event.httpStatus)
        assertEquals("http_response", event.phase)
        assertEquals("input[0].content", event.parameter)
        assertFalse(Json.encodeToString(event).contains("PRIVATE"))
        assertEquals(event, event.sanitized())
    }
    @Test fun `image preparation failures retain stage and status without private payload`() {
        me.rerere.ai.util.ImagePreparationReason.entries.forEach { reason ->
            me.rerere.ai.util.ImagePreparationPhase.entries.forEach { phase ->
                val original=me.rerere.ai.util.ImagePreparationException(reason,phase,502)
                val event=diagnosticFailure(IllegalStateException("PRIVATE CHAT TOKEN URL",original),123)
                assertEquals(reason.code,event.code);assertTrue(event.code in diagnosticCodes)
                assertEquals(phase.diagnosticPhase,event.phase);assertEquals(502,event.httpStatus)
                assertEquals(event,event.sanitized());assertFalse(Json.encodeToString(event).contains("PRIVATE"))
            }
        }
        assertNull(diagnosticFailure(me.rerere.ai.util.ImagePreparationException(
            me.rerere.ai.util.ImagePreparationReason.NETWORK,me.rerere.ai.util.ImagePreparationPhase.LOOKUP)).httpStatus)
    }
    @Test fun `skill receipt code and safe correlation stay linked without arguments`() {
        val id="9c6f0b39-5073-4c58-8af1-0147ba234f56"
        val receipt=Json.parseToJsonElement("""{"ok":false,"reason_code":"skill_directory_missing","diagnostic_id":"$id"}""").let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("skill_directory_missing",toolDiagnosticCode(receipt))
        val event=DiagnosticEvent(1,"tool",toolDiagnosticCode(receipt),toolName="orbis_skill_register",operation="register",reference=id)
        assertEquals(event,event.sanitized())
        assertNull(event.httpStatus)
        val unsafe=event.copy(toolName="PRIVATE",operation="PRIVATE",reference="PRIVATE").sanitized()
        assertNull(unsafe.toolName);assertNull(unsafe.operation);assertNull(unsafe.reference)
        assertFalse(Json.encodeToString(unsafe).contains("PRIVATE"))
    }
    @Test fun `wrapped local capacity failure stays local without exposing private wrapper`() {
        val error = IllegalStateException("PRIVATE CHAT SECRET timeout",
            me.rerere.rikkahub.data.db.MessageNodeCapacityException("generation_tail_capacity"))
        val event = diagnosticFailure(error, 456)
        assertEquals("generation_tail_capacity", event.code)
        assertEquals("local_storage", event.phase)
        assertNull(event.httpStatus)
        assertEquals(event, event.sanitized())
        assertTrue(event.code in diagnosticCodes)
        assertFalse(Json.encodeToString(event).contains("SECRET"))
    }
    @Test fun `unknown capacity code falls back without retaining arbitrary data`() {
        val event = diagnosticFailure(me.rerere.rikkahub.data.db.MessageNodeCapacityException("PRIVATE_TOKEN"))
        assertEquals("message_node_capacity", event.code)
        assertEquals("local_storage", event.phase)
        assertFalse(Json.encodeToString(event).contains("PRIVATE_TOKEN"))
    }
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
        mapOf(401 to "model_auth_rejected", 402 to "model_payment_required", 429 to "model_rate_limited", 502 to "model_server_failed", 422 to "model_request_rejected").forEach { (status, code) ->
            assertEquals(code, diagnosticFailure(HttpException("private", code = "SECRET", httpStatus = status)).code)
        }
        assertEquals("unclassified_failure", diagnosticFailure(HttpException("private", code = "SECRET", httpStatus = 409)).code)
    }
    @Test fun `ST window mismatch is retained separately from local save errors`() {
        val event = diagnosticFailure(HttpException("PRIVATE", code = "tool_continuation_thread_mismatch", httpStatus = 409))
        assertEquals("tool_continuation_thread_mismatch", event.code)
        assertEquals("http_response", event.phase)
        assertEquals(409, event.httpStatus)
        assertEquals(event.code, diagnosticCode("HTTP 409: tool_continuation_thread_mismatch"))
        assertFalse(Json.encodeToString(event).contains("PRIVATE"))
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

    @Test fun `local persistence stages distinguish failed save from pending copy without exposing chat`() {
        me.rerere.rikkahub.data.ai.checkpoint.HistoryPersistenceStage.entries.forEach { stage ->
            val event = diagnosticFailure(IllegalStateException("PRIVATE CHAT SECRET",
                me.rerere.rikkahub.data.ai.checkpoint.HistoryPersistenceException(stage)))
            assertEquals(stage.code, event.code)
            assertTrue(event.code in diagnosticCodes)
            assertEquals("local_recovery", event.phase)
            assertEquals(event, event.sanitized())
            assertFalse(Json.encodeToString(event).contains("SECRET"))
            assertNull(event.httpStatus)
        }
    }
}
