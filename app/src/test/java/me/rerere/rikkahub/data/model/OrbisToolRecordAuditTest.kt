package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Independent cross-feature audit of deleted-call ordering, references and context accounting. */
class OrbisToolRecordAuditTest {
    private val before = LocalDateTime(2026, 9, 24, 20, 0)
    private val now = LocalDateTime(2026, 9, 24, 21, 0)
    private val after = LocalDateTime(2026, 9, 24, 22, 0)
    private val model = Uuid.random()

    private fun tool(id: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = "tool_$id", input = "{}",
        output = listOf(UIMessagePart.Text("receipt_$id")),
    )

    private fun message(parts: List<UIMessagePart>) = UIMessage(
        role = MessageRole.ASSISTANT, parts = parts, createdAt = before, finishedAt = before, modelId = model,
    )

    private fun edit(message: UIMessage, id: String, restore: Boolean = false) =
        message.withToolRecordEdit(OrbisToolRecordEdit(message.id, id, restore), now)

    private fun permutations(ids: List<String>): List<List<String>> =
        if (ids.isEmpty()) listOf(emptyList()) else ids.flatMap { first ->
            permutations(ids - first).map { listOf(first) + it }
        }

    @Test fun everyDeleteAndRestoreOrderKeepsTheOriginalInterleavedSlotOrder() {
        val ids = listOf("a", "b", "c")
        val original = message(listOf(UIMessagePart.Text("before"), tool("a"),
            UIMessagePart.Text("middle"), tool("b"), tool("c"), UIMessagePart.Text("after")))
        permutations(ids).forEach { deletionOrder ->
            permutations(ids).forEach { restorationOrder ->
                var current = original
                deletionOrder.forEach { id -> current = edit(current, id) }
                assertEquals(original.parts.filterNot { it is UIMessagePart.Tool }, current.parts)
                restorationOrder.forEachIndexed { index, id ->
                    current = edit(current, id, restore = true)
                    val restored = restorationOrder.take(index + 1).toSet()
                    assertEquals(original.parts.filter {
                        it !is UIMessagePart.Tool || it.toolCallId in restored
                    }, current.parts)
                }
                assertEquals(original.parts, current.parts)
                assertTrue(current.deletedToolRecords.isEmpty())
                assertEquals(6L, current.toolRecordRevision)
            }
        }
    }

    @Test fun deletionAndRestorationNeverReplayToolsOrChangeTheirOutputObjects() {
        val target = tool("target")
        val sibling = tool("sibling")
        val original = message(listOf(target, sibling, UIMessagePart.Text("正文")))
        val deleted = edit(original, "target")
        assertSame(sibling, deleted.parts.first())
        assertSame(target, deleted.deletedToolRecords.single().tool)
        assertSame(target.output, deleted.deletedToolRecords.single().tool.output)
        val restored = edit(deleted, "target", restore = true)
        assertSame(target, restored.parts.first())
        assertTrue((restored.parts.first() as UIMessagePart.Tool).isExecuted)
    }

    @Test fun olderFullSnapshotCannotResurrectDeletedCallsOrEraseAnIndependentTranslation() {
        val original = message(listOf(tool("a"), UIMessagePart.Text("spoken prose")))
        val committed = edit(original, "a").toMessageNode()
        val delayed = committed.copy(messages = listOf(original.copy(translation = "译文")))
        val merged = preserveToolRecordEdits(delayed, committed).currentMessage
        assertEquals("译文", merged.translation)
        assertEquals(committed.currentMessage.parts, merged.parts)
        assertEquals(committed.currentMessage.deletedToolRecords, merged.deletedToolRecords)
        assertEquals(committed.currentMessage.toolRecordRevision, merged.toolRecordRevision)
    }

    @Test fun olderDeletedSnapshotCannotUndoACommittedRestoration() {
        val original = message(listOf(UIMessagePart.Text("before"), tool("a"), UIMessagePart.Text("after")))
        val deleted = edit(original, "a")
        val restored = edit(deleted, "a", restore = true).toMessageNode()
        val delayed = restored.copy(messages = listOf(deleted))
        val merged = preserveToolRecordEdits(delayed, restored).currentMessage
        assertEquals(original.parts, merged.parts)
        assertTrue(merged.deletedToolRecords.isEmpty())
        assertEquals(2L, merged.toolRecordRevision)
    }

    @Test fun undoAttachmentsStillCountAsLocalReferencesButNotLiveToolOutput() {
        val target = tool("picture").copy(output = listOf(
            UIMessagePart.Image("file:///test-image.png"), UIMessagePart.Document("file:///test.txt", "test.txt"),
        ))
        val deleted = edit(message(listOf(target, UIMessagePart.Text("正文"))), "picture")
        assertTrue(deleted.parts.localFileUrls().isEmpty())
        assertEquals(setOf("file:///test-image.png", "file:///test.txt"),
            deleted.partsWithToolUndoAttachments().localFileUrls())
    }

    @Test fun deletedLargeReceiptDoesNotCountTowardCompactionEstimate() {
        val target = tool("huge").copy(output = listOf(UIMessagePart.Text("机密工具回执".repeat(40_000))))
        val original = message(listOf(target, UIMessagePart.Text("正文")))
        val deleted = edit(original, "huge")
        val plain = deleted.copy(deletedToolRecords = emptyList(), toolRecordRevision = 0, toolRecordsUpdatedAt = null)
        assertEquals(estimateCompactionTokens(listOf(plain)), estimateCompactionTokens(listOf(deleted)))
        assertTrue(estimateCompactionTokens(listOf(original)) > estimateCompactionTokens(listOf(deleted)) + 100_000)
    }

    @Test fun oldProviderUsageIsInvalidatedWhenToolRecordsWereEditedAfterTheResponse() {
        val original = message(listOf(tool("a"), UIMessagePart.Text("正文"))).copy(
            usage = TokenUsage(promptTokens = 340_000, completionTokens = 300, totalTokens = 340_300),
        )
        assertTrue(estimateCurrentContext(listOf(original), model).tokens >= 340_000)
        val deleted = edit(original, "a")
        assertTrue(estimateCurrentContext(listOf(deleted), model).tokens < 1_000)
        val restored = edit(deleted, "a", restore = true)
        assertTrue(estimateCurrentContext(listOf(restored), model).tokens < 1_000)
    }

    @Test fun subsequentProviderUsageCanBecomeAuthoritativeAgainWithoutCountingUndoPayload() {
        val deleted = edit(message(listOf(tool("a"), UIMessagePart.Text("旧正文"))), "a")
        val next = message(listOf(UIMessagePart.Text("新正文"))).copy(
            finishedAt = after,
            usage = TokenUsage(promptTokens = 5_000, completionTokens = 20, totalTokens = 5_020),
        )
        assertEquals(5_020L, estimateCurrentContext(listOf(deleted, next), model).tokens)
    }
}
