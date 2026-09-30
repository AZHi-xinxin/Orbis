package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real measured LazyColumn with synthetic height changes; no chat/provider/storage access. */
@RunWith(AndroidJUnit4::class)
class ChatListAutoFollowTest {
    @get:Rule val compose = createShellComposeRule()
    private lateinit var list: LazyListState
    private val follow = ChatBottomFollowState()
    private var replyHeight by mutableIntStateOf(100)
    private var appended by mutableIntStateOf(0)
    private var loading by mutableStateOf(true)
    private var enabled by mutableStateOf(true)

    private fun show(atBottom: Boolean = true) {
        compose.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = if (atBottom) 1000 else 2)
            ChatListAutoFollow(list, follow, enabled)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.height(320.dp).testTag("follow-list")) {
                items(12, key = { "history-$it" }) {
                    Box(Modifier.fillMaxWidth().height(64.dp))
                }
                item("reply") { Box(Modifier.fillMaxWidth().height(replyHeight.dp)) }
                if (loading) item("loading") { Spacer(Modifier.height(36.dp)) }
                items(appended, key = { "new-$it" }) { Box(Modifier.fillMaxWidth().height(450.dp)) }
                item("bottom") { Spacer(Modifier.fillMaxWidth().height(5.dp).testTag("follow-bottom")) }
            }
        }
        compose.waitUntil(5_000) { list.layoutInfo.totalItemsCount > 0 }
        if (atBottom) awaitBottom()
    }

    private fun awaitBottom() {
        compose.waitUntil(5_000) {
            !list.isScrollInProgress && !list.canScrollForward &&
                list.layoutInfo.visibleItemsInfo.any { it.key == "bottom" }
        }
    }

    private fun position(): Pair<Int, Int> = compose.runOnIdle {
        list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
    }

    @Test fun pinnedBottomFollowsStreamingPastViewportAndLateFinalImageHeight() {
        show()
        listOf(450, 800, 1200).forEach { height ->
            compose.runOnIdle { replyHeight = height }
            awaitBottom()
        }
        compose.runOnIdle { loading = false; replyHeight = 1500 }
        awaitBottom()
        compose.runOnIdle { replyHeight = 2200 } // Delayed image decode/final bubble remeasure.
        awaitBottom()
    }

    @Test fun newReplyInsertionDoesNotCancelAnExistingBottomPin() {
        show()
        compose.runOnIdle { appended = 1 }
        awaitBottom()
        compose.runOnIdle { appended = 2 }
        awaitBottom()
    }

    @Test fun userScrollingUpIsNotPulledBackByStreamingOrFinalLayout() {
        show()
        compose.onNodeWithTag("follow-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { !list.isScrollInProgress }
        assertTrue(list.canScrollForward)
        val before = position()
        compose.runOnIdle { replyHeight = 1600; loading = false; appended = 1 }
        compose.waitForIdle()
        assertEquals(before, position())
        assertTrue(list.canScrollForward)
    }

    @Test fun openingAtHistoryDoesNotForceScrollOnContentGrowth() {
        show(atBottom = false)
        val before = position()
        compose.runOnIdle { replyHeight = 1300; appended = 1 }
        compose.waitForIdle()
        assertEquals(before, position())
        assertTrue(list.canScrollForward)
    }

    @Test fun explicitHistoryJumpWinsThenBottomButtonRestoresFollow() {
        show()
        compose.runOnIdle { follow.stopForNavigation(); list.requestScrollToItem(2) }
        compose.waitForIdle()
        assertEquals(2, list.firstVisibleItemIndex)
        compose.runOnIdle { replyHeight = 1400 }
        compose.waitForIdle()
        assertEquals(2, list.firstVisibleItemIndex)
        compose.runOnIdle { follow.followBottom(); list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
        awaitBottom()
        compose.runOnIdle { replyHeight = 1800 }
        awaitBottom()
    }

    @Test fun disabledAutoScrollDoesNotFollowOrRecallAStalePinWhenReenabled() {
        show()
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        val before = position()
        compose.runOnIdle { replyHeight = 1700 }
        compose.waitForIdle()
        assertEquals(before, position())
        assertTrue(list.canScrollForward)
        compose.runOnIdle { enabled = true }
        compose.waitForIdle()
        assertEquals(before, position())
        assertFalse(list.isScrollInProgress)
    }

    @Test fun historyNavigationAfterSendWinsWhenTheNewReplyArrivesLater() {
        show()
        compose.runOnIdle {
            follow.followForSend(list.isScrollInProgress)
            follow.stopForNavigation()
            list.requestScrollToItem(2)
        }
        compose.waitForIdle()
        val before = position()
        assertEquals(2, before.first)
        compose.runOnIdle { appended = 1; replyHeight = 1600 }
        compose.waitForIdle()
        assertEquals(before, position())
        assertTrue(list.canScrollForward)
    }

    @Test fun upwardGestureAfterSendWinsOverLaterStreamingLayout() {
        show()
        compose.runOnIdle { follow.followForSend(list.isScrollInProgress) }
        compose.onNodeWithTag("follow-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { !list.isScrollInProgress }
        val before = position()
        assertTrue(list.canScrollForward)
        compose.runOnIdle { appended = 1; replyHeight = 1800; loading = false }
        compose.waitForIdle()
        assertEquals(before, position())
    }

    @Test fun sendWhileDisabledThenSwipeUpwardDoesNotPullBackOnReenable() {
        show()
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { follow.followForSend(list.isScrollInProgress); appended = 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("follow-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { !list.isScrollInProgress }
        val before = position()
        assertTrue(list.canScrollForward)
        compose.runOnIdle { enabled = true }
        compose.waitForIdle()
        assertEquals(before, position())
        compose.runOnIdle { appended = 2 }
        compose.waitForIdle()
        assertEquals(before, position())
    }

    @Test fun bottomButtonWhileDisabledThenSwipeUpwardDoesNotPullBackOnReenable() {
        show(atBottom = false)
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { follow.followBottom(); list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1) }
        awaitBottom()
        compose.onNodeWithTag("follow-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { !list.isScrollInProgress }
        val before = position()
        assertTrue(list.canScrollForward)
        compose.runOnIdle { enabled = true }
        compose.waitForIdle()
        assertEquals(before, position())
    }
}
