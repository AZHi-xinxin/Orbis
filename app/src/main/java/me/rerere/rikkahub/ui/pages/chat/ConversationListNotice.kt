package me.rerere.rikkahub.ui.pages.chat

import androidx.paging.LoadState

internal enum class ConversationListNotice { LOADING, REFRESH_FAILED, MORE_FAILED, EMPTY }

/** A failed or still-loading query must never be presented as proof that history is empty. */
internal fun conversationListNotice(
    itemCount: Int,
    refresh: LoadState,
    append: LoadState,
    prepend: LoadState,
): ConversationListNotice? = when {
    refresh is LoadState.Error -> ConversationListNotice.REFRESH_FAILED
    refresh is LoadState.Loading -> if (itemCount == 0) ConversationListNotice.LOADING else null
    append is LoadState.Error || prepend is LoadState.Error -> ConversationListNotice.MORE_FAILED
    itemCount == 0 && (append is LoadState.Loading || prepend is LoadState.Loading) -> ConversationListNotice.LOADING
    itemCount == 0 -> ConversationListNotice.EMPTY
    else -> null
}
