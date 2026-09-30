package me.rerere.rikkahub.web.routes

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisEventRequestFailureTest {
    @Test fun invalidTimeIsDefinitivelyRejected() {
        assertEquals("invalid_event_time" to 400, orbisEventRequestFailure(IllegalArgumentException("invalid_event_time")))
    }
    @Test fun malformedInputDoesNotLeakPayload() {
        assertEquals("invalid_event_request" to 400, orbisEventRequestFailure(SerializationException("synthetic private payload")))
    }
    @Test fun ownershipConflictRemainsConflict() {
        assertEquals("event_target_changed" to 409, orbisEventRequestFailure(IllegalStateException("event_target_changed")))
    }
    @Test fun storageFailureRemainsAmbiguousWithoutLeakingDetails() {
        assertEquals("event_request_failed" to 503, orbisEventRequestFailure(java.io.IOException("private storage location")))
    }
    @Test fun capacityAndSizeRemainDistinct() {
        assertEquals("event_inbox_full" to 507, orbisEventRequestFailure(IllegalArgumentException("event_inbox_full")))
        assertEquals("event_request_too_large" to 413, orbisEventRequestFailure(IllegalArgumentException("event_request_too_large")))
    }
}
