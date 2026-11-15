package me.rerere.rikkahub.service

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.files.localizeInlineImageParts
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HumanContinuationImagesTest {
    private class Store : GenerationCheckpointStore {
        var bytes: ByteArray? = null
        override fun read(conversationId: Uuid) = bytes?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes = null }
    }

    @Test fun `recover image receipt without discarding later conversation or replaying tools`() = runTest {
        val persisted = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("old").toMessageNode()))
        val image = UIMessagePart.Image("data:image/jpeg;base64," + "A".repeat(900_000))
        val receipt = UIMessagePart.Tool("finished-call", "photo", "{}", output = listOf(UIMessagePart.Text("receipt"), image))
        val snapshot = persisted.copy(messageNodes = persisted.messageNodes +
            UIMessage.assistant("").copy(parts = listOf(receipt)).toMessageNode() +
            List(224) { UIMessage.user("later $it").toMessageNode() })
        val store = Store(); val journal = HumanContinuationJournal(store); val run = Uuid.random()
        journal.checkpoint(run, persisted, snapshot)
        val recovered = checkNotNull(journal.recover(persisted))
        val normalized = localizeUnstoredContinuationImages(recovered, persisted) { parts ->
            localizeInlineImageParts(parts) { "file:///private/upload/verified.jpg" }
        }
        assertEquals(snapshot.messageNodes.map { it.id }, normalized.snapshot.messageNodes.map { it.id })
        assertEquals(snapshot.messageNodes.drop(2), normalized.snapshot.messageNodes.drop(2))
        val tool = normalized.snapshot.messageNodes[1].currentMessage.parts.single() as UIMessagePart.Tool
        assertEquals(receipt.copy(output = listOf(receipt.output[0], image.copy(url = "file:///private/upload/verified.jpg"))), tool)
        journal.checkpoint(run, normalized.baseline, normalized.snapshot)
        assertEquals(normalized.snapshot, checkNotNull(HumanContinuationJournal(store).recover(persisted)).snapshot)
        assertTrue(journal.clearCommitted(run, normalized.snapshot))
        assertNull(store.bytes)
    }

    @Test fun `already committed nodes and alternative replies remain unchanged`() = runTest {
        val old = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image("data:image/png;base64,old"))).toMessageNode()
        val persisted = Conversation(assistantId = Uuid.random(), messageNodes = listOf(old))
        val new = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image("data:image/png;base64,new"))).toMessageNode()
            .let { it.copy(messages = it.messages + UIMessage.assistant("alternative")) }
        val recovered = HumanContinuationJournal.Recovery(Uuid.random(), persisted,
            persisted.copy(messageNodes = persisted.messageNodes + new), persisted)
        val normalized = localizeUnstoredContinuationImages(recovered, persisted) { parts ->
            localizeInlineImageParts(parts) { assertFalse(it.endsWith("old")); "file:///new.png" }
        }
        assertEquals(old, normalized.snapshot.messageNodes[0])
        assertEquals(new.messages[1], normalized.snapshot.messageNodes[1].messages[1])
        assertEquals(new.selectIndex, normalized.snapshot.messageNodes[1].selectIndex)
    }

    @Test fun `file persistence failure leaves recovery bytes intact`() = runTest {
        val persisted = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("old").toMessageNode()))
        val next = persisted.copy(messageNodes = persisted.messageNodes + UIMessage.assistant("").copy(
            parts = listOf(UIMessagePart.Image("data:image/png;base64,A"))).toMessageNode())
        val store = Store(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(Uuid.random(), persisted, next)
        val original = store.bytes!!.copyOf()
        assertTrue(runCatching {
            localizeUnstoredContinuationImages(journal.recover(persisted)!!, persisted) { error("disk full") }
        }.isFailure)
        assertArrayEquals(original, store.bytes)
        assertEquals(next, journal.recover(persisted)!!.snapshot)
    }

    @Test fun `new alternative in an existing node is localized without rewriting its saved reply`() = runTest {
        val old = UIMessage.assistant("saved reply").copy(parts = listOf(
            UIMessagePart.Image("data:image/png;base64,already-saved"))).toMessageNode()
        val persisted = Conversation(assistantId = Uuid.random(), messageNodes = listOf(old))
        val alternative = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("new-call", "photo", "{}", output = listOf(
                UIMessagePart.Image("data:image/png;base64,new")))))
        val snapshot = persisted.copy(messageNodes = listOf(old.copy(
            messages = old.messages + alternative, selectIndex = 1)))
        val store = Store(); val journal = HumanContinuationJournal(store); val run = Uuid.random()
        journal.checkpoint(run, persisted, snapshot)
        val normalized = localizeUnstoredContinuationImages(journal.recover(persisted)!!, persisted) { parts ->
            localizeInlineImageParts(parts) { assertTrue(it.endsWith(",new")); "file:///private/new.png" }
        }
        assertEquals(old.messages[0], normalized.snapshot.messageNodes[0].messages[0])
        assertEquals(1, normalized.snapshot.messageNodes[0].selectIndex)
        val tool = normalized.snapshot.currentMessages[0].parts[0] as UIMessagePart.Tool
        assertEquals("file:///private/new.png", (tool.output[0] as UIMessagePart.Image).url)
        journal.checkpoint(run, normalized.baseline, normalized.snapshot)
        assertEquals(normalized.snapshot, journal.recover(persisted)!!.snapshot)
    }

    @Test fun `same live message can acquire an image after its earlier version was committed`() = runTest {
        val old = UIMessage.assistant("earlier text").toMessageNode()
        val persisted = Conversation(assistantId = Uuid.random(), messageNodes = listOf(old))
        val next = old.copy(messages = listOf(old.currentMessage.copy(parts = old.currentMessage.parts +
            UIMessagePart.Tool("call", "photo", "{}", output = listOf(UIMessagePart.Image("data:image/png;base64,new"))))))
        val recovery = HumanContinuationJournal.Recovery(Uuid.random(), persisted,
            persisted.copy(messageNodes = listOf(next)), persisted)
        val normalized = localizeUnstoredContinuationImages(recovery, persisted) { parts ->
            localizeInlineImageParts(parts) { "file:///private/new.png" }
        }
        assertEquals(old.id, normalized.snapshot.messageNodes[0].id)
        assertEquals(old.currentMessage.id, normalized.snapshot.currentMessages[0].id)
        assertEquals(old.currentMessage.parts[0], normalized.snapshot.currentMessages[0].parts[0])
        assertEquals("file:///private/new.png", ((normalized.snapshot.currentMessages[0].parts[1]
            as UIMessagePart.Tool).output[0] as UIMessagePart.Image).url)
    }
}
