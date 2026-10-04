package me.rerere.rikkahub.data.orbis.sentinel

// Persist fixed diagnostics only: never provider errors, endpoints, credentials or screen text.
private val knownObservationFailures = setOf(
    "service_disabled", "service_not_connected", "observation_busy", "vision_not_configured",
    "unsafe_endpoint", "device_locked", "screen_not_interactive", "accessibility_permission_required",
    "screen_capture_permission_required", "screen_capture_busy", "screen_capture_timeout",
    "accessibility_disconnected", "screen_capture_failed_or_protected", "screen_capture_too_large",
    "screen_capture_failed", "screen_capture_missing_image", "screen_capture_invalid_image",
    "screen_capture_unavailable", "screen_observation_interrupted", "analysis_missing",
    "request_timeout", "request_failed", "analysis_failed", "incomplete_reasoning", "empty_content",
    "unsupported_structure", "ambiguous_json", "invalid_json", "incomplete_json", "missing_message",
    "non_string_message", "empty_message", "invalid_response", "truncated_response", "filtered_response",
    "refusal_response", "tool_calls_response", "unsupported_content",
    "vision_request_rejected", "vision_auth_rejected", "vision_endpoint_not_found", "vision_server_timeout",
    "vision_payload_too_large", "vision_rate_limited", "vision_server_error", "vision_http_error",
    "vision_dns_failed", "vision_tls_failed", "vision_connection_failed",
    "observation_context_read_failed", "diary_write_failed",
)

internal fun screenObservationFailureReason(errorCode: String?, reportedOk: Boolean = false): String = when {
    errorCode in knownObservationFailures -> "screen_observation_$errorCode"
    reportedOk -> "screen_observation_empty_content"
    else -> "screen_observation_unavailable"
}
