package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostToolFailureTest {
    private fun call(
        name: String = "unknown_tool",
        approval: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = "synthetic-call-id",
        toolName = name,
        input = "{not-valid-json: do-not-echo}",
        approvalState = approval,
        metadata = buildJsonObject { put("existing", "preserved") },
    )

    @Test
    fun `missing tool uses real dispatch gate without parsing or executing`() = runBlocking {
        var executions = 0
        val available = Tool(name = "other_tool", description = "synthetic", execute = {
            executions++
            error("must not execute an alternative tool")
        })
        var enteredCallback = false
        val original = call(approval = ToolApprovalState.Approved)
        val result = original.dispatchProvidedTool(listOf(available)) {
            enteredCallback = true
            // This would throw if the unknown tool reached argument parsing.
            it.execute(Json.parseToJsonElement(original.input))
            error("must not reach execution callback")
        }
        assertFalse(enteredCallback)
        assertEquals(0, executions)
        assertEquals(HostToolFailure.NOT_PROVIDED, result.hostToolFailure())
        assertEquals(original.toolCallId, result.toolCallId)
        assertEquals(original.toolName, result.toolName)
        assertEquals(original.input, result.input)
        assertEquals(original.approvalState, result.approvalState)
        assertEquals(original.metadata?.get("existing"), result.metadata?.get("existing"))
        assertTrue(result.isExecuted)
        assertFalse(result.canResumeExecution)
        assertFalse(result.isPending)
        val text = (result.output.single() as UIMessagePart.Text).text
        assertFalse(text.contains(original.input))
        assertFalse(text.contains(original.toolName))
        val output = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("status", "reason_code", "message", "execution_performed"), output.keys)
        assertEquals("tool_not_provided", output.getValue("reason_code").jsonPrimitive.content)
        assertFalse(output.getValue("execution_performed").jsonPrimitive.boolean)
    }

    @Test
    fun `exact name only never selects differently cased or prefixed tool`() = runBlocking {
        val tools = listOf("UNKNOWN_TOOL", "prefix_unknown_tool").map { name ->
            Tool(name = name, description = "synthetic", execute = { error("never executed") })
        }
        val result = call().dispatchProvidedTool(tools) { error("no fuzzy fallback") }
        assertEquals(HostToolFailure.NOT_PROVIDED, result.hostToolFailure())
    }

    @Test
    fun `known tool callback receives exact definition once`() = runBlocking {
        var calls = 0
        val definition = Tool(name = "provided", description = "synthetic", execute = { emptyList() })
        val original = call("provided")
        val expected = original.copy(output = listOf(UIMessagePart.Text("existing result")))
        val result = original.dispatchProvidedTool(listOf(definition)) {
            assertSame(definition, it)
            calls++
            expected
        }
        assertEquals(1, calls)
        assertSame(expected, result)
        assertNull(result.hostToolFailure())
    }

    @Test
    fun `dispatch does not swallow cancellation or retry callback`() = runBlocking {
        var calls = 0
        val definition = Tool(name = "provided", description = "synthetic", execute = { emptyList() })
        val cancellation = CancellationException("synthetic cancellation")
        try {
            call("provided").dispatchProvidedTool(listOf(definition)) {
                calls++
                throw cancellation
            }
            error("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `completed result is never overwritten or reexecuted`() = runBlocking {
        val completed = call().copy(output = listOf(UIMessagePart.Text("preserve completed result")))
        HostToolFailure.entries.forEach { assertSame(completed, completed.withHostToolFailure(it)) }
        assertSame(completed, completed.dispatchProvidedTool(emptyList()) { error("must not execute") })
        assertNull(completed.hostToolFailure())
    }

    @Test
    fun `interruption and explicit stop preserve unknown execution state`() {
        listOf(HostToolFailure.INTERRUPTED, HostToolFailure.USER_CANCELLED).forEach { reason ->
            val result = call(approval = ToolApprovalState.Pending).withHostToolFailure(reason)
            val output = Json.parseToJsonElement((result.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(JsonNull, output["execution_performed"])
            assertEquals(reason, result.hostToolFailure())
            assertEquals(ToolApprovalState.Pending, result.approvalState)
            assertFalse(result.isPending)
            assertFalse(result.canResumeExecution)
        }
    }

    @Test
    fun `serialization preserves host failure original call and metadata`() {
        val result: UIMessagePart = call(approval = ToolApprovalState.Approved)
            .withHostToolFailure(HostToolFailure.NOT_PROVIDED)
        val restored = Json.decodeFromString<UIMessagePart>(Json.encodeToString(result)) as UIMessagePart.Tool
        assertEquals(result, restored)
        assertEquals(HostToolFailure.NOT_PROVIDED, restored.hostToolFailure())
        assertFalse(restored.canResumeExecution)
    }

    @Test
    fun `arbitrary external error result is not classified as host failure`() {
        val external = call().copy(output = listOf(UIMessagePart.Text(
            """{"error":"tool_not_provided","reason_code":"tool_not_provided","orbis_host_tool_failure":"tool_not_provided"}"""
        )))
        assertNull(external.hostToolFailure())
        val markers = listOf(JsonNull, JsonPrimitive(1), JsonObject(emptyMap()), JsonPrimitive("unrecognized"))
        markers.forEach { marker ->
            assertNull(external.copy(metadata = buildJsonObject { put("orbis_host_tool_failure", marker) }).hostToolFailure())
        }
        assertNull(call().copy(metadata = buildJsonObject {
            put("orbis_host_tool_failure", "tool_not_provided")
        }).hostToolFailure())
    }

    @Test
    fun `message cleanup defaults to interruption and unknown execution`() {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call()))
        val result = original.finishInterruptedHostTools()
        val tool = result.parts.single() as UIMessagePart.Tool
        assertEquals(original.id, result.id)
        assertEquals(HostToolFailure.INTERRUPTED, tool.hostToolFailure())
        assertEquals("生成被中断，未收到完整工具结果；是否执行未知。", tool.hostToolFailure()?.message)
        val output = Json.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonNull, output["execution_performed"])
        assertFalse(tool.canResumeExecution)
    }

    @Test
    fun `explicit user stop is distinct from default message cleanup`() {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call()))
        val result = original.finishInterruptedHostTools(HostToolFailure.USER_CANCELLED)
        val tool = result.parts.single() as UIMessagePart.Tool
        assertEquals(HostToolFailure.USER_CANCELLED, tool.hostToolFailure())
        assertEquals("你已停止生成，未收到完整工具结果；是否执行未知。", tool.hostToolFailure()?.message)
        assertNull(tool.hostToolFailure()?.executionPerformed)
    }

    @Test
    fun `message cleanup preserves completed results text and pending identity`() {
        val completed = call("completed").copy(output = listOf(UIMessagePart.Text("keep original result")))
        val pending = call("pending", ToolApprovalState.Approved).copy(toolCallId = "pending-call")
        val text = UIMessagePart.Text("keep message text")
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text, completed, pending))
        val result = original.finishInterruptedHostTools()
        assertEquals(original.id, result.id)
        assertSame(text, result.parts[0])
        assertSame(completed, result.parts[1])
        val finished = result.parts[2] as UIMessagePart.Tool
        assertEquals(pending.toolCallId, finished.toolCallId)
        assertEquals(pending.input, finished.input)
        assertEquals(pending.approvalState, finished.approvalState)
        assertEquals(pending.metadata?.get("existing"), finished.metadata?.get("existing"))
        assertEquals(HostToolFailure.INTERRUPTED, finished.hostToolFailure())
        assertFalse(finished.canResumeExecution)
    }

    @Test
    fun `message without pending tools remains the same object`() {
        val completed = call().copy(output = listOf(UIMessagePart.Text("existing result")))
        val withCompleted = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completed))
        val textOnly = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("text")))
        assertSame(withCompleted, withCompleted.finishInterruptedHostTools())
        assertSame(textOnly, textOnly.finishInterruptedHostTools())
        assertSame(withCompleted, withCompleted.finishInterruptedHostTools(HostToolFailure.USER_CANCELLED))
    }
}
