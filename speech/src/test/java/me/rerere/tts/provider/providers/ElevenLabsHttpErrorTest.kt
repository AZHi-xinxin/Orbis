package me.rerere.tts.provider.providers

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsHttpErrorTest {
    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://synthetic.invalid/private-voice").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun redirectIsReportedAsRedirectWithoutLeakingLocationOrPretendingItIsQuotaFailure() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            response(code, "<html>private response</html>").newBuilder()
                .header("Location", "https://private.invalid/path?api_key=private-key").build().use {
                    val error = elevenLabsHttpError(it)
                    assertEquals(code, error.statusCode)
                    assertFalse(error.isRetryable)
                    assertTrue(error.message!!.contains("跳转"))
                    assertFalse(error.message!!.contains("网关暂时异常"))
                    assertFalse(error.message!!.contains("服务返回额度不足"))
                    assertFalse(error.message!!.contains("private"))
                }
        }
    }

    @Test fun gatewayFailureIsNotMisclassifiedAsBillingAndRemainsRetryable() {
        for (code in listOf(502, 503, 504)) {
            response(code, """{"detail":{"status":"quota_exceeded","message":"private text and private-api-key"}}""").use {
                val error = elevenLabsHttpError(it)
                assertEquals(code, error.statusCode)
                assertTrue(error.isRetryable)
                assertTrue(error.message!!.contains("网关"))
                assertFalse(error.message!!.contains("服务返回额度不足"))
                assertFalse(error.message!!.contains("private"))
            }
        }
    }

    @Test fun actualQuotaCodeIsDistinguishedFromAuthenticationDespiteSameHttp401() {
        response(401, """{"detail":{"status":"quota_exceeded","message":"private"}}""").use {
            val error = elevenLabsHttpError(it)
            assertTrue(error.message!!.contains("服务返回额度不足"))
            assertFalse(error.isRetryable)
        }
        response(401, """{"detail":{"status":"invalid_api_key","message":"private-api-key"}}""").use {
            val error = elevenLabsHttpError(it)
            assertTrue(error.message!!.contains("身份验证失败"))
            assertFalse(error.message!!.contains("private"))
        }
    }

    @Test fun htmlTruncatedOversizedAndUnexpectedErrorBodiesCannotLeakOrBreakErrorHandling() {
        for (body in listOf("<html>private-api-key</html>", """{"detail":["private"]}""",
            """{"detail":{"status":"private","message":"private"}}""",
            "{\"padding\":\"" + "private".repeat(2_000) + "\",\"detail\":{\"status\":\"quota_exceeded\"}}")) {
            response(401, body).use {
                val error = elevenLabsHttpError(it)
                assertTrue(error.message!!.contains("身份验证失败"))
                assertFalse(error.message!!.contains("private"))
            }
        }
    }

    @Test fun rateLimitAndForbiddenCatalogueHaveDifferentActionableMessages() {
        response(429, "{}").use {
            val error = elevenLabsHttpError(it)
            assertTrue(error.isRetryable)
            assertTrue(error.message!!.contains("频繁"))
            assertFalse(error.message!!.contains("额度"))
        }
        response(403, "{}").use {
            val error = elevenLabsHttpError(it, catalogue = true)
            assertFalse(error.isRetryable)
            assertTrue(error.message!!.contains("Models 读取权限"))
            assertFalse(error.message!!.contains("Voice ID"))
        }
    }
}
