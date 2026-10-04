package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Ending the local owner is not evidence that an external tool succeeded or was cancelled. */
internal data class GatewayTerminalSummary(
    val attempted: Boolean = false,
    val checked: Int = 0,
    val retired: Int = 0,
    val pending: Int = 0,
    val uncertain: Boolean = false,
) {
    val mustKeepPaused: Boolean get() = attempted && (pending > 0 || uncertain)
    val notice: String get() = if (mustKeepPaused)
        "本地本轮已停止，但网关收尾尚未确认；队列保持暂停，可点检查恢复。没有重发消息或重做工具。"
    else if (retired > 0)
        "已自动结束本轮遗留的网关等待；工具结果未知的步骤仍需核对，没有重做工具。"
    else "本轮已记录的网关请求不再占用旧轮；没有重发消息或重做工具。"
}

/**
 * Use only this ended invocation's captured requests (including an exactly matched approval
 * handoff). A verified response advertises the protocol before any automatic control traffic.
 * A newer continuation may lose its response headers, so inspect same-scope requests as well.
 * The status permit, not the local terminal classification, authorizes each exact retirement.
 */
internal suspend fun <T> finishTerminatedGatewayRequests(
    requests: List<T>,
    advertised: (T) -> Boolean,
    sameScope: (T, T) -> Boolean,
    probe: suspend (T) -> GatewayStopProbe,
    finish: suspend (request: T, capabilityEvidence: T) -> Boolean,
): GatewayTerminalSummary {
    val evidence = requests.filter(advertised)
    val selected = requests.take(16).mapNotNull { request ->
        evidence.firstOrNull { sameScope(request, it) }?.let { request to it }
    }
    if (selected.isEmpty()) return GatewayTerminalSummary()
    // Key roulette or a changed route can leave another request outside the proved scope.
    // Do not send its credentials to control, but do not report the whole invocation clear.
    var result = GatewayTerminalSummary(attempted = true, uncertain = selected.size != requests.size)
    return try {
        // Short bounded housekeeping before the owning job completes, never an unbounded retry
        // or background model/tool execution. The original server timeout remains a fallback.
        withTimeoutOrNull(4_000) {
            for ((request, proof) in selected) {
                result = result.copy(checked = result.checked + 1)
                when (probe(request)) {
                    GatewayStopProbe.NOT_CURRENT -> Unit
                    GatewayStopProbe.CAN_STOP -> {
                        result = if (finish(request, proof)) result.copy(retired = result.retired + 1)
                        else result.copy(pending = result.pending + 1)
                    }
                    GatewayStopProbe.GENERATING, GatewayStopProbe.CLEANUP_PENDING,
                    GatewayStopProbe.UNSUPPORTED -> result = result.copy(pending = result.pending + 1)
                }
            }
            result
        } ?: result.copy(uncertain = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        result.copy(uncertain = true)
    }
}
