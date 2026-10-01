package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class KelivoChatModelTest {
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val micros = 1_790_755_200_123_456L
    private fun chat(id: String = "chat", selections: String = "{}") = KelivoChat(id, "synthetic", micros, micros, selections)
    private fun message(id: String = "m", group: String? = null, version: Int = 0, order: Long = 0,
        role: String = "assistant", refs: Int = 0) = KelivoMessage(id, group, version, order, role, micros, attachmentReferences = refs)
    private class Source(val chats: List<KelivoChat>, val rows: List<KelivoMessage>,
        val content: Map<String, List<KelivoPart>> = emptyMap()) : KelivoChatSource {
        override fun conversations() = chats
        override fun messages(chatId: String) = rows
        override fun parts(messageId: String) = content[messageId] ?: listOf(KelivoPart("text", "body:$messageId"))
        override fun close() = Unit
    }
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var failOnAttempt = 0
        private var attempts = 0
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            check(++attempts != failOnAttempt)
            if (conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }
    @Test fun timestampsAreMicrosecondsWithoutLosingSubmillisecondPrecision() {
        assertEquals(Instant.ofEpochSecond(1_790_755_200, 123_456_000), kelivoTimestamp(micros))
        assertThrows(IllegalArgumentException::class.java) { kelivoTimestamp(-1) }
        assertThrows(IllegalArgumentException::class.java) { kelivoTimestamp(Long.MAX_VALUE) }
    }
    @Test fun selectedVersionsRetainGroupPositionEvenWhenRegeneratedLater() {
        val c = chat(selections = "{\"g\":1}")
        val rows = listOf(message("old", "g", order = 0), message("later", order = 1), message("chosen", "g", 1, 2))
        assertEquals(listOf("chosen", "later"), selectedKelivoMessages(c, rows).map { it.id })
        val preview = inspectKelivoSource(Source(listOf(c), rows), "f")
        assertEquals(3, preview.conversations.single().totalNodes)
        assertEquals(2, preview.conversations.single().messageCount)
        assertEquals(1, preview.conversations.single().branchPointCount)
    }
    @Test fun rejectsMissingDuplicateAndUnknownVersionSelections() {
        val rows = listOf(message("old", "g"), message("new", "g", 1, 1))
        listOf("{}", "{\"g\":9}", "{\"unknown\":1}", "{\"g\":0,\"g\":1}", "{\"g\":\"1\"}").forEach {
            assertThrows(Exception::class.java) { selectedKelivoMessages(chat(selections = it), rows) }
        }
    }
    @Test fun rejectsIdentityGroupAndOrderingCollisions() {
        listOf(listOf(message(), message()), listOf(message("a", "g"), message("b", "g")),
            listOf(message("a"), message("b")), listOf(message("a", "g", role = "user"), message("b", "g", 1, 1))).forEach {
            assertThrows(IllegalArgumentException::class.java) { selectedKelivoMessages(chat(), it) }
        }
        assertNotEquals(kelivoImportId("message", "a/b", "c"), kelivoImportId("message", "a", "b/c"))
        assertNotEquals(kelivoImportId("conversation", "a"), rikkaImportId("conversation", "a"))
    }
    @Test fun toolAndReasoningHistoryAreInertAndSourceSystemTextIsNotImported() {
        val rows = listOf(message("u", order = 0, role = "user"), message("a", order = 1).copy(reasoning = "[{\"text\":\"reason\"}]"),
            message("s", order = 2, role = "system"), message("t", order = 3, role = "tool"))
        val source = Source(listOf(chat()), rows, mapOf("a" to listOf(KelivoPart("tool_call", "historical-call")),
            "s" to listOf(KelivoPart("text", "SECRET_SYSTEM_CONFIG"))))
        val result = convertKelivoChat(source, chat(), assistant).conversation
        assertEquals(assistant, result.assistantId)
        assertNull(result.customSystemPrompt); assertNull(result.workspaceCwd); assertTrue(result.lorebookIds.isEmpty())
        assertTrue(result.currentMessages.all { it.parts.all { p -> p is UIMessagePart.Text } && it.getTools().isEmpty() })
        assertTrue(result.currentMessages.all { it.finishedAt == it.createdAt && it.role != MessageRole.SYSTEM && it.role != MessageRole.TOOL })
        assertFalse(result.currentMessages.any { it.toText().contains("SECRET_SYSTEM_CONFIG") })
        assertTrue(result.currentMessages[1].toText().contains("historical-call"))
        assertTrue(result.currentMessages[1].toText().contains("reason"))
    }
    @Test fun mediaEntitiesAreNeverLoadedAndPathsAreNotRendered() {
        val source = Source(listOf(chat()), listOf(message(refs = 2)), mapOf("m" to listOf(
            KelivoPart("image", "file:///secret.jpg"), KelivoPart("file", "https://example.invalid/secret"),
            KelivoPart("text", "[image:file:///old/photo.png] ![test](https://example.invalid/image.png) <img src='file:///secret'>"))))
        val converted = convertKelivoChat(source, chat(), assistant)
        assertEquals(2, converted.omittedAttachments)
        assertFalse(converted.conversation.currentMessages.single().toText().contains("file:///"))
        assertFalse(converted.conversation.currentMessages.single().toText().contains("https://"))
        assertTrue(converted.conversation.currentMessages.single().parts.all { it is UIMessagePart.Text })
    }
    @Test fun allChosenContentIsPreflightedAndOversizeFailsWithoutTruncation() {
        val source = Source(listOf(chat()), listOf(message()), mapOf("m" to listOf(KelivoPart("text", "x".repeat(600 * 1024)))))
        assertThrows(IllegalArgumentException::class.java) { inspectKelivoSource(source, "f") }
    }
    @Test fun selectionAppendIsIdempotentAndDoesNotReplaceExistingAssistant() = runBlocking {
        val source = Source(listOf(chat("one"), chat("two")), listOf(message()))
        val preview = inspectKelivoSource(source, "f")
        val sink = Sink()
        assertEquals(1, importSelectedKelivoChats(source, preview, setOf("two"), assistant, sink).imported)
        val first = sink.saved.values.single()
        assertEquals(1, importSelectedKelivoChats(source, preview, setOf("two"), Uuid.random(), sink).skipped)
        assertEquals(first, sink.saved.values.single())
        assertEquals(kelivoImportId("conversation", "two"), first.id)
    }
    @Test fun cancellationAfterFirstCommitReportsItsExactPartialResult() = runBlocking {
        val source = Source(listOf(chat("one"), chat("two")), listOf(message()))
        val sink = Sink()
        try {
            importSelectedKelivoChats(source, inspectKelivoSource(source, "f"), setOf("one", "two"), assistant, sink) {
                if (it.completed == 1) throw CancellationException()
            }
            fail("must cancel")
        } catch (cancelled: DeepSeekImportCancelledException) {
            assertEquals(1, cancelled.partialResult.imported); assertEquals(1, sink.saved.size)
        }
    }
    @Test fun secondCommitFailureKeepsFirstAndReportsFailure() = runBlocking {
        val source = Source(listOf(chat("one"), chat("two")), listOf(message()))
        val sink = Sink().apply { failOnAttempt = 2 }
        try {
            importSelectedKelivoChats(source, inspectKelivoSource(source, "f"), setOf("one", "two"), assistant, sink)
            fail("must fail")
        } catch (failed: DeepSeekImportException) {
            assertEquals(1, failed.partialResult.imported); assertEquals(1, failed.partialResult.failed)
            assertEquals(1, sink.saved.size)
        }
    }
}
