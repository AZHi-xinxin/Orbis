package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal enum class GatewayStopProbe { NOT_CURRENT, UNSUPPORTED, CAN_STOP, GENERATING, CLEANUP_PENDING }
internal data class GatewayStopSummary(val retired: Int = 0, val pending: Int = 0,
    val unsupported: Int = 0, val checked: Int = 0, val uncertain: Boolean = false) {
    val notice: String get() = when {
        uncertain -> "未能确认网关旧轮已结束，队列保持暂停。可稍后再次检查；没有重发消息或工具。"
        pending > 0 -> "网关旧轮仍在执行或收尾，暂不能解除等待。队列保持暂停，可稍后再次检查；不会重做工具。"
        retired > 0 -> "已确认结束匹配的网关旧轮等待。外部工具是否已经执行仍需核对；不会重做工具。确认后可恢复后续消息。" +
            if (unsupported > 0) "另有旧连接不支持核对接口，不能据此认定全部连接空闲。" else ""
        unsupported > 0 -> "当前服务未提供旧轮核对接口，未强行解除等待。队列保持暂停；需等待原服务收尾或检查服务状态。"
        checked > 0 -> "已记录的请求已不占用网关当前轮次。没有重发消息或工具；核对聊天后可恢复后续消息。"
        else -> "没有本次运行的精确网关请求记录，未改动远端状态。重启前的旧轮不能据此强制解锁；队列保持暂停。"
    }
}

/** Only inspect remembered exact requests. One failed/new request cannot hide an older wait. */
internal suspend fun <T> stopRememberedGatewayRequests(
    requests: List<T>,
    probe: suspend (T) -> GatewayStopProbe,
    retire: suspend (T) -> Boolean,
): GatewayStopSummary {
    var result = GatewayStopSummary()
    for (request in requests.take(16)) {
        result = result.copy(checked = result.checked + 1)
        when (probe(request)) {
            GatewayStopProbe.NOT_CURRENT -> Unit
            GatewayStopProbe.UNSUPPORTED -> result = result.copy(unsupported = result.unsupported + 1)
            GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING -> result = result.copy(pending = result.pending + 1)
            GatewayStopProbe.CAN_STOP -> result = if (retire(request)) result.copy(retired = result.retired + 1)
                else result.copy(pending = result.pending + 1)
        }
    }
    return result
}

/** Bounded explicit check, never a background polling/retry loop. */
internal suspend fun boundedGatewayStopCheck(block: suspend () -> GatewayStopSummary): GatewayStopSummary = try {
    withTimeoutOrNull(15_000) { block() } ?: GatewayStopSummary(uncertain = true)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    GatewayStopSummary(uncertain = true)
}
