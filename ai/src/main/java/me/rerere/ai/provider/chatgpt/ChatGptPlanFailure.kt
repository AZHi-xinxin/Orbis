package me.rerere.ai.provider.chatgpt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.rerere.ai.util.HttpException
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

enum class ChatGptFailureKind { AUTHORIZATION, SIGN_IN, PERMISSION, MODEL, LIMIT, TEMPORARY, REQUEST, CAPABILITY, NETWORK, PROTOCOL, CANCELLED }
enum class ChatGptBodyShape { EMPTY, ERROR_OBJECT, DETAIL, JSON_OTHER, HTML, TEXT, TOO_LARGE, UNAVAILABLE, STREAM_ERROR }
enum class ChatGptRequestStage { INFERENCE, MODEL_LIST, TOKEN_EXCHANGE, TOKEN_REFRESH, IDENTITY_KEYS, DISCOVERY }

/** Only finite categories / allowlisted identifiers survive this boundary. No raw exception cause,
 * response text, URL, headers, token, email, or request data is retained in a displayable throwable. */
class ChatGptPlanFailure private constructor(
    val kind: ChatGptFailureKind,
    val httpStatus: Int?,
    val errorCode: String?,
    val parameter: String?,
    val requestId: String?,
    val bodyShape: ChatGptBodyShape?,
    val stage: ChatGptRequestStage,
) : IllegalStateException(render(kind, httpStatus, errorCode, parameter, requestId, bodyShape, stage)) {
    companion object {
        private val codes = setOf(
            "subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
            "subscription_sharing_usage_unavailable", "subscription_sharing_unsupported_capability",
            "subscription_sharing_route_not_supported", "subscription_sharing_invalid_user",
            "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context",
            "subscription_sharing_user_unavailable", "model_not_found", "model_not_available",
            "invalid_request_error", "unsupported_parameter", "invalid_value", "invalid_token",
            "insufficient_permissions", "rate_limit_exceeded", "service_unavailable", "server_error",
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused", "invalid_client",
        )
        private val parameters = setOf("model", "input", "instructions", "tools", "tool_choice", "service_tier",
            "store", "stream", "reasoning", "text", "include", "background", "conversation", "max_output_tokens",
            "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention",
            "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")
        private val requestIdPattern = Regex("(?:req_[a-fA-F0-9]{16,64}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")
        fun safeRequestId(value: String?): String? = value?.takeIf { it.length <= 68 && requestIdPattern.matches(it) }

        private fun create(status: Int? = null, code: String? = null, parameter: String? = null,
            requestId: String? = null, shape: ChatGptBodyShape? = null,
            stage: ChatGptRequestStage = ChatGptRequestStage.INFERENCE, fallback: ChatGptFailureKind = ChatGptFailureKind.PROTOCOL): ChatGptPlanFailure {
            val safeStatus = status?.takeIf { it in 100..599 }
            val safeCode = code?.takeIf { it in codes }
            val kind = when {
                safeCode in setOf("invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused") ||
                    safeCode == "invalid_grant" && stage == ChatGptRequestStage.TOKEN_REFRESH -> ChatGptFailureKind.SIGN_IN
                safeCode == "invalid_grant" && stage == ChatGptRequestStage.TOKEN_EXCHANGE -> ChatGptFailureKind.SIGN_IN
                safeCode in setOf("model_not_found", "model_not_available") -> ChatGptFailureKind.MODEL
                safeCode == "subscription_sharing_usage_limit_exceeded" || safeCode == "rate_limit_exceeded" -> ChatGptFailureKind.LIMIT
                safeCode in setOf("subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable", "service_unavailable", "server_error") -> ChatGptFailureKind.TEMPORARY
                safeCode in setOf("subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported", "invalid_client", "invalid_request_error", "unsupported_parameter", "invalid_value") -> ChatGptFailureKind.REQUEST
                safeCode in setOf("subscription_sharing_user_not_eligible", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context", "insufficient_permissions") -> ChatGptFailureKind.PERMISSION
                safeCode in setOf("subscription_sharing_invalid_user", "invalid_token") -> ChatGptFailureKind.AUTHORIZATION
                safeStatus == 401 -> ChatGptFailureKind.AUTHORIZATION
                safeStatus == 403 && shape == ChatGptBodyShape.HTML -> ChatGptFailureKind.NETWORK
                safeStatus == 403 -> ChatGptFailureKind.PERMISSION
                safeStatus == 429 -> ChatGptFailureKind.LIMIT
                safeStatus != null && safeStatus >= 500 -> ChatGptFailureKind.TEMPORARY
                safeStatus != null && safeStatus in 400..499 -> ChatGptFailureKind.REQUEST
                else -> fallback
            }
            return ChatGptPlanFailure(kind, safeStatus, safeCode, parameter?.takeIf { it in parameters }, safeRequestId(requestId), shape, stage)
        }

        fun from(error: Throwable, status: Int? = null, requestId: String? = null,
            stage: ChatGptRequestStage = ChatGptRequestStage.INFERENCE): ChatGptPlanFailure {
            if (error is ChatGptPlanFailure) return if (error.httpStatus == null && (status != null || requestId != null))
                create(status, error.errorCode, error.parameter, requestId ?: error.requestId, error.bodyShape, error.stage, error.kind) else error
            val local = when (error.message) {
                "siwc_reauth_required", "siwc_not_signed_in" -> ChatGptFailureKind.SIGN_IN
                "siwc_permission_missing" -> ChatGptFailureKind.PERMISSION
                "siwc_limit" -> ChatGptFailureKind.LIMIT
                "siwc_capability" -> ChatGptFailureKind.CAPABILITY
                "siwc_signed_out" -> ChatGptFailureKind.CANCELLED
                else -> when (error) {
                    is SocketTimeoutException, is IOException -> ChatGptFailureKind.NETWORK
                    is SerializationException -> ChatGptFailureKind.PROTOCOL
                    else -> ChatGptFailureKind.PROTOCOL
                }
            }
            val http = error as? HttpException
            return create(http?.httpStatus ?: status, http?.code, http?.rejectedParameter, requestId, stage = stage, fallback = local)
        }

        /** Invoked only for failed HTTP responses; bounded read, finite shape classification. */
        fun http(error: Throwable?, response: Response?, stage: ChatGptRequestStage = ChatGptRequestStage.INFERENCE): ChatGptPlanFailure {
            if (response == null) return from(error ?: IOException(), stage = stage)
            val status = response.code
            val id = response.header("x-request-id")
            // A 2xx SSE body may still be active (readTimeout=0). Never try to reread it after
            // a transport failure; preserve the network/protocol cause category and headers only.
            if (response.isSuccessful) return from(error ?: IllegalStateException("siwc_stream_interrupted"), status, id, stage)
            val body = response.body
            return try {
                val source = body.source()
                if (source.request(65_537)) create(status, requestId = id, shape = ChatGptBodyShape.TOO_LARGE, stage = stage)
                else fromBody(status, source.buffer.readUtf8(), id, stage)
            } catch (_: Exception) { create(status, requestId = id, shape = ChatGptBodyShape.UNAVAILABLE, stage = stage) }
        }

        internal fun fromBody(status: Int, body: String, requestId: String? = null,
            stage: ChatGptRequestStage = ChatGptRequestStage.INFERENCE): ChatGptPlanFailure {
            if (body.length > 65_536) return create(status, requestId = requestId, shape = ChatGptBodyShape.TOO_LARGE, stage = stage)
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject
            val shape = when {
                body.isBlank() -> ChatGptBodyShape.EMPTY
                error != null -> ChatGptBodyShape.ERROR_OBJECT
                root?.containsKey("detail") == true -> ChatGptBodyShape.DETAIL
                root != null || body.trimStart().startsWith('[') -> ChatGptBodyShape.JSON_OTHER
                body.trimStart().startsWith('<') -> ChatGptBodyShape.HTML
                else -> ChatGptBodyShape.TEXT
            }
            // Token endpoints use top-level OAuth error codes; never interpret detail/message prose.
            val code = error?.string("code") ?: if (stage in setOf(ChatGptRequestStage.TOKEN_EXCHANGE, ChatGptRequestStage.TOKEN_REFRESH)) root?.string("error") else null
            return create(status, code, error?.string("param"), requestId, shape, stage)
        }

        fun stream(body: JsonObject): ChatGptPlanFailure {
            val error = (body["response"] as? JsonObject)?.get("error") as? JsonObject ?: body["error"] as? JsonObject ?: body
            return create(code = error.string("code"), parameter = error.string("param"),
                requestId = body.string("request_id"), shape = ChatGptBodyShape.STREAM_ERROR)
        }
        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun render(kind: ChatGptFailureKind, status: Int?, code: String?, parameter: String?, requestId: String?, shape: ChatGptBodyShape?, stage: ChatGptRequestStage): String {
            val help = when (kind) {
                ChatGptFailureKind.AUTHORIZATION -> "ChatGPT 未接受本次账号或套餐权限，请核对所选账号及授权；这不一定需要重新登录。"
                ChatGptFailureKind.SIGN_IN -> "ChatGPT 尚未登录或本次授权已不可用，请重新授权。"
                ChatGptFailureKind.PERMISSION -> "ChatGPT 账号、工作区或服务地区的权限限制了此请求。请核对权限，不要反复登录。"
                ChatGptFailureKind.MODEL -> "当前账号无法使用所选 ChatGPT 模型。请刷新模型列表并确认选择。"
                ChatGptFailureKind.LIMIT -> "ChatGPT 请求受到用量或速率限制，请到 ChatGPT 设置 → Usage 核对，稍后再试；不代表整个套餐已用尽。"
                ChatGptFailureKind.TEMPORARY -> "ChatGPT 服务或套餐状态暂时不可用。授权已保留，请稍后再试，无需因此重新登录。"
                ChatGptFailureKind.REQUEST -> "ChatGPT 拒绝了请求格式、能力或接入配置。请根据诊断信息检查，不要重复发送相同请求。"
                ChatGptFailureKind.CAPABILITY -> if (status == null) {
                    "Orbis 本地检查发现当前 ChatGPT 套餐接口不支持的工具或能力配置，本次生成请求尚未发出。这不是账号被封的通知。请检查模型内置工具设置；原生生图不受此套餐接口支持。"
                } else {
                    "当前 ChatGPT 响应包含 Orbis 套餐接入暂不支持的工具或能力，已停止处理该调用。这不是账号被封的通知；不会自动重跑工具。"
                }
                ChatGptFailureKind.NETWORK -> "ChatGPT 网络连接未完成，请检查网络后再试；授权已保留。"
                ChatGptFailureKind.PROTOCOL -> "ChatGPT 响应未完整完成或格式无法识别，已保留收到的内容。请提供下方诊断信息排查。"
                ChatGptFailureKind.CANCELLED -> "ChatGPT 已退出，本轮已停止。"
            }
            val diagnostic = listOfNotNull("阶段=$stage", status?.let { "HTTP=$it" }, code?.let { "code=$it" },
                parameter?.let { "param=$it" }, shape?.let { "shape=$it" }, requestId?.let { "request_id=$it" }).joinToString(" · ")
            return "$help\n$diagnostic\n没有自动重发，也没有切换到付费 API。"
        }
    }
}
