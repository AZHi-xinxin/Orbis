package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only synthetic messages and in-memory receipts; no SettingsStore, files or paid model. */
class GenerationContextSelectionTest {
    private val snapshots = Json { encodeDefaults = true }
    private fun history() = listOf(UIMessage.user("SYNTHETIC_FIRST_SENTENCE")) + (1..42).map {
        if (it % 2 == 0) UIMessage.user("synthetic user $it") else UIMessage.assistant("synthetic assistant $it")
    }

    @Test fun `finite then zero in same branch reselects first sentence without changing stored original`() = runBlocking {
        val stored = history()
        val before = snapshots.encodeToString(stored)
        val finite = GenerationContextSelection(stored, 20)
        val firstWake = GenerationInputSnapshot().input { finite.requestMessages() }
        assertFalse(finite.includesWholeBranch)
        assertFalse(firstWake.any { it.toText() == "SYNTHETIC_FIRST_SENTENCE" })
        // Saving the next generated reply appends to full history, not to firstWake's suffix.
        val nextBranch = stored + UIMessage.assistant("synthetic finite reply") + UIMessage.user("synthetic next wake")
        val restored = GenerationContextSelection(nextBranch, 0)
        val nextWake = GenerationInputSnapshot().input { restored.requestMessages() }
        assertTrue(restored.includesWholeBranch)
        assertEquals("SYNTHETIC_FIRST_SENTENCE", nextWake.first().toText())
        assertEquals(nextBranch, nextWake)
        assertEquals(before, snapshots.encodeToString(stored))
        assertEquals(before, snapshots.encodeToString(nextBranch.take(stored.size)))
        val receipt = restored.receipt(nextWake, 1234)
        assertEquals(0, receipt.contextMessageLimit)
        assertEquals(1, receipt.firstPreparedSourcePosition)
        assertTrue(receipt.firstLocalMessagePrepared)
    }

    @Test fun `input transform cannot mutate locally stored metadata or tool results`() {
        val output = mutableListOf<UIMessagePart>(UIMessagePart.Text("synthetic result"))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("original", JsonObject(mapOf("stable" to JsonPrimitive(true)))),
            UIMessagePart.Tool("synthetic-call", "read", "{}", output = output),
        ))
        val before = snapshots.encodeToString(original)
        val selected = GenerationContextSelection(listOf(original), 0).requestMessages().single()
        assertNotSame(original, selected)
        assertNotSame(original.parts, selected.parts)
        assertNotSame(original.parts.first(), selected.parts.first())
        assertNotSame(original.getTools().single().output, selected.getTools().single().output)
        (selected.parts as MutableList<UIMessagePart>)[0] = UIMessagePart.Text("transformed",
            JsonObject(mapOf("mutated" to JsonPrimitive(true))))
        (selected.getTools().single().output as MutableList<UIMessagePart>)[0] = UIMessagePart.Text("transformed result")
        assertEquals(before, snapshots.encodeToString(original))
    }

    @Test fun `changing the limit cannot rewrite already prepared automatic continuation prefix`() = runBlocking {
        val stored = history()
        var limit = 20
        val snapshot = GenerationInputSnapshot()
        val first = snapshot.input { GenerationContextSelection(stored, limit).requestMessages() }
        val call = UIMessagePart.Tool("synthetic-call", "read", "{}")
        snapshot.appendCompletedResponse(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call)),
            listOf(call.copy(output = listOf(UIMessagePart.Text("result")))))
        limit = 0
        val continuation = snapshot.input { GenerationContextSelection(stored, limit).requestMessages() }
        assertEquals(first, continuation.take(first.size))
        assertFalse(continuation.any { it.toText() == "SYNTHETIC_FIRST_SENTENCE" })
        val newWake = GenerationInputSnapshot().input { GenerationContextSelection(stored, limit).requestMessages() }
        assertEquals("SYNTHETIC_FIRST_SENTENCE", newWake.first().toText())
    }

    @Test fun `zero does not silently undo explicit compaction but preserves its full active branch`() {
        val summary = UIMessage.assistant("synthetic explicitly authored summary").copy(parts = listOf(
            UIMessagePart.Text("synthetic explicitly authored summary",
                JsonObject(mapOf(COMPACTION_SUMMARY_MARKER to JsonPrimitive(true)))),
        ))
        val active = listOf(summary) + history().drop(20)
        val finite = GenerationContextSelection(active, 20)
        assertEquals(summary, finite.requestMessages().first())
        val full = GenerationContextSelection(active, 0)
        assertEquals(active, full.requestMessages())
        assertTrue(full.receipt(full.requestMessages(), 1234).compactionSummaryAtStart)
        assertFalse(full.requestMessages().any { it.toText() == "SYNTHETIC_FIRST_SENTENCE" })
    }

    @Test fun `receipt distinguishes projection from a transformer omitting the earliest message`() {
        val source = history()
        val selection = GenerationContextSelection(source, 0)
        val prepared = listOf(UIMessage.system("synthetic system")) + selection.requestMessages().drop(1)
        val receipt = selection.receipt(prepared, 1234)
        assertEquals(source.size, receipt.sourceMessageCount)
        assertEquals(source.size, receipt.selectedMessageCount)
        assertEquals(1, receipt.firstSelectedPosition)
        assertEquals(2, receipt.firstPreparedSourcePosition)
        assertTrue(receipt.firstLocalMessageSelected)
        assertFalse(receipt.firstLocalMessagePrepared)
        assertFalse(receipt.toString().contains("SYNTHETIC_FIRST_SENTENCE"))
        assertFalse(receipt.toString().contains(source.first().id.toString()))
    }

    @Test fun `receipt treats empty invalid first message as not provider ready`() {
        val source = listOf(UIMessage.user(""), UIMessage.user("synthetic valid"))
        val selection = GenerationContextSelection(source, 0)
        val receipt = selection.receipt(selection.requestMessages(), 1234)
        assertTrue(receipt.firstLocalMessageSelected)
        assertFalse(receipt.firstLocalMessagePrepared)
        assertEquals(2, receipt.firstPreparedSourcePosition)
        assertEquals(1, receipt.preparedMessageCount)
    }

    @Test fun `in memory receipt store is bounded and replaces only same conversation`() {
        val selection = GenerationContextSelection(history(), 20)
        val receipt = selection.receipt(selection.requestMessages(), 1234)
        val store = GenerationContextReceiptStore(2)
        store.record("synthetic-a", receipt)
        store.record("synthetic-b", receipt)
        store.record("synthetic-a", receipt.copy(contextMessageLimit = 0))
        store.record("synthetic-c", receipt)
        assertEquals(setOf("synthetic-a", "synthetic-c"), store.receipts.value.keys)
        assertEquals(0, store.receipts.value.getValue("synthetic-a").contextMessageLimit)
        assertTrue(GenerationContextReceiptStore().receipts.value.isEmpty())
    }
}
