package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import org.junit.Assert.*
import org.junit.Test

class GenerationObservationSnapshotTest {
    private val report = "{\"窗口\":\"测试窗\",\"本助手最近活跃\":\"2026-10-21T06:24:00Z\"}"
    private val marker = "[Orbis全局观测]"
    private fun call(id: String) = UIMessagePart.Tool(id, "compact", "{\"keep_recent\":0}")
    private fun result(call: UIMessagePart.Tool) = call.copy(output = listOf(UIMessagePart.Text("actual compact receipt")))
    private fun response(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun summary() = UIMessage.assistant("original authored summary").copy(parts = listOf(
        UIMessagePart.Text("original authored summary", JsonObject(mapOf(COMPACTION_SUMMARY_MARKER to JsonPrimitive(true)))),
    ))
    private fun reportCount(messages: List<UIMessage>): Int {
        fun count(parts: List<UIMessagePart>): Int = parts.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> if (part.text.startsWith(marker)) 1 else 0
                is UIMessagePart.Tool -> count(part.output)
                else -> 0
            }
        }
        return messages.sumOf { count(it.parts) }
    }

    @Test fun `tool continuations retain identical frozen observation and prepared prefix`() = runBlocking {
        val system = UIMessage.system("stable system")
        val human = UIMessage.user("human-authored text")
        val snapshot = GenerationInputSnapshot(observation = report)
        val first = snapshot.input { listOf(system, human) }
        val tool = call("first")
        snapshot.appendCompletedResponse(response(tool), listOf(result(tool)))
        val second = snapshot.input { error("must not reload observation or human history") }
        assertEquals(first, second.take(first.size))
        assertEquals(system, second.first())
        assertEquals(1, reportCount(second))
        assertEquals("human-authored text", human.toText())
        assertTrue(second.last().getTools().single().isExecuted)
    }

    @Test fun `compaction retains a single report on retained human without mutating replacement`() = runBlocking {
        val system = UIMessage.system("stable system")
        val human = UIMessage.user("retained human")
        val snapshot = GenerationInputSnapshot(observation = report)
        snapshot.input { listOf(system, human) }
        val tool = call("retain")
        val raw = response(tool)
        val results = listOf(result(tool))
        val bubble = response(*results.toTypedArray())
        val originalSummary = summary()
        val replacement = listOf(originalSummary, human, bubble)
        val before = replacement.map(::copyGenerationMessage)
        val prepared = snapshot.prepareCompactionInput(replacement, raw, results, bubble.id)
        assertEquals(1, reportCount(prepared))
        assertEquals(listOf(system), prepared.filter { it.role == MessageRole.SYSTEM })
        assertTrue(prepared.single { it.id == human.id }.toText().contains(marker + report))
        assertEquals(before, replacement)
        assertEquals(results, prepared.last().getTools())
        snapshot.acceptCompactionInput(prepared, raw, results, bubble.id)
        assertEquals(prepared, snapshot.input { error("must not regenerate") })
    }

    @Test fun `compaction dropping all human messages carries observation in tool receipt only`() = runBlocking {
        val system = UIMessage.system("stable system")
        val human = UIMessage.user("discarded human")
        val snapshot = GenerationInputSnapshot(observation = report)
        val accepted = snapshot.input { listOf(system, human) }
        val tool = call("drop")
        val raw = response(tool)
        val results = listOf(result(tool))
        val bubble = response(*results.toTypedArray())
        val originalSummary = summary()
        val replacement = listOf(originalSummary, bubble)
        val before = replacement.map(::copyGenerationMessage)
        val prepared = snapshot.prepareCompactionInput(replacement, raw, results, bubble.id)
        assertEquals(1, reportCount(prepared))
        assertFalse(prepared.any { it.role == MessageRole.USER })
        assertEquals(listOf(system), prepared.filter { it.role == MessageRole.SYSTEM })
        assertEquals(originalSummary, prepared[1])
        assertEquals(before, replacement)
        assertEquals(accepted, snapshot.input { error("preparation must not commit") })
        val projectedTool = prepared.last().getTools().single()
        assertEquals(tool.toolCallId, projectedTool.toolCallId)
        assertEquals(tool.toolName, projectedTool.toolName)
        assertEquals(tool.input, projectedTool.input)
        assertEquals(results.single().output, projectedTool.output.take(results.single().output.size))
        assertEquals(marker + report, (projectedTool.output.last() as UIMessagePart.Text).text)
        assertTrue(projectedTool.isExecuted)
        snapshot.acceptCompactionInput(prepared, raw, results, bubble.id)
        assertEquals(prepared, snapshot.input { error("must not regenerate") })
    }

    @Test fun `repeated compaction moves host report without accumulating or changing durable receipts`() = runBlocking {
        val snapshot = GenerationInputSnapshot(observation = report)
        snapshot.input { listOf(UIMessage.user("human")) }
        val firstTool = call("first-compact")
        val firstRaw = response(firstTool)
        val firstResults = listOf(result(firstTool))
        val firstBubble = response(*firstResults.toTypedArray())
        val first = snapshot.prepareCompactionInput(listOf(summary(), firstBubble), firstRaw, firstResults, firstBubble.id)
        snapshot.acceptCompactionInput(first, firstRaw, firstResults, firstBubble.id)
        val secondTool = call("second-compact")
        val secondRaw = response(secondTool)
        val secondResults = listOf(result(secondTool))
        val secondBubble = response(*secondResults.toTypedArray())
        val replacement = listOf(summary(), firstBubble, secondBubble)
        val before = replacement.map(::copyGenerationMessage)
        val second = snapshot.prepareCompactionInput(replacement, secondRaw, secondResults, secondBubble.id)
        assertEquals(1, reportCount(second))
        assertEquals(before, replacement)
        assertEquals(listOf("first-compact", "second-compact"), second.flatMap { it.getTools() }.map { it.toolCallId })
        assertTrue(second.flatMap { it.getTools() }.all { it.isExecuted })
        assertFalse(second.any { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM })
    }
}
