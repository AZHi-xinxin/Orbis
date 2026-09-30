package me.rerere.rikkahub.ui.pages.chat

/**
 * Tracks the reader's intent, not whether the *new* layout still fits the viewport. Content
 * growth may move the bottom off screen without cancelling an already pinned reader.
 * No timers, message data, or assumptions about generation/Markdown are involved.
 */
class ChatBottomFollowState {
    private var following: Boolean? = null
    private var awaitingNavigation = false
    private var resamplePosition = false

    /** Explicit history navigation must win even before its requested remeasure arrives. */
    fun stopForNavigation() {
        following = false
        awaitingNavigation = true
    }

    /** The reader explicitly chose the bottom button. */
    fun followBottom() {
        following = true
        awaitingNavigation = false
    }

    /**
     * Decide once, synchronously with sending. Actual new-content layout performs the scroll;
     * no delayed callback may override a later gesture or explicit history navigation.
     */
    fun followForSend(scrolling: Boolean) {
        if (scrolling) following = false else followBottom()
    }

    /** Re-enabling auto-scroll samples the current position instead of recalling an old pin. */
    fun forgetPosition() {
        following = null
        // Send/bottom-button handlers can run while observation is disabled. They must not
        // restore an old pin that later overrides scrolling performed while unobserved.
        resamplePosition = true
    }

    fun onLayout(hasItems: Boolean, atBottom: Boolean, scrolling: Boolean, dragging: Boolean): Boolean {
        if (!hasItems) return false
        if (resamplePosition) {
            following = atBottom
            resamplePosition = false
        }
        if (scrolling || dragging) {
            if (!atBottom) {
                following = false
                awaitingNavigation = false
            } else if (!awaitingNavigation) following = true
            return false // Never compete with a gesture, fling or explicit animated jump.
        }
        if (awaitingNavigation) {
            if (!atBottom) awaitingNavigation = false
            return false
        }
        if (atBottom) following = true
        if (following == null) following = atBottom // Opening at history is not a bottom request.
        return following == true && !atBottom
    }
}
