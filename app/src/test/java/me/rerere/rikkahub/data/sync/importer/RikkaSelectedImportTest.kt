package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Synthetic cursor-like sources and an in-memory sink; no Android DB, real accounts or APIs. */
class RikkaSelectedImportTest {
    private val owner = Uuid.random()
    private val fingerprint = "a".repeat(64)

    private class Source(val chats: List<RikkaChatSnapshotReader.Chat>,
        val rows: Map<String, List<RikkaChatSnapshotReader.Node>>) : RikkaChatSource {
        var visited = 0
        override fun conversations() = chats
        override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) {
            rows.getValue(conversationId).forEach { visited++; visit(it) }
        }
        override fun close() = Unit
    }

    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var insertCalls = 0
        var failInsert = 0
        var raceDuplicate = false
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            insertCalls++
            if (insertCalls == failInsert) error("synthetic storage failure")
            if (raceDuplicate || conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }

    private fun chat(id: String = Uuid.random().toString()) =
        RikkaChatSnapshotReader.Chat(id, "synthetic window", 1_000, 2_000)

    private fun row(vararg messages: UIMessage, selected: Int = 0) =
        RikkaChatSnapshotReader.Node(Uuid.random().toString(), JsonInstant.encodeToString(messages.toList()), selected)

    private fun source(count: Int = 2): Source {
        val chats = (0 until count).map { chat() }
        return Source(chats, chats.associate { it.id to listOf(row(UIMessage.user("synthetic ${it.id}"))) })
    }

    private suspend fun run(source: Source, sink: Sink, selected: Set<String> = source.chats.map { it.id }.toSet(),
        rollback: MutableList<String> = mutableListOf(), prepared: MutableList<String> = mutableListOf(),
        progress: (DeepSeekImportProgress) -> Unit = {}): DeepSeekImportResult {
        val preview = inspectRikkaSource(source, fingerprint)
        return importSelectedRikkaChats(source, preview, selected, sink, prepare = { chat ->
            prepared += chat.id
            PreparedRikkaChat(convertRikkaChat(source, chat, owner, { part, _ -> part }), 1,
                rollbackAttachments = { rollback += chat.id })
        }, onProgress = progress)
    }

    @Test fun `preview has a single all branches choice and exact original branch counts`() = runBlocking {
        val chat = chat()
        val source = Source(listOf(chat), mapOf(chat.id to listOf(
            row(UIMessage.assistant("selected"), UIMessage.assistant("alternative"), selected = 1),
            row(UIMessage.user("next")),
        )))
        val preview = inspectRikkaSource(source, fingerprint)
        assertEquals(fingerprint, preview.fingerprint)
        val window = preview.conversations.single()
        assertEquals(chat.id, window.sourceId)
        assertEquals(2, window.totalNodes)
        assertEquals(3, window.messageCount)
        assertEquals(1, window.branchPointCount)
        assertEquals("all", window.defaultLeafId)
        assertEquals("all_branches_preserved", window.defaultSelectionReason)
        assertEquals(listOf(DeepSeekBranchPreview("all", 3, window.updatedAt, true)), window.branches)
    }

    @Test fun `selected conversion retains every branch and does not import system configuration`() = runBlocking {
        val first = chat(); val ignored = chat()
        val alternate = row(UIMessage.assistant("one"), UIMessage.assistant("two"), selected = 1)
        val source = Source(listOf(first, ignored), mapOf(first.id to listOf(alternate,
            row(UIMessage.system("secret original system setting"))), ignored.id to listOf(row(UIMessage.user("not selected")))))
        val sink = Sink()
        val result = run(source, sink, setOf(first.id))
        assertEquals(1, result.imported)
        assertEquals(3, result.messages)
        assertEquals(1, sink.insertCalls)
        val imported = sink.saved.values.single()
        assertEquals(owner, imported.assistantId)
        assertEquals(rikkaImportId("conversation", first.id), imported.id)
        assertEquals(1, imported.messageNodes.first().selectIndex)
        assertEquals(2, imported.messageNodes.first().messages.size)
        assertNull(imported.customSystemPrompt)
        assertNull(imported.workspaceCwd)
        assertTrue(imported.lorebookIds.isEmpty())
        assertTrue(imported.currentMessages.none { it.role == MessageRole.SYSTEM })
        assertFalse(imported.currentMessages.last().parts.toString().contains("secret original"))
    }

    @Test fun `repeat selection skips existing identity without converting attachments again`() = runBlocking {
        val source = source(1); val sink = Sink(); val prepared = mutableListOf<String>()
        assertEquals(1, run(source, sink, prepared = prepared).imported)
        val stored = sink.saved.values.single()
        val preview = inspectRikkaSource(source, fingerprint)
        val repeated = importSelectedRikkaChats(source, preview, setOf(source.chats.single().id), sink,
            prepare = { error("Existing window must not recreate attachments or change its owner") })
        assertEquals(1, repeated.skipped)
        assertEquals(0, repeated.imported)
        assertSame(stored, sink.saved.values.single())
        assertEquals(1, prepared.size)
    }

    @Test fun `foreign and malformed selection ids are rejected before any preparation or write`() = runBlocking {
        val source = source(1)
        for (selected in listOf(setOf(Uuid.random().toString()), setOf("../window"), emptySet())) {
            val sink = Sink(); val prepared = mutableListOf<String>()
            val error = runCatching { run(source, sink, selected, prepared = prepared) }.exceptionOrNull()
            assertTrue(error is DeepSeekImportException)
            assertEquals(0, (error as DeepSeekImportException).partialResult.imported)
            assertTrue(prepared.isEmpty())
            assertTrue(sink.saved.isEmpty())
        }
    }

    @Test fun `later commit failure retains earlier window and rolls back only current attachments`() = runBlocking {
        val source = source(3); val sink = Sink().apply { failInsert = 2 }
        val rollback = mutableListOf<String>(); val prepared = mutableListOf<String>()
        val error = runCatching { run(source, sink, rollback = rollback, prepared = prepared) }.exceptionOrNull()
        assertTrue(error is DeepSeekImportException)
        val partial = (error as DeepSeekImportException).partialResult
        assertEquals(1, partial.imported)
        assertEquals(1, partial.failed)
        assertEquals(source.chats[1].id, partial.failures.single().sourceId)
        assertEquals(1, sink.saved.size)
        assertEquals(source.chats.take(2).map { it.id }, prepared)
        assertEquals(listOf(source.chats[1].id), rollback)
        assertFalse(partial.failures.single().reason.contains("synthetic storage failure"))
    }

    @Test fun `cancel receipt includes first committed window and retry skips it`() = runBlocking {
        val source = source(3); val sink = Sink(); val rollback = mutableListOf<String>()
        val error = runCatching { run(source, sink, rollback = rollback) { progress ->
            if (progress.completed == 1) throw CancellationException("synthetic human cancelled")
        } }.exceptionOrNull()
        assertTrue(error is DeepSeekImportCancelledException)
        assertEquals(1, (error as DeepSeekImportCancelledException).partialResult.imported)
        assertEquals(1, sink.saved.size)
        assertTrue(rollback.isEmpty())
        val resumed = run(source, sink)
        assertEquals(2, resumed.imported)
        assertEquals(1, resumed.skipped)
        assertEquals(3, sink.saved.size)
    }

    @Test fun `race duplicate commit rolls back unused uploads and reports skip`() = runBlocking {
        val source = source(1); val sink = Sink().apply { raceDuplicate = true }
        val rollback = mutableListOf<String>()
        val result = run(source, sink, rollback = rollback)
        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(0, result.attachmentReferences)
        assertEquals(listOf(source.chats.single().id), rollback)
    }

    @Test fun `source ids remain exact and case distinct`() = runBlocking {
        val lower = chat("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa")
        val upper = chat("AAAAAAAA-1111-4111-8111-AAAAAAAAAAAA")
        val source = Source(listOf(lower, upper), mapOf(lower.id to listOf(row(UIMessage.user("lower"))),
            upper.id to listOf(row(UIMessage.user("upper")))))
        val sink = Sink()
        assertEquals(1, run(source, sink, setOf(upper.id)).imported)
        assertTrue(rikkaImportId("conversation", upper.id) in sink.saved)
        assertFalse(rikkaImportId("conversation", lower.id) in sink.saved)
    }

    @Test fun `invalid branch indices and duplicate identities fail preflight without any commits`() = runBlocking {
        val chat = chat(); val message = UIMessage.user("synthetic")
        val invalid = listOf(row(message, selected = 1), row(message, message),
            row(message).copy(messages = "malformed JSON"), row(message).copy(id = "not-a-uuid"))
        for (row in invalid) {
            val source = Source(listOf(chat), mapOf(chat.id to listOf(row)))
            assertTrue(runCatching { inspectRikkaSource(source, fingerprint) }.exceptionOrNull() is IllegalArgumentException)
        }
        val repeated = Source(listOf(chat), mapOf(chat.id to listOf(row(message), row(message))))
        assertTrue(runCatching { inspectRikkaSource(repeated, fingerprint) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `preview visits large window one node at a time and responds to cancellation`() = runBlocking {
        val chat = chat()
        var visited = 0
        val source = object : RikkaChatSource {
            override fun conversations() = listOf(chat)
            override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) {
                repeat(5000) { index -> visited++; visit(row(UIMessage.user("synthetic $index"))) }
            }
            override fun close() = Unit
        }
        val preview = inspectRikkaSource(source, fingerprint)
        assertEquals(5000, visited)
        assertEquals(5000, preview.conversations.single().messageCount)
        visited = 0
        val error = runCatching { inspectRikkaSource(source, fingerprint) {
            if (visited >= 25) throw CancellationException("synthetic cancelled preview")
        } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(25, visited)
    }

    @Test fun `single node and window limits reject oversized text without truncating`() = runBlocking {
        val chat = chat()
        val oversized = row().copy(messages = "x".repeat(RikkaChatLimits.MAX_NODE_BYTES + 1))
        assertTrue(runCatching { decodeRikkaNode(oversized) }.exceptionOrNull() is IllegalArgumentException)
        var visited = 0
        val longText = "x".repeat(900_000)
        val source = object : RikkaChatSource {
            override fun conversations() = listOf(chat)
            override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) {
                repeat(50) { visited++; visit(row(UIMessage.user(longText))) }
            }
            override fun close() = Unit
        }
        assertTrue(runCatching { inspectRikkaSource(source, fingerprint) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(visited in 35..40)
    }

    @Test fun `fingerprint changes with archive bytes and hashing is cancellable`() {
        val path = Files.createTempFile("orbis-synthetic-rikka-fingerprint-", ".zip")
        try {
            Files.write(path, byteArrayOf(1, 2, 3))
            val first = rikkaArchiveFingerprint(path.toFile())
            assertEquals(first, rikkaArchiveFingerprint(path.toFile()))
            Files.write(path, byteArrayOf(1, 2, 4))
            assertNotEquals(first, rikkaArchiveFingerprint(path.toFile()))
            assertTrue(runCatching { rikkaArchiveFingerprint(path.toFile()) { throw CancellationException("cancel") } }
                .exceptionOrNull() is CancellationException)
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun `archive copy cancellation stops before additional chunks`() {
        val output = ByteArrayOutputStream()
        var checks = 0
        val error = runCatching {
            RikkaChatArchive.copyLimited(ByteArrayInputStream(ByteArray(200_000)), output, 300_000,
                checkCancelled = { if (++checks == 3) throw CancellationException("cancel") })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(2 * 65536, output.size())
    }

    @Test fun `low storage guard reserves 64 MiB before every write without filling a real disk`() {
        val reserve = RikkaChatArchive.MIN_FREE_BYTES
        RikkaChatArchive.requireExtractionSpace(reserve + 65536, 65536)
        assertTrue(runCatching { RikkaChatArchive.requireExtractionSpace(reserve + 65535, 65536) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RikkaChatArchive.requireExtractionSpace(0, 1) }
            .exceptionOrNull() is IllegalArgumentException)
        val output = ByteArrayOutputStream()
        var simulatedFree = reserve + 65536
        val error = runCatching {
            RikkaChatArchive.copyLimited(ByteArrayInputStream(ByteArray(100_000)), output, 200_000,
                beforeWrite = { bytes ->
                    RikkaChatArchive.requireExtractionSpace(simulatedFree, bytes)
                    simulatedFree -= bytes
                })
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(65536, output.size())
        assertEquals(reserve, simulatedFree)
    }
}
