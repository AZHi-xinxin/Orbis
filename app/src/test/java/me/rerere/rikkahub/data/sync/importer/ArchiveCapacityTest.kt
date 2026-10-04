package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveCapacityTest {
    @Test fun streamBudgetsDoNotUseIntOrTheOld64MiBCap() {
        assertEquals(1024L * 1024 * 1024, DeepSeekArchive.MAX_JSON_BYTES)
        assertEquals(DeepSeekArchive.MAX_JSON_BYTES, OperitChatArchive.MAX_ARCHIVE_BYTES)
        assertEquals(8L * 1024 * 1024 * 1024, RikkaChatArchive.MAX_ARCHIVE_BYTES)
        ArchiveCapacity.requireSize(80_289_258, DeepSeekArchive.MAX_JSON_BYTES)
        ArchiveCapacity.requireSize(Int.MAX_VALUE.toLong() + 1, RikkaChatArchive.MAX_ARCHIVE_BYTES)
        assertEquals(ArchiveFailure.SIZE_LIMIT, failure { ArchiveCapacity.requireSize(ArchiveCapacity.MAX_ZIP_BYTES + 1, ArchiveCapacity.MAX_ZIP_BYTES) }.reason)
    }

    @Test fun individualWindowAndWholeStreamBudgetsAreIndependent() {
        val budget = StreamingJsonBudget(totalChars = 100, itemChars = 10, itemValues = 4)
        assertEquals(10, DeepSeekStrictJson.arrayItems(StringReader("[" + List(10) { "[1,2]" }.joinToString(",") + "]"), {}, budget).count())
        assertEquals(ArchiveFailure.WINDOW_LIMIT,
            failure { DeepSeekStrictJson.arrayItems(StringReader("[\"12345678901\"]"), {}, budget).toList() }.reason)
        assertEquals(ArchiveFailure.WINDOW_LIMIT,
            failure { DeepSeekStrictJson.arrayItems(StringReader("[[1,2,3,4]]"), {}, budget).toList() }.reason)
        assertEquals(ArchiveFailure.SIZE_LIMIT,
            failure { DeepSeekStrictJson.arrayItems(StringReader("[" + " ".repeat(101) + "0]"), {}, budget).toList() }.reason)
    }

    @Test fun freeSpaceCannotOverflowAndKeepsReserve() {
        ArchiveCapacity.requireSpace(ArchiveCapacity.MIN_FREE_BYTES + 3, 3)
        assertEquals(ArchiveFailure.INSUFFICIENT_SPACE,
            failure { ArchiveCapacity.requireSpace(ArchiveCapacity.MIN_FREE_BYTES + 2, 3) }.reason)
        assertEquals(ArchiveFailure.INSUFFICIENT_SPACE,
            failure { ArchiveCapacity.requireSpace(Long.MAX_VALUE, Long.MAX_VALUE) }.reason)
    }

    @Test fun kelivoLargeArchiveCannotMaterializeAnUnboundedReasoningWindow() {
        requireKelivoWindowReadBudget(50_000, KelivoChatLimits.MAX_WINDOW_BYTES)
        assertEquals(ArchiveFailure.WINDOW_LIMIT,
            failure { requireKelivoWindowReadBudget(50_001, 1) }.reason)
        assertEquals(ArchiveFailure.WINDOW_LIMIT,
            failure { requireKelivoWindowReadBudget(1, KelivoChatLimits.MAX_WINDOW_BYTES + 1) }.reason)
        assertEquals(ArchiveFailure.WINDOW_LIMIT,
            failure { requireKelivoWindowReadBudget(1, ArchiveCapacity.MAX_STREAM_JSON_BYTES) }.reason)
    }

    @Test fun everyCopiedZipEntryChecksActualLengthAndCrc() {
        val directory = Files.createTempDirectory("archive-capacity-test").toFile()
        try {
            val archive = File(directory, "synthetic.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("entry")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("entry")
                val output = ByteArrayOutputStream()
                assertEquals(3, ArchiveCapacity.copyZipEntry(zip, entry, output, 3).toInt())
                assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
                entry.crc = entry.crc xor 1
                assertEquals(ArchiveFailure.CHECKSUM, failure { ArchiveCapacity.copyZipEntry(zip, entry, ByteArrayOutputStream(), 3) }.reason)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun classifiedErrorsDoNotExposePrivateParserTextOrPaths() {
        val marker = "PRIVATE_USER_MESSAGE_AND_PATH"
        for (error in listOf(IllegalArgumentException(marker), java.io.IOException(marker),
            java.util.zip.ZipException(marker), ArchiveReadException(ArchiveFailure.INSUFFICIENT_SPACE))) {
            assertFalse(ArchiveCapacity.publicError(error).contains(marker))
        }
        assertNotEquals(ArchiveCapacity.publicError(ArchiveReadException(ArchiveFailure.INSUFFICIENT_SPACE)),
            ArchiveCapacity.publicError(java.util.zip.ZipException(marker)))
    }

    @Test fun unsafePathsStayRejected() {
        listOf("../a", "/a", "a\\b", "C:/a", "a//b", "a/./b", "a\u0000b").forEach { path ->
            assertEquals(ArchiveFailure.UNSAFE_PATH, failure { ArchiveCapacity.requireSafePath(path) }.reason)
        }
        ArchiveCapacity.requireSafePath("upload/test.jpg")
    }

    private fun failure(block: () -> Unit): ArchiveReadException = try {
        block(); fail("expected a classified archive error"); error("unreachable")
    } catch (failure: ArchiveReadException) { failure }
}
