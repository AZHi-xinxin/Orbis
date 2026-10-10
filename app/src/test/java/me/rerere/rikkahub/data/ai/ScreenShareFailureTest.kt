package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.ai.transformers.ScreenShareFrameRevokedException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScreenShareFailureTest {
    @Test fun `provider reflected image is removed while status and exact busy proof survive`() {
        val unsafe = HttpException("PRIVATE_IMAGE_data:image/jpeg;base64,abcdefghijklmnop", code = "PRIVATE_IMAGE",
            errorType = "PRIVATE_IMAGE", httpStatus = 409, rejectedParameter = "messages", gatewayBusyBeforeGeneration = true)
        val safe = sanitizeScreenShareFailure(unsafe) as HttpException
        assertFalse(safe.toString().contains("PRIVATE_IMAGE"))
        assertNull(safe.cause); assertNull(safe.code); assertNull(safe.errorType)
        assertEquals(409, safe.httpStatus); assertEquals("messages", safe.rejectedParameter)
        assertTrue(safe.gatewayBusyBeforeGeneration)
    }
    @Test fun `exact known terminal classification is retained without provider prose`() {
        val safe = sanitizeScreenShareFailure(HttpException("PRIVATE_IMAGE", code = "upstream_empty_completion", errorType = "stiller_gateway_error", httpStatus = 502))
        val classified = KnownEmptyCompletionFailure.classify(safe, null)
        assertTrue(classified is KnownEmptyCompletionFailure)
        assertFalse(classified.stackTraceToString().contains("PRIVATE_IMAGE"))
    }
    @Test fun `local revocation and cancellation keep identity but arbitrary network causes are removed`() {
        val revoked = ScreenShareFrameRevokedException()
        assertSame(revoked, sanitizeScreenShareFailure(revoked))
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, sanitizeScreenShareFailure(cancelled))
        val safe = sanitizeScreenShareFailure(IOException("PRIVATE_IMAGE", RuntimeException("PRIVATE_IMAGE")))
        assertTrue(safe is IOException); assertNull(safe.cause)
        assertFalse(safe.stackTraceToString().contains("PRIVATE_IMAGE"))
    }

    @Test fun `revocation after an unknown earlier network attempt cannot masquerade as unsent local work`() {
        val revoked = ScreenShareFrameRevokedException()
        assertSame(revoked, sanitizeScreenShareFailure(revoked, hadUnknownTransportFailure = false))
        val unknown = sanitizeScreenShareFailure(revoked, hadUnknownTransportFailure = true)
        assertTrue(unknown is ScreenShareRevokedAfterUnknownRequestException)
        assertFalse(unknown is IOException)
        assertFalse(unknown is ScreenShareFrameRevokedException)
        assertNull(unknown.cause)
        val messages = listOf(me.rerere.ai.ui.UIMessage.user("synthetic request"))
        assertFalse(me.rerere.rikkahub.service.canContinueAfterIndependentModelFailure(unknown, messages, messages, false))
    }
}
