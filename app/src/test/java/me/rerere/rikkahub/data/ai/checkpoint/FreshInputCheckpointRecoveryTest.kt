package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Real journal, synthetic private storage/Room snapshots. No provider, device or tool calls. */
class FreshInputCheckpointRecoveryTest {
    private class Store : GenerationCheckpointStore {
        var bytes: ByteArray? = null
        override fun read(conversationId: Uuid) = bytes?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes = null }
    }
    private val json = Json { encodeDefaults = true }
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("history"), UIMessage.user("question")).map { it.toMessageNode() })
    private fun changedPrefix(c: Conversation) = c.copy(messageNodes = listOf(c.messageNodes.first().let { n ->
        n.copy(messages = listOf(n.currentMessage.copy(translation = "human translation")))
    }) + c.messageNodes.drop(1))
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun nodeHash(nodes: List<MessageNode>): String = digest(nodes.flatMap {
        json.encodeToString(it).toByteArray().toList() + 0.toByte()
    }.toByteArray())
    /** Manufacture an exact old V1 record, including its old raw protected-history digests. */
    private fun legacy(store: Store, original: Conversation) {
        val envelope = json.parseToJsonElement(checkNotNull(store.bytes).toString(Charsets.UTF_8)).jsonObject
        val record = envelope.getValue("record").jsonObject.toMutableMap()
        record["version"] = JsonPrimitive(1)
        record["prefixHash"] = JsonPrimitive(nodeHash(original.messageNodes.dropLast(1)))
        record["suffixHash"] = JsonPrimitive(nodeHash(emptyList()))
        record["baseTailHash"] = JsonPrimitive(nodeHash(original.messageNodes.takeLast(1)))
        val value = JsonObject(record)
        store.bytes = JsonObject(mapOf("record" to value,
            "sha256" to JsonPrimitive(digest(value.toString().toByteArray())))).toString().toByteArray()
    }
    private fun reject(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is GenerationCheckpointException)
    }

    @Test fun `explicit continuation acknowledges complete legacy tail without rewriting edited history`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val original = base(); val h = j.begin(original)
        val reply = UIMessage.assistant("already saved reply").toMessageNode()
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + reply)
        legacy(store, original)
        val stored = changedPrefix(original.copy(messageNodes = original.messageNodes + reply))
        reject { j.recover(stored) }
        val recovered = checkNotNull(j.recover(stored, allowAlreadyCommittedTail = true))
        assertFalse(recovered.changed)
        assertSame(stored, recovered.conversation)
        j.clearAfterDurableCommit(h, stored, allowAlreadyCommittedTail = true)
        assertFalse(j.hasCheckpoint(original.id))
        // The next ordinary request can start in the SAME conversation, not a replacement chat.
        assertEquals(original.id, j.begin(stored).conversationId)
    }

    @Test fun `explicit recovery never merges an unsaved tail across a history edit`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val original = base(); val h = j.begin(original)
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) +
            UIMessage.assistant("not saved yet").toMessageNode())
        val before = store.bytes!!.copyOf()
        val stored = changedPrefix(original)
        reject { j.recover(stored, allowAlreadyCommittedTail = true) }
        reject { j.clearAfterDurableCommit(h, stored, allowAlreadyCommittedTail = true) }
        assertArrayEquals(before, store.bytes)
    }

    @Test fun `explicit acknowledgement rechecks tail and refuses new owner epoch altered receipt or duplicate node`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val original = base(); val h = j.begin(original)
        val reply = UIMessage.assistant("saved").toMessageNode()
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + reply)
        val stored = changedPrefix(original.copy(messageNodes = original.messageNodes + reply))
        assertNotNull(j.recover(stored, true))
        val before = store.bytes!!.copyOf()
        listOf(stored.copy(assistantId = Uuid.random()), stored.copy(compactionEpoch = 1),
            stored.copy(messageNodes = stored.messageNodes.dropLast(1) + reply.copy(messages = listOf(UIMessage.assistant("changed")))),
            stored.copy(messageNodes = listOf(reply) + stored.messageNodes.drop(1))).forEach { changed ->
            reject { j.clearAfterDurableCommit(h, changed, true) }
            assertArrayEquals(before, store.bytes)
        }
    }

    @Test fun `old unknown tool only acknowledges its exact persisted interrupted receipt and retains unknown IDs`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val original = base(); val h = j.begin(original)
        val tool = UIMessagePart.Tool("old-call", "synthetic_write", "{}")
        val reply = UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + reply,
            GenerationToolTransition(tool.toolCallId, tool.toolName, GenerationToolStatus.STARTED))
        val unknown = changedPrefix(original.copy(messageNodes = original.messageNodes + reply))
        reject { j.recover(unknown, true) }
        reject { j.clearAfterDurableCommit(h, unknown, true) }
        val interrupted = changedPrefix(checkNotNull(j.recover(original)).conversation)
        val recovery = checkNotNull(j.recover(interrupted, true))
        assertEquals(setOf("old-call"), recovery.unknownToolIds)
        assertFalse(recovery.changed)
        // Production persists an independent unknown-effect hold before this exact clear.
        j.clearAfterDurableCommit(h, interrupted, true)
    }

    @Test fun `read and fold edits in the mutable tail survive restoring an unfinished reply`() {
        val original = base().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) +
            c.messageNodes.last().let { n -> n.copy(messages = listOf(n.currentMessage.copy(
                orbisEvent = OrbisEventMetadata("receipt", "source", "event", 1L)))) }) }
        val store = Store(); val j = GenerationCheckpointJournal(store); val h = j.begin(original)
        val reply = UIMessage.assistant("partial saved reply").toMessageNode()
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + reply)
        val last = original.messageNodes.last(); val msg = last.currentMessage
        val stored = original.applyEventPresentation(original.assistantId,
            OrbisEventPresentationEdit(last.id, msg.id, msg.orbisEvent!!, msg.toText(), true, false))
        val recovered = checkNotNull(j.recover(stored))
        assertTrue(recovered.conversation.currentMessages[1].orbisEvent!!.read)
        assertFalse(recovered.conversation.currentMessages[1].orbisEvent!!.collapsed)
        assertEquals(reply, recovered.conversation.messageNodes.last())
        j.clearAfterDurableCommit(h, recovered.conversation)
    }

    @Test fun `v2 event identity payload and timestamps still participate in the protected digest`() {
        val seed = base(); val node = seed.messageNodes.first()
        val message = node.currentMessage.copy(orbisEvent = OrbisEventMetadata("r", "s", "e", 1L))
        val original = seed.copy(messageNodes = listOf(node.copy(messages = listOf(message))) + seed.messageNodes.drop(1))
        val store = Store(); val j = GenerationCheckpointJournal(store); j.begin(original)
        listOf(message.copy(orbisEvent = message.orbisEvent!!.copy(source = "different")),
            message.copy(orbisEvent = message.orbisEvent!!.copy(receivedAt = 2L)),
            message.copy(parts = listOf(UIMessagePart.Text("different")))).forEach { changed ->
            reject { j.recover(original.copy(messageNodes = listOf(node.copy(messages = listOf(changed))) + original.messageNodes.drop(1))) }
        }
        assertTrue(j.hasCheckpoint(original.id))
    }

    @Test fun `corruption is not bypassed by explicit recovery`() {
        val store = Store(); val j = GenerationCheckpointJournal(store); val original = base()
        store.bytes = "broken".toByteArray()
        reject { j.recover(original, true) }
        assertEquals("broken", store.bytes!!.toString(Charsets.UTF_8))
    }
}
