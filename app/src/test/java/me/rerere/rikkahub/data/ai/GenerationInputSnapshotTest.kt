package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.ai.compaction.AppliedCompaction
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.ai.compaction.compactionReminder
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationInputSnapshotTest {
    private fun summary(text: String) = UIMessage.assistant(text).copy(parts = listOf(
        UIMessagePart.Text(text, JsonObject(mapOf(COMPACTION_SUMMARY_MARKER to JsonPrimitive(true)))),
    ))

    @Test fun `compaction state is absent from both initial system and same run continuation`() = runBlocking {
        val messages = listOf(UIMessage.user("synthetic original message"))
        val control = ConversationCompactionControl(350_000, { messages }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 1) })
        val hostPrompt = "synthetic frozen host prompt"
        val systemText = buildString {
            append(hostPrompt)
            control.tools().forEach { appendLine(); append(it.systemPrompt(Model(), messages)) }
        }
        assertEquals(hostPrompt, systemText.trim())
        val snapshot = GenerationInputSnapshot()
        val system = UIMessage.system(systemText).copy(isSynthetic = true)
        snapshot.input { listOf(system) + messages + compactionReminder(350_000, 350_000)!! }
        val compact = call("compact")
        val authored = response(UIMessagePart.Text("my own synthetic summary"), compact)
        val results = listOf(result(compact))
        val completed = response(*results.toTypedArray())
        val rebased = snapshot.prepareCompactionInput(
            listOf(summary("my own synthetic summary"), completed), authored, results, completed.id,
        )
        snapshot.acceptCompactionInput(rebased, authored, results, completed.id)
        val continuation = snapshot.input { error("same run must not rebuild system") }
        assertEquals(listOf(system), continuation.filter { it.role == MessageRole.SYSTEM })
        assertFalse(continuation.any { it.toText().contains("Orbis compact 状态") || it.toText().contains("Orbis 上下文用量参考") })
        assertTrue(continuation.last().getTools().single().isExecuted)
    }

    @Test fun `compaction rebase preserves frozen system removes old reminder and keeps raw paired response`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val old = UIMessage.user("discard old source")
        val frozen = UIMessage.system("frozen device=90").copy(isSynthetic = true)
        snapshot.input { listOf(frozen, old, compactionReminder(100, 100)!!) }
        val compact = call("compact")
        val peer = call("peer")
        val raw = response(UIMessagePart.Text("raw {battery_level}"), compact, peer)
        val results = listOf(result(compact), result(peer))
        val bubble = response(*results.toTypedArray())
        val start = summary("AI exact summary")
        val rebased = snapshot.prepareCompactionInput(listOf(start, bubble), raw, results, bubble.id)
        assertEquals(listOf(frozen, start), rebased.take(2))
        assertEquals("raw {battery_level}", rebased.last().toText().trim())
        assertEquals(listOf("compact", "peer"), rebased.last().getTools().map { it.toolCallId })
        assertTrue(rebased.last().getTools().all { it.isExecuted })
        assertFalse(rebased.any { it.toText().contains("discard old source") || it.toText().contains("Orbis 上下文用量参考") })
        snapshot.acceptCompactionInput(rebased, raw, results, bubble.id)
        assertEquals(rebased, snapshot.input { error("must not rerender dynamic transforms") })
    }

    @Test fun `preparing compaction leaves accepted snapshot unchanged until durable acknowledgement`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val old = listOf(UIMessage.user("original"))
        snapshot.input { old }
        val compact = call()
        val raw = response(compact)
        val completed = result(compact)
        snapshot.prepareCompactionInput(listOf(summary("summary"), response(completed)), raw, listOf(completed), Uuid.random())
        assertEquals(old, snapshot.input { error("rerender") })
        val failed = compact.copy(output = listOf(UIMessagePart.Text("commit failed, original kept")))
        snapshot.appendCompletedResponse(raw, listOf(failed))
        val next = snapshot.input { error("rerender") }
        assertEquals(old, next.take(1))
        assertEquals("commit failed, original kept", (next.last().getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test fun `rebase retains independent provider segments even when UI merged their tool batches`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("older")) }
        val bubbleId = Uuid.random()
        val earlier = call("earlier")
        val earlierResponse = response(earlier)
        snapshot.appendCompletedResponse(earlierResponse, listOf(result(earlier)), bubbleId)
        val compact = call("compact")
        val currentResponse = response(UIMessagePart.Text("summary"), compact)
        val merged = response(result(earlier), result(compact)).copy(id = bubbleId)
        val rebased = snapshot.prepareCompactionInput(listOf(summary("summary"), merged), currentResponse, listOf(result(compact)), bubbleId)
        assertEquals(3, rebased.size)
        assertEquals(listOf("earlier"), rebased[1].getTools().map { it.toolCallId })
        assertEquals(listOf("compact"), rebased[2].getTools().map { it.toolCallId })
        assertEquals(earlierResponse.id, rebased[1].id)
    }

    @Test fun `compaction input preparation rejects unpaired batch before commit`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("original")) }
        val compact = call("compact")
        val peer = call("peer")
        assertThrows(IllegalStateException::class.java) {
            snapshot.prepareCompactionInput(listOf(summary("summary")), response(compact, peer), listOf(result(compact)), Uuid.random())
        }
        assertEquals("original", snapshot.input { error("rerender") }.single().toText())
    }

    private fun call(id: String = "call-1") = UIMessagePart.Tool(id, "read", "{\"query\":\"one\"}")
    private fun result(call: UIMessagePart.Tool) = call.copy(output = listOf(UIMessagePart.Text("actual result")))
    private fun response(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test fun `initial transforms context selection and dynamic input execute only once`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        var count = 0
        var battery = 90
        suspend fun input() = snapshot.input {
            count++
            listOf(UIMessage.system("date=day$count battery=$battery lorebook=$count memory=$count"), UIMessage.user("original"))
        }
        val initial = input()
        battery = 20
        val tool = call()
        snapshot.appendCompletedResponse(response(tool), listOf(result(tool)))
        val next = input()
        assertEquals(1, count)
        assertEquals(initial, next.take(initial.size))
        assertEquals(3, next.size)
    }

    @Test fun `new human invocation reads new configuration and memories`() = runBlocking {
        var memory = "old memory"
        val first = GenerationInputSnapshot().input { listOf(UIMessage.system(memory)) }
        memory = "new memory"
        val next = GenerationInputSnapshot().input { listOf(UIMessage.system(memory)) }
        assertEquals("old memory", first.single().toText())
        assertEquals("new memory", next.single().toText())
    }

    @Test fun `separate runs never share snapshots`() = runBlocking {
        val a = GenerationInputSnapshot()
        val b = GenerationInputSnapshot()
        a.input { listOf(UIMessage.user("branch A")) }
        b.input { listOf(UIMessage.user("branch B")) }
        assertEquals("branch A", a.input { error("rerender") }.single().toText())
        assertEquals("branch B", b.input { error("rerender") }.single().toText())
    }

    @Test fun `failed initial transformation does not publish half a snapshot`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        try {
            snapshot.input { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            // Cancellation escapes to the caller; no input or resume registry was persisted.
        }
        assertEquals("fresh", snapshot.input { listOf(UIMessage.user("fresh")) }.single().toText())
    }

    @Test fun `snapshot detaches authored messages mutable lists metadata and synthetic flag`() = runBlocking {
        val metadata = mutableMapOf("key" to JsonPrimitive("old"))
        val part = UIMessagePart.Text("author text", JsonObject(metadata))
        val parts = mutableListOf<UIMessagePart>(part)
        val history = mutableListOf(UIMessage(role = MessageRole.USER, parts = parts, isSynthetic = true))
        val snapshot = GenerationInputSnapshot()
        val first = snapshot.input { history }
        metadata["key"] = JsonPrimitive("changed")
        part.metadata = JsonObject(emptyMap())
        parts.clear()
        history.clear()
        val next = snapshot.input { error("rerender") }
        assertEquals(first, next)
        assertTrue(next.single().isSynthetic)
        assertEquals(JsonPrimitive("old"), next.single().parts.single().metadata?.get("key"))
    }

    @Test fun `consumer mutations cannot change a later request`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val first = snapshot.input { listOf(UIMessage.user("unchanged")) }
        (first.single().parts.single() as UIMessagePart.Text).metadata =
            JsonObject(mapOf("provider" to JsonPrimitive("mutation")))
        val second = snapshot.input { error("rerender") }
        assertEquals(null, second.single().parts.single().metadata)
        assertNotSame(first.single(), second.single())
    }

    @Test fun `tool response appends actual output without transforming model authored text`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val initial = snapshot.input { listOf(UIMessage.user("user {battery_level}")) }
        val tool = call()
        val authored = "model {battery_level}\n<keep-exact>\nUnicode 星"
        snapshot.appendCompletedResponse(response(UIMessagePart.Text(authored), tool), listOf(result(tool)))
        val next = snapshot.input { error("global transforms must not run again") }
        assertEquals(initial, next.take(1))
        assertEquals(authored, (next.last().parts.first() as UIMessagePart.Text).text)
        assertEquals(tool.input, next.last().getTools().single().input)
        assertEquals("actual result", (next.last().getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test fun `successive tool only responses remain separate segments with fixed earlier prefix`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("start")) }
        val first = call("first")
        snapshot.appendCompletedResponse(response(first), listOf(result(first)))
        val prefix = snapshot.input { error("rerender") }
        val second = call("second")
        snapshot.appendCompletedResponse(response(second), listOf(result(second)))
        val next = snapshot.input { error("rerender") }
        assertEquals(prefix, next.take(prefix.size))
        assertEquals(listOf("first"), next[1].getTools().map { it.toolCallId })
        assertEquals(listOf("second"), next[2].getTools().map { it.toolCallId })
    }

    @Test fun `parallel tool results retain original response ordering`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("start")) }
        val first = call("first")
        val second = call("second")
        snapshot.appendCompletedResponse(response(first, second), listOf(result(second), result(first)))
        assertEquals(listOf("first", "second"), snapshot.input { error("rerender") }.last().getTools().map { it.toolCallId })
    }

    @Test fun `response and result buffers are detached at append`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("start")) }
        val tool = call()
        val text = UIMessagePart.Text("result", JsonObject(mapOf("original" to JsonPrimitive(true))))
        val outputs = mutableListOf<UIMessagePart>(text)
        val modelPart = UIMessagePart.Text("model", JsonObject(mapOf("original" to JsonPrimitive(true))))
        snapshot.appendCompletedResponse(response(modelPart, tool), listOf(tool.copy(output = outputs)))
        text.metadata = null
        modelPart.metadata = null
        outputs.clear()
        val appended = snapshot.input { error("rerender") }.last()
        assertEquals(JsonPrimitive(true), appended.parts.first().metadata?.get("original"))
        assertEquals(JsonPrimitive(true), appended.getTools().single().output.single().metadata?.get("original"))
    }

    @Test fun `tool denial remains an actual tool result and does not grant execution`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("start")) }
        val tool = call()
        val denied = result(tool).copy(approvalState = ToolApprovalState.Denied("no"))
        snapshot.appendCompletedResponse(response(tool), listOf(denied))
        assertEquals(denied.approvalState, snapshot.input { error("rerender") }.last().getTools().single().approvalState)
    }

    @Test fun `response mismatch is rejected before tool execution`() {
        val snapshot = GenerationInputSnapshot()
        val tool = call()
        listOf(
            tool.copy(toolName = "write"),
            tool.copy(input = "private contents"),
            tool.copy(toolCallId = "another"),
        ).forEach { changed ->
            val error = assertThrows(IllegalStateException::class.java) {
                snapshot.validateResponseTools(response(tool), listOf(changed))
            }
            assertFalse(error.message.orEmpty().contains("private contents"))
        }
    }

    @Test fun `ambiguous or missing tool calls are rejected`() {
        val snapshot = GenerationInputSnapshot()
        val tool = call()
        assertThrows(IllegalStateException::class.java) { snapshot.validateResponseTools(response(tool, tool), listOf(tool, tool)) }
        assertThrows(IllegalStateException::class.java) { snapshot.validateResponseTools(response(call("")), listOf(call(""))) }
        assertThrows(IllegalStateException::class.java) { snapshot.validateResponseTools(response(tool), emptyList()) }
        assertThrows(IllegalStateException::class.java) { snapshot.validateResponseTools(UIMessage.user("not model"), emptyList()) }
    }

    @Test fun `reused call id inside automatic chain is rejected`() = runBlocking<Unit> {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("start")) }
        val tool = call()
        snapshot.appendCompletedResponse(response(tool), listOf(result(tool)))
        assertThrows(IllegalStateException::class.java) { snapshot.validateResponseTools(response(tool), listOf(tool)) }
    }

    @Test fun `historical ids in a previous human wake do not poison a new run`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val tool = call()
        snapshot.input { listOf(response(result(tool)), UIMessage.user("new wake")) }
        snapshot.validateResponseTools(response(tool), listOf(tool))
    }

    @Test fun `empty result cannot silently lose a tool call in serialization`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val initial = snapshot.input { listOf(UIMessage.user("start")) }
        val tool = call()
        assertThrows(IllegalStateException::class.java) { snapshot.appendCompletedResponse(response(tool), listOf(tool)) }
        assertEquals(initial, snapshot.input { error("rerender") })
    }

    @Test fun `tool schema suppliers freeze once per invocation without changing execution or approvals`() = runBlocking {
        var schemaReads = 0
        var executions = 0
        val properties = mutableMapOf("value" to JsonObject(mapOf("type" to JsonPrimitive("string"))))
        val original = Tool(
            name = "test",
            description = "fixed",
            parameters = { schemaReads++; InputSchema.Obj(JsonObject(properties)) },
            needsApproval = { true },
            execute = { executions++; listOf(UIMessagePart.Text("executed")) },
        )
        val frozen = snapshotGenerationTools(listOf(original)).single()
        val first = frozen.parameters()
        properties.clear()
        assertEquals(first, frozen.parameters())
        assertEquals(1, schemaReads)
        assertTrue(frozen.needsApproval(JsonObject(emptyMap())))
        assertEquals(0, executions)
        frozen.execute(JsonObject(emptyMap()))
        assertEquals(1, executions)
        snapshotGenerationTools(listOf(original))
        assertEquals(2, schemaReads)
    }

    @Test fun `null tool schemas retain their existing null semantics`() {
        val original = Tool(name = "no-schema", description = "fixed", execute = { emptyList() })
        assertEquals(null, snapshotGenerationTools(listOf(original)).single().parameters())
    }

    @Test fun `empty executed output gets factual host receipt without changing call metadata`() {
        val metadata = JsonObject(mapOf("thought_signature" to JsonPrimitive("synthetic-signature")))
        val original = call().copy(metadata = metadata, approvalState = ToolApprovalState.Approved)
        val completed = original.withGenerationToolOutput(emptyList())
        assertTrue(completed.isExecuted)
        assertEquals(original.toolCallId, completed.toolCallId)
        assertEquals(original.toolName, completed.toolName)
        assertEquals(original.input, completed.input)
        assertEquals(original.approvalState, completed.approvalState)
        assertSame(metadata, completed.metadata)
        val receipt = completed.output.single() as UIMessagePart.Text
        assertEquals("[Host receipt: tool invocation returned no content.]", receipt.text)
        assertEquals(JsonObject(mapOf("host_empty_tool_result" to JsonPrimitive(true))), receipt.metadata)
        assertFalse(receipt.text.contains("success", ignoreCase = true))
        assertEquals(null, completed.hostToolFailure())
    }

    @Test fun `nonempty structured output and original objects remain untouched`() {
        val structured = JsonObject(mapOf("structuredContent" to JsonObject(mapOf("value" to JsonPrimitive(7)))))
        val part = UIMessagePart.Text("{\"original\":7}", structured)
        val output = listOf<UIMessagePart>(part)
        val completed = call().withGenerationToolOutput(output)
        assertSame(output, completed.output)
        assertSame(part, completed.output.single())
        assertSame(structured, completed.output.single().metadata)
        assertEquals(null, completed.output.single().metadata?.get("host_empty_tool_result"))
    }

    @Test fun `nonempty list containing empty text is not reinterpreted as an empty invocation`() {
        val part = UIMessagePart.Text("")
        val output = listOf<UIMessagePart>(part)
        val completed = call().withGenerationToolOutput(output)
        assertSame(output, completed.output)
        assertSame(part, completed.output.single())
        assertEquals(null, part.metadata)
    }

    @Test fun `mixed batch retains both actual content and factual empty receipt in snapshot`() = runBlocking<Unit> {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { listOf(UIMessage.user("synthetic batch")) }
        val first = call("first")
        val empty = call("empty")
        val original = UIMessagePart.Text("actual side effect receipt")
        val results = listOf(first.withGenerationToolOutput(listOf(original)), empty.withGenerationToolOutput(emptyList()))
        snapshot.appendCompletedResponse(response(first, empty), results)
        val captured = snapshot.input { error("rerender") }.last().getTools()
        assertEquals(listOf("first", "empty"), captured.map { it.toolCallId })
        assertTrue(captured.all { it.isExecuted })
        assertEquals(original, captured.first().output.single())
        assertEquals(JsonPrimitive(true), captured.last().output.single().metadata?.get("host_empty_tool_result"))
    }
}
