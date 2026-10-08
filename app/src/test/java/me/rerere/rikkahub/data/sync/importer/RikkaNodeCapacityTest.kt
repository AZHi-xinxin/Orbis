package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic rows only; no SQLite, phone, user archive, or attachment writes. */
class RikkaNodeCapacityTest {
    private val chat = RikkaChatSnapshotReader.Chat(Uuid.random().toString(), "synthetic", 1, 2)
    private val owner = Uuid.random()

    private fun source(messages: List<UIMessage>): RikkaChatSource {
        val row = RikkaChatSnapshotReader.Node(Uuid.random().toString(), JsonInstant.encodeToString(messages), 0)
        return object : RikkaChatSource {
            override fun conversations() = listOf(chat)
            override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) {
                assertEquals(chat.id, conversationId)
                visit(row)
            }
            override fun close() = Unit
        }
    }

    private suspend fun convert(source: RikkaChatSource) =
        convertRikkaChat(source, chat, owner, { part, _ -> part })

    private fun exactAsciiMessage(bytes: Int): UIMessage {
        val empty = UIMessage.assistant("")
        val projected = decodeRikkaNode(RikkaChatSnapshotReader.Node(Uuid.random().toString(),
            JsonInstant.encodeToString(listOf(empty)), 0))
        val overhead = MessageNodeBudget.measureNode(projected)
        return empty.copy(parts = listOf(UIMessagePart.Text("x".repeat(bytes - overhead))))
    }

    @Test fun `exact target byte boundary is accepted without truncation`() = runBlocking {
        val message = exactAsciiMessage(MessageNodeBudget.MAX_NODE_BYTES)
        val imported = convert(source(listOf(message))).messageNodes.single().messages
        assertEquals(MessageNodeBudget.MAX_NODE_BYTES, MessageNodeBudget.measureNode(imported))
        assertEquals(message.toText(), imported.single().toText())
        assertEquals(RikkaChatContentDecoder.marker(), imported.single().parts.single().metadata)
    }

    @Test fun `source below one MiB but one byte over target is classified as node capacity`() = runBlocking {
        val original = listOf(exactAsciiMessage(MessageNodeBudget.MAX_NODE_BYTES + 1))
        val source = source(original)
        // Reading this bounded foreign row remains allowed; conversion must not publish it.
        assertEquals(1, inspectRikkaSource(source, "synthetic").conversations.size)
        val failure = runCatching { convert(source) }.exceptionOrNull()
        assertTrue(failure is ArchiveReadException)
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(failure)))
        assertFalse(ArchiveCapacity.publicError(failure).contains("格式"))
        source.visitNodes(chat.id) { assertEquals(original, JsonInstant.decodeFromString<List<UIMessage>>(it.messages)) }
    }

    @Test fun `all alternatives count together even when each branch is small`() = runBlocking {
        val alternatives = listOf(exactAsciiMessage(410_000), exactAsciiMessage(410_000))
        alternatives.forEach { assertTrue(MessageNodeBudget.measureNode(listOf(it)) < MessageNodeBudget.MAX_NODE_BYTES) }
        val failure = runCatching { convert(source(alternatives)) }.exceptionOrNull()
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(failure)))
    }

    @Test fun `reasoning Unicode and JSON escaping count with the body`() = runBlocking {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("文".repeat(110_000)),
            UIMessagePart.Reasoning("\u0001".repeat(80_000)),
        ))
        // Only 190k UTF-16 units, but 810k JSON bytes before the serialized envelope.
        val failure = runCatching { convert(source(listOf(message))) }.exceptionOrNull()
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(failure)))
    }

    @Test fun `check occurs after part mapping and includes added tool output`() = runBlocking {
        val smallSource = source(listOf(UIMessage.assistant("source")))
        val failure = runCatching {
            convertRikkaChat(smallSource, chat, owner, { _, _ ->
                UIMessagePart.Tool(toolCallId = "synthetic", toolName = "historical", input = "{}",
                    output = listOf(UIMessagePart.Text("x".repeat(MessageNodeBudget.MAX_NODE_BYTES))))
            })
        }.exceptionOrNull()
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(failure)))
    }

    @Test fun `capacity failure never calls the selected import sink or counts a partial window`() = runBlocking {
        val source = source(listOf(exactAsciiMessage(MessageNodeBudget.MAX_NODE_BYTES + 1)))
        val preview = inspectRikkaSource(source, "synthetic")
        var inserts = 0
        val failure = runCatching {
            importSelectedRikkaChats(source, preview, setOf(chat.id), object : DeepSeekImportSink {
                override suspend fun exists(id: Uuid) = false
                override suspend fun insert(conversation: Conversation): Boolean { inserts++; return true }
            }, prepare = {
                PreparedRikkaChat(convert(source), 0, {})
            })
        }.exceptionOrNull()
        assertTrue(failure is DeepSeekImportException)
        failure as DeepSeekImportException
        assertEquals(ArchiveFailure.NODE_LIMIT, failure.failureReason)
        assertEquals(0, inserts)
        assertEquals(0, failure.partialResult.imported)
        assertEquals(1, failure.partialResult.failed)
    }
}
