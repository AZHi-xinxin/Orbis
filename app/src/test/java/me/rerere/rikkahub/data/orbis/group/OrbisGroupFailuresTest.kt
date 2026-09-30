package me.rerere.rikkahub.data.orbis.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.util.HttpException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class OrbisGroupFailuresTest {
    private val secret = "Bearer synthetic-private-marker https://sensitive.invalid/path?token=private"
    private fun message(details: OrbisGroupFailureDetails) = OrbisGroupMessage(
        id = "id", roomId = "room", roundId = "round", memberId = "member", name = secret,
        text = secret, createdAt = 0, updatedAt = 0, sequence = 17, errorReason = details.category,
        errorDetails = details, status = OrbisGroupMessageStatus.FAILED,
    )

    @Test fun http400IsRequestRejectionNotTransportOutage() {
        val d = classifyGroupFailure(HttpException(secret, "invalid_parameter", httpStatus = 400))
        assertEquals("request_rejected", d.category); assertEquals(400, d.httpStatus)
        assertEquals("invalid_parameter", d.providerCode)
        assertFalse(groupFailureDiagnostic(message(d)).contains(secret))
    }
    @Test fun http401And403HaveDifferentFixedPrompts() {
        assertEquals("authentication_failed", classifyGroupFailure(HttpException(secret, httpStatus = 401)).category)
        assertEquals("permission_denied", classifyGroupFailure(HttpException(secret, httpStatus = 403)).category)
    }
    @Test fun http404DoesNotGuessModelVersusEndpoint() {
        val d = classifyGroupFailure(HttpException(secret, "model_not_found", httpStatus = 404))
        assertEquals("model_or_endpoint_missing", d.category); assertEquals(404, d.httpStatus)
    }
    @Test fun http429PreservesStatusAndKnownQuotaCodeWithoutReplay() {
        val d = classifyGroupFailure(HttpException(secret, "insufficient_quota", httpStatus = 429))
        assertEquals("rate_limited", d.category); assertEquals("insufficient_quota", d.providerCode)
        assertTrue(groupFailureDiagnostic(message(d)).contains("没有自动重试"))
    }
    @Test fun serverFailuresAreDistinctFromTimeoutsAndUnknownHttpRejections() {
        listOf(500, 502, 503, 599).forEach { assertEquals("provider_unavailable", classifyGroupFailure(HttpException(secret, httpStatus = it)).category) }
        listOf(408, 504).forEach { assertEquals("request_timeout", classifyGroupFailure(HttpException(secret, httpStatus = it)).category) }
        assertEquals("request_too_large", classifyGroupFailure(HttpException(secret, httpStatus = 413)).category)
        assertEquals("http_rejected", classifyGroupFailure(HttpException(secret, httpStatus = 409)).category)
    }
    @Test fun numericProviderCodeIsNotMistakenForHttpStatus() {
        val d = classifyGroupFailure(HttpException("HTTP 401: $secret", code = "401", errorType = "arbitrary_provider_error"))
        assertNull(d.httpStatus); assertNull(d.providerCode); assertEquals("unavailable", d.category)
        val legacy = classifyGroupFailure(HttpException(secret, "502", "openai_transport_error"))
        assertEquals(502, legacy.httpStatus)
    }
    @Test fun socketTimeoutAndBoundedCoroutineTimeoutAreRequestTimeouts() = runTest {
        assertEquals("request_timeout", classifyGroupFailure(SocketTimeoutException(secret)).category)
        val timeout = try { withTimeout(10) { awaitCancellation() }; error("unreachable") }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { e }
        assertEquals("request_timeout", classifyGroupFailure(timeout).category)
    }
    @Test fun directAndWrappedUserCancellationAreNeverConvertedToFailure() {
        val cancelled = CancellationException(secret)
        listOf(cancelled, IllegalStateException(secret, cancelled)).forEach { error ->
            try { classifyGroupFailure(error); fail("cancellation must escape") }
            catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }
    @Test fun networkAndResponseParsingHaveSafeCategories() {
        listOf(UnknownHostException(secret) to "network_dns", ConnectException(secret) to "network_connection",
            SSLException(secret) to "network_tls", IOException(secret) to "network_io",
            SerializationException(secret) to "invalid_response").forEach { (error, expected) ->
            val d = classifyGroupFailure(error)
            assertEquals(expected, d.category); assertNull(d.httpStatus); assertNull(d.providerCode)
            assertFalse(groupFailureDiagnostic(message(d)).contains(secret))
        }
    }
    @Test fun knownBusinessCodesWorkWithoutHttpAndUnknownCodesAreNotEchoed() {
        assertEquals("empty_reply", classifyGroupFailure(HttpException(secret, "upstream_empty_completion", "stiller_gateway_error")).category)
        val unknown = classifyGroupFailure(HttpException(secret, secret, secret))
        assertEquals("unavailable", unknown.category); assertNull(unknown.providerCode)
        val safe = Json.encodeToString(unknown)
        assertFalse(safe.contains("synthetic-private-marker")); assertFalse(safe.contains("sensitive.invalid"))
    }
    @Test fun wrappedDomainFailureKeepsOnlyWhitelistedCode() {
        assertEquals("storage_unavailable", classifyGroupFailure(IllegalStateException(secret, OrbisGroupException("storage_unavailable"))).category)
        assertEquals("unavailable", classifyGroupFailure(OrbisGroupException(secret)).category)
    }
    @Test fun storedOrImportedDiagnosticIsSanitizedAgainForClipboard() {
        val output = groupFailureDiagnostic(message(OrbisGroupFailureDetails(secret, 777, secret)))
        assertTrue(output.contains("消息编号：17")); assertTrue(output.contains("分类：unavailable"))
        assertFalse(output.contains("777")); assertFalse(output.contains(secret)); assertFalse(output.contains("Bearer"))
    }
    @Test fun oldMessagesStillDecodeAndNewDiagnosticsRoundTrip() {
        val old = """{"id":"i","roomId":"r","roundId":"t","memberId":null,"name":"human","createdAt":0,"updatedAt":0}"""
        assertNull(Json.decodeFromString<OrbisGroupMessage>(old).errorDetails)
        val row = message(OrbisGroupFailureDetails("request_rejected", 400, "invalid_parameter"))
        assertEquals(row, Json.decodeFromString<OrbisGroupMessage>(Json.encodeToString(row)))
    }
    @Test fun siliconFlowDocumentedBusinessCodesRemainDistinctFromHttp() {
        val rejected = classifyGroupFailure(HttpException(secret, "20012", httpStatus = 400))
        assertEquals("request_rejected", rejected.category)
        assertEquals("20012", rejected.providerCode); assertEquals(400, rejected.httpStatus)
        val unavailable = classifyGroupFailure(HttpException(secret, "50505"))
        assertEquals("provider_unavailable", unavailable.category); assertNull(unavailable.httpStatus)
        assertFalse(groupFailureDiagnostic(message(rejected)).contains(secret))
    }
    @Test fun knownRejectedParameterCanBeCopiedWithoutOriginalErrorOrChat() {
        val details = classifyGroupFailure(HttpException(secret, "invalid_parameter", httpStatus = 400,
            rejectedParameter = "max_tokens"))
        assertEquals("max_tokens", details.rejectedParameter)
        val output = groupFailureDiagnostic(message(details))
        assertTrue(output.contains("服务明确拒绝的字段：max_tokens"))
        assertFalse(output.contains(secret))
        assertEquals(details, Json.decodeFromString<OrbisGroupFailureDetails>(Json.encodeToString(details)))
    }
    @Test fun untrustedParameterValuesAndPathsNeverReachPersistenceOrClipboard() {
        listOf(secret, "messages[0].content", "model=private", "max_tokens\nBearer private", "MAX_TOKENS").forEach { input ->
            val details = classifyGroupFailure(HttpException(secret, httpStatus = 400, rejectedParameter = input))
            assertNull(details.rejectedParameter)
            assertFalse(Json.encodeToString(details).contains(input))
            val restored = OrbisGroupFailureDetails("request_rejected", 400, rejectedParameter = input)
            assertFalse(groupFailureDiagnostic(message(restored)).contains(input))
        }
    }
    @Test fun oldErrorDetailsDecodeWithoutParameterAndProseDoesNotInventOne() {
        val old = Json.decodeFromString<OrbisGroupFailureDetails>("""{"category":"request_rejected","httpStatus":400}""")
        assertNull(old.rejectedParameter)
        assertNull(classifyGroupFailure(HttpException("invalid max_tokens: $secret", httpStatus = 400)).rejectedParameter)
    }
}
