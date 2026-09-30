package me.rerere.ai.provider.providers.openai

import me.rerere.ai.util.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Response objects only: no socket or network call. */
class OpenAIHttpStatusTest {
    private fun response(status: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://unused.invalid/never-requested").build())
        .protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun knownProviderCodeNeverReplacesActualTransportStatus() {
        response(400, """{"error":{"message":"invalid parameter","code":"invalid_parameter","type":"provider_error"}}""").use {
            val failure = openAIStreamFailure(null, it) as HttpException
            assertEquals(400, failure.httpStatus); assertEquals("invalid_parameter", failure.code)
        }
    }
    @Test fun emptyBodyStillCarriesTransportStatus() {
        listOf(400, 401, 403, 404, 429, 500, 502, 503, 504).forEach { status ->
            response(status, "").use {
                val failure = openAIStreamFailure(null, it) as HttpException
                assertEquals(status, failure.httpStatus); assertEquals(status.toString(), failure.code)
            }
        }
    }
    @Test fun unknownBodyIsNotEchoedToErrors() {
        val marker = "synthetic-private-marker"
        response(502, """{"diagnostic":{"token":"$marker"}}""").use {
            val failure = openAIStreamFailure(null, it) as HttpException
            assertEquals(502, failure.httpStatus); assertFalse(failure.message.orEmpty().contains(marker))
        }
    }
    @Test fun businessCodeInSuccessfulTransportDoesNotInventHttpFailure() {
        response(200, """{"error":{"message":"failure","code":"401"}}""").use {
            val failure = openAIStreamFailure(null, it) as HttpException
            assertNull(failure.httpStatus); assertEquals("401", failure.code)
        }
    }
}
