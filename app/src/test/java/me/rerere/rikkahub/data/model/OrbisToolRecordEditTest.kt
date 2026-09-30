package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.withoutDeletedToolRecordData
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisToolRecordEditTest {
    private val now = LocalDateTime(2026, 9, 24, 21, 30)
    private fun tool(id: String) = UIMessagePart.Tool(id, "synthetic_$id", """{"request":"$id"}""",
        listOf(UIMessagePart.Text("receipt-$id")))
    private fun fixture(): UIMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Reasoning("first thought"), UIMessagePart.Text("  first prose\n"), tool("one"), tool("two"),
        UIMessagePart.Reasoning("second thought"), UIMessagePart.Text("last prose"), tool("three"),
    ))
    private fun UIMessage.delete(id: String) = withToolRecordEdit(OrbisToolRecordEdit(this.id, id, false), now)
    private fun UIMessage.restore(id: String) = withToolRecordEdit(OrbisToolRecordEdit(this.id, id, true), now)
    private fun reject(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalStateException) { }
    }

    @Test fun `delete removes exactly one entire call and receipt without touching other parts`() {
        val original = fixture()
        val deleted = original.delete("one")
        assertEquals(original.parts.filterNot { it is UIMessagePart.Tool && it.toolCallId == "one" }, deleted.parts)
        assertEquals(listOf("two", "three"), deleted.getTools().map { it.toolCallId })
        assertEquals(original.getTools().first(), deleted.deletedToolRecords.single().tool)
        // toText() inserts a separator for every non-text part; compare real prose, including
        // exact whitespace and metadata, rather than these implementation-only blank slots.
        assertEquals(original.parts.filterIsInstance<UIMessagePart.Text>(), deleted.parts.filterIsInstance<UIMessagePart.Text>())
        assertEquals(1L, deleted.toolRecordRevision)
        assertEquals(now, deleted.toolRecordsUpdatedAt)
    }

    @Test fun `undo restores exact parts without invoking a tool`() {
        val original = fixture()
        val restored = original.delete("two").restore("two")
        assertEquals(original.parts, restored.parts)
        assertTrue(restored.deletedToolRecords.isEmpty())
        assertEquals(2L, restored.toolRecordRevision)
    }

    @Test fun `every deletion and restoration ordering preserves original placement`() {
        val orders = listOf(listOf("one", "two", "three"), listOf("three", "one", "two"), listOf("two", "three", "one"))
        orders.forEach { deletes -> orders.forEach { restores ->
            val original = fixture()
            val deleted = deletes.fold(original) { value, id -> value.delete(id) }
            assertTrue(deleted.getTools().isEmpty())
            val restored = restores.fold(deleted) { value, id -> value.restore(id) }
            assertEquals(original.parts, restored.parts)
            assertTrue(restored.deletedToolRecords.isEmpty())
        } }
    }

    @Test fun `deleting again after restoring remains lossless`() {
        val original = fixture()
        val value = original.delete("two").delete("one").restore("two").delete("two").restore("one").restore("two")
        assertEquals(original.parts, value.parts)
    }

    @Test fun `serialization keeps undo across restart and never converts into text`() {
        val original = fixture()
        val deleted = original.delete("two")
        val restarted = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(deleted))
        assertEquals(deleted, restarted)
        assertEquals(original.parts, restarted.restore("two").parts)
        assertFalse(restarted.toText().contains("receipt-two"))
    }

    @Test fun `legacy message JSON defaults to no local removal metadata`() {
        val value = Json.decodeFromString<UIMessage>("""{"role":"assistant","parts":[]}""")
        assertTrue(value.deletedToolRecords.isEmpty())
        assertEquals(0L, value.toolRecordRevision)
        assertNull(value.toolRecordsUpdatedAt)
    }

    @Test fun `invalid duplicate missing pending and foreign tool requests are rejected`() {
        val original = fixture()
        reject { original.delete("missing") }
        reject { original.restore("one") }
        reject { original.delete("one").delete("one") }
        reject { original.copy(role = MessageRole.USER).delete("one") }
        reject { original.copy(parts = original.parts + tool("one")).delete("one") }
        reject { original.copy(parts = original.parts + tool("pending").copy(output = emptyList())).delete("one") }
    }

    @Test fun `hidden branch cannot be edited through the current branch action`() {
        val visible = fixture()
        val hidden = fixture()
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(visible, hidden))))
        reject { conversation.applyToolRecordEdit(OrbisToolRecordEdit(hidden.id, "one", false), now) }
        val edited = conversation.applyToolRecordEdit(OrbisToolRecordEdit(visible.id, "one", false), now)
        assertEquals(hidden, edited.messageNodes.single().messages[1])
    }

    @Test fun `selected empty after deletion remains local undo holder without invented prose`() {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("one")))
        val deleted = original.delete("one")
        assertTrue(deleted.parts.isEmpty())
        assertFalse(deleted.isValidToUpload())
        assertEquals("", deleted.toText())
        assertEquals(original.parts, deleted.restore("one").parts)
    }

    @Test fun `late snapshot cannot resurrect deleted records and keeps unrelated translation`() {
        val original = fixture()
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(original.toMessageNode()))
        val committed = before.applyToolRecordEdit(OrbisToolRecordEdit(original.id, "one", false), now)
        val incoming = before.copy(messageNodes = listOf(before.messageNodes.single().copy(messages = listOf(original.copy(translation = "new translation")))))
        val reconciled = withCommittedToolRecordEdits(incoming, committed)
        assertEquals(committed.currentMessages.single().parts, reconciled.currentMessages.single().parts)
        assertEquals("new translation", reconciled.currentMessages.single().translation)
    }

    @Test fun `late deleted snapshot cannot undo an already committed restoration`() {
        val original = fixture()
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(original.toMessageNode()))
        val deleted = before.applyToolRecordEdit(OrbisToolRecordEdit(original.id, "one", false), now)
        val restored = deleted.applyToolRecordEdit(OrbisToolRecordEdit(original.id, "one", true), now)
        assertEquals(restored, withCommittedToolRecordEdits(deleted, restored))
    }

    @Test fun `merge does not resurrect removed nodes or overwrite a different owner`() {
        val original = fixture()
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(original.toMessageNode()))
        val committed = before.applyToolRecordEdit(OrbisToolRecordEdit(original.id, "one", false), now)
        assertTrue(withCommittedToolRecordEdits(before.copy(messageNodes = emptyList()), committed).messageNodes.isEmpty())
        val foreign = before.copy(assistantId = Uuid.random())
        assertEquals(foreign, withCommittedToolRecordEdits(foreign, committed))
    }

    @Test fun `stale conflicting prose edit is rejected instead of losing text or reviving tool`() {
        val original = fixture()
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(original.toMessageNode()))
        val committed = before.applyToolRecordEdit(OrbisToolRecordEdit(original.id, "one", false), now)
        val conflicting = before.copy(messageNodes = listOf(before.messageNodes.single().copy(messages = listOf(
            original.copy(parts = listOf(UIMessagePart.Text("different prose")) + original.getTools()),
        ))))
        reject { withCommittedToolRecordEdits(conflicting, committed) }
    }

    @Test fun `attachments remain reachable through local undo but not active parts`() {
        val attached = tool("one").copy(output = listOf(UIMessagePart.Image("file:///synthetic/image.png")))
        val deleted = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(attached)).delete("one")
        assertTrue(deleted.parts.localFileUrls().isEmpty())
        assertEquals(setOf("file:///synthetic/image.png"), deleted.partsWithToolUndoAttachments().localFileUrls())
    }

    @Test fun `changed prose may save but restore refuses to guess an insertion position`() {
        val original = fixture()
        val deleted = original.delete("one")
        val changed = deleted.copy(parts = deleted.parts.map {
            if (it is UIMessagePart.Text) it.copy(text = "changed text") else it
        })
        val node = deleted.toMessageNode()
        val incoming = node.copy(messages = listOf(changed))
        assertEquals(changed.parts, preserveToolRecordEdits(incoming, node).currentMessage.parts)
        reject { changed.restore("one") }
    }

    @Test fun `forked attachment URLs can refresh anchors without changing active prose`() {
        val deleted = fixture().delete("one")
        val forked = deleted.copy(deletedToolRecords = deleted.deletedToolRecords.map {
            it.copy(tool = it.tool.copy(output = listOf(UIMessagePart.Image("file:///synthetic/fork.png"))))
        }).reanchorDeletedToolRecords()
        assertEquals(deleted.parts, forked.parts)
        assertEquals("file:///synthetic/fork.png", (forked.restore("one").getTools().first().output.single() as UIMessagePart.Image).url)
    }

    @Test fun `token estimate and provider copy exclude even huge local undo payloads`() {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("visible"),
            tool("one").copy(output = listOf(UIMessagePart.Text("PRIVATE_UNDO_PAYLOAD".repeat(10000))))))
        val deleted = original.delete("one")
        val stripped = deleted.withoutDeletedToolRecordData()
        assertEquals(estimateCompactionTokens(listOf(stripped)), estimateCompactionTokens(listOf(deleted)))
        assertTrue(estimateCompactionTokens(listOf(deleted)) < estimateCompactionTokens(listOf(original)))
        assertFalse(Json.encodeToString(stripped).contains("PRIVATE_UNDO_PAYLOAD"))
        assertEquals(deleted.parts, stripped.parts)
    }

    @Test fun `stale provider usage is ignored until a response after latest tool edit`() {
        val modelId = Uuid.random()
        val original = fixture().copy(modelId = modelId, finishedAt = LocalDateTime(2026, 9, 24, 21, 0),
            usage = TokenUsage(promptTokens = 350000, completionTokens = 100))
        val deleted = original.delete("one")
        assertTrue(estimateCurrentContext(listOf(deleted), modelId).tokens < 1000)
        val later = UIMessage.assistant("new reply").copy(modelId = modelId,
            finishedAt = LocalDateTime(2026, 9, 24, 21, 31), usage = TokenUsage(promptTokens = 1000, completionTokens = 50))
        assertEquals(1050L, estimateCurrentContext(listOf(deleted, later), modelId).tokens)
    }
}
