package me.rerere.ai.provider.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.HttpException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatGptPlanFailureTest {
    private val requestId = "req_0123456789abcdef0123456789abcdef"
    @Test fun localUnsupportedCapabilityDoesNotClaimServerRejectedOrBannedAccount() {
        val error = ChatGptPlanFailure.from(IllegalArgumentException("siwc_capability"))
        assertEquals(ChatGptFailureKind.CAPABILITY, error.kind)
        assertNull(error.httpStatus)
        assertNull(error.cause)
        assertTrue(error.message!!.contains("Orbis 本地检查"))
        assertTrue(error.message!!.contains("本次生成请求尚未发出"))
        assertFalse(error.message!!.contains("ChatGPT 拒绝了"))
        assertTrue(error.message!!.contains("没有切换到付费 API"))
    }
    @Test fun unsupportedStreamCapabilityWithHttpMetadataDoesNotClaimRequestWasNeverSent() {
        val error = ChatGptPlanFailure.from(IllegalArgumentException("siwc_capability"), 200, requestId)
        assertEquals(ChatGptFailureKind.CAPABILITY, error.kind)
        assertEquals(200, error.httpStatus)
        assertEquals(requestId, error.requestId)
        assertTrue(error.message!!.contains("响应包含"))
        assertFalse(error.message!!.contains("尚未发出"))
        val enriched = ChatGptPlanFailure.from(
            ChatGptPlanFailure.from(IllegalArgumentException("siwc_capability")), 200, requestId)
        assertEquals(error.message, enriched.message)
    }
    @Test fun identityKeyHtmlBlockKeepsItsStageAndIsNotMisreportedAsPlanPermission() {
        val error = ChatGptPlanFailure.fromBody(403, "<html>private challenge</html>", requestId,
            ChatGptRequestStage.IDENTITY_KEYS)
        assertEquals(ChatGptFailureKind.NETWORK, error.kind)
        assertEquals(ChatGptRequestStage.IDENTITY_KEYS, error.stage)
        assertEquals(ChatGptBodyShape.HTML, error.bodyShape)
        assertEquals(403, error.httpStatus)
        assertFalse(error.message!!.contains("private challenge"))
        val timeout = ChatGptPlanFailure.from(IOException("secret"), stage = ChatGptRequestStage.IDENTITY_KEYS)
        assertEquals(ChatGptRequestStage.IDENTITY_KEYS, timeout.stage)
        assertEquals(ChatGptFailureKind.NETWORK, timeout.kind)
    }
    @Test fun admissionStatusAndShapeSurviveWithoutMessageText() {
        mapOf(401 to ChatGptFailureKind.AUTHORIZATION, 403 to ChatGptFailureKind.PERMISSION, 503 to ChatGptFailureKind.TEMPORARY).forEach { (status, kind) ->
            val error = ChatGptPlanFailure.fromBody(status, """{"detail":"Bearer private-secret user@example.com"}""", requestId)
            assertEquals(kind, error.kind); assertEquals(status, error.httpStatus)
            assertEquals(ChatGptBodyShape.DETAIL, error.bodyShape); assertEquals(requestId, error.requestId)
            assertFalse(error.message!!.contains("private-secret")); assertFalse(error.message!!.contains("user@example.com"))
            assertNull(error.cause)
        }
    }
    @Test fun structuredCodesHaveDistinctRecoveryAndSafeParameter() {
        mapOf(
            "model_not_found" to ChatGptFailureKind.MODEL,
            "subscription_sharing_usage_limit_exceeded" to ChatGptFailureKind.LIMIT,
            "subscription_sharing_usage_unavailable" to ChatGptFailureKind.TEMPORARY,
            "subscription_sharing_user_unavailable" to ChatGptFailureKind.TEMPORARY,
            "subscription_sharing_unsupported_capability" to ChatGptFailureKind.REQUEST,
            "subscription_sharing_route_not_supported" to ChatGptFailureKind.REQUEST,
            "subscription_sharing_invalid_user" to ChatGptFailureKind.AUTHORIZATION,
            "subscription_sharing_user_not_eligible" to ChatGptFailureKind.PERMISSION,
            "chatpass_v2_scope_not_authorized" to ChatGptFailureKind.PERMISSION,
            "chatpass_v2_invalid_authorization_context" to ChatGptFailureKind.PERMISSION,
        ).forEach { (code, kind) ->
            val error = ChatGptPlanFailure.fromBody(400, """{"error":{"code":"$code","param":"model","message":"secret"}}""")
            assertEquals(kind, error.kind); assertEquals(code, error.errorCode); assertEquals("model", error.parameter)
            assertEquals(ChatGptBodyShape.ERROR_OBJECT, error.bodyShape)
            assertFalse(error.message!!.contains("secret"))
        }
    }
    @Test fun arbitraryCodeParameterRequestIdAndDetailNeverBecomeDiagnosticIdentifiers() {
        val error = ChatGptPlanFailure.fromBody(403, """{"error":{"code":"token-private-secret","param":"input[0].user@example.com","message":"secret"}}""", "req_private-secret")
        assertNull(error.errorCode); assertNull(error.parameter); assertNull(error.requestId)
        assertFalse(error.toString().contains("secret")); assertFalse(error.stackTraceToString().contains("user@example"))
        val proseCode = ChatGptPlanFailure.fromBody(503, """{"detail":"subscription_sharing_usage_limit_exceeded"}""")
        assertNull(proseCode.errorCode); assertEquals(ChatGptFailureKind.TEMPORARY, proseCode.kind)
    }
    @Test fun requestIdOnlyAcceptsRestrictedOpaqueFormats() {
        assertEquals(requestId, ChatGptPlanFailure.safeRequestId(requestId))
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", ChatGptPlanFailure.safeRequestId("01234567-89ab-cdef-0123-456789abcdef"))
        listOf("secret", "user@example.com", "https://private.invalid", "req_abcdef\n", "Bearer token", "req_" + "a".repeat(65)).forEach {
            assertNull(ChatGptPlanFailure.safeRequestId(it))
        }
    }
    @Test fun htmlEmptyOversizedAndMalformedResponsesRemainFinite() {
        mapOf("" to ChatGptBodyShape.EMPTY, "<html>secret</html>" to ChatGptBodyShape.HTML,
            "secret" to ChatGptBodyShape.TEXT, "[]" to ChatGptBodyShape.JSON_OTHER,
            "x".repeat(65_537) to ChatGptBodyShape.TOO_LARGE).forEach { (body, shape) ->
            val error = ChatGptPlanFailure.fromBody(503, body)
            assertEquals(shape, error.bodyShape); assertEquals(503, error.httpStatus)
            assertTrue(error.message!!.length < 1000)
            assertFalse(error.message!!.contains("secret"))
        }
    }
    @Test fun realResponseObjectUsesBoundedBodyAndHeaderOnly() {
        fun response(body: String) = Response.Builder().request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(503).message("private-secret").header("x-request-id", requestId)
            .header("Authorization", "Bearer private-secret").body(body.toResponseBody()).build()
        response("""{"detail":"private-secret"}""").use { response ->
            val error = ChatGptPlanFailure.http(null, response)
            assertEquals(503, error.httpStatus); assertEquals(requestId, error.requestId)
            assertEquals(ChatGptBodyShape.DETAIL, error.bodyShape); assertFalse(error.message!!.contains("private-secret"))
        }
        response("x".repeat(65_537)).use { assertEquals(ChatGptBodyShape.TOO_LARGE, ChatGptPlanFailure.http(null, it).bodyShape) }
    }
    @Test fun rawThrowableAndHttpCauseNeverLeakWhileKnownFieldsSurvive() {
        val error = ChatGptPlanFailure.from(HttpException("Authorization: Bearer private-secret", "model_not_found", "secret", 404, "model"))
        assertEquals(ChatGptFailureKind.MODEL, error.kind); assertEquals(404, error.httpStatus)
        assertEquals("model_not_found", error.errorCode); assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains("private-secret"))
        assertEquals(ChatGptFailureKind.NETWORK, ChatGptPlanFailure.from(IOException("private-secret")).kind)
        assertEquals(ChatGptFailureKind.PROTOCOL, ChatGptPlanFailure.from(IllegalStateException("private-secret")).kind)
    }
    @Test fun networkFailureAfterSseOpenedNeverReadsTheActiveBody() {
        var reads = 0
        val body = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): okio.BufferedSource { reads++; error("must not read active SSE") }
        }
        val response = Response.Builder().request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("x-request-id", requestId).body(body).build()
        val error = ChatGptPlanFailure.http(IOException("private network detail"), response)
        assertEquals(0, reads); assertEquals(ChatGptFailureKind.NETWORK, error.kind)
        assertEquals(200, error.httpStatus); assertEquals(requestId, error.requestId)
        assertNull(error.bodyShape); assertFalse(error.message!!.contains("private"))
        assertEquals(ChatGptFailureKind.NETWORK, ChatGptPlanFailure.http(IOException("secret"), null).kind)
    }
    @Test fun oauth400DoesNotAlwaysRequestAnotherLogin() {
        val client = ChatGptPlanFailure.fromBody(400, """{"error":"invalid_client","error_description":"secret"}""", stage = ChatGptRequestStage.TOKEN_REFRESH)
        assertEquals(ChatGptFailureKind.REQUEST, client.kind)
        val revoked = ChatGptPlanFailure.fromBody(400, """{"error":"invalid_grant"}""", stage = ChatGptRequestStage.TOKEN_REFRESH)
        assertEquals(ChatGptFailureKind.SIGN_IN, revoked.kind)
        val unknown = ChatGptPlanFailure.fromBody(400, "{}", stage = ChatGptRequestStage.TOKEN_REFRESH)
        assertEquals(ChatGptFailureKind.REQUEST, unknown.kind)
    }
    @Test fun streamErrorsKeepFiniteCodeAndAttachHttpMetadata() {
        val raw = Json.parseToJsonElement("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_unavailable","message":"secret","param":"model"}}}""").jsonObject
        val error = ChatGptPlanFailure.from(ChatGptPlanFailure.stream(raw), 200, requestId)
        assertEquals(ChatGptFailureKind.TEMPORARY, error.kind); assertEquals(200, error.httpStatus)
        assertEquals(requestId, error.requestId); assertEquals(ChatGptBodyShape.STREAM_ERROR, error.bodyShape)
        assertFalse(error.message!!.contains("secret"))
        assertSame(error, ChatGptPlanFailure.from(error))
    }
}
