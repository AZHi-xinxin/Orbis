package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OrbisVideoFailureOwnershipTest {
    private fun live(callId: String = "call-new") = OrbisVideoCallState(
        callId = callId, assistantId = "owner", conversationId = "conversation",
        foreground = true, capturedCount = 2, lastFrameId = "frame-new", notice = "new-call-notice",
    )

    @Test fun `late old call storage limit cannot pause or replace new call notice`() = runTest {
        val state = live()
        val oldToken = Any()
        val newToken = Any()
        val result = scopedVideoFailureState(state, newToken, "call-old", oldToken,
            "old storage limit", pauseSampling = true)
        assertSame(state, result)
        assertFalse(result.samplingPaused)
        assertEquals("new-call-notice", result.notice)
    }

    @Test fun `same call late old attachment cannot pause or notify replacement camera`() = runTest {
        val state = live()
        for (pause in listOf(false, true)) {
            assertSame(state, scopedVideoFailureState(state, Any(), "call-new", Any(),
                "old camera failure", pauseSampling = pause))
        }
        assertSame(state, scopedVideoFailureState(state, null, "call-new", Any(),
            "camera detached", pauseSampling = true))
    }

    @Test fun `late sampler notice never targets another call even without camera token`() = runTest {
        val state = live()
        assertSame(state, scopedVideoFailureState(state, null, "call-old", null, "old skipped frame"))
        assertSame(state, scopedVideoFailureState(state, Any(), "call-old", Any(), "old frame failure"))
    }

    @Test fun `current owner receives storage pause and sampler notice preserves pause`() = runTest {
        val token = Any()
        val state = live()
        val paused = scopedVideoFailureState(state, token, "call-new", token, "storage full", true)
        assertTrue(paused.samplingPaused)
        assertEquals(state.copy(samplingPaused = true, notice = "storage full"), paused)
        val notice = scopedVideoFailureState(paused, token, "call-new", token, "frame skipped")
        assertTrue(notice.samplingPaused)
        assertEquals("frame skipped", notice.notice)
    }

    @Test fun `outer cancellation propagates instead of publishing any failure state`() = runTest {
        val token = Any()
        val state = live()
        var published = state
        try {
            withTimeout(10) {
                try { delay(20) }
                catch (_: CancellationException) {
                    published = scopedVideoFailureState(published, token, "call-new", token,
                        "must not publish", pauseSampling = true)
                }
            }
            fail("The surrounding timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertSame(state, published)
    }
}
