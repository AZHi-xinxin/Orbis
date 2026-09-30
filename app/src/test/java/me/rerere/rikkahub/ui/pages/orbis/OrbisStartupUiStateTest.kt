package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic state only: explicit time avoids Android's clock; no Activity, settings or services. */
class OrbisStartupUiStateTest {
    private val target = "synthetic-target-chat"
    private fun state(enabled: Boolean = true, targetChatId: String? = target) =
        OrbisStartupUiState(enabled, targetChatId, startedAtMillis = 1234L)

    @Test fun matchingChatCanReportReadyWithoutChangingTheStartTime() {
        val state = state()
        assertTrue(state.visible)
        assertFalse(state.chatReady)
        state.reportChatReady(target)
        assertTrue(state.chatReady)
        assertTrue(state.visible) // The cover owner chooses its exit; readiness does not reopen or dismiss it.
        assertEquals(1234L, state.startedAtMillis)
    }

    @Test fun differentChatCannotSatisfyReadiness() {
        val state = state()
        state.reportChatReady("synthetic-other-chat")
        assertFalse(state.chatReady)
        assertTrue(state.visible)
        state.reportChatReady(target)
        assertTrue(state.chatReady)
    }

    @Test fun absentTargetDoesNotAcceptAnUnrelatedChat() {
        val state = state(targetChatId = null)
        state.reportChatReady(target)
        state.reportChatReady("")
        assertFalse(state.chatReady)
    }

    @Test fun lateReadinessCannotReopenADismissedCover() {
        val state = state()
        state.dismiss()
        repeat(10) { state.reportChatReady(target) }
        assertFalse(state.visible)
        assertFalse(state.chatReady)
        assertEquals(1234L, state.startedAtMillis)
    }

    @Test fun disabledCoverIgnoresReadinessAndDismiss() {
        val state = state(enabled = false)
        state.reportChatReady(target)
        state.dismiss()
        state.reportChatReady(target)
        assertFalse(state.visible)
        assertFalse(state.chatReady)
    }

    @Test fun manualDismissIsIdempotentBeforeAndAfterReadiness() {
        for (readyFirst in listOf(false, true)) {
            val state = state()
            if (readyFirst) state.reportChatReady(target)
            repeat(10) { state.dismiss() }
            state.reportChatReady(target)
            assertFalse(state.visible)
            assertEquals(readyFirst, state.chatReady)
            assertEquals(1234L, state.startedAtMillis)
        }
    }
}
