package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests production eligibility directly; never disable the instrumentation recovery gate. */
class LocationUploaderEligibilityTest {
    private val url = "https://ingress.example.test/loverconnect/alert"

    @Test fun disabledSenderNeverReadsQueue() {
        assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(false, url, 32) {
            throw AssertionError("Queue must not be opened while disabled")
        })
    }

    @Test fun missingCredentialsNeverReadQueue() {
        assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(true, url, 0) {
            throw AssertionError("Queue must not be opened without credentials")
        })
        assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(true, url, 15) { true })
    }

    @Test fun invalidOrUnrelatedEndpointNeverReadsQueue() {
        listOf("", "https://ingress.example.test/unrelated", "http://public.example.test/loverconnect/alert").forEach {
            assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(true, it, 32) {
                throw AssertionError("Queue must not be opened for an invalid endpoint")
            })
        }
    }

    @Test fun validSenderWithEmptyOrAbsentQueueDoesNotStart() {
        var reads = 0
        assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(true, url, 32) { reads++; false })
        assertEquals(1, reads)
    }

    @Test fun validSenderWithPendingWorkStillStarts() {
        assertTrue(LocationSafetyUploadPolicy.shouldStartUploader(true, url, 16) { true })
    }

    @Test fun deliveryEligibilityDoesNotRequireActiveTracking() {
        // Queue delivery remains eligible independently of tracking being off or paused.
        assertTrue(LocationSafetyUploadPolicy.shouldStartUploader(true,
            "http://127.0.0.1:8790/loverconnect/alert", 32) { true })
    }

    @Test fun unreadableQueueFailsClosedWithoutDiscardingAnything() {
        assertFalse(LocationSafetyUploadPolicy.shouldStartUploader(true, url, 32) {
            throw IllegalStateException("synthetic unreadable queue")
        })
    }
}
