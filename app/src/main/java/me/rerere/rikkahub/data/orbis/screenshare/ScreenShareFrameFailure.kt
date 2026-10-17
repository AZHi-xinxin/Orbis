package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

internal enum class ScreenShareFrameReason(val code: String, val description: String) {
    NOT_BOUND("screen_share_not_bound", "本轮没有绑定已开启的屏幕共享；没有自动打开共享或麦克风。"),
    ENDED("screen_share_ended", "本次屏幕共享已结束，原画面不可用；可以继续文字对话。"),
    CHANGED("screen_share_changed", "共享会话或画面授权已更新，本轮旧绑定不可用；请在当前共享中重新发起查看。"),
    PAUSED("screen_share_paused", "共享画面暂时关闭，文字聊天仍可继续；没有自动开启画面。"),
    AUTHORIZATION_LOST("screen_share_authorization_lost", "系统屏幕授权已终止，需要人类重新授权；没有自动开启共享。"),
    LOCKED("screen_locked", "手机当前已锁屏，本次不能读取画面。"),
    RATE_LIMITED("screen_capture_too_frequent", "距上次采集不足3秒，请按retry_after_ms稍后再看；这不表示共享已结束。"),
    NOT_READY("screen_capture_not_ready", "共享采集器暂未就绪，尚未取得画面；可以继续文字对话。"),
    BUSY("screen_capture_busy", "上一张画面仍在读取，本次未重复采集；稍后再试。"),
    TIMEOUT("screen_capture_timeout", "本次画面读取超时，未取得画面；可以继续文字对话。"),
    CANCELLED("screen_capture_cancelled", "本次画面读取已取消，未取得画面；可以继续文字对话。"),
    EXPIRED("screen_frame_expired", "要回看的临时画面已不在缓冲中；这不表示共享已结束。"),
    INVALID_INDEX("screen_frame_index_invalid", "回看序号k必须为0到7。"),
    UNAVAILABLE("screen_frame_unavailable", "本次未取得可用画面，不能据此判断共享已结束；可以继续文字对话。"),
}

internal class ScreenShareFrameFailure(val reason: ScreenShareFrameReason, val retryAfterMs: Long? = null) :
    IllegalStateException(reason.code)

internal fun requireScreenFrameAccess(owner: String, conversation: String, session: String?, epoch: Long?,
    currentOwner: String?, currentConversation: String?, currentSession: String?, currentEpoch: Long,
    enabled: Boolean, authorized: Boolean, locked: Boolean) {
    val reason = when {
        session == null -> ScreenShareFrameReason.NOT_BOUND
        currentSession != null && (session != currentSession || owner != currentOwner || conversation != currentConversation) -> ScreenShareFrameReason.CHANGED
        !authorized -> ScreenShareFrameReason.AUTHORIZATION_LOST
        currentSession == null -> ScreenShareFrameReason.ENDED
        locked -> ScreenShareFrameReason.LOCKED
        !enabled -> ScreenShareFrameReason.PAUSED
        epoch != null && epoch != currentEpoch -> ScreenShareFrameReason.CHANGED
        else -> null
    }
    if (reason != null) throw ScreenShareFrameFailure(reason)
}

internal fun requireScreenCaptureInterval(now: Long, previous: Long) {
    if (previous == 0L) return
    val elapsed = (now - previous).coerceIn(0, 3000)
    if (elapsed < 3000) throw ScreenShareFrameFailure(ScreenShareFrameReason.RATE_LIMITED, 3000 - elapsed)
}

/** Only enumerated local facts are exposed; exception messages may contain private screen data. */
internal fun screenShareFrameFailureReply(error: Exception): List<UIMessagePart> {
    val failure = error as? ScreenShareFrameFailure ?: ScreenShareFrameFailure(when (error) {
        is TimeoutCancellationException -> ScreenShareFrameReason.TIMEOUT
        is CancellationException -> ScreenShareFrameReason.CANCELLED
        else -> ScreenShareFrameReason.UNAVAILABLE
    })
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("status", "unavailable")
        put("frame_available", false)
        put("reason_code", failure.reason.code)
        put("message", failure.reason.description)
        failure.retryAfterMs?.let { put("retry_after_ms", it) }
    }.toString()))
}

internal suspend fun screenShareFrameResult(read: suspend () -> List<UIMessagePart>): List<UIMessagePart> = try {
    read()
} catch (cancelled: CancellationException) {
    // Timeout/cancelled capture deferred is local. A cancelled chat must still end immediately.
    currentCoroutineContext().ensureActive()
    screenShareFrameFailureReply(cancelled)
} catch (failure: Exception) {
    screenShareFrameFailureReply(failure)
}
