package me.rerere.rikkahub.data.orbis.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.safeRejectedParameter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only finite, known diagnostics are durable. Throwable messages and bodies never enter group DB. */
@Serializable
data class OrbisGroupFailureDetails(
    val category: String,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    val rejectedParameter: String? = null,
)

private val groupProviderCodes = setOf(
    "invalid_api_key", "authentication_error", "permission_denied", "insufficient_quota",
    "rate_limit_exceeded", "model_not_found", "context_length_exceeded", "invalid_request_error",
    "invalid_parameter", "content_filter", "content_policy_violation", "server_error",
    "service_unavailable", "upstream_empty_stream", "upstream_empty_completion",
    "20012", "50505", // SiliconFlow's documented request-rejection / overload business codes, not HTTP.
)
private val groupFailureCategories = setOf(
    "request_rejected", "authentication_failed", "permission_denied", "model_or_endpoint_missing",
    "request_timeout", "request_too_large", "rate_limited", "provider_unavailable", "http_rejected",
    "network_dns", "network_connection", "network_tls", "network_io", "invalid_response", "unavailable",
)
private val groupDomainCodes = setOf(
    "not_ready", "settings_loading", "room_missing", "room_limit", "invalid_title", "busy", "other_room_busy",
    "member_limit", "member_missing", "no_members", "assistant_missing", "model_missing", "ambiguous_model",
    "provider_disabled", "invalid_input", "invalid_attachment", "attachment_text_too_large", "image_unsupported",
    "context_too_large", "response_too_large", "empty_reply", "unexpected_tool", "stale_retry", "not_retryable",
    "storage_unavailable", "interrupted",
)

/** Cancellation stays cancellation; timeout is a failed request and never authorizes replay. */
internal fun classifyGroupFailure(error: Throwable): OrbisGroupFailureDetails {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = error
    repeat(8) {
        val item = current ?: return@repeat
        if (causes.any { it === item }) return@repeat
        causes += item
        current = item.cause
    }
    causes.filterIsInstance<CancellationException>().firstOrNull { it !is TimeoutCancellationException }?.let { throw it }
    if (causes.any { it is TimeoutCancellationException || it is SocketTimeoutException }) {
        return OrbisGroupFailureDetails("request_timeout")
    }
    causes.filterIsInstance<OrbisGroupException>().firstOrNull()?.let {
        return OrbisGroupFailureDetails(it.code.takeIf { code -> code in groupDomainCodes } ?: "unavailable")
    }
    causes.filterIsInstance<HttpException>().firstOrNull()?.let { http ->
        // Old transport exceptions used `code` for HTTP status; provider business codes are not HTTP.
        val status = http.httpStatus?.takeIf { it in 400..599 }
            ?: http.code?.takeIf { http.errorType == "openai_transport_error" }?.toIntOrNull()?.takeIf { it in 400..599 }
        val providerCode = http.code?.takeIf { it in groupProviderCodes }
            ?: http.errorType?.takeIf { it in groupProviderCodes }
        val category = when (status) {
            400, 422 -> "request_rejected"
            401 -> "authentication_failed"
            403 -> "permission_denied"
            404 -> "model_or_endpoint_missing"
            408, 504 -> "request_timeout"
            413 -> "request_too_large"
            429 -> "rate_limited"
            in 500..599 -> "provider_unavailable"
            null -> when (providerCode) {
                "invalid_api_key", "authentication_error" -> "authentication_failed"
                "permission_denied", "insufficient_quota" -> "permission_denied"
                "rate_limit_exceeded" -> "rate_limited"
                "model_not_found" -> "model_or_endpoint_missing"
                "context_length_exceeded" -> "request_too_large"
                "invalid_request_error", "invalid_parameter", "content_filter", "content_policy_violation", "20012" -> "request_rejected"
                "server_error", "service_unavailable", "50505" -> "provider_unavailable"
                "upstream_empty_stream", "upstream_empty_completion" -> "empty_reply"
                else -> "unavailable"
            }
            else -> "http_rejected"
        }
        return OrbisGroupFailureDetails(category, status, providerCode, safeRejectedParameter(http.rejectedParameter))
    }
    return OrbisGroupFailureDetails(when {
        causes.any { it is SSLException } -> "network_tls"
        causes.any { it is UnknownHostException } -> "network_dns"
        causes.any { it is ConnectException } -> "network_connection"
        causes.any { it is IOException } -> "network_io"
        causes.any { it is SerializationException } -> "invalid_response"
        else -> "unavailable"
    })
}

/** Revalidate even restored/imported rows so clipboard/UI cannot echo arbitrary stored error data. */
internal fun OrbisGroupFailureDetails.sanitized() = OrbisGroupFailureDetails(
    category = category.takeIf { it in groupFailureCategories || it in groupDomainCodes } ?: "unavailable",
    httpStatus = httpStatus?.takeIf { it in 400..599 },
    providerCode = providerCode?.takeIf { it in groupProviderCodes },
    rejectedParameter = safeRejectedParameter(rejectedParameter),
)

internal fun groupFailureDiagnostic(message: OrbisGroupMessage): String {
    val details = (message.errorDetails ?: OrbisGroupFailureDetails(message.errorReason ?: "unavailable")).sanitized()
    return buildString {
        append("Orbis 群聊诊断 v1\n消息编号：").append(message.sequence.coerceAtLeast(0))
        append("\n分类：").append(details.category)
        details.httpStatus?.let { append("\nHTTP：").append(it) }
        details.providerCode?.let { append("\n服务错误码：").append(it) }
        details.rejectedParameter?.let { append("\n服务明确拒绝的字段：").append(it) }
        append("\n说明：").append(OrbisGroupException(details.category).message)
        append("\n没有自动重试，也没有更换模型。")
    }
}
