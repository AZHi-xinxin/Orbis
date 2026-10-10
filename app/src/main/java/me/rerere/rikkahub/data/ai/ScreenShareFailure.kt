package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.safeRejectedParameter
import me.rerere.rikkahub.data.ai.transformers.ScreenShareFrameRevokedException
import java.io.IOException

/** A provider can reflect its input in an error. Never let shared-screen bytes enter logs/journals. */
internal fun sanitizeScreenShareFailure(error: Throwable, hadUnknownTransportFailure: Boolean = false): Throwable = when {
    error is ScreenShareFrameRevokedException && hadUnknownTransportFailure ->
        ScreenShareRevokedAfterUnknownRequestException()
    else -> sanitizeScreenShareFailureDetails(error)
}

/** Not an IOException: neither automatic replay nor safe local-revocation release applies. */
internal class ScreenShareRevokedAfterUnknownRequestException : IllegalStateException(
    "共享画面已关闭；此前联网请求的结果尚未确认。已停止重试，保留原有执行状态，请在原聊天核对后继续。")

private fun sanitizeScreenShareFailureDetails(error: Throwable): Throwable = when (error) {
    is ScreenShareFrameRevokedException, is CancellationException, is GenerationDurabilityException,
    is GenerationToolOutcomeUnknownException -> error
    is HttpException -> HttpException("屏幕共享模型请求未完成${error.httpStatus?.let { "（HTTP $it）" }.orEmpty()}，请检查服务连接或模型配置。",
        code = error.code?.takeIf { it == "upstream_empty_completion" },
        errorType = error.errorType?.takeIf { it == "stiller_gateway_error" },
        httpStatus = error.httpStatus, rejectedParameter = safeRejectedParameter(error.rejectedParameter),
        gatewayBusyBeforeGeneration = error.gatewayBusyBeforeGeneration)
    is IOException -> IOException("屏幕共享模型连接暂未完成。")
    is Exception -> IllegalStateException("屏幕共享模型请求未完成，请检查服务连接或模型配置。")
    else -> error
}
