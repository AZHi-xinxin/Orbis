package com.lover.connect

/** Fixed diagnostics only. Never include server response bodies, URLs or exception messages. */
internal fun visionHttpFailure(status: Int): String? = when (status) {
    in 200..299 -> null
    400, 422 -> "vision_request_rejected"
    401, 403 -> "vision_auth_rejected"
    404 -> "vision_endpoint_not_found"
    408, 504 -> "vision_server_timeout"
    413 -> "vision_payload_too_large"
    429 -> "vision_rate_limited"
    in 500..599 -> "vision_server_error"
    else -> "vision_http_error"
}

internal fun visionRequestFailure(error: Exception): String = when (error) {
    is java.net.SocketTimeoutException -> "request_timeout"
    is java.net.UnknownHostException -> "vision_dns_failed"
    is javax.net.ssl.SSLException -> "vision_tls_failed"
    is java.net.ConnectException -> "vision_connection_failed"
    is java.io.IOException -> "request_failed"
    else -> "analysis_failed"
}

internal enum class ObservationStage { CAPTURE, CONTEXT, REQUEST, DECODE, DIARY }

internal fun observationStageFailure(stage: ObservationStage, error: Exception): String = when (stage) {
    ObservationStage.CAPTURE -> "screen_capture_failed"
    ObservationStage.CONTEXT -> "observation_context_read_failed"
    ObservationStage.REQUEST -> visionRequestFailure(error)
    ObservationStage.DECODE -> "analysis_failed"
    ObservationStage.DIARY -> "diary_write_failed"
}
