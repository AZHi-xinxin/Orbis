package me.rerere.rikkahub.data.orbis.companiontools

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ForegroundChatRequestTest {
    @Test fun acceptedLaunchWithoutActualVisibleChatIsNotSuccess() = runTest {
        val tracker=ForegroundChatRequest()
        var launches=0
        val result=tracker.request("one") { launches++ }
        assertEquals(1,launches)
        assertFalse(result.confirmed)
        assertEquals("foreground_not_observed",result.status)
    }
    @Test fun onlyFocusedRequestedChatAcknowledgesLaunch() = runTest {
        val tracker=ForegroundChatRequest()
        val request=async { tracker.request("wanted") {} }
        delay(10)
        tracker.observe("other","other",true)
        tracker.observe("wanted","wanted",false)
        assertFalse(request.isCompleted)
        tracker.observe("wanted","wanted",true)
        assertEquals(ForegroundChatReceipt(true,"visible"),request.await())
    }
    @Test fun focusedExistingChatNeedsNoLaunchAndLeavingClearsIt() = runTest {
        val tracker=ForegroundChatRequest()
        tracker.observe("one","chat",true)
        assertEquals("already_visible",tracker.request("chat") { error("must not launch") }.status)
        tracker.observe("one","chat",false)
        assertFalse(tracker.isVisible("chat"))
    }
    @Test fun obsoleteScreenDisposalDoesNotClearReplacementScreen() {
        val tracker=ForegroundChatRequest()
        tracker.observe("old","chat",true)
        tracker.observe("new","chat",true)
        tracker.observe("old","chat",false)
        assertTrue(tracker.isVisible("chat"))
    }
    @Test fun deniedLaunchDoesNotWaitOrClaimSuccess() = runTest {
        val tracker=ForegroundChatRequest()
        val result=tracker.request("one") { throw SecurityException() }
        assertFalse(result.confirmed)
        assertEquals("system_permission_required",result.status)
    }
    @Test fun concurrentRequestsCannotRaceTwoChatsToForeground() = runTest {
        val tracker=ForegroundChatRequest()
        val first=async { tracker.request("one") {} }
        delay(1)
        assertEquals("another_request_running",tracker.request("two") { error("must not launch") }.status)
        tracker.observe("one","one",true)
        assertTrue(first.await().confirmed)
    }
}
