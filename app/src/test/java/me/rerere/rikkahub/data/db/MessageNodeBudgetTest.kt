package me.rerere.rikkahub.data.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Synthetic exact-byte tests: no model, database, files, or private conversation. */
@OptIn(ExperimentalSerializationApi::class)
class MessageNodeBudgetTest {
    private fun tool(index: Int = 0) = UIMessagePart.Tool(
        toolCallId = "synthetic-$index", toolName = "gallery_append", input = "{}",
    )

    private fun nodeOfSize(bytes: Int, tools: Int = 0): MessageNode {
        val message = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("")) + List(tools) { tool(it) })
        val overhead = MessageNodeBudget.measureNode(listOf(message), Int.MAX_VALUE)
        require(bytes >= overhead)
        return message.copy(parts = listOf(UIMessagePart.Text("x".repeat(bytes - overhead))) +
            message.parts.drop(1)).toMessageNode()
    }

    private fun interrupted(node: MessageNode): List<UIMessage> = node.messages.mapIndexed { index, message ->
        if (index != node.selectIndex) message else message.copy(parts = message.parts.map {
            if (it is UIMessagePart.Tool && !it.isExecuted) it.withHostToolFailure(HostToolFailure.INTERRUPTED) else it
        })
    }

    @Test fun `measurement exactly matches UTF8 persistence including escaping and invalid surrogate normalization`() {
        val text = "中🙂\"\\\n\u0000\u0001\uD83D!\uDC22".repeat(1400)
        val messages = listOf(UIMessage.assistant(text), UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(tool().copy(input = text, output = listOf(UIMessagePart.Text(text))))))
        val output = ByteArrayOutputStream()
        JsonInstant.encodeToStream(messages, output)
        assertEquals(output.size(), MessageNodeBudget.measureNode(messages))
        assertArrayEquals(output.toByteArray(), MessageNodeBudget.encodeNode(messages).toByteArray(Charsets.UTF_8))
        assertEquals(output.size(), MessageNodeBudget.measureNode(messages, output.size()))
        assertThrows(MessageNodeCapacityException::class.java) {
            MessageNodeBudget.measureNode(messages, output.size() - 1)
        }
        assertThrows(MessageNodeCapacityException::class.java) {
            MessageNodeBudget.encodeNode(messages, output.size() - 1)
        }
    }

    @Test fun `storage exact hard limit accepts boundary and refuses one extra byte`() {
        val node = nodeOfSize(MessageNodeBudget.MAX_NODE_BYTES)
        assertEquals(MessageNodeBudget.MAX_NODE_BYTES, MessageNodeBudget.measureNode(node.messages))
        assertThrows(MessageNodeCapacityException::class.java) {
            MessageNodeBudget.measureNode(nodeOfSize(MessageNodeBudget.MAX_NODE_BYTES + 1).messages)
        }
    }

    @Test fun `generation soft and hard limits preserve a recovery reserve`() {
        assertFalse(MessageNodeBudget.admitTail(listOf(nodeOfSize(MessageNodeBudget.SOFT_NODE_BYTES - 1))))
        assertTrue(MessageNodeBudget.admitTail(listOf(nodeOfSize(MessageNodeBudget.SOFT_NODE_BYTES))))
        assertTrue(MessageNodeBudget.admitTail(listOf(nodeOfSize(MessageNodeBudget.MAX_GENERATION_NODE_BYTES))))
        assertThrows(MessageNodeCapacityException::class.java) {
            MessageNodeBudget.admitTail(listOf(nodeOfSize(MessageNodeBudget.MAX_GENERATION_NODE_BYTES + 1)))
        }
        assertTrue(MessageNodeBudget.MAX_GENERATION_NODE_BYTES < MessageNodeBudget.MAX_NODE_BYTES)
    }

    @Test fun `all branches count even when tiny selected branch is last`() {
        val first = nodeOfSize(400 * 1024).currentMessage
        val last = UIMessage.assistant("selected")
        val node = first.toMessageNode().copy(messages = listOf(first, first.copy(id = last.id), last), selectIndex = 2)
        assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(listOf(node)) }
    }

    @Test fun `tail aggregate reaches soft then refuses hard across multiple individually safe nodes`() {
        val node = nodeOfSize(500 * 1024)
        assertFalse(MessageNodeBudget.admitTail(List(8) { node }))
        assertTrue(MessageNodeBudget.admitTail(List(9) { node }))
        assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(List(13) { node }) }
    }

    @Test fun `256 pending tools have exact interrupted recovery budget without modifying approvals`() {
        val node = nodeOfSize(MessageNodeBudget.MAX_GENERATION_NODE_BYTES, tools = 256).let { raw ->
            raw.copy(messages = listOf(raw.currentMessage.copy(parts = raw.currentMessage.parts.map {
                if (it is UIMessagePart.Tool) it.copy(approvalState = ToolApprovalState.Pending) else it
            })))
        }
        // Approval metadata consumes real bytes too; choose the exact post-approval safe size.
        val total = MessageNodeBudget.measureNode(node.messages, Int.MAX_VALUE)
        val text = node.currentMessage.parts.first() as UIMessagePart.Text
        val bounded = node.copy(messages = listOf(node.currentMessage.copy(parts =
            listOf(text.copy(text = text.text.dropLast(total - MessageNodeBudget.MAX_GENERATION_NODE_BYTES))) +
                node.currentMessage.parts.drop(1))))
        val recoveryBytes = MessageNodeBudget.measureNode(interrupted(bounded), Int.MAX_VALUE)
        if (recoveryBytes > MessageNodeBudget.MAX_NODE_BYTES) {
            assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(listOf(bounded)) }
        } else {
            assertTrue(MessageNodeBudget.admitTail(listOf(bounded)))
        }
        assertTrue(bounded.currentMessage.getTools().all { it.isPending && !it.isExecuted })
    }

    @Test fun `large provider tool batch cannot exhaust per node recovery space despite raw fitting`() {
        val node = nodeOfSize(MessageNodeBudget.MAX_GENERATION_NODE_BYTES, tools = 600)
        assertTrue(MessageNodeBudget.measureNode(interrupted(node), Int.MAX_VALUE) > MessageNodeBudget.MAX_NODE_BYTES)
        assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(listOf(node)) }
        assertTrue(node.currentMessage.getTools().all { !it.isExecuted })
    }

    @Test fun `recovery aggregate has its own hard bound even when raw aggregate fits`() {
        val node = nodeOfSize(600 * 1024, tools = 350)
        assertTrue(MessageNodeBudget.measureNode(node.messages).toLong() * 10 < MessageNodeBudget.MAX_TAIL_BYTES)
        val projected = MessageNodeBudget.measureNode(interrupted(node), Int.MAX_VALUE)
        assertTrue(projected <= MessageNodeBudget.MAX_NODE_BYTES)
        assertTrue(projected.toLong() * 10 > MessageNodeBudget.MAX_TAIL_BYTES)
        assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(List(10) { node }) }
    }

    @Test fun `Chinese and control characters in tool inputs cannot bypass exact bytes with character count`() {
        for (text in listOf("中".repeat(250_000), "\u0000".repeat(125_000))) {
            assertTrue(text.length < MessageNodeBudget.MAX_GENERATION_NODE_BYTES)
            val node = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool().copy(input = text))).toMessageNode()
            assertThrows(MessageNodeCapacityException::class.java) { MessageNodeBudget.admitTail(listOf(node)) }
        }
    }

    @Test fun `recovery budget only adds markers to selected branch and preserves completed tools`() {
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool()))
        val completed = pending.copy(parts = listOf(tool().copy(output = listOf(UIMessagePart.Text("saved")))))
        val node = pending.toMessageNode().copy(messages = listOf(pending, completed), selectIndex = 1)
        val before = JsonInstant.encodeToString(node.messages)
        assertFalse(MessageNodeBudget.admitTail(listOf(node)))
        assertEquals(before, JsonInstant.encodeToString(node.messages))
        assertEquals(node.messages, interrupted(node))
    }
}
