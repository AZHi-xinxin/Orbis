package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationConflictPreservationTest {
    private class Store : GenerationCheckpointStore {
        var bytes: ByteArray? = null
        var archived: ByteArray? = null
        var failArchive = false
        var failDelete = false
        override fun read(conversationId: Uuid) = bytes?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun preserveConflict(conversationId: Uuid, bytes: ByteArray) {
            check(!failArchive); archived = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) { check(!failDelete); bytes = null }
    }
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("old prefix").toMessageNode(), UIMessage.user("tail").toMessageNode()))
    private fun Conversation.changed() = copy(messageNodes = listOf(UIMessage.user("edited history").toMessageNode()) + messageNodes.drop(1))

    @Test fun `incompatible valid prefix is preserved exactly then permits a fresh journal without changing history`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val old = base()
        j.begin(old); val bytes = store.bytes!!.copyOf(); val current = old.changed()
        var held = false
        assertTrue(j.preserveIncompatible(current, { true }, { held = true }))
        assertTrue(held); assertNull(store.bytes); assertArrayEquals(bytes, store.archived)
        j.begin(current); assertFalse(j.recover(current)!!.changed)
        assertArrayEquals(bytes, store.archived)
    }

    @Test fun `recoverable content is not archived or discarded`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val old = base()
        val handle = j.begin(old)
        val tail = old.messageNodes.takeLast(1) + UIMessage.assistant("recover this reply").toMessageNode()
        j.checkpoint(handle, old.assistantId, old.compactionEpoch, tail)
        val bytes = store.bytes!!.copyOf()
        assertFalse(j.preserveIncompatible(old, { true }, { error("must not hold") }))
        assertArrayEquals(bytes, store.bytes); assertNull(store.archived)
        assertEquals(tail, j.recover(old)!!.conversation.messageNodes.drop(1))
    }

    @Test fun `malformed foreign active and failed storage or hold retain the active bytes`() {
        for (failure in listOf("malformed", "foreign", "active", "archive", "delete", "hold")) {
            val store = Store(); val j = GenerationCheckpointJournal(store); val old = base(); j.begin(old)
            var current = old.changed()
            if (failure == "malformed") store.bytes = "private invalid record".toByteArray()
            if (failure == "foreign") current = current.copy(assistantId = Uuid.random())
            store.failArchive = failure == "archive"; store.failDelete = failure == "delete"
            val bytes = store.bytes!!.copyOf()
            val error = runCatching { j.preserveIncompatible(current, { failure != "active" }, {
                if (failure == "hold") error("hold unavailable")
            }) }.exceptionOrNull()
            assertNotNull(failure, error); assertArrayEquals(failure, bytes, store.bytes)
            assertFalse(error!!.message.orEmpty().contains("private invalid record"))
        }
    }

    @Test fun `changed record or producer after archival cannot retire a successor`() {
        for (replace in listOf(true, false)) {
            val store = Store(); val j = GenerationCheckpointJournal(store); val old = base(); j.begin(old)
            val bytes = store.bytes!!.copyOf(); var quiet = true
            val next = "different record".toByteArray()
            assertTrue(runCatching { j.preserveIncompatible(old.changed(), { quiet }, {
                if (replace) store.bytes = next else quiet = false
            }) }.isFailure)
            assertArrayEquals(bytes, store.archived); assertArrayEquals(if (replace) next else bytes, store.bytes)
        }
    }

    @Test fun `unknown external tool remains in archive and requires durable hold before slot is freed`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val old = base(); val handle = j.begin(old)
        val tool = UIMessagePart.Tool(toolCallId = "call", toolName = "external", input = "{}")
        j.checkpoint(handle, old.assistantId, old.compactionEpoch,
            old.messageNodes.takeLast(1) + UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode(),
            GenerationToolTransition("call", "external", GenerationToolStatus.STARTED))
        val bytes = store.bytes!!.copyOf(); var held = false
        assertTrue(j.preserveIncompatible(old.changed(), { true }, { held = true }))
        assertTrue(held); assertArrayEquals(bytes, store.archived); assertNull(store.bytes)
    }
}
