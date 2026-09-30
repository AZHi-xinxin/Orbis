package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException

/** Never retry unknown outcomes, partial streams, or a tool continuation. */
internal fun consultationBusyDelayMillis(
    error: Throwable, deadline: Long?, now: Long, sawOutput: Boolean,
): Long? {
    if (deadline == null || deadline <= now || sawOutput) return null
    val http = error as? HttpException ?: return null
    if (http.httpStatus != 409 || http.errorType != "stiller_gateway_error" ||
        http.code != "human_turn_in_progress" || !http.gatewayBusyBeforeGeneration) return null
    return minOf(2_000L, deadline - now)
}
