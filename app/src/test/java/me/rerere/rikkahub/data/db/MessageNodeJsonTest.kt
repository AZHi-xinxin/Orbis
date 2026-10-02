package me.rerere.rikkahub.data.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.GenerationToolStatus
import me.rerere.rikkahub.data.ai.checkpoint.GenerationToolTransition
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

/** Synthetic data only. Does not open a database, model, MCP session, or application. */
@OptIn(ExperimentalSerializationApi::class)
class MessageNodeJsonTest {
    private fun tool(output: String) = UIMessagePart.Tool(
        toolCallId = "synthetic-call", toolName = "synthetic-pulse", input = "{}",
        output = listOf(UIMessagePart.Text(output)),
    )

    @Test fun `valid Unicode remains identical to old persisted JSON including buffer boundaries`() {
        val text = "中文🙂🐢\"\\\n\u0000e\u0301\u2028".repeat(1600)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text(text), UIMessagePart.Reasoning(text),
            tool(text).copy(metadata = JsonObject(mapOf("emoji🙂" to JsonPrimitive(text)))),
        ))
        val messages = listOf(message, message.copy(id = Uuid.random(), translation = text))
        val old = JsonInstant.encodeToString(messages)
        val safe = encodeMessageNodeMessages(messages)
        assertEquals(old, safe)
        assertArrayEquals(old.toByteArray(Charsets.UTF_8), safe.toByteArray(Charsets.UTF_8))
        assertEquals(messages, JsonInstant.decodeFromString<List<UIMessage>>(safe))
    }

    @Test fun `lone high surrogate at text end cannot reach the native SQLite binder`() {
        val messages = listOf(UIMessage.assistant("before\uD83D"))
        val unsafe = JsonInstant.encodeToString(messages)
        assertTrue(unsafe.contains("\uD83D\"")) // The old encoder leaves it next to the JSON quote.
        val safe = encodeMessageNodeMessages(messages)
        assertFalse(safe.any { it.isHighSurrogate() || it.isLowSurrogate() })
        val restored = JsonInstant.decodeFromString<List<UIMessage>>(safe)
        assertEquals("before?", (restored.single().parts.single() as UIMessagePart.Text).text)
        assertEquals(messages.single().id, restored.single().id)
    }

    @Test fun `invalid Unicode matches journal encoder without disturbing other fields or branches`() {
        for (text in listOf("left\uD83D", "\uDC22right", "a\uD83Db", "\uD83D\uD83D", "🙂\uDC22中")) {
            val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                tool(text).copy(
                    input = text,
                    metadata = JsonObject(mapOf(text to JsonPrimitive(text))),
                ),
            )), UIMessage.user("unchanged alternative 🙂"))
            val stream = ByteArrayOutputStream()
            Json { encodeDefaults = true }.encodeToStream(messages, stream)
            val safe = encodeMessageNodeMessages(messages)
            assertEquals(stream.toByteArray().toString(Charsets.UTF_8), safe)
            val restored = JsonInstant.decodeFromString<List<UIMessage>>(safe)
            assertEquals(messages.map { it.id }, restored.map { it.id })
            assertEquals(messages.last(), restored.last())
            assertEquals(safe, encodeMessageNodeMessages(restored))
        }
    }

    @Test fun `completed journal and safe database representation recover without mismatch or replay`() {
        val store = MemoryStore()
        val journal = GenerationCheckpointJournal(store)
        val base = Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("synthetic request").toMessageNode()))
        val handle = journal.begin(base)
        val pending = tool("").copy(output = emptyList())
        val node = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)).toMessageNode()
        journal.checkpoint(handle, base.assistantId, 0, base.messageNodes + node,
            GenerationToolTransition(pending.toolCallId, pending.toolName, GenerationToolStatus.STARTED))
        val completed = node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(tool("kept\uD83D")))))
        journal.checkpoint(handle, base.assistantId, 0, base.messageNodes + completed,
            GenerationToolTransition(pending.toolCallId, pending.toolName, GenerationToolStatus.COMPLETED))
        val reloaded = completed.copy(messages = JsonInstant.decodeFromString(encodeMessageNodeMessages(completed.messages)))
        val persisted = base.copy(messageNodes = base.messageNodes + reloaded)
        val recovered = checkNotNull(journal.recover(persisted))
        assertFalse(recovered.changed)
        assertTrue(recovered.unknownToolIds.isEmpty())
        assertEquals(persisted, recovered.conversation)
        assertTrue(journal.hasCheckpoint(base.id)) // Checking/encoding must not clear recovery evidence.
    }

    @Test fun `oversize tool preview source itself is not mutated by safe storage encoding`() {
        val original = "文".repeat(4095) + "🙂" + "tail".repeat(9000)
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(original))))
        val safe = encodeMessageNodeMessages(messages)
        assertEquals(messages, JsonInstant.decodeFromString<List<UIMessage>>(safe))
        assertEquals(original, ((messages.single().parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text)
    }

    private class MemoryStore : GenerationCheckpointStore {
        private val records = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = records[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { records[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { records.remove(conversationId) }
    }
}
