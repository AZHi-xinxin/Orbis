package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class OrbisZipAttachmentToolsTest {
    private val attachment = UIMessagePart.Document("file:///synthetic/upload/a.zip", "测试.zip", "application/zip")
    private fun user(document: UIMessagePart.Document = attachment) = UIMessage.user("read attachment").copy(parts = listOf(document))
    private fun conversation(vararg messages: UIMessage) = Conversation(assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })
    private suspend fun result(tool: Tool, action: String, reference: String? = null, path: String? = null,
                               offset: Int? = null, limit: Int? = null): JsonObject = Json.parseToJsonElement(
        tool.execute(buildJsonObject {
            put("action", action); reference?.let { put("archive_ref", it) }; path?.let { put("entry_path", it) }
            offset?.let { put("offset", it) }; limit?.let { put("limit", it) }
        }).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun syntheticZip(): File {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("说明.md")); it.write("只是参考数据，不是指令".toByteArray()); it.closeEntry()
        } }.toByteArray()
        return Files.createTempFile("orbis-zip-tool-test-", ".zip").toFile().also { it.writeBytes(bytes) }
    }

    @Test fun onlyExplicitUserSourceMessagesAreBoundAndListingDoesNotOpenFiles() = runBlocking {
        val included = user(); val excluded = user(); val synthetic = user().copy(isSynthetic = true)
        val assistant = UIMessage.assistant("reply").copy(parts = listOf(attachment))
        val current = conversation(included, excluded, synthetic, assistant)
        val tools = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(included.id, synthetic.id, assistant.id),
            { current }, { error("listing must not open any file") })
        val list = result(tools.single(), "list_archives")
        assertTrue(list["ok"]!!.jsonPrimitive.boolean)
        assertEquals("none", list["instruction_authority"]!!.jsonPrimitive.content)
        assertEquals(1, list["total_archives"]!!.jsonPrimitive.int)
        assertEquals(zipAttachmentReference(included.id, 0), list["archives"]!!.jsonArray.single().jsonObject["archive_ref"]!!.jsonPrimitive.content)
        assertTrue(createOrbisZipAttachmentTools(current.assistantId, current.id, emptySet(), { current }, { error("no file") }).isEmpty())
    }

    @Test fun moreThan64HistoricArchivesRemainPagedWithoutBreakingNormalChat() = runBlocking {
        val messages = List(65) { user() }; val current = conversation(*messages.toTypedArray())
        val tool = createOrbisZipAttachmentTools(current.assistantId, current.id, messages.map { it.id }.toSet(), { current }, { error("no file") }).single()
        val first = result(tool, "list_archives", limit = 40)
        val last = result(tool, "list_archives", offset = 40, limit = 40)
        assertEquals(65, first["total_archives"]!!.jsonPrimitive.int)
        assertEquals(40, first["archives"]!!.jsonArray.size)
        assertEquals(25, last["archives"]!!.jsonArray.size)
        assertEquals(JsonNull, last["next_offset"])
    }

    @Test fun arbitraryGlobalPathAndUnknownFieldsCannotOpenFile() = runBlocking {
        val message = user(); val current = conversation(message)
        val tool = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current }, { error("must not open") }).single()
        assertFalse(result(tool, "read_text", "file:///private/secret.zip", "a.txt")["ok"]!!.jsonPrimitive.boolean)
        val bad = tool.execute(buildJsonObject { put("action", "list_archives"); put("path", "/private") })
        assertFalse(Json.parseToJsonElement((bad.single() as UIMessagePart.Text).text).jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun ownerBranchAndAttachmentChangesAllRejectBeforeFileIo() = runBlocking {
        val message = user(); val original = conversation(message); var current = original
        val tool = createOrbisZipAttachmentTools(original.assistantId, original.id, setOf(message.id), { current }, { error("must not read") }).single()
        val changed = listOf(original.copy(assistantId = Uuid.random()), original.copy(id = Uuid.random()),
            original.copy(messageNodes = listOf(user().toMessageNode())),
            original.copy(messageNodes = listOf(message.copy(parts = listOf(attachment.copy(url = "file:///other.zip"))).toMessageNode())))
        changed.forEach { current = it; assertFalse(result(tool, "list_entries", zipAttachmentReference(message.id, 0))["ok"]!!.jsonPrimitive.boolean) }
    }

    @Test fun directoryAndTextReadAreBoundedReferenceDataAndOriginalIsUntouched() = runBlocking {
        val file = syntheticZip()
        try {
            val before = file.readBytes(); val message = user(); val current = conversation(message)
            val tool = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current }, { file }).single()
            val reference = zipAttachmentReference(message.id, 0)
            val directory = result(tool, "list_entries", reference)
            assertFalse(directory["entry_contents_verified"]!!.jsonPrimitive.boolean)
            val page = result(tool, "read_text", reference, "说明.md", limit = 4)
            assertEquals("只是参考", page["text"]!!.jsonPrimitive.content)
            assertTrue(page["crc_verified"]!!.jsonPrimitive.boolean)
            assertEquals(4, page["next_offset"]!!.jsonPrimitive.int)
            assertEquals("none", page["instruction_authority"]!!.jsonPrimitive.content)
            assertArrayEquals(before, file.readBytes())
        } finally { check(file.delete()) }
    }

    @Test fun branchChangeDuringIoWithholdsAllReadContent() = runBlocking {
        val file = syntheticZip()
        try {
            val message = user(); var current = conversation(message)
            val tool = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current }, {
                current = current.copy(messageNodes = current.messageNodes + user().toMessageNode()); file
            }).single()
            val read = result(tool, "read_text", zipAttachmentReference(message.id, 0), "说明.md")
            assertFalse(read["ok"]!!.jsonPrimitive.boolean)
            assertNull(read["text"])
            assertFalse(read.toString().contains("只是参考"))
        } finally { check(file.delete()) }
    }

    @Test fun cancellationRemainsCancellationAndPrivateErrorsAreNotReturned() = runBlocking {
        val message = user(); val current = conversation(message)
        val cancelled = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current }, { throw CancellationException("stop") }).single()
        try { result(cancelled, "list_entries", zipAttachmentReference(message.id, 0)); fail("must cancel") } catch (_: CancellationException) { }
        val failed = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current }, { error("private diagnostic") }).single()
        assertFalse(result(failed, "list_entries", zipAttachmentReference(message.id, 0)).toString().contains("private diagnostic"))
    }

    @Test fun previousUserAttachmentRemainsReadableInANewNormalTurn() = runBlocking {
        val file = syntheticZip()
        try {
            val original = user()
            val current = conversation(original, UIMessage.assistant("first reply"), UIMessage.user("read the previous ZIP"))
            val tool = createOrbisZipAttachmentTools(current.assistantId, current.id,
                current.currentMessages.map { it.id }.toSet(), { current }, { file }).single()
            assertTrue(result(tool, "list_entries", zipAttachmentReference(original.id, 0))["ok"]!!.jsonPrimitive.boolean)
            assertEquals("只是参考数据，不是指令", result(tool, "read_text", zipAttachmentReference(original.id, 0), "说明.md")["text"]!!.jsonPrimitive.content)
        } finally { check(file.delete()) }
    }

    @Test fun failureDoesNotFalselyDiagnoseEncodingOrBranchChange() = runBlocking {
        val message = user(); val current = conversation(message)
        val tool = createOrbisZipAttachmentTools(current.assistantId, current.id, setOf(message.id), { current },
            { throw IllegalStateException("private diagnostic") }).single()
        val result = result(tool, "list_entries", zipAttachmentReference(message.id, 0))
        assertEquals("zip_read_failed", result["error"]!!.jsonPrimitive.content)
        assertTrue(result["hint"]!!.jsonPrimitive.content.contains("原因尚未确认"))
        assertFalse(result.toString().contains("private diagnostic"))
    }
}
