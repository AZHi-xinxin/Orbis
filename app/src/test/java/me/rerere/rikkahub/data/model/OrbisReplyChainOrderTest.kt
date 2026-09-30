package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

/** Tests the actual grouping function consumed by ChatMessage, not a parallel test-only grouper. */
class OrbisReplyChainOrderTest {
    private val started = Instant.parse("2026-09-24T10:00:00Z")
    private val finished = Instant.parse("2026-09-24T10:00:01Z")

    private fun reasoning(value: String) = UIMessagePart.Reasoning(value, started, finished)
    private fun tool(id: String) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "tool_$id",
        input = "{\"id\":\"$id\"}",
        output = listOf(UIMessagePart.Text("receipt_$id")),
    )

    private fun flatten(blocks: List<MessagePartBlock>): List<UIMessagePart> = blocks.flatMap { block ->
        when (block) {
            is MessagePartBlock.ContentBlock -> listOf(block.part)
            is MessagePartBlock.ThinkingBlock -> block.steps.map { step ->
                when (step) {
                    is ThinkingStep.ReasoningStep -> step.reasoning
                    is ThinkingStep.ToolStep -> step.tool
                    is ThinkingStep.ServerToolStep -> step.tool
                }
            }
        }
    }

    private fun assertOriginalOrder(parts: List<UIMessagePart>): List<MessagePartBlock> {
        val blocks = parts.groupMessageParts()
        val restored = flatten(blocks)
        assertEquals(parts.size, restored.size)
        parts.zip(restored).forEach { (original, grouped) -> assertSame(original, grouped) }
        blocks.filterIsInstance<MessagePartBlock.ContentBlock>().forEach { block ->
            assertSame(parts[block.index], block.part)
        }
        return blocks
    }

    @Test fun requestedThinkingToolProseThinkingToolThinkingProseOrderIsExact() {
        val parts = listOf(reasoning("r1"), tool("1"), UIMessagePart.Text("中途正文"),
            reasoning("r2"), tool("2"), reasoning("r3"), UIMessagePart.Text("最后正文"))
        val blocks = assertOriginalOrder(parts)
        assertEquals(4, blocks.size)
        assertEquals(2, (blocks[0] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(2, (blocks[1] as MessagePartBlock.ContentBlock).index)
        assertEquals(3, (blocks[2] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(6, (blocks[3] as MessagePartBlock.ContentBlock).index)
    }

    @Test fun paragraphAndActionProjectionDoesNotMoveIntermediateProsePastTools() {
        val options = OrbisChatFlowSettings(distinguishActions = true,
            markers = setOf(OrbisActionMarker.FULLWIDTH_ROUND))
        val parts = listOf(reasoning("r1"), tool("1"), UIMessagePart.Text("先说\n\n（点头）再说"),
            reasoning("r2"), tool("2"), UIMessagePart.Text("最后说"))
        val displayed = assertOriginalOrder(parts).flatMap { block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> block.steps.map { step ->
                    when (step) {
                        is ThinkingStep.ReasoningStep -> "reasoning:${step.reasoning.reasoning}"
                        is ThinkingStep.ToolStep -> "tool:${step.tool.toolCallId}"
                        is ThinkingStep.ServerToolStep -> "server:${step.tool.toolCallId}"
                    }
                }
                is MessagePartBlock.ContentBlock -> {
                    val text = (block.part as UIMessagePart.Text).text
                    splitOrbisReply(text, options).filter {
                        text.substring(it.start, it.endExclusive).isNotBlank()
                    }.map {
                        (if (it.action) "action:" else "prose:") + text.substring(it.start, it.endExclusive)
                    }
                }
            }
        }
        assertEquals(listOf("reasoning:r1", "tool:1", "prose:先说\n\n", "action:（点头）", "prose:再说",
            "reasoning:r2", "tool:2", "prose:最后说"), displayed)
    }

    @Test fun parallelAndServerToolsRemainBetweenTheOriginalContentBlocks() {
        val server = UIMessagePart.ServerTool("server", "web_search", status = ServerToolStatus.COMPLETED)
        val parts = listOf(UIMessagePart.Text("开头"), tool("a"), tool("b"), server,
            reasoning("r"), UIMessagePart.Image("https://example.invalid/image.png"),
            UIMessagePart.Text("正文"), tool("c"))
        val blocks = assertOriginalOrder(parts)
        assertEquals(5, blocks.size)
        assertEquals(4, (blocks[1] as MessagePartBlock.ThinkingBlock).steps.size)
        assertSame(server, ((blocks[1] as MessagePartBlock.ThinkingBlock).steps[2] as ThinkingStep.ServerToolStep).tool)
        assertTrue((blocks[2] as MessagePartBlock.ContentBlock).part is UIMessagePart.Image)
        assertTrue(blocks.last() is MessagePartBlock.ThinkingBlock)
    }

    @Test fun eachStreamingPartPrefixPreservesAllExistingPartObjects() {
        val parts = listOf(reasoning("r1"), tool("1"), UIMessagePart.Text("中途"),
            reasoning("r2"), tool("2"), reasoning("r3"), UIMessagePart.Text("末尾"))
        for (size in 0..parts.size) assertOriginalOrder(parts.take(size))
    }

    @Test fun growingIntermediateTextDoesNotDuplicateOrMoveToolReceipts() {
        val firstReasoning = reasoning("r1")
        val firstTool = tool("1")
        val secondReasoning = reasoning("r2")
        val secondTool = tool("2")
        val text = "正在说话。（点头）\n\n下一段。"
        for (size in 0..text.length) {
            val parts = listOf(firstReasoning, firstTool, UIMessagePart.Text(text.take(size)),
                secondReasoning, secondTool)
            val restored = flatten(assertOriginalOrder(parts))
            assertEquals(listOf(firstTool, secondTool), restored.filterIsInstance<UIMessagePart.Tool>())
            assertEquals(1, restored.filterIsInstance<UIMessagePart.Text>().size)
        }
    }

    @Test fun toolOutputTextIsNeverPromotedToSpokenProse() {
        val firstTool = tool("private_receipt")
        val parts = listOf(firstTool, UIMessagePart.Text("真正的正文"))
        val blocks = assertOriginalOrder(parts)
        val content = blocks.filterIsInstance<MessagePartBlock.ContentBlock>()
        assertEquals(listOf("真正的正文"), content.map { (it.part as UIMessagePart.Text).text })
        assertSame(firstTool.output, ((blocks[0] as MessagePartBlock.ThinkingBlock).steps.single() as ThinkingStep.ToolStep).tool.output)
    }

    @Test fun equalReasoningTimestampsDoNotMergeSeparateStepsOrLoseTheirOrder() {
        val parts = listOf(reasoning("first"), reasoning("second"), tool("1"),
            UIMessagePart.Text("正文"), reasoning("third"))
        val blocks = assertOriginalOrder(parts)
        assertEquals(listOf("first", "second"), (blocks.first() as MessagePartBlock.ThinkingBlock)
            .steps.filterIsInstance<ThinkingStep.ReasoningStep>().map { it.reasoning.reasoning })
        assertEquals("third", ((blocks.last() as MessagePartBlock.ThinkingBlock)
            .steps.single() as ThinkingStep.ReasoningStep).reasoning.reasoning)
    }
}
