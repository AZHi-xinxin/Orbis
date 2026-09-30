package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBottomFollowStateTest {
    private fun ChatBottomFollowState.layout(bottom: Boolean, scrolling: Boolean = false,
        dragging: Boolean = false, hasItems: Boolean = true) = onLayout(hasItems, bottom, scrolling, dragging)

    @Test fun openingAtHistoryDoesNotJumpToTheBottom() {
        val state = ChatBottomFollowState()
        repeat(3) { assertFalse(state.layout(bottom = false)) }
    }

    @Test fun bottomPinSurvivesNewContentPushingTheEndOutOfView() {
        val state = ChatBottomFollowState()
        assertFalse(state.layout(bottom = true))
        repeat(5) { assertTrue(state.layout(bottom = false)) }
        assertFalse(state.layout(bottom = true))
    }

    @Test fun gestureAtBottomIsNotCompetedWith() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        assertFalse(state.layout(bottom = true, dragging = true))
        assertFalse(state.layout(bottom = false, dragging = true))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun FlingAwaySuspendsFollowUntilTheReaderReturnsToBottom() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        assertFalse(state.layout(bottom = false, scrolling = true))
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = true, scrolling = true))
        assertFalse(state.layout(bottom = true))
        assertTrue(state.layout(bottom = false))
    }

    @Test fun explicitHistoryJumpSuppressesTheOldBottomLayoutBeforeRemeasure() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.stopForNavigation()
        repeat(3) { assertFalse(state.layout(bottom = true)) }
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun explicitBottomButtonRearmsFollowing() {
        val state = ChatBottomFollowState()
        state.stopForNavigation()
        state.layout(bottom = false)
        state.followBottom()
        assertTrue(state.layout(bottom = false))
        assertFalse(state.layout(bottom = true))
    }

    @Test fun noItemsCannotInventABottomPin() {
        val state = ChatBottomFollowState()
        assertFalse(state.layout(bottom = true, hasItems = false))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun disablingAndReenablingWhileReadingHistoryDoesNotRecallOldPin() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.forgetPosition()
        assertFalse(state.layout(bottom = false))
    }

    @Test fun finishingGenerationDoesNotResetTheLayoutOnlyPolicy() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        // Generation ends, then Markdown/image height changes: no loading flag is required.
        assertTrue(state.layout(bottom = false))
        state.layout(bottom = true)
        assertTrue(state.layout(bottom = false))
    }

    @Test fun ownInstantRemeasureDoesNotLookLikeAUserScroll() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        assertTrue(state.layout(bottom = false))
        assertFalse(state.layout(bottom = true))
        assertTrue(state.layout(bottom = false))
    }

    @Test fun independentConversationStateDoesNotInheritAnotherConversationsPin() {
        val old = ChatBottomFollowState()
        old.layout(bottom = true)
        assertTrue(old.layout(bottom = false))
        assertFalse(ChatBottomFollowState().layout(bottom = false))
    }

    @Test fun newerHistoryNavigationOverridesSendIntentBeforeNewReplyLayout() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.followForSend(scrolling = false)
        state.stopForNavigation()
        assertFalse(state.layout(bottom = true)) // Old layout before the requested jump.
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = false)) // New reply arrives; no deferred send command.
    }

    @Test fun newerUpwardGestureOverridesSendIntent() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.followForSend(scrolling = false)
        assertFalse(state.layout(bottom = false, scrolling = true, dragging = true))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun sendDuringAnExistingScrollDoesNotArmOrDeferFollowing() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.followForSend(scrolling = true)
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun idleSendFromHistoryCanFollowTheActualNewReplyLayout() {
        val state = ChatBottomFollowState()
        assertFalse(state.layout(bottom = false))
        state.followForSend(scrolling = false)
        assertTrue(state.layout(bottom = false))
        assertFalse(state.layout(bottom = true))
    }

    @Test fun sendWhileObservationIsDisabledCannotRestoreAStalePinOnReenable() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.forgetPosition()
        state.followForSend(scrolling = false)
        // The reader moves to history while disabled, so no intermediate onLayout runs.
        assertFalse(state.layout(bottom = false, hasItems = false))
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = false))
    }

    @Test fun bottomButtonWhileDisabledCannotOverrideLaterUnobservedScroll() {
        val state = ChatBottomFollowState()
        state.layout(bottom = true)
        state.forgetPosition()
        state.followBottom()
        assertFalse(state.layout(bottom = false))
        assertFalse(state.layout(bottom = false))
        state.layout(bottom = true)
        assertTrue(state.layout(bottom = false)) // Returning to the bottom still rearms.
    }

    @Test fun firstExplicitBottomInitializationIsNotTreatedAsReenabling() {
        val state = ChatBottomFollowState()
        state.followBottom()
        assertFalse(state.layout(bottom = false, hasItems = false))
        assertTrue(state.layout(bottom = false))
    }
}
