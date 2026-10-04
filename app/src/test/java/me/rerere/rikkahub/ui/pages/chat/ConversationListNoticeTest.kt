package me.rerere.rikkahub.ui.pages.chat

import androidx.paging.LoadState
import org.junit.Assert.*
import org.junit.Test

class ConversationListNoticeTest {
    private val ready = LoadState.NotLoading(endOfPaginationReached = true)
    private val failure = LoadState.Error(IllegalStateException("private provider error must not enter UI text"))

    @Test fun `initial loading does not mean an empty history`() {
        assertEquals(ConversationListNotice.LOADING, conversationListNotice(0, LoadState.Loading, ready, ready))
    }

    @Test fun `failed refresh is visible even with zero or cached rows`() {
        listOf(0, 4).forEach { count ->
            assertEquals(ConversationListNotice.REFRESH_FAILED, conversationListNotice(count, failure, ready, ready))
        }
    }

    @Test fun `cached rows remain visible during refresh`() {
        assertNull(conversationListNotice(4, LoadState.Loading, ready, ready))
    }

    @Test fun `failed pagination is not silently hidden`() {
        assertEquals(ConversationListNotice.MORE_FAILED, conversationListNotice(4, ready, failure, ready))
        assertEquals(ConversationListNotice.MORE_FAILED, conversationListNotice(0, ready, ready, failure))
    }

    @Test fun `pending pagination with no rows is still loading`() {
        assertEquals(ConversationListNotice.LOADING, conversationListNotice(0, ready, LoadState.Loading, ready))
        assertEquals(ConversationListNotice.LOADING, conversationListNotice(0, ready, ready, LoadState.Loading))
    }

    @Test fun `only a successful settled empty query shows empty`() {
        assertEquals(ConversationListNotice.EMPTY, conversationListNotice(0, ready, ready, ready))
        assertNull(conversationListNotice(4, ready, ready, ready))
    }
}
