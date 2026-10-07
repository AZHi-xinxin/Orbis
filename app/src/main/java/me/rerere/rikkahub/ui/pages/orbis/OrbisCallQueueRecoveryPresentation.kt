package me.rerere.rikkahub.ui.pages.orbis

/** Only suppress known queue/skip notices; camera permissions, capture and storage errors remain. */
internal fun videoNoticeAlongsideQueueRecovery(notice: String?, replyBlocked: Boolean): String? {
    if (notice == null || !replyBlocked) return notice
    return notice.takeUnless {
        it.startsWith("聊天队列已暂停") || it.startsWith("当前消息队列卡顿") ||
            it.startsWith("本次画面已跳过") || it.startsWith("本次画面未获得回复，已跳过")
    }
}
