package me.rerere.rikkahub.data.orbis.screenshare

/** Local evidence, not a network ping or a promise that an idle model is connected. */
enum class ScreenShareHealth { IDLE, CONNECTING, READY, BUSY, PAUSED, ERROR, STALE, AUTHORIZATION_LOST }

internal data class ScreenShareHealthInput(
    val active: Boolean, val authorized: Boolean = true, val screenEnabled: Boolean = true,
    val frameAt: Long = 0, val now: Long, val intervalSeconds: Int = 30,
    val generationActive: Boolean = false, val queued: Boolean = false,
    val paused: Boolean = false, val pendingTool: Boolean = false, val voiceBlocked: Boolean = false,
    val recoveryBlocked: Boolean = false,
    val captureFailed: Boolean = false, val helperFailed: Boolean = false,
    val observerFailed: Boolean = false, val retryScheduled: Boolean = false,
    val chatFailed: Boolean = false,
)
internal data class ScreenShareHealthPresentation(val health: ScreenShareHealth, val text: String, val canRetry: Boolean)

internal fun screenShareHealth(input: ScreenShareHealthInput): ScreenShareHealthPresentation = with(input) {
    fun present(state: ScreenShareHealth, text: String, retry: Boolean = false) = ScreenShareHealthPresentation(state, text, retry)
    when {
        !authorized -> present(ScreenShareHealth.AUTHORIZATION_LOST, "屏幕授权已结束，请重新授权")
        !active -> present(ScreenShareHealth.IDLE, "共享已结束")
        !screenEnabled -> present(ScreenShareHealth.PAUSED, "画面已关闭")
        pendingTool -> present(ScreenShareHealth.PAUSED, "工具待处理，请查看原聊天")
        recoveryBlocked -> present(ScreenShareHealth.ERROR, "旧轮待安全核对，请查看原聊天")
        paused || voiceBlocked -> present(ScreenShareHealth.PAUSED, "回复已暂停，可检查新输入连接", true)
        observerFailed -> present(ScreenShareHealth.ERROR, "聊天状态暂不可读", true)
        captureFailed -> present(ScreenShareHealth.ERROR, if (retryScheduled) "画面暂不可用，正在重试" else "画面采集失败，可重试", true)
        generationActive || queued -> present(ScreenShareHealth.BUSY, "正在回复或处理消息")
        chatFailed -> present(ScreenShareHealth.ERROR, "聊天有新报错，可检查连接", true)
        frameAt <= 0 -> present(ScreenShareHealth.CONNECTING, "正在等待首帧", true)
        now - frameAt > maxOf(15_000L, intervalSeconds * 2_000L + 5_000L) -> present(ScreenShareHealth.STALE, "画面已过期，可重试采集", true)
        helperFailed -> present(ScreenShareHealth.ERROR, if (retryScheduled) "画面就绪，观察正在重试" else "画面就绪，观察暂不可用", true)
        else -> present(ScreenShareHealth.READY, "画面就绪")
    }
}

/** Bounded retries apply only to read-only screen capture/helper requests, never human/tool turns. */
internal data class ScreenShareRetry(val failures: Int = 0, val nextAt: Long? = null) {
    val exhausted: Boolean get() = failures > 0 && nextAt == null
    fun failed(now: Long): ScreenShareRetry {
        val failures = (failures + 1).coerceAtMost(4)
        val delay = listOf(3_000L, 5_000L, 15_000L).getOrNull(failures - 1)
        return ScreenShareRetry(failures, delay?.let { now + it })
    }
    fun permits(now: Long): Boolean = !exhausted && (nextAt == null || now >= nextAt)
}

internal data class ScreenShareReply(val id: String, val text: String, val assistant: Boolean,
    val event: Boolean = false, val synthetic: Boolean = false)

/** Baseline includes every pre-existing branch. A pre-existing active streaming reply is explicit. */
internal fun latestScreenShareReply(messages: List<ScreenShareReply>, baselineIds: Set<String>, streamingAtStart: String?): ScreenShareReply? =
    messages.lastOrNull { it.assistant && !it.event && !it.synthetic && (it.id !in baselineIds || it.id == streamingAtStart) }
