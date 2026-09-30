package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

private data class BottomFollowLayout(
    val layout: LazyListLayoutInfo,
    val atBottom: Boolean,
    val scrolling: Boolean,
    val dragging: Boolean,
)

/** Follow actual remeasurements, including deferred bubble parsing and decoded image height. */
@Composable
internal fun ChatListAutoFollow(
    state: LazyListState,
    followState: ChatBottomFollowState,
    enabled: Boolean,
) {
    val dragged by state.interactionSource.collectIsDraggedAsState()
    val latestDragged by rememberUpdatedState(dragged)
    LaunchedEffect(state, followState, enabled) {
        if (!enabled) {
            followState.forgetPosition()
            return@LaunchedEffect
        }
        snapshotFlow {
            BottomFollowLayout(state.layoutInfo, !state.canScrollForward,
                state.isScrollInProgress, latestDragged)
        }.collect { frame ->
            if (followState.onLayout(frame.layout.totalItemsCount > 0, frame.atBottom,
                    frame.scrolling, frame.dragging)) {
                // requestScrollToItem schedules one remeasure, not an animated scroll/gesture.
                // The real final spacer works even if a single reply is taller than the screen.
                state.requestScrollToItem(frame.layout.totalItemsCount - 1)
            }
        }
    }
}
