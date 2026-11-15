package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import me.rerere.rikkahub.data.ai.mcp.classifyMcpToolFailure
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolFailureClassificationTest {
    @Test fun `MCP non checkpoint receipt retains safe HTTP facts without upstream content`() {
        val failure = classifyMcpToolFailure(StreamableHttpError(502, "SECRET token and upstream body"), 1234)
        val payload = generationToolFailurePayload(failure)
        assertEquals("mcp_http_failed", payload["reason_code"]!!.jsonPrimitive.content)
        assertEquals("mcp_tools_call", payload["phase"]!!.jsonPrimitive.content)
        assertEquals(502, payload["http_status"]!!.jsonPrimitive.int)
        assertEquals(1234, payload["elapsed_ms"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, payload["execution_performed"])
        assertFalse(payload["automatic_retry"]!!.jsonPrimitive.boolean)
        assertTrue(payload["error"]!!.jsonPrimitive.content.contains("mcp_http_failed"))
        assertFalse(payload.toString().contains("SECRET"))
    }

    @Test fun `MCP network failure never invents an HTTP status`() {
        val payload = generationToolFailurePayload(classifyMcpToolFailure(java.io.IOException("PRIVATE URL"), 2))
        assertEquals("mcp_network_failed", payload["reason_code"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["http_status"])
        assertFalse(payload.toString().contains("PRIVATE"))
    }

    @Test fun `non MCP errors keep private messages hidden and preflight rejection stays known false`() {
        assertFalse(generationToolFailurePayload(IllegalStateException("PRIVATE KEY")).toString().contains("PRIVATE"))
        val rejected = ToolRejectedBeforeExecutionException("safe public rejection")
        assertEquals(rejectedToolPayload(rejected), generationToolFailurePayload(rejected))
    }

    @Test fun `checkpoint MCP failure still stops as unknown with its classified cause`() {
        val failure = classifyMcpToolFailure(java.io.IOException("PRIVATE BODY"), 3)
        assertTrue(toolOutcomeIsUnknown(true, true, failure))
        val terminal = GenerationToolOutcomeUnknownException(failure)
        assertEquals(failure, terminal.cause)
        assertTrue(terminal.message!!.contains("mcp_network_failed"))
        assertFalse(terminal.toString().contains("PRIVATE"))
    }

    @Test fun `side effect free prewrite rejection stays known false after dispatch checkpoint`() {
        val failure = ToolRejectedBeforeExecutionException("时间参数无效，未修改配置。")

        assertFalse(toolOutcomeIsUnknown(executionStarted = true, durableCheckpoints = true, failure))
        val payload = rejectedToolPayload(failure)
        assertEquals("failed", payload["status"]!!.jsonPrimitive.content)
        assertEquals("tool_rejected_before_execution", payload["reason_code"]!!.jsonPrimitive.content)
        assertFalse(payload["execution_performed"]!!.jsonPrimitive.boolean)
        assertEquals("时间参数无效，未修改配置。", payload["message"]!!.jsonPrimitive.content)
    }

    @Test fun `ordinary failure after dispatch remains unknown with durable checkpoints`() {
        assertTrue(toolOutcomeIsUnknown(
            executionStarted = true,
            durableCheckpoints = true,
            failure = IllegalStateException("synthetic post-dispatch failure"),
        ))
    }

    @Test fun `ordinary failure before dispatch is not promoted to unknown`() {
        assertFalse(toolOutcomeIsUnknown(
            executionStarted = false,
            durableCheckpoints = true,
            failure = IllegalArgumentException("synthetic pre-dispatch failure"),
        ))
    }
}
