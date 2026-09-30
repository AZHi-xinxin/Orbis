package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HttpException(
    message: String,
    val code: String? = null,
    val errorType: String? = null,
    /** Actual transport status, not a provider business code or text parsed from its message. */
    val httpStatus: Int? = null,
    /** Finite request-field identity only; never an upstream message, value or JSON path. */
    val rejectedParameter: String? = null,
    /** Parsed only from the exact ST pre-generation busy contract, never free-form prose. */
    val gatewayBusyBeforeGeneration: Boolean = false,
) : RuntimeException(message)

private val diagnosticRequestParameters = setOf(
    "model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "top_k",
    "stream", "stream_options", "enable_thinking", "thinking_budget", "reasoning_effort",
    "tools", "tool_choice", "response_format", "stop", "frequency_penalty", "presence_penalty",
    "seed", "n",
)

/** Reused at persistence / clipboard boundaries. Never infer a field from free-form error prose. */
fun safeRejectedParameter(value: String?): String? = value?.takeIf { it in diagnosticRequestParameters }

private fun JsonObject.rejectedRequestParameter(): String? =
    listOf("param", "parameter").firstNotNullOfOrNull { key ->
        safeRejectedParameter((this[key] as? JsonPrimitive)?.contentOrNull)
    }

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                val detail = this[foundField]!!.parseErrorDetail()
                // Keep machine-readable failure identity separate from display text. A user's
                // prose containing an error-code name must never authorize an automatic retry.
                HttpException(
                    message = detail.message.orEmpty(),
                    code = detail.code ?: (this["code"] as? JsonPrimitive)?.contentOrNull,
                    errorType = detail.errorType ?: (this["type"] as? JsonPrimitive)?.contentOrNull,
                    rejectedParameter = detail.rejectedParameter ?: rejectedRequestParameter(),
                    gatewayBusyBeforeGeneration = detail.gatewayBusyBeforeGeneration ||
                        (this["type"] == JsonPrimitive("stiller_gateway_error") &&
                            this["code"] == JsonPrimitive("human_turn_in_progress") &&
                            this["retry_class"] == JsonPrimitive("busy_before_generation") &&
                            this["generation_started"] == JsonPrimitive(false)),
                )
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
