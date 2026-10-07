package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PeriodicVideoFrameAdmissionTest {
    @Test fun blockedPeriodicTickNeverInvokesTheCameraOrCreatesAFrame() = runTest {
        var captures = 0
        val result = capturePeriodicFrameIfAllowed(allowed = { false }, capture = { captures++; "frame" })
        assertNull(result)
        assertEquals(0, captures)
    }

    @Test fun permissionIsRecheckedAfterCaptureBeforeAnyFrameHandoff() = runTest {
        var allowed = true
        var captures = 0
        val result = capturePeriodicFrameIfAllowed(allowed = { allowed }, capture = {
            captures++
            allowed = false // recovery/owner change while the camera suspended
            "frame retained locally"
        })
        assertNull(result)
        assertEquals(1, captures)
    }

    @Test fun unchangedAdmissionAllowsExactlyOneFrame() = runTest {
        var checks = 0
        var captures = 0
        val result = capturePeriodicFrameIfAllowed(allowed = { checks++; true }, capture = { captures++; "frame" })
        assertEquals("frame", result)
        assertEquals(2, checks)
        assertEquals(1, captures)
    }

    @Test fun captureCancellationIsNotConvertedIntoAPermissionOrRetry() = runTest {
        var captures = 0
        try {
            capturePeriodicFrameIfAllowed(allowed = { true }, capture = {
                captures++
                throw CancellationException("synthetic sampler cancellation")
            })
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, captures)
        }
    }
}
