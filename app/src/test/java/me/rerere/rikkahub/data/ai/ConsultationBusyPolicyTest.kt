package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test

class ConsultationBusyPolicyTest {
    private fun busy(status: Int = 409, proof: Boolean = true) = HttpException("busy",
        code = "human_turn_in_progress", errorType = "stiller_gateway_error", httpStatus = status,
        gatewayBusyBeforeGeneration = proof)
    @Test fun exactProofWaitsOnlyBeforeAnyOutputAndWithinDeadline() {
        assertEquals(2_000L, consultationBusyDelayMillis(busy(), 9_000L, 1_000L, false))
        assertEquals(50L, consultationBusyDelayMillis(busy(), 1_050L, 1_000L, false))
        assertNull(consultationBusyDelayMillis(busy(), 9_000L, 1_000L, true))
        assertNull(consultationBusyDelayMillis(busy(), null, 1_000L, false))
        assertNull(consultationBusyDelayMillis(busy(), 1_000L, 1_000L, false))
    }
    @Test fun proseGeneric409AndIoNeverGrantReplay() {
        assertNull(consultationBusyDelayMillis(busy(proof = false), 9_000L, 1_000L, false))
        assertNull(consultationBusyDelayMillis(busy(status = 500), 9_000L, 1_000L, false))
        assertNull(consultationBusyDelayMillis(java.io.IOException("busy_before_generation"), 9_000L, 1_000L, false))
        assertNull(consultationBusyDelayMillis(HttpException("human_turn_in_progress", httpStatus = 409), 9_000L, 1_000L, false))
    }
}
