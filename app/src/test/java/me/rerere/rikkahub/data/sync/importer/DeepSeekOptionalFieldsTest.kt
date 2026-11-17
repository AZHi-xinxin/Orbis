package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekOptionalFieldsTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private fun session() = Json.parseToJsonElement("""{"id":"c","title":"synthetic","pinned":true,"current_message_id":2,"messages":[
      {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"question"},{"type":"FILE","files":[{"file_name":"paper.pdf","export_path":"chat_sessions/c_files/paper.pdf"}]}]},
      {"id":2,"parent_id":1,"role":"ASSISTANT","inserted_at":"not-a-date","fragments":[{"type":"THINK","content":"thought","elapsed_secs":2.5},{"type":"TOOL_SEARCH","status":"FINISHED","queries":["synthetic"],"results":[{"url":"https://example.invalid/result","title":"Result"}]},{"type":"RESPONSE","content":"answer"}]}
    ]}""").jsonObject
    private fun zip(raw: JsonObject = session(), index: String? = null, extra: Map<String,String> = emptyMap()): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            val entries = linkedMapOf("export/manifest.json" to "{\"export_format_version\":3}",
                "export/chat_sessions/c.json" to raw.toString(), "export/chat_sessions/c_files/paper.pdf" to "%PDF-synthetic")
            if (index != null) entries["export/chat_session_index.json"] = index
            entries.putAll(extra)
            entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
        }
    }
    @Test fun absentIndexAndOptionalMetadataRecoverWithVisibleWarningsAndInertHistory() {
        val file = zip(); val bytes = file.readBytes()
        val preview = DeepSeekArchive.inspect(file)
        assertEquals(2, preview.conversations.single().messageCount)
        assertTrue(preview.conversations.single().branches.single().timeUnknown)
        assertTrue(preview.warnings.any { it.contains("索引") })
        assertTrue(preview.warnings.any { it.contains("时间") })
        DeepSeekArchive.open(file) {}.use { archive ->
            val source = archive.conversations().single()
            val converted = DeepSeekChatImporter.convert(source, "2", owner).conversation
            assertTrue(converted.isPinned)
            assertEquals(converted.currentMessages, me.rerere.rikkahub.data.ai.transformers.applyTimeReminder(converted.currentMessages))
            assertEquals("question", (converted.currentMessages.first().parts.first() as UIMessagePart.Text).text)
            assertEquals("answer", (converted.currentMessages.last().parts.last() as UIMessagePart.Text).text)
            assertTrue(converted.currentMessages.all { it.deepSeekTimeUnknown() && it.getTools().isEmpty() })
            val reply = converted.currentMessages.last()
            val reasoning = reply.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
            assertEquals(2500L, (reasoning.finishedAt!! - reasoning.createdAt).inWholeMilliseconds)
            assertEquals("not-a-date", reasoning.metadata!!["source_timestamp_raw"]!!.jsonPrimitive.content)
            val history = reply.parts.filterIsInstance<UIMessagePart.Text>().firstNotNullOf { it.deepSeekHistoryTool() }
            assertEquals("历史记录 · TOOL_SEARCH", history.toolName)
            assertEquals(1, reply.annotations.size)
            val stream = java.io.ByteArrayOutputStream()
            assertTrue(archive.copyAttachment("chat_sessions/c_files/paper.pdf", stream))
            assertEquals("%PDF-synthetic", stream.toString("UTF-8"))
        }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("application/pdf", deepSeekMime("paper.PDF"))
    }
    @Test fun existingBrokenIndexAndConflictingRootsNeverUseFallback() {
        assertThrows(Exception::class.java) { DeepSeekArchive.inspect(zip(index = "[{\"id\":\"c\",\"message_count\":99}]")) }
        assertThrows(Exception::class.java) { DeepSeekArchive.inspect(zip(extra = mapOf(
            "other/manifest.json" to "{\"export_format_version\":3}", "other/chat_sessions/c.json" to session().toString()))) }
        val wrongId = JsonObject(session() + ("id" to JsonPrimitive("other")))
        assertThrows(Exception::class.java) { DeepSeekArchive.inspect(zip(raw = wrongId)) }
    }
    @Test fun declaredWrongAttachmentSizeIsStillRejected() {
        val raw = Json.parseToJsonElement(session().toString().replace("\"file_name\":\"paper.pdf\"", "\"file_size\":999,\"file_name\":\"paper.pdf\"")).jsonObject
        assertThrows(Exception::class.java) { DeepSeekArchive.inspect(zip(raw)) }
    }
    @Test fun veryLongReasoningDoesNotBecomeOversizedUnsplitMetadata() {
        val raw = Json.parseToJsonElement(session().toString().replace("\"thought\"", "\"" + "x".repeat(1100000) + "\"")).jsonObject
        DeepSeekArchive.open(zip(raw)) {}.use { archive ->
            val result = DeepSeekChatImporter.convert(archive.conversations().single(), "2", owner).conversation
            assertEquals(1100000, result.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().sumOf { it.reasoning.length })
            assertTrue(result.currentMessages.all { it.getTools().isEmpty() })
        }
    }
}
