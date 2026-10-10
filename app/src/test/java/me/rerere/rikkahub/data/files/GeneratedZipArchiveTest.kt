package me.rerere.rikkahub.data.files

import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedZipArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(path: String = "说明.md", text: String = "你好\nZIP") = GeneratedZipArchive.TextFile(path, text)

    @Test fun generatedArchiveRoundTripsThroughRealInputParserAndCrc() {
        val source = listOf(entry(), entry("src/main.kt", "fun main() { println(\"hi\") }"), entry("README", ""))
        val zip = GeneratedZipArchive.create("作品.zip", source)
        val file = temp.newFile("created.zip").apply { writeBytes(zip.bytes) }
        assertEquals(source.map { it.path }, ZipAttachmentArchive.inspect(file).map { it.path })
        source.forEach { assertEquals(it.text, ZipAttachmentArchive.readText(file, it.path).text) }
        ZipFile(file).use { archive -> archive.entries().asSequence().forEach { assertEquals(0, it.method) } }
    }

    @Test fun repetitiveLargeTextCannotTripOurInputCompressionBombGuard() {
        val text = "a".repeat(1024 * 1024 + 1)
        val zip = GeneratedZipArchive.create("large.zip", listOf(entry(text = text)))
        val file = temp.newFile().apply { writeBytes(zip.bytes) }
        assertEquals(text.length.toLong(), ZipAttachmentArchive.inspect(file).single().bytes)
        assertEquals(text.length, ZipAttachmentArchive.readText(file, "说明.md").totalChars)
    }

    @Test fun unsafeNamesAndPathsNeverCreateArchives() {
        listOf("../out.zip", "/out.zip", "C:/out.zip", "out.txt", "folder/out.zip").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create(name, listOf(entry())) }
        }
        listOf("../x.txt", "/x.txt", "a/../x.txt", "C:\\x.txt", "a//x.txt", "a.txt/", "payload.zip", "photo.png", "x\u0000.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("safe.zip", listOf(entry(path))) }
        }
    }

    @Test fun aliasesDuplicatesAndFileDirectoryConflictsAreRejected() {
        listOf(listOf(entry("A.txt"), entry("a.txt")), listOf(entry("é.txt"), entry("e\u0301.txt")),
            listOf(entry("a.txt"), entry("a.txt/b.txt"))).forEach { entries ->
            assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("safe.zip", entries) }
        }
    }

    @Test fun entryCountSizeUtf8AndOutputLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", (0..128).map { entry("$it.txt") }) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", listOf(entry(text = "a".repeat(GeneratedZipArchive.MAX_ENTRY_BYTES + 1)))) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", listOf(entry(text = "汉".repeat(GeneratedZipArchive.MAX_ENTRY_BYTES / 2)))) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", listOf(entry(text = "\u0000"))) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { GeneratedZipArchive.create("a.zip", listOf(entry(text = "\uD800"))) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", (1..4).map { entry("$it.txt", "a".repeat(GeneratedZipArchive.MAX_ENTRY_BYTES)) }) }
        assertThrows(IllegalArgumentException::class.java) { GeneratedZipArchive.create("a.zip", (1..5).map { entry("$it.txt", "a".repeat(GeneratedZipArchive.MAX_ENTRY_BYTES)) }) }
    }

    @Test fun cancellationCheckRunsBeforeEveryEntryAndReturnsNoPartialResult() {
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            GeneratedZipArchive.create("a.zip", listOf(entry(), entry("b.md"))) { if (++checks == 3) error("cancelled") }
        }
        assertEquals(3, checks)
    }
}
