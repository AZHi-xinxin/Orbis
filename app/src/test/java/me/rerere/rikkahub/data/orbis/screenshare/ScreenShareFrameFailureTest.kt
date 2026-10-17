package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ScreenShareFrameFailureTest {
    private fun reason(error: Exception) = Json.parseToJsonElement(
        (screenShareFrameFailureReply(error).single() as UIMessagePart.Text).text).jsonObject

    @Test fun throttleHasItsOwnCodeAndRemainingTime() {
        val error = runCatching { requireScreenCaptureInterval(2_000, 1_000) }.exceptionOrNull() as ScreenShareFrameFailure
        assertEquals("screen_capture_too_frequent", reason(error)["reason_code"]?.jsonPrimitive?.content)
        assertEquals("2000", reason(error)["retry_after_ms"]?.jsonPrimitive?.content)
        requireScreenCaptureInterval(4_000, 1_000)
        requireScreenCaptureInterval(1, 0)
    }

    @Test fun knownReasonsStayDistinctAndUnknownExceptionCannotLeakContent() {
        ScreenShareFrameReason.entries.forEach { known ->
            assertEquals(known.code, reason(ScreenShareFrameFailure(known))["reason_code"]?.jsonPrimitive?.content)
        }
        val result = reason(IllegalStateException("private screen content"))
        assertEquals("screen_frame_unavailable", result["reason_code"]?.jsonPrimitive?.content)
        assertFalse(result.toString().contains("private screen content"))
    }

    private fun access(session: String? = "s", current: String? = "s", epoch: Long = 1,
        authorized: Boolean = true, enabled: Boolean = true, locked: Boolean = false) =
        requireScreenFrameAccess("owner", "chat", session, 1, "owner", "chat", current, epoch,
            enabled, authorized, locked)

    private fun checkReason(expected: ScreenShareFrameReason, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull() as ScreenShareFrameFailure
        assertEquals(expected, failure.reason)
    }

    @Test fun accessChecksExplainActualCauseWithoutChangingAuthorization() {
        access()
        checkReason(ScreenShareFrameReason.NOT_BOUND) { access(session = null) }
        checkReason(ScreenShareFrameReason.CHANGED) { access(current = "new") }
        checkReason(ScreenShareFrameReason.CHANGED) { access(epoch = 2) }
        checkReason(ScreenShareFrameReason.ENDED) { access(current = null) }
        checkReason(ScreenShareFrameReason.AUTHORIZATION_LOST) { access(authorized = false) }
        checkReason(ScreenShareFrameReason.PAUSED) { access(enabled = false) }
        checkReason(ScreenShareFrameReason.LOCKED) { access(locked = true) }
    }

    @Test fun localCaptureTimeoutIsExplainedButCancellingChatStillCancels() = runTest {
        val local = screenShareFrameResult { withTimeout(1) { awaitCancellation() } }
        assertTrue((local.single() as UIMessagePart.Text).text.contains("screen_capture_timeout"))
        val cancelledFrame = screenShareFrameResult { throw CancellationException("frame withdrawn") }
        assertTrue((cancelledFrame.single() as UIMessagePart.Text).text.contains("screen_capture_cancelled"))
        val chat = async { screenShareFrameResult { awaitCancellation() } }
        runCurrent()
        chat.cancelAndJoin()
        assertTrue(chat.isCancelled)
    }
}
