package me.rerere.ai.provider.providers.openai

import me.rerere.ai.util.HttpException
import me.rerere.ai.util.safeRejectedParameter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Synthetic HTTP objects only. Diagnostics do not trigger a network request or automatic retry. */
class OpenAIRejectedParameterTest {
    private fun failure(body: String): HttpException = Response.Builder()
        .request(Request.Builder().url("https://unused.invalid/no-request").build())
        .protocol(Protocol.HTTP_1_1).code(400).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build().use {
            openAIStreamFailure(null, it) as HttpException
        }

    @Test fun structuredFieldIsCarriedAlongsideTransportStatusAndBusinessCode() {
        val error = failure("""{"error":{"message":"invalid request","code":"20012","param":"max_tokens"}}""")
        assertEquals(400, error.httpStatus); assertEquals("20012", error.code)
        assertEquals("max_tokens", error.rejectedParameter)
    }
    @Test fun nestedExplicitParameterTakesPrecedenceOverOuterEnvelope() {
        val error = failure("""{"param":"model","error":{"message":"rejected","parameter":"enable_thinking"}}""")
        assertEquals("enable_thinking", error.rejectedParameter)
    }
    @Test fun allFiniteParameterNamesAndNullAreRevalidated() {
        listOf("model", "messages", "stream_options", "temperature", "top_p", "max_tokens", "enable_thinking").forEach {
            assertEquals(it, safeRejectedParameter(it))
        }
        assertNull(safeRejectedParameter(null))
    }
    @Test fun privateValuesJsonPathsAndUnexpectedTypesAreNeverParameterMetadata() {
        listOf("\"Bearer synthetic-private\"", "\"messages[0].content\"", "\"MAX_TOKENS\"",
            "{\"token\":\"private\"}", "[\"max_tokens\"]", "17", "null").forEach { parameter ->
            assertNull(failure("""{"error":{"message":"rejected","param":$parameter}}""").rejectedParameter)
        }
    }
    @Test fun messageOnlyErrorsNeverGuessWhichParameterCausedRejection() {
        assertNull(failure("""{"message":"max_tokens and enable_thinking are mentioned here","code":"20012"}""").rejectedParameter)
    }
    @Test fun parameterOnlyEnvelopeDoesNotEchoUnknownBodyOrInventDetail() {
        val error = failure("""{"param":"max_tokens","secret":"synthetic-private"}""")
        assertEquals(400, error.httpStatus); assertNull(error.rejectedParameter)
        assertFalse(error.message.orEmpty().contains("synthetic-private"))
    }
}
