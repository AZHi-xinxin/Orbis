package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.data.orbis.contact.IncomingCallOutcome
import me.rerere.rikkahub.service.OrbisCallFailure
import org.junit.Assert.*
import org.junit.Test

class OrbisIncomingCallResultTest {
    private val attempt = IncomingCallAttempt("test-attempt", "test-assistant", "test-conversation", "合成测试", 100, 30)

    @Test fun `failed preflight receipt includes safe actionable message without claiming connection`() {
        val result = attempt.copy(outcome = IncomingCallOutcome.FAILED, failureCode = OrbisCallFailure.ASR_MODEL.code).incomingResult()
        assertEquals(OrbisCallFailure.ASR_MODEL.explanation, result.getValue("failure_message").jsonPrimitive.content)
        assertEquals(OrbisCallFailure.ASR_MODEL.code, result.getValue("failure_code").jsonPrimitive.content)
        assertEquals(JsonNull, result["call_id"])
        assertFalse(result.getValue("fallback_attempted").jsonPrimitive.boolean)
        assertFalse(result.getValue("redial_scheduled").jsonPrimitive.boolean)
    }

    @Test fun `successful receipt preserves fields and adds only null failure message`() {
        val result = attempt.copy(outcome = IncomingCallOutcome.CONNECTED, connectedCallId = "connected-test").incomingResult()
        assertEquals(JsonNull, result["failure_message"])
        assertEquals("connected-test", result.getValue("call_id").jsonPrimitive.content)
        assertEquals("connected", result.getValue("outcome").jsonPrimitive.content)
    }

    @Test fun `unknown legacy failure yields generic fixed description`() {
        val result = attempt.copy(outcome = IncomingCallOutcome.FAILED, failureCode = "unrecognized_legacy_code").incomingResult()
        assertEquals(OrbisCallFailure.UNKNOWN.explanation, result.getValue("failure_message").jsonPrimitive.content)
        assertEquals("unrecognized_legacy_code", result.getValue("failure_code").jsonPrimitive.content)
    }
}
