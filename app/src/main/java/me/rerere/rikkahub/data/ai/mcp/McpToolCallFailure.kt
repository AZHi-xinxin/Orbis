package me.rerere.rikkahub.data.ai.mcp

import io.ktor.client.plugins.ResponseException
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Only closed diagnostic facts cross into chat/checkpoints. Never retain an upstream body,
 * URL, credential, arguments or the original exception as a cause. No automatic tool replay. */
internal class McpToolCallFailure(
    val code: String,
    val httpStatus: Int?,
    val elapsedMs: Long,
) : IllegalStateException(buildString {
    append("MCP 工具调用未完成 [").append(code).append("]")
    httpStatus?.let { append("，HTTP ").append(it) }
    append("；等待 ").append(elapsedMs / 1000.0).append(" 秒。是否执行未知，请核对结果后再操作。")
})

internal suspend fun <T> callMcpToolOnce(block: suspend () -> T): T {
    val started = System.nanoTime()
    try {
        return block()
    } catch (error: Exception) {
        // An SDK-owned timeout/cancel must not masquerade as the human stopping generation.
        // Real parent cancellation still propagates unchanged and cannot trigger a retry.
        currentCoroutineContext().ensureActive()
        throw classifyMcpToolFailure(error, (System.nanoTime() - started) / 1_000_000)
    }
}

internal fun classifyMcpToolFailure(error: Throwable, elapsedMs: Long): McpToolCallFailure {
    if (error is McpToolCallFailure) return error
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && chain.size < 8 && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    val status = chain.firstNotNullOfOrNull {
        when (it) {
            is StreamableHttpError -> it.code
            is ResponseException -> it.response.status.value
            else -> null
        }?.takeIf { value -> value in 100..599 }
    }
    val rpc = chain.filterIsInstance<McpException>().firstOrNull()
    val code = when {
        status in setOf(401, 403) -> "mcp_auth_rejected"
        status != null -> "mcp_http_failed"
        rpc?.code == RPCError.ErrorCode.REQUEST_TIMEOUT || chain.any { it is TimeoutCancellationException || it is java.net.SocketTimeoutException } -> "mcp_call_timeout"
        rpc?.code == RPCError.ErrorCode.CONNECTION_CLOSED || chain.any { it is CancellationException } -> "mcp_connection_closed"
        rpc?.code == RPCError.ErrorCode.PARSE_ERROR -> "mcp_response_invalid"
        chain.any { it is SerializationException || it is StreamableHttpError } -> "mcp_response_invalid"
        rpc != null -> "mcp_rpc_failed"
        chain.any { it is IOException } -> "mcp_network_failed"
        else -> "mcp_call_failed"
    }
    return McpToolCallFailure(code, status, elapsedMs.coerceIn(0, 86_400_000))
}
