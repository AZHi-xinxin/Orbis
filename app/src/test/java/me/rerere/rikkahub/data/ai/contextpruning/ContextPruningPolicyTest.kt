package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.rikkahub.data.ai.tools.contextPruningAppliedReceipt
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

internal val pruningTestTime = LocalDateTime(2026, 10, 4, 12, 0)
internal fun pruningTestTool(id: String = Uuid.random().toString()) = UIMessagePart.Tool(id, "workspace_read_file", "{}",
    listOf(UIMessagePart.Text("synthetic tool result"), UIMessagePart.Document("file:/synthetic/kept.md", "kept.md")))
internal fun pruningTestReply(parts: List<UIMessagePart> = listOf(UIMessagePart.Reasoning("synthetic thinking"),
    pruningTestTool(), UIMessagePart.Text("synthetic final prose"))) = UIMessage(role = MessageRole.ASSISTANT,
    parts = parts, finishedAt = pruningTestTime)
internal fun pruningTestMessages(): List<UIMessage> = listOf(UIMessage.user("old round"), pruningTestReply(),
    UIMessage.user("recent round"), pruningTestReply(), UIMessage.user("current round"), pruningTestReply())
internal fun pruningTestState() = ContextPruningState(assistantId = Uuid.random().toString(), conversationId = Uuid.random().toString())
internal fun appliedState(messages: List<UIMessage>, state: ContextPruningState = pruningTestState(),
    mode: ContextPruningMode = ContextPruningMode.BOTH): ContextPruningState {
    val plan = planContextPruning(messages, state, mode)
    return state.copy(batches = listOf(ContextPruningBatch(Uuid.random().toString(), plan.planId, plan.marks, 1)))
}

class ContextPruningPolicyTest {
    @Test fun `old reasoning and complete tool pairs disappear only from projection and raw bytes remain intact`() {
        val messages = pruningTestMessages()
        val raw = contextPruningJson.encodeToString(messages)
        val state = appliedState(messages)
        val projected = projectContextPruningForRequest(messages, state)
        assertEquals(listOf(UIMessagePart.Text("synthetic final prose")), projected[1].parts)
        assertEquals(messages[3].parts, projected[3].parts)
        assertEquals(messages[5].parts, projected[5].parts)
        assertTrue(projected.drop(1).all { it.usageContextInvalidated })
        assertEquals(raw, contextPruningJson.encodeToString(messages))
        assertTrue((messages[1].getTools().single().output[1] as UIMessagePart.Document).url.endsWith("kept.md"))
        val display = projectContextPruningForDisplay(messages[1], state)
        assertEquals(setOf(0), display.hiddenReasoningIndexes)
        assertEquals(setOf(1), display.hiddenToolIndexes)
    }

    @Test fun `fewer than three user rounds are fully protected`() {
        val messages = pruningTestMessages().takeLast(4)
        assertTrue(planContextPruning(messages, pruningTestState()).marks.isEmpty())
    }

    @Test fun `reasoning only and tools only preserve all other exact parts`() {
        for (mode in listOf(ContextPruningMode.REASONING, ContextPruningMode.TOOLS)) {
            val messages = pruningTestMessages().toMutableList()
            if (mode == ContextPruningMode.REASONING) messages[1] = messages[1].copy(parts = messages[1].parts.filterNot { it is UIMessagePart.Tool })
            val next = projectContextPruningForRequest(messages, appliedState(messages, mode = mode))[1]
            val expected = messages[1].parts.filterNot { if (mode == ContextPruningMode.REASONING)
                it is UIMessagePart.Reasoning else it is UIMessagePart.Tool }
            assertEquals(expected, next.parts)
        }
    }

    @Test fun `reasoning content stays when a provider may require it beside retained tool calls`() {
        val plan = planContextPruning(pruningTestMessages(), pruningTestState(), ContextPruningMode.REASONING)
        assertEquals(0, plan.reasoningCount)
        assertEquals(0, plan.toolCount)
    }

    @Test fun `signature encrypted content thought signature and unknown metadata protect entire protocol turn`() {
        for (key in listOf("signature", "encrypted_content", "reasoning_id", "thoughtSignature", "openrouter_reasoning_details", "future_protocol")) {
            val base = pruningTestMessages()
            val signed = base[1].copy(parts = listOf(UIMessagePart.Reasoning("signed", metadata =
                JsonObject(mapOf(key to JsonPrimitive("synthetic protocol value")))), pruningTestTool(), UIMessagePart.Text("done")))
            val messages = base.toMutableList().also { it[1] = signed }
            assertTrue(key, planContextPruning(messages, pruningTestState()).marks.isEmpty())
        }
    }

    @Test fun `unfinished reply pending tool and no terminal prose keep whole old turn intact`() {
        val base = pruningTestMessages()
        val unsafe = listOf(base[1].copy(finishedAt = null),
            pruningTestReply(listOf(pruningTestTool().copy(output = emptyList()), UIMessagePart.Text("done"))),
            pruningTestReply(listOf(pruningTestTool())),
            pruningTestReply(listOf(UIMessagePart.Reasoning("unfinished", finishedAt = null), UIMessagePart.Text("done"))))
        unsafe.forEach { candidate ->
            assertTrue(planContextPruning(base.toMutableList().also { it[1] = candidate }, pruningTestState()).marks.isEmpty())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun `legacy split calls separate tool role and server tools never lose protocol pairs`() {
        val base = pruningTestMessages()
        val unsupported = listOf(
            UIMessagePart.ToolCall("legacy", "read", "{}"),
            UIMessagePart.ToolResult("legacy", "read", JsonPrimitive("result"), JsonObject(emptyMap())),
            UIMessagePart.ServerTool("server", "search", status = ServerToolStatus.COMPLETED))
        unsupported.forEach { part ->
            val messages = base.toMutableList().also { it[1] = pruningTestReply(listOf(part, UIMessagePart.Text("done"))) }
            assertTrue(planContextPruning(messages, pruningTestState()).marks.isEmpty())
        }
        val separate = base.toMutableList().also { it.add(2, UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("legacy")))) }
        assertTrue(planContextPruning(separate, pruningTestState()).marks.isEmpty())
    }

    @Test fun `changed original branch content cannot match a saved exclusion`() {
        val base = pruningTestMessages()
        val state = appliedState(base)
        val edited = base.toMutableList().also { it[1] = it[1].copy(parts = it[1].parts + UIMessagePart.Text("edited")) }
        assertSame(edited, projectContextPruningForRequest(edited, state))
        assertTrue(projectContextPruningForDisplay(edited[1], state).hiddenPartIndexes.isEmpty())
    }

    @Test fun `restoration returns the original request and leaves manual deleted tool undo untouched`() {
        val base = pruningTestMessages().toMutableList()
        base[1] = base[1].copy(deletedToolRecords = listOf(DeletedToolRecord(pruningTestTool(), 0, pruningTestTime)))
        val state = appliedState(base)
        val projected = projectContextPruningForRequest(base, state)
        assertEquals(base[1].deletedToolRecords, projected[1].deletedToolRecords)
        assertSame(base, projectContextPruningForRequest(base, state.copy(batches = state.batches.map { it.copy(restored = true) })))
    }

    @Test fun `a tool only intermediate message can be omitted but final prose survives`() {
        val base = pruningTestMessages().toMutableList()
        base.add(1, pruningTestReply(listOf(pruningTestTool())))
        val state = appliedState(base)
        val projected = projectContextPruningForRequest(base, state)
        assertEquals(base.size - 1, projected.size)
        assertTrue(projected.any { it.toText().contains("synthetic final prose") })
    }

    @Test fun `preview digest stays stable during current chain but changes for a new user boundary`() {
        val base = pruningTestMessages()
        val state = pruningTestState()
        val original = planContextPruning(base, state)
        val continued = base.toMutableList().also { it[it.lastIndex] = it.last().copy(parts = it.last().parts + pruningTestTool()) }
        assertEquals(original.planId, planContextPruning(continued, state).planId)
        assertNotEquals(original.planId, planContextPruning(base + UIMessage.user("new wake"), state).planId)
    }

    @Test fun `successful committed control folds visually now but is sent once in current tool chain`() {
        val base = pruningTestMessages()
        val state = appliedState(base)
        val batch = state.batches.single()
        val control = UIMessagePart.Tool("control", CONTEXT_PRUNING_TOOL_NAME,
            """{"action":"apply","plan_id":"${batch.planId}"}""",
            listOf(UIMessagePart.Text(contextPruningAppliedReceipt(batch).toString())))
        val withControl = base.toMutableList().also { it[it.lastIndex] = pruningTestReply(listOf(control, UIMessagePart.Text("done"))) }
        assertEquals(setOf(0), projectContextPruningForDisplay(withControl.last(), state).controlToolIndexes)
        assertEquals(listOf(control), projectContextPruningForRequest(withControl, state).last().getTools())
        val nextWake = withControl + UIMessage.user("new independent wake")
        assertTrue(projectContextPruningForRequest(nextWake, state)[withControl.lastIndex].getTools().isEmpty())
        assertEquals(1, state.batches.size) // Projection never writes another clearing operation.
    }

    @Test fun `preview failure restore forged and signed control receipts are never mislabeled deleted`() {
        val base = pruningTestMessages()
        val state = appliedState(base)
        val batch = state.batches.single()
        val receipt = contextPruningAppliedReceipt(batch).toString()
        val tools = listOf(
            UIMessagePart.Tool("p", CONTEXT_PRUNING_TOOL_NAME, """{"action":"preview"}""", listOf(UIMessagePart.Text(receipt))),
            UIMessagePart.Tool("f", CONTEXT_PRUNING_TOOL_NAME, "{}", listOf(UIMessagePart.Text("""{"status":"failed"}"""))),
            UIMessagePart.Tool("r", CONTEXT_PRUNING_TOOL_NAME, "{}", listOf(UIMessagePart.Text("""{"status":"restored"}"""))),
            UIMessagePart.Tool("x", "other_tool", "{}", listOf(UIMessagePart.Text(receipt))))
        tools.forEach { assertTrue(projectContextPruningForDisplay(pruningTestReply(listOf(it)), state).controlToolIndexes.isEmpty()) }
        val signed = UIMessagePart.Tool("s", CONTEXT_PRUNING_TOOL_NAME,
            """{"action":"apply","plan_id":"${batch.planId}"}""", listOf(UIMessagePart.Text(receipt)),
            metadata = JsonObject(mapOf("thoughtSignature" to JsonPrimitive("synthetic"))))
        assertTrue(projectContextPruningForDisplay(pruningTestReply(listOf(signed)), state).controlToolIndexes.isEmpty())
        assertTrue(projectContextPruningForDisplay(pruningTestReply(listOf(signed.copy(metadata = null))), pruningTestState()).controlToolIndexes.isEmpty())
    }

    @Test fun `committed control accepts legacy omitted reuse field and either boolean value`() {
        val state = appliedState(pruningTestMessages())
        val batch = state.batches.single()
        val legacy = JsonObject(contextPruningAppliedReceipt(batch) - "reused_existing_batch")
        for (receipt in listOf(legacy, contextPruningAppliedReceipt(batch, reused = false),
            contextPruningAppliedReceipt(batch, reused = true))) {
            val control = pruningReceiptControl(batch, receipt)
            assertTrue(isCommittedPruningControl(control, state))
            assertEquals(setOf(0), projectContextPruningForDisplay(pruningTestReply(listOf(control)), state).controlToolIndexes)
        }
    }

    @Test fun `reuse receipt field rejects strings numbers and explicit null`() {
        val state = appliedState(pruningTestMessages())
        val batch = state.batches.single()
        val valid = contextPruningAppliedReceipt(batch)
        for (invalid in listOf(JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(1),
            JsonPrimitive(0), JsonNull)) {
            val receipt = JsonObject(valid + ("reused_existing_batch" to invalid))
            val control = pruningReceiptControl(batch, receipt)
            assertFalse(isCommittedPruningControl(control, state))
            assertTrue(projectContextPruningForDisplay(pruningTestReply(listOf(control)), state).controlToolIndexes.isEmpty())
        }
    }

    @Test fun `both legacy and reuse receipts reject unknown extra keys`() {
        val state = appliedState(pruningTestMessages())
        val batch = state.batches.single()
        val valid = contextPruningAppliedReceipt(batch, reused = true)
        for (base in listOf(valid, JsonObject(valid - "reused_existing_batch"))) {
            val receipt = JsonObject(base + ("unexpected_receipt_field" to JsonPrimitive(true)))
            assertFalse(isCommittedPruningControl(pruningReceiptControl(batch, receipt), state))
        }
    }

    @Test fun `reuse flag never substitutes for matching persisted batch plan and counts`() {
        val state = appliedState(pruningTestMessages())
        val batch = state.batches.single()
        for (reused in listOf(false, true)) {
            val valid = contextPruningAppliedReceipt(batch, reused)
            val control = pruningReceiptControl(batch, valid)
            assertTrue(isCommittedPruningControl(control, state))
            assertFalse(isCommittedPruningControl(control, state.copy(batches = emptyList())))
            assertFalse(isCommittedPruningControl(control, state.copy(batches = listOf(batch.copy(id = Uuid.random().toString())))))
            assertFalse(isCommittedPruningControl(control.copy(input =
                """{"action":"apply","plan_id":"${"f".repeat(64)}"}"""), state))
            val wrongCount = JsonObject(valid + ("excluded_tool_pairs" to JsonPrimitive(batch.toolCount + 1)))
            assertFalse(isCommittedPruningControl(pruningReceiptControl(batch, wrongCount), state))
        }
    }

    private fun pruningReceiptControl(batch: ContextPruningBatch, receipt: JsonObject) = UIMessagePart.Tool(
        "synthetic-pruning-control", CONTEXT_PRUNING_TOOL_NAME,
        """{"action":"apply","plan_id":"${batch.planId}"}""",
        listOf(UIMessagePart.Text(receipt.toString())),
    )

    @Test fun `batch preview limits are bounded and tool ids cannot be ambiguous across messages`() {
        val base = pruningTestMessages().toMutableList()
        val same = pruningTestTool("duplicate")
        base[1] = pruningTestReply(listOf(same, UIMessagePart.Text("done")))
        base[3] = pruningTestReply(listOf(same, UIMessagePart.Text("done")))
        assertTrue(planContextPruning(base, pruningTestState()).marks.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { planContextPruning(base, pruningTestState(), maxMessages = 201) }
    }
}
