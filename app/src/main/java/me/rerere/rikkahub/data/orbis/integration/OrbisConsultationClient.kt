package me.rerere.rikkahub.data.orbis.integration

import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.coroutines.resumeWithException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import me.rerere.rikkahub.data.orbis.consultation.ConsultationFeaturePolicy
import me.rerere.rikkahub.data.orbis.consultation.consultationFeature

/** Human-only consultation surface. No provider, ordinary chat, AI claim/submit, or ST API. */
internal class OrbisConsultationException(val reason: String) : Exception("consultation_unavailable")
internal enum class ConsultationRole { HUMAN, OPERATOR }
// Not a data class: toString must not expose a credential or private body.
internal class ConsultationAuthorization internal constructor(
    internal val credential: OrbisConnectionCredential,
    val role: ConsultationRole,
    val relayEnabled: Boolean,
)
internal data class ConsultationSession(val id: String, val state: String, val createdAt: Long,
    val updatedAt: Long, val remainingRounds: Int, val emergencyVisible: Boolean, val archiveIncomplete: Boolean = false)
internal data class ConsultationSegment(val sequence: Int, val part: Int, val speaker: String, val body: String)
internal data class ConsultationPublicView(val session: ConsultationSession, val segments: List<ConsultationSegment>)
internal data class ConsultationInspection(val session: ConsultationSession, val messages: List<ConsultationSegment>)
internal data class ConsultationRetryStatus(val sessionId: String, val requestId: String, val speaker: String,
    val available: Boolean, val retryCount: Int)

internal fun consultationBase(value: String): String {
    try {
        val uri = URI(value)
        require(uri.rawPath.orEmpty().split('/').none { it in setOf(".", "..") })
        require(uri.rawPath.orEmpty().none { it == '%' || it == '\\' })
        require(value.none { it == '\\' || it.isISOControl() })
        return normalizeOrbisIntegrationUrl(value)
    } catch (_: Exception) { throw OrbisConsultationException("invalid_connection") }
}
internal fun consultationToken(value: String): String = value.trim().also {
    if (it.length !in 32..512 || it.any { c -> c.code !in 33..126 }) throw OrbisConsultationException("invalid_connection")
}

/** Every button authorizes against server capabilities, never a client role toggle. */
internal class OrbisConsultationClient(client: OkHttpClient = OkHttpClient(),
    private val feature: ConsultationFeaturePolicy = consultationFeature) {
    private val http = client.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun human(credential: OrbisConnectionCredential): ConsultationAuthorization = authorize(credential, ConsultationRole.HUMAN)
    suspend fun operator(humanConnection: OrbisConnectionCredential, separateToken: String): ConsultationAuthorization {
        val token = consultationToken(separateToken)
        if (token == humanConnection.token) throw OrbisConsultationException("operator_token_required")
        return authorize(OrbisConnectionCredential(humanConnection.baseUrl, token, humanConnection.revision), ConsultationRole.OPERATOR)
    }
    private suspend fun authorize(credential: OrbisConnectionCredential, expected: ConsultationRole): ConsultationAuthorization {
        val body = request(credential, "capabilities")
        if (body.text("protocol", 64) != "orbis.consultation/1" || !body.flag("human_public_segments_only"))
            throw OrbisConsultationException("protocol_mismatch")
        val role = body.text("role", 16)
        if (role != if (expected == ConsultationRole.HUMAN) "human" else "operator")
            throw OrbisConsultationException(if (expected == ConsultationRole.HUMAN) "human_token_required" else "operator_token_required")
        return ConsultationAuthorization(credential, expected, body.flag("enabled"))
    }
    suspend fun sessions(auth: ConsultationAuthorization): List<ConsultationSession> {
        val rows = request(auth.credential, "sessions")["sessions"] as? JsonArray ?: invalid()
        if (rows.size > 100) invalid()
        return rows.map { metadata(it as? JsonObject ?: invalid()) }.also {
            if (it.map { item -> item.id }.distinct().size != it.size) invalid()
        }
    }
    suspend fun listening(auth: ConsultationAuthorization, sessionId: String): ConsultationPublicView {
        // This screen never uses elevated credentials as a substitute for human listening.
        if (auth.role != ConsultationRole.HUMAN) throw OrbisConsultationException("human_token_required")
        val body = request(auth.credential, sessionPath(sessionId, "listening"))
        val session = matchingMetadata(body, sessionId)
        val rows = body["segments"] as? JsonArray ?: invalid()
        if (rows.size > 512) invalid()
        val segments = rows.map { value ->
            val item = value as? JsonObject ?: invalid()
            ConsultationSegment(item.number("seq", 1, 40).toInt(), item.number("part", 0, 8192).toInt(),
                item.identifier("speaker"), item.text("body", 16384))
        }
        if (segments.map { it.sequence to it.part }.distinct().size != segments.size) invalid()
        // Ignore additional keys entirely: no question, private context, tool output, or hidden body DTO.
        return ConsultationPublicView(session, segments)
    }
    suspend fun inspection(auth: ConsultationAuthorization, session: ConsultationSession): ConsultationInspection {
        if (auth.role == ConsultationRole.HUMAN && !session.emergencyVisible)
            throw OrbisConsultationException("inspection_not_authorized")
        val body = request(auth.credential, sessionPath(session.id, "inspection"))
        val verified = matchingMetadata(body, session.id)
        if (auth.role == ConsultationRole.HUMAN && !verified.emergencyVisible)
            throw OrbisConsultationException("inspection_not_authorized")
        val rows = body["messages"] as? JsonArray ?: invalid()
        if (rows.size > 40) invalid()
        val messages = rows.map { value ->
            val item = value as? JsonObject ?: invalid()
            ConsultationSegment(item.number("seq", 1, 40).toInt(), 0, item.identifier("speaker"), item.text("body", 16384))
        }
        if (messages.map { it.sequence }.distinct().size != messages.size) invalid()
        return ConsultationInspection(verified, messages)
    }
    suspend fun ownSummary(auth: ConsultationAuthorization, sessionId: String): String? {
        if (auth.role != ConsultationRole.HUMAN) throw OrbisConsultationException("human_token_required")
        val body = request(auth.credential, sessionPath(sessionId, "summary"))
        return if (body["summary"] == JsonNull) null else body.text("summary", 16384)
    }
    suspend fun retryStatus(auth: ConsultationAuthorization, sessionId: String): ConsultationRetryStatus {
        if (auth.role != ConsultationRole.HUMAN) throw OrbisConsultationException("human_token_required")
        val body = request(auth.credential, sessionPath(sessionId, "retry_status"))
        if (body.text("session_id", 32) != sessionId) invalid()
        val requestId = body.text("request_id", 32)
        if (!Regex("[a-f0-9]{32}").matches(requestId)) invalid()
        return ConsultationRetryStatus(sessionId, requestId, body.identifier("speaker"), body.flag("retry_available"),
            body.number("retry_count", 0, 2).toInt())
    }
    suspend fun retryOnce(auth: ConsultationAuthorization, status: ConsultationRetryStatus, confirmation: String): String {
        if (auth.role != ConsultationRole.HUMAN || !Regex("[a-f0-9]{32}").matches(confirmation))
            throw OrbisConsultationException("human_token_required")
        val body = request(auth.credential, sessionPath(status.sessionId, "retry_turn"), post = true, payload = buildJsonObject {
            put("previous_request_id", status.requestId); put("confirmation_id", confirmation)
        })
        if (body.text("session_id", 32) != status.sessionId || body.text("previous_request_id", 32) != status.requestId) invalid()
        val newId = body.text("request_id", 32)
        if (!Regex("[a-f0-9]{32}").matches(newId) || newId == status.requestId) invalid()
        return body.text("state", 32).also { if (it !in setOf("ACTIVE", "PAUSED", "CLOSED", "ARCHIVING", "ARCHIVED")) invalid() }
    }
    suspend fun stop(auth: ConsultationAuthorization, sessionId: String): String {
        val result = request(auth.credential, sessionPath(sessionId, "stop"), post = true)
        // A second explicit stop must not turn an already finished/archiving session
        // into an unknown result. These states all prohibit another dialogue turn.
        val state = (result["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (state !in setOf("CLOSED", "ARCHIVING", "ARCHIVED"))
            throw OrbisConsultationException("stop_unknown")
        return requireNotNull(state)
    }
    suspend fun manualClose(auth: ConsultationAuthorization, sessionId: String, reason: String): String {
        if (auth.role != ConsultationRole.OPERATOR) throw OrbisConsultationException("operator_token_required")
        require(reason.isNotBlank() && reason.toByteArray().size <= 500)
        val result = request(auth.credential, sessionPath(sessionId, "manual_close"), post = true,
            payload = buildJsonObject { put("reason", reason) })
        if (result["state"]?.jsonPrimitive?.content != "ARCHIVED") throw OrbisConsultationException("stop_unknown")
        return result["archive_outcome"]?.jsonPrimitive?.content?.takeIf { it in setOf("complete", "manual_incomplete") }
            ?: throw OrbisConsultationException("invalid_response")
    }
    private fun sessionPath(id: String, operation: String): String {
        if (!Regex("[a-f0-9]{32}").matches(id)) throw OrbisConsultationException("invalid_session")
        return "sessions/$id/$operation"
    }
    private suspend fun request(credential: OrbisConnectionCredential, endpoint: String, post: Boolean = false,
        payload: JsonObject = buildJsonObject {}): JsonObject = withContext(Dispatchers.IO) {
        if (!feature.enabled) throw OrbisConsultationException("not_open")
        try {
            val base = consultationBase(credential.baseUrl).toHttpUrl()
            val token = consultationToken(credential.token)
            val url = base.newBuilder().addPathSegments("v1/consultation/$endpoint").build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("Accept", "application/json").header("Cache-Control", "no-store")
            if (post) builder.post(object : RequestBody() {
                private val data = payload.toString().toByteArray(Charsets.UTF_8)
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = data.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(data) }
            }) else builder.get()
            http.newCall(builder.build()).consultationAwait().use { response ->
                if (response.code in 300..399) throw OrbisConsultationException("redirect_refused")
                if (!response.isSuccessful) throw OrbisConsultationException(when (response.code) {
                    401, 403 -> "unauthorized"
                    404 -> "not_found"
                    409 -> "server_refused"
                    429 -> "rate_limited"
                    else -> if (post) "stop_unknown" else "relay_unavailable"
                })
                val body = response.body
                if (body.contentLength() > 786432 || body.source().request(786433)) invalid()
                val bytes = body.source().buffer.readByteArray()
                try {
                    Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject ?: invalid()
                } finally { bytes.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (known: OrbisConsultationException) {
            if (post && known.reason == "invalid_response") throw OrbisConsultationException("stop_unknown")
            throw known
        } catch (_: Exception) { throw OrbisConsultationException(if (post) "stop_unknown" else "relay_unavailable") }
    }
}

private suspend fun Call.consultationAwait(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, body, _ -> body.close() }
        }
    })
}

private fun invalid(): Nothing = throw OrbisConsultationException("invalid_response")
private fun JsonObject.text(key: String, maxBytes: Int): String {
    val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    if (value.toByteArray(Charsets.UTF_8).size > maxBytes || value.any { it.code < 32 && it !in "\n\r\t" }) invalid()
    return value
}
private fun JsonObject.identifier(key: String): String = text(key, 100).also {
    if (!Regex("[A-Za-z0-9_-]{8,100}").matches(it)) invalid()
}
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
private fun JsonObject.number(key: String, low: Long, high: Long): Long =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in low..high } ?: invalid()
private fun metadata(body: JsonObject): ConsultationSession {
    val id = body.text("session_id", 32)
    if (!Regex("[a-f0-9]{32}").matches(id)) invalid()
    val state = body.text("state", 32)
    if (state !in setOf("WAITING", "ACTIVE", "PAUSED", "TERMINATING", "CLOSED", "ARCHIVING", "ARCHIVED")) invalid()
    return ConsultationSession(id, state, body.number("created_at", 0, 253402300799), body.number("updated_at", 0, 253402300799),
        body.number("remaining_rounds", 0, 20).toInt(), body.flag("emergency_visible"),
        body["archive_outcome"]?.jsonPrimitive?.contentOrNull == "manual_incomplete")
}
private fun matchingMetadata(body: JsonObject, expected: String): ConsultationSession = metadata(body).also { if (it.id != expected) invalid() }

internal fun consultationFailureText(error: Exception): String = when ((error as? OrbisConsultationException)?.reason) {
    "not_open" -> "正在开发，暂未开放"
    "manual_retry_unavailable" -> "暂不能安全重试。请在本轮发言失败的那台已绑定手机操作；需要服务端支持，且原请求已暂停、未提交、未执行工具并保留完整失败记录。没有调用模型或改变会话。"
    "manual_retry_unknown" -> "这次重试结果尚未确认。请先刷新会话，不要重新配对或另建会话；确认编号与原记录已保留，不会自动再发。"
    "human_token_required" -> "此入口只接受人类专用授权；AI 或平台授权不能代用。"
    "operator_token_required" -> "平台查验需要独立的平台授权；点击次数不会授予权限。"
    "invalid_connection" -> "请检查咨询室中继根地址与专用授权；这不是模型、ST 或 TechHub 连接。"
    "unauthorized" -> "咨询室授权未通过，请检查本页的专用连接。"
    "inspection_not_authorized" -> "这次会话未开放紧急查验；没有读取私密正文。"
    "protocol_mismatch" -> "服务不支持所需的咨询室权限协议，已停止读取。"
    "redirect_refused" -> "服务返回跳转，已阻止向其它地址传递授权。"
    "not_found" -> "未找到你有权访问的会话或咨询室接口。"
    "server_refused" -> "服务未授权这次操作或会话状态已改变，请先刷新核实。"
    "rate_limited" -> "请求过于频繁，请稍后手动刷新。"
    "invalid_response" -> "服务响应未通过边界校验，没有显示这份内容。"
    "stop_unknown" -> "停止结果尚未确认，请先刷新会话核实；不会自动再次停止。"
    "configuration_changed" -> "连接或授权已变更，已清除本页内容，请重新刷新。"
    else -> "咨询室暂不可用，请确认中继已部署；没有重试或发起模型请求。"
}
