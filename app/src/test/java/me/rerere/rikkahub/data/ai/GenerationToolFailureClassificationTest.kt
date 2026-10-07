package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolFailureClassificationTest {
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
