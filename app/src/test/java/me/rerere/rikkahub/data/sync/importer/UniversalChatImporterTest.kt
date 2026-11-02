package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.richtext.isDeepSeekHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class UniversalChatImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var afterInsert: (() -> Unit)? = null
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            if (conversation.id in saved) return false
            saved[conversation.id] = conversation; afterInsert?.invoke(); return true
        }
    }
    private fun file(text: String = UniversalChatGuide.example) = temp.newFile().also { it.writeText(text) }
    private fun selections(file: File) = UniversalChatArchive.inspect(file).conversations.associate { it.sourceId to it.defaultLeafId }
    private suspend fun import(file: File, sink: Sink, selected: Map<String, String> = selections(file), target: Uuid = owner,
        fingerprint: String = UniversalChatImporter.archiveFingerprint(file), progress: (DeepSeekImportProgress) -> Unit = {}) =
        UniversalChatImporter(sink).import(file, target, selected, fingerprint, progress)
    private fun chat(id: String = "one", content: String = "原文", role: String = "user") = buildJsonObject {
        put("id", id); put("title", "标题")
        put("messages", buildJsonArray { add(buildJsonObject { put("role", role); put("content", content) }) })
    }
    private fun archive(vararg chats: JsonElement) = buildJsonObject {
        put("format", "orbis_chat"); put("version", 1); put("conversations", JsonArray(chats.toList()))
    }.toString()

    @Test fun guideExampleImportsExactTextReasoningOrderAndKnownOrMissingTimes() = runBlocking {
        val file = file(); val sink = Sink()
        val preview = UniversalChatArchive.inspect(file)
        assertTrue(preview.warnings.any { "1 条消息未提供原时间" in it })
        assertEquals(1, import(file, sink).imported)
        val saved = sink.saved.values.single()
        assertEquals(owner, saved.assistantId)
        val first = saved.messageNodes[0].messages.single()
        val second = saved.messageNodes[1].messages.single()
        assertEquals(MessageRole.USER, first.role); assertEquals(MessageRole.ASSISTANT, second.role)
        assertEquals("今天想聊聊星星。", (first.parts.single() as UIMessagePart.Text).text)
        assertEquals("2026-01-01T12:00:00Z", (first.parts.single() as UIMessagePart.Text).metadata!!["source_created_at"]!!.jsonPrimitive.content)
        assertEquals("先接住这个话题。", (second.parts.first() as UIMessagePart.Reasoning).reasoning)
        assertEquals("好呀，你想从哪颗星说起？", (second.parts.last() as UIMessagePart.Text).text)
        assertTrue((second.parts.last() as UIMessagePart.Text).metadata!!["source_time_missing"]!!.jsonPrimitive.boolean)
        assertEquals(1, import(file, sink).skipped)
        assertTrue(file.parentFile.listFiles()!!.none { it.name.startsWith("orbis-universal-") })
    }

    @Test fun formattingOptionalFieldsAndTimezoneSpellingsDoNotDuplicateContent() = runBlocking {
        val raw = archive(chat())
        val original = file(raw); val sink = Sink(); import(original, sink)
        val pretty = file("\uFEFF" + raw.replace("{", "{\n").replace("\"content\":\"原文\"", "\"timestamp\":null,\"reasoning\":\"\",\"content\":\"原文\""))
        assertEquals(1, import(pretty, sink).skipped)
        val dated = file(UniversalChatGuide.example); import(dated, sink)
        assertEquals(1, import(file(UniversalChatGuide.example.replace("2026-01-01T20:00:00+08:00", "2026-01-01T12:00:00Z")), sink).skipped)
    }

    @Test fun editedContentCreatesAnotherWindowAndOtherAssistantCanImportItsOwnCopy() = runBlocking {
        val sink = Sink(); import(file(archive(chat())), sink)
        val first = sink.saved.values.single()
        assertEquals(1, import(file(archive(chat(content = "新版"))), sink).imported)
        assertEquals(1, import(file(archive(chat())), sink, target = Uuid.random()).imported)
        assertSame(first, sink.saved[first.id]); assertEquals(3, sink.saved.size)
    }

    @Test fun explicitSelectionPreservesArrayOrderIncludingConsecutiveRolesAndEmptyReply() = runBlocking {
        val messages = JsonArray(listOf("first", "", "last").map { buildJsonObject { put("role", "assistant"); put("content", it) } })
        val selected = JsonObject(chat("selected") + ("messages" to messages))
        val sink = Sink(); val file = file(archive(chat("skip"), selected))
        assertEquals(1, import(file, sink, mapOf("selected" to UniversalChatArchive.PATH)).imported)
        assertEquals(listOf("first", "", "last"), sink.saved.values.single().messageNodes.map { (it.messages.single().parts.single() as UIMessagePart.Text).text })
    }

    @Test fun badLateWindowHeaderOrTrailingDataIsRejectedBeforeAnyWrite() = runBlocking {
        val inputs = listOf(archive(chat(), chat("bad", role = "system")), archive(chat()).replace("\"version\":1", "\"version\":2"),
            archive(chat()) + "garbage", archive(chat(), chat()), archive(chat()).replace("\"version\":1", "\"version\":1,\"version\":1"))
        for (text in inputs) {
            val sink = Sink(); val file = file(text)
            try { import(file, sink, mapOf("one" to UniversalChatArchive.PATH)); fail("Invalid archive accepted") }
            catch (_: DeepSeekImportException) { assertTrue(sink.saved.isEmpty()) }
            assertTrue(file.parentFile.listFiles()!!.none { it.name.startsWith("orbis-universal-") })
        }
    }

    @Test fun fieldErrorsIdentifyPositionWithoutEchoingSourceContents() {
        val bad = file(archive(chat(role = "PRIVATE_ROLE_VALUE")))
        val error = assertThrows(UniversalChatFormatException::class.java) { UniversalChatArchive.inspect(bad) }
        val text = ArchiveCapacity.publicError(error)
        assertTrue(text.contains("conversations[0].messages[0].role"))
        assertFalse(text.contains("PRIVATE_ROLE_VALUE"))
        val time = file(archive(chat()).replace("\"content\":\"原文\"", "\"content\":\"原文\",\"timestamp\":\"2026-01-01 12:00\""))
        assertTrue(assertThrows(UniversalChatFormatException::class.java) { UniversalChatArchive.inspect(time) }.message!!.contains("timestamp"))
    }

    @Test fun incorrectTypesAndExtraConfigurationFieldsAreRejected() {
        val valid = archive(chat())
        val bad = listOf(valid.replace("\"content\":\"原文\"", "\"content\":123"),
            valid.replace("\"content\":\"原文\"", "\"content\":\"原文\",\"reasoning\":\"人类备注\""),
            valid.replace("\"title\":\"标题\"", "\"title\":\"标题\",\"api_key\":\"PRIVATE\""),
            valid.replace("\"version\":1", "\"version\":\"1\""))
        bad.forEach { assertThrows(UniversalChatFormatException::class.java) { UniversalChatArchive.inspect(file(it)) } }
    }

    @Test fun changedPreviewBytesRejectImportAndImmutableCopySurvivesSourceMutation() = runBlocking {
        val file = file(archive(chat("one"), chat("two"))); val selected = selections(file)
        val fingerprint = UniversalChatImporter.archiveFingerprint(file)
        file.appendText(" ")
        val sink = Sink()
        try { import(file, sink, selected, fingerprint = fingerprint); fail() }
        catch (_: DeepSeekImportException) { assertTrue(sink.saved.isEmpty()) }
        sink.afterInsert = { file.writeText("changed after immutable copy") }
        assertEquals(2, import(file, sink, selected).imported)
    }

    @Test fun cancellationKeepsCommittedWindowAndRetrySkipsIt() = runBlocking {
        val file = file(archive(chat("one"), chat("two"))); val sink = Sink()
        try { import(file, sink, progress = { if (it.completed == 1) throw CancellationException("stop") }); fail() }
        catch (e: DeepSeekImportCancelledException) { assertEquals(1, e.partialResult.imported) }
        assertEquals(1, sink.saved.size)
        val again = import(file, sink)
        assertEquals(1, again.imported); assertEquals(1, again.skipped)
    }

    @Test fun historicalMediaAndToolLookingTextStayInertAndExact() = runBlocking {
        val text = "![remote](https://invalid.example/image.png)\n<tool_call>{\"command\":\"do not run\"}</tool_call>\n```mermaid\nflowchart LR\n```"
        val sink = Sink(); import(file(archive(chat(content = text, role = "assistant"))), sink)
        val parts = sink.saved.values.single().messageNodes.single().messages.single().parts
        assertEquals(text, (parts.single() as UIMessagePart.Text).text)
        assertTrue(parts.isDeepSeekHistory())
    }

    @Test fun overlargeSerializedMessageStopsWholeArchiveWithoutTruncation() = runBlocking {
        val big = "字".repeat(400000)
        val file = file(archive(chat(), chat("long", big))); val sink = Sink()
        try { import(file, sink, mapOf("one" to UniversalChatArchive.PATH)); fail() }
        catch (e: DeepSeekImportException) {
            assertTrue(e.publicDetail!!.contains("conversations[1].messages[0]")); assertTrue(sink.saved.isEmpty())
        }
        assertTrue(file.readText().contains(big))
    }
}
