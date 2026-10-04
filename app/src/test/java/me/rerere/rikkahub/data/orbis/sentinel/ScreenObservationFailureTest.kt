package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.*
import org.junit.Test

class ScreenObservationFailureTest {
    @Test fun distinctCausesSurviveWithoutBeingCalledVendorIncompatibility() {
        val causes = listOf("service_not_connected", "vision_not_configured", "device_locked",
            "accessibility_permission_required", "screen_capture_failed_or_protected", "request_failed",
            "truncated_response", "unsafe_endpoint")
        causes.forEach {
            assertEquals("screen_observation_$it", screenObservationFailureReason(it))
            assertTrue(screenObservationFailureReason(it).matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}")))
        }
        assertEquals(causes.size, causes.map { screenObservationFailureReason(it) }.toSet().size)
    }

    @Test fun unknownDiagnosticsNeverPersistArbitraryPayloads() {
        listOf(null, "", "https://private.invalid?token=secret", "private screen content",
            "vivo_not_supported", "request_failed\nsecret").forEach {
            assertEquals("screen_observation_unavailable", screenObservationFailureReason(it))
        }
    }

    @Test fun successfulReceiptWithoutContentIsNotReportedAsSuccessfulObservation() {
        assertEquals("screen_observation_empty_content", screenObservationFailureReason(null, true))
    }
}
