package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.GenerationToolOutcomeUnknownException
import me.rerere.rikkahub.data.orbis.diagnostics.diagnosticFailure
import me.rerere.rikkahub.data.orbis.diagnostics.sanitized
import org.junit.Assert.*
import org.junit.Test

class McpToolCallFailureTest {
    @Test fun `HTTP failure survives unknown outcome boundary without upstream secrets`() {
        val failure = classifyMcpToolFailure(StreamableHttpError(502, "PRIVATE_TOKEN_PRIVATE_BODY"), 2500)
        val wrapped = GenerationToolOutcomeUnknownException(failure)
        val event = diagnosticFailure(wrapped, 42)
        assertEquals("mcp", event.source)
        assertEquals("mcp_http_failed", event.code)
        assertEquals(502, event.httpStatus)
        assertEquals(2500L, event.elapsedMs)
        assertEquals(event, event.sanitized())
        assertFalse(Json.encodeToString(event).contains("PRIVATE"))
        assertFalse(wrapped.stackTraceToString().contains("PRIVATE"))
        assertNull(failure.cause)
    }

    @Test fun `protocol timeout has no invented HTTP status`() {
        val event = diagnosticFailure(classifyMcpToolFailure(McpException(RPCError.ErrorCode.REQUEST_TIMEOUT, "PRIVATE"), 120000))
        assertEquals("mcp_call_timeout", event.code)
        assertNull(event.httpStatus)
        assertEquals("mcp_tools_call", event.phase)
    }

    @Test fun `SDK owned timeout stays a failure without cancelling the generation`() = runBlocking {
        var calls = 0
        val error = runCatching { callMcpToolOnce { calls++; withTimeout(5) { delay(1000) } } }.exceptionOrNull()
        assertEquals("mcp_call_timeout", (error as McpToolCallFailure).code)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(1, calls)
    }

    @Test fun `human cancellation propagates and never retries`() = runBlocking {
        var calls = 0
        val error = runCatching { withTimeout(10) { callMcpToolOnce { calls++; delay(1000) } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, calls)
    }

    @Test fun `transport cancellation while host remains active is connection failure`() = runBlocking {
        val error = runCatching { callMcpToolOnce { throw CancellationException("PRIVATE") } }.exceptionOrNull()
        assertEquals("mcp_connection_closed", (error as McpToolCallFailure).code)
        assertTrue(currentCoroutineContext().isActive)
        assertFalse(error.stackTraceToString().contains("PRIVATE"))
    }
}
