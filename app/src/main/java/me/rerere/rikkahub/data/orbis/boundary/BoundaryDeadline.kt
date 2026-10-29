package me.rerere.rikkahub.data.orbis.boundary

/** Upgrade old unbounded pauses once; reopening cannot extend their deadline. No input is replayed. */
internal fun AssistantBoundary.restoreBoundedPauses(now: Long): AssistantBoundary {
    fun restore(target: String, pause: ConversationBoundary): ConversationBoundary {
        if (!pause.paused) return pause
        val start = pause.startedAt ?: events.lastOrNull { it.action == "pause" && it.target == target }?.at ?: now
        val maximum = start.coerceAtMost(now) + BOUNDARY_MAX_PAUSE_MS
        val deadline = pause.until?.coerceAtMost(maximum) ?: maximum
        // A process restart has no surviving generation to own a pending claim. A new tap is required.
        return pause.copy(until = deadline, startedAt = start, requestUsed = false,
            revision = if (deadline != pause.until) revision + 1 else pause.revision)
    }
    val next = copy(conversations = conversations.mapValues { (id, pause) -> restore(id, pause) },
        assistantPause = assistantPause?.let { restore("assistant", it) },
        events = events.takeLast(BOUNDARY_HISTORY_LIMIT))
    return if (next.conversations != conversations || next.assistantPause != assistantPause)
        next.event(now, "system", "pause_policy_restored", "") else next
}

internal fun boundaryRemaining(until: Long, now: Long): String {
    val seconds = ((until - now).coerceAtLeast(0) + 999) / 1000
    return when {
        seconds == 0L -> "已到约定恢复时间"
        seconds >= 3600 -> "还剩 ${seconds / 3600} 小时 ${seconds % 3600 / 60} 分 ${seconds % 60} 秒"
        seconds >= 60 -> "还剩 ${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "还剩 $seconds 秒"
    }
}
