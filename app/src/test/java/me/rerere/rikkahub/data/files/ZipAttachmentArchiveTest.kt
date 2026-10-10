package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipAttachmentArchiveTest {
    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { if (stored) {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            } }); zip.write(bytes); zip.closeEntry()
        } } }.toByteArray()
    private fun withZip(bytes: ByteArray, action: (File) -> Unit) {
        val file = Files.createTempFile("orbis-synthetic-zip-", ".zip").toFile()
        try { file.writeBytes(bytes); action(file) } finally { check(file.delete()) }
    }
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("must reject $code") } catch (error: ZipAttachmentException) { assertEquals(code, error.code) }
    }
    private fun central(bytes: ByteArray): Int = (0..bytes.size - 46).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
    }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) { repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() } }

    @Test fun mimeAndExtensionRecognitionExcludeDocumentContainers() {
        assertTrue(ZipAttachmentArchive.isZip("中文.ZIP", "application/octet-stream"))
        assertTrue(ZipAttachmentArchive.isZip("attachment", "application/x-zip-compressed"))
        assertTrue(ZipAttachmentArchive.isZip("a.zip", "text/plain"))
        for (name in listOf("a.docx", "book.epub", "sheet.xlsx", "a.pptx")) assertFalse(ZipAttachmentArchive.isZip(name, "application/zip"))
        assertFalse(ZipAttachmentArchive.isZip("note.txt", "text/plain"))
    }
    @Test fun signatureGuardRecognizesMislabelledAndEmptyZipWithoutReadingItsText() {
        withZip(zip("a.txt" to "hello".toByteArray())) { assertTrue(ZipAttachmentArchive.hasZipSignature(it)) }
        withZip(zip()) { assertTrue(ZipAttachmentArchive.hasZipSignature(it)) }
        withZip("ordinary text".toByteArray()) { assertFalse(ZipAttachmentArchive.hasZipSignature(it)) }
        withZip(byteArrayOf(0x50, 0x4b)) { assertFalse(ZipAttachmentArchive.hasZipSignature(it)) }
    }
    @Test fun utf8ChineseDirectoryAndPaginatedTextAreReadWithoutExtraction() {
        val bytes = zip("中文/" to byteArrayOf(), "中文/记录.txt" to "第一行\n第二行😀".toByteArray(), "picture.png" to byteArrayOf(0, 1))
        withZip(bytes) { file ->
            val page = ZipAttachmentArchive.directory(file, 0, 1)
            assertEquals(3, page.totalEntries); assertEquals(1, page.nextOffset)
            assertEquals("中文/", page.entries.single().path)
            val rest = ZipAttachmentArchive.directory(file, 1, 2)
            assertNull(rest.nextOffset); assertFalse(rest.entries.last().textSupported)
            val text = ZipAttachmentArchive.readText(file, "中文/记录.txt", 0, 4)
            assertEquals("第一行\n", text.text); assertEquals(4, text.nextOffset)
            assertEquals("第二行😀", ZipAttachmentArchive.readText(file, "中文/记录.txt", 4).text)
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun sourceCodeIsOnlyTextAndNestedZipAndBinaryAreNeverExpanded() {
        withZip(zip("run.sh" to "echo NOT_EXECUTED".toByteArray(), "inner.zip" to zip("a.txt" to byteArrayOf(1)))) { file ->
            assertEquals("echo NOT_EXECUTED", ZipAttachmentArchive.readText(file, "run.sh").text)
            rejected("zip_entry_not_utf8_text_type") { ZipAttachmentArchive.readText(file, "inner.zip") }
        }
    }
    @Test fun unsafePathsAndDuplicateNamesAreRejectedBeforeReading() {
        for (name in listOf("../x.txt", "/tmp/x.txt", "a/../x.txt", "a\\x.txt", "C:x.txt", "./x.txt", "a//x.txt", "x. ", "x\n.txt")) {
            withZip(zip(name to "safe".toByteArray())) { file -> rejected("zip_unsafe_entry_path") { ZipAttachmentArchive.inspect(file) } }
        }
        withZip(zip("a.txt" to byteArrayOf(1), "A.txt" to byteArrayOf(2))) { file ->
            rejected("zip_duplicate_entry") { ZipAttachmentArchive.inspect(file) }
        }
    }
    @Test fun linksAndEncryptedEntriesAreRejected() {
        val link = zip("link.txt" to "../private".toByteArray())
        put32(link, central(link) + 38, 0xa1ff0000L)
        withZip(link) { file -> rejected("zip_link_or_special_entry") { ZipAttachmentArchive.inspect(file) } }
        val encrypted = zip("secret.txt" to "secret".toByteArray())
        encrypted[central(encrypted) + 8] = (encrypted[central(encrypted) + 8].toInt() or 1).toByte()
        withZip(encrypted) { file -> rejected("zip_encrypted_not_supported") { ZipAttachmentArchive.inspect(file) } }
    }
    @Test fun inconsistentLocalNameAndTruncatedPackageAreRejected() {
        val bytes = zip("one.txt" to "hello".toByteArray())
        val corrupt = bytes.copyOf().also { it[30] = 'x'.code.toByte() }
        withZip(corrupt) { file -> rejected("zip_inconsistent_local_header") { ZipAttachmentArchive.inspect(file) } }
        withZip(bytes.copyOf(bytes.size - 3)) { file -> rejected("zip_invalid_end_record") { ZipAttachmentArchive.inspect(file) } }
    }
    @Test fun crcIsCheckedEvenWhenZipFileWouldReturnCorruptStoredText() {
        val bytes = zip("a.txt" to "HELLO".toByteArray(), stored = true)
        bytes[35] = 'X'.code.toByte()
        withZip(bytes) { file -> rejected("zip_crc_or_size_mismatch") { ZipAttachmentArchive.readText(file, "a.txt") } }
    }
    @Test fun boundedDeclaredExpansionAndHighCompressionBombAreRejected() {
        val bytes = zip("a.txt" to "small".toByteArray())
        put32(bytes, central(bytes) + 24, ZipAttachmentArchive.MAX_ENTRY_BYTES + 1)
        withZip(bytes) { file -> rejected("zip_expansion_limit") { ZipAttachmentArchive.inspect(file) } }
        withZip(zip("bomb.txt" to ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })) { file ->
            rejected("zip_compression_ratio_limit") { ZipAttachmentArchive.inspect(file) }
        }
    }
    @Test fun entryCountBoundIsEnforced() {
        val entries = (0..ZipAttachmentArchive.MAX_ENTRIES).map { "$it.txt" to byteArrayOf() }.toTypedArray()
        withZip(zip(*entries)) { file -> rejected("zip_too_many_entries") { ZipAttachmentArchive.inspect(file) } }
    }
    @Test fun utf8DecoderRejectsBinaryOrLegacyTextInsteadOfReplacingBytes() {
        withZip(zip("binary.txt" to byteArrayOf(1, 0), "legacy.txt" to byteArrayOf(0xff.toByte()))) { file ->
            rejected("zip_entry_binary_not_supported") { ZipAttachmentArchive.readText(file, "binary.txt") }
            rejected("zip_text_encoding_unsupported") { ZipAttachmentArchive.readText(file, "legacy.txt") }
        }
    }
    @Test fun invalidUtf8NamesAreExplicitlyRejected() {
        val bytes = zip("a.txt" to "safe".toByteArray())
        bytes[30] = 0xff.toByte(); bytes[central(bytes) + 46] = 0xff.toByte()
        withZip(bytes) { file -> rejected("zip_filename_encoding_unsupported") { ZipAttachmentArchive.inspect(file) } }
    }
    @Test fun pagesEnforceOutputLimitsAndDoNotSplitSurrogatePairs() {
        withZip(zip("a.txt" to "a😀b".toByteArray())) { file ->
            assertEquals("a", ZipAttachmentArchive.readText(file, "a.txt", 0, 2).text)
            assertEquals("😀", ZipAttachmentArchive.readText(file, "a.txt", 1, 2).text)
            rejected("zip_invalid_page") { ZipAttachmentArchive.readText(file, "a.txt", 2, 2) }
            rejected("zip_invalid_page") { ZipAttachmentArchive.readText(file, "a.txt", 0, 8001) }
            rejected("zip_invalid_page") { ZipAttachmentArchive.directory(file, 0, 41) }
        }
    }
    @Test fun uploadCopyReadsAtMostOneByteBeyondLimitAndDoesNotCommitExtraBytes() {
        val input = object : java.io.InputStream() {
            var count = 0L
            override fun read(): Int { count++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { count += length; return length }
        }
        val output = object : java.io.OutputStream() { var count = 0L
            override fun write(value: Int) { count++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
        }
        rejected("zip_archive_too_large") { ZipAttachmentArchive.copyBounded(input, output) }
        assertEquals(ZipAttachmentArchive.MAX_ARCHIVE_BYTES + 1, input.count)
        assertEquals(ZipAttachmentArchive.MAX_ARCHIVE_BYTES, output.count)
    }
    @Test fun emptyArchiveIsValidAndCopyPreservesBytes() {
        val bytes = zip()
        val copied = ByteArrayOutputStream()
        ZipAttachmentArchive.copyBounded(ByteArrayInputStream(bytes), copied)
        assertArrayEquals(bytes, copied.toByteArray())
        withZip(bytes) { assertTrue(ZipAttachmentArchive.inspect(it).isEmpty()) }
    }
}
