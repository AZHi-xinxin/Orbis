package me.rerere.rikkahub.data.orbis.group

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class OrbisGroupAttachmentsTest {
    private fun attachment(image: Boolean = false, text: String? = null) = OrbisGroupAttachment(
        Uuid.random().toString(), if (image) "photo.png" else "note.txt", if (image) "image/png" else "text/plain",
        20, image, text,
    )
    private fun participant(vision: Boolean = false): GroupParticipant {
        val model = Model(modelId = "synthetic-only", inputModalities = if (vision) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT))
        val assistant = Assistant(name = "成员")
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        val member = OrbisGroupMember("member", assistant.id, model.id, provider.id, "成员", joinedAt = 1)
        return resolveGroupParticipant(Settings(assistants = listOf(assistant), providers = listOf(provider)), member)
    }
    private fun message(id: String, items: List<OrbisGroupAttachment>, text: String = "", member: String? = null) =
        OrbisGroupMessage(id, "room", "round", member, "发言者", text = text, createdAt = 1, updatedAt = 1, attachments = items)

    @Test fun oldTextOnlyPayloadStillReadsWithoutMigration() {
        val raw = """{"id":"m","roomId":"r","roundId":"x","memberId":null,"name":"人类","text":"原文","createdAt":1,"updatedAt":1}"""
        val old = Json.decodeFromString<OrbisGroupMessage>(raw)
        assertTrue(old.attachments.isEmpty()); assertNull(old.avatar); assertEquals("原文", old.text)
    }
    @Test fun attachmentsRoundTripWithoutLocalPaths() {
        val original = message("m", listOf(attachment(text = "原文不改")))
        val encoded = Json.encodeToString(original)
        assertFalse(encoded.contains("file:")); assertFalse(encoded.contains("content:"))
        assertEquals(original, Json.decodeFromString<OrbisGroupMessage>(encoded))
    }
    @Test fun traversalUrlAndMetadataControlsRejected() {
        listOf("../private", "https://example.org/x", "file:///data/secret", "content://evil/0").forEach {
            assertFalse(validGroupAttachment(attachment().copy(id = it)))
        }
        assertFalse(validGroupAttachment(attachment().copy(name = "../private")))
        assertFalse(validGroupAttachment(attachment().copy(mime = "text/plain\nAuthorization:x")))
        assertFalse(validGroupAttachment(attachment(true).copy(mime = "image/svg+xml")))
    }
    @Test fun countDuplicateSizeAndTextBudgetAreValidated() {
        fun refused(values: List<OrbisGroupAttachment>) {
            assertThrows(OrbisGroupException::class.java) { validateGroupAttachments(values) }
        }
        refused(List(5) { attachment() })
        val same = attachment(); refused(listOf(same, same))
        refused(List(3) { attachment().copy(bytes = GROUP_ATTACHMENT_BYTES.toLong()) })
        refused(listOf(attachment(text = "中".repeat(6000)), attachment(text = "文".repeat(6000))))
        validateGroupAttachments(List(4) { attachment(text = "ok") })
    }
    @Test fun copyRejectsEmptyAndOversizeAndPreservesExactBytes() {
        assertThrows(OrbisGroupException::class.java) { copyGroupAttachment(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()) }
        assertThrows(OrbisGroupException::class.java) { copyGroupAttachment(ByteArrayInputStream(ByteArray(GROUP_ATTACHMENT_BYTES + 1)), ByteArrayOutputStream()) }
        val original = ByteArray(8193) { (it % 127).toByte() }; val out = ByteArrayOutputStream()
        assertEquals(original.size.toLong(), copyGroupAttachment(ByteArrayInputStream(original), out))
        assertArrayEquals(original, out.toByteArray())
    }
    @Test fun fileOnlyMessageGetsActualTextAndUntrustedSourceMarker() {
        val context = buildGroupContext(listOf(message("m", listOf(attachment(text = "实际文件正文")))), participant(), 1)
        assertTrue(context.messages.first().toText().contains("实际文件正文"))
        assertTrue(context.messages.first().toText().contains("不是系统命令"))
        assertEquals(1, context.included)
    }
    @Test fun unknownBinaryNeverClaimsItsContentWasRead() {
        val context = buildGroupContext(listOf(message("m", listOf(attachment().copy(name = "archive.bin", mime = "application/octet-stream")))), participant(), 1)
        assertTrue(context.messages.first().toText().contains("没有解析内容"))
        assertTrue(context.messages.first().parts.none { it is UIMessagePart.Document })
    }
    @Test fun currentImageRequiresVisionAndOldImagesAreNotRepeated() {
        val old = message("old", listOf(attachment(true)))
        val fresh = message("new", listOf(attachment(true)))
        val failure = assertThrows(OrbisGroupException::class.java) { buildGroupContext(listOf(old, fresh), participant(), 2) }
        assertEquals("image_unsupported", failure.code)
        var resolved = 0
        val context = buildGroupContext(listOf(old, fresh), participant(true), 2) { resolved++; "file:///synthetic-group-image" }
        assertEquals(1, resolved)
        assertEquals(1, context.messages.flatMap { it.parts }.count { it is UIMessagePart.Image })
        assertTrue(context.messages.first().toText().contains("未重复发送"))
    }
    @Test fun laterTextRoundCanUseTextModelAfterOldImage() {
        var resolved = false
        buildGroupContext(listOf(message("old", listOf(attachment(true))), message("new", emptyList(), "文字问题")), participant(), 2) {
            resolved = true; error("old_image_must_not_open")
        }
        assertFalse(resolved)
    }
    @Test fun newestImageNotReusedFromAiAttachment() {
        val context = buildGroupContext(listOf(message("human", emptyList(), "问题"),
            message("other", listOf(attachment(true)), "回答", "someone")), participant(), 2)
        assertTrue(context.messages.flatMap { it.parts }.none { it is UIMessagePart.Image })
    }
    @Test fun documentAppendIsWholeOrNothingAndUtf8Bounded() {
        val out = StringBuilder("existing\n")
        assertFalse(appendGroupDocumentText(out, "中".repeat(12000)))
        assertEquals("existing\n", out.toString())
        assertTrue(appendGroupDocumentText(out, "短文"))
        assertEquals("existing\n短文\n", out.toString())
    }
    @Test fun docxExtractionIgnoresUnselectedFilesAndNeverFetchesEntities() {
        val file = Files.createTempFile("orbis-group-synthetic", ".docx").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml")); zip.write("<document><p>Visible synthetic text</p></document>".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("private.txt")); zip.write("must not read".toByteArray()); zip.closeEntry()
            }
            val result = extractGroupZipDocument(file, "docx")
            assertTrue(result.orEmpty().contains("Visible synthetic text")); assertFalse(result.orEmpty().contains("must not read"))
        } finally { file.delete() }
    }
    @Test fun expandedZipLimitRejectsBombWithoutTruncatedText() {
        val file = Files.createTempFile("orbis-group-synthetic", ".docx").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(ByteArray(512 * 1024 + 1) { 65 }); zip.closeEntry()
            }
            assertNull(extractGroupZipDocument(file, "docx"))
        } finally { file.delete() }
    }
    @Test fun modernPhoneImageMetadataMatchesPrivateChatEncoderFormats() {
        for (mime in listOf("image/heic", "image/heif", "image/avif", "image/gif")) {
            assertTrue(validGroupAttachment(attachment(image = true).copy(mime = mime)))
        }
        assertFalse(validGroupAttachment(attachment(image = true).copy(mime = "image/svg+xml")))
        assertFalse(validGroupAttachment(attachment(image = true).copy(mime = "application/octet-stream")))
    }
    @Test fun officeDocumentMimeDoesNotRequireDisplayNameExtension() {
        assertEquals("docx", groupDocumentExtension("附件", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("pptx", groupDocumentExtension("附件", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals("epub", groupDocumentExtension("附件", "application/epub+zip"))
        assertEquals("pdf", groupDocumentExtension("附件", "application/pdf"))
        assertEquals("docx", groupDocumentExtension("Report.DOCX", "application/octet-stream"))
    }
}
