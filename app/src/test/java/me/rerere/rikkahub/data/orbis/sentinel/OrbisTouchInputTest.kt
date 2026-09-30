package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.*
import org.junit.Test

class OrbisTouchInputTest {
    @Test fun physicalMetadataIsRetainedExactly() {
        assertEquals(OrbisTouchInput("touch:fixture-1", 1234),
            parseOrbisTouchInput("""{"event_id":"touch:fixture-1","occurred_at":1234}"""))
    }
    @Test fun senderCannotInjectPromptTargetOrSource() {
        for (field in listOf("text", "prompt", "source", "conversation_id", "assistant_id")) {
            assertTrue(runCatching { parseOrbisTouchInput(
                """{"event_id":"fixture","occurred_at":1234,"$field":"forged"}""") }.isFailure)
        }
    }
    @Test fun malformedIdentityAndTimeRejected() {
        for (body in listOf("{}", """{"event_id":"bad\nidentity","occurred_at":1234}""",
            """{"event_id":"fixture","occurred_at":0}""", """{"event_id":"fixture","occurred_at":-1}"""))
            assertTrue(runCatching { parseOrbisTouchInput(body) }.isFailure)
    }
    @Test fun requestSizeIsBoundedBeforeDecode() {
        assertEquals("event_request_too_large", runCatching { parseOrbisTouchInput("字".repeat(1000)) }.exceptionOrNull()?.message)
    }
}
