package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmergencyArchiveSchemaTest {
    @get:Rule val temporary = TemporaryFolder()
    private val metadata = EmergencyArchiveMetadata("synthetic.orbis", "fixture", 1L)
    private val bytes = "unchanged synthetic bytes".toByteArray()

    private fun manifest(path: String, schema: Int = 2) = EmergencyArchiveManifest(
        schemaVersion = schema, metadata = metadata,
        roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveSourceRoot(it, "/synthetic/$it", it == "files") },
        files = listOf(EmergencyArchiveFileRecord(path, bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, 1L)),
        directories = listOf("files"),
    )

    private fun archive(manifest: EmergencyArchiveManifest, entry: String, name: String = "test.zip"): File =
        File(temporary.root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry()
                zip.putNextEntry(ZipEntry(EmergencyArchive.MANIFEST_ENTRY))
                zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
            }
        }

    @Test fun schemaTwoColonAndUnicodeNamesVerifyThroughOpaquePayloadOnEveryHost() {
        val path = "files/软件包:arm64.list"
        val manifest = manifest(path)
        val entry = EmergencyArchive.payloadEntryName(2, path)
        assertTrue(entry.matches(Regex("payload/[0-9a-f]{64}\\.bin")))
        assertFalse(entry.contains("arm64"))
        val archive = archive(manifest, entry)
        assertEquals(manifest, EmergencyArchive.verify(archive))
        if (System.getProperty("os.name").equals("Linux", true)) {
            val target = File(temporary.root, "linux-target")
            EmergencyArchive.extractVerified(archive, target)
            assertArrayEquals(bytes, File(target, path).readBytes())
        } else {
            val target = File(temporary.root, "unsupported-target")
            assertThrows(IOException::class.java) { EmergencyArchive.extractVerified(archive, target) }
            assertFalse(target.exists())
        }
    }

    @Test fun legacySchemaOneStillExtractsAndStillRejectsColon() {
        val legacy = manifest("files/old-safe", 1)
        val archive = archive(legacy, "payload/files/old-safe")
        assertEquals(legacy, EmergencyArchive.verify(archive))
        val target = File(temporary.root, "legacy")
        EmergencyArchive.extractVerified(archive, target)
        assertArrayEquals(bytes, File(target, "files/old-safe").readBytes())
        val unsafe = archive(manifest("files/old:stream", 1), "payload/files/old:stream", "old-unsafe.zip")
        assertThrows(IOException::class.java) { EmergencyArchive.verify(unsafe) }
    }

    @Test fun schemaCannotBeChangedWithoutChangingPayloadMapping() {
        val second = archive(manifest("files/a"), "payload/files/a", "v2-with-legacy-entry.zip")
        assertThrows(IOException::class.java) { EmergencyArchive.verify(second) }
        val first = archive(manifest("files/a", 1), EmergencyArchive.payloadEntryName(2, "files/a"), "v1-with-id.zip")
        assertThrows(IOException::class.java) { EmergencyArchive.verify(first) }
    }

    @Test fun logicalPathsAreNeitherDecodedNorNormalizedAndMalformedUnicodeIsRejected() {
        val paths = listOf("files/a:b", "files/a_b", "files/a%3Ab", "files/%2e%2e", "files/é", "files/e\u0301")
        assertEquals(paths.size, paths.map { EmergencyArchive.payloadEntryName(2, it) }.toSet().size)
        listOf("files/../escape", "/files/a", "files//a", "files/./a", "files\\a", "C:/a", "files/\u0000", "files/\uD800", "files/\uDC00").forEach {
            assertThrows(IOException::class.java) { EmergencyArchive.payloadEntryName(2, it) }
        }
    }

    @Test fun schemaTwoTraversalManifestCannotBeMadeValidByUsingAnOpaquePayloadName() {
        val zip = archive(manifest("files/../escape"), "payload/" + "a".repeat(64) + ".bin")
        val target = File(temporary.root, "hostile")
        assertThrows(IOException::class.java) { EmergencyArchive.extractVerified(zip, target) }
        assertFalse(target.exists())
        assertFalse(File(temporary.root, "escape").exists())
    }

    @Test fun windowsNamesAndCaseAliasesAreRejectedBeforeMaterialization() {
        listOf("files/a:b", "files/CON.txt", "files/nul", "files/COM1.bin", "files/a.", "files/a ",
            "files/a?b", "files/a*b", "files/a|b", "files/a\"b", "files/a<b", "files/a>b", "files/a\u0001b").forEach {
            assertThrows(IOException::class.java) {
                EmergencyArchivePaths.requireMaterializable(listOf("files", it), windows = true, linux = false)
            }
        }
        assertThrows(IOException::class.java) {
            EmergencyArchivePaths.requireMaterializable(listOf("files", "files/A", "files/a"), windows = true, linux = false)
        }
        EmergencyArchivePaths.requireMaterializable(listOf("files", "files/包:arm64"), windows = false, linux = true)
    }

    @Test fun everyExpensiveCreateStageReportsBoundedMonotoneProgressIncludingZeroLengthFiles() {
        val source = temporary.newFolder("source")
        val files = File(source, "files").apply { mkdirs() }
        File(files, "large").writeBytes(ByteArray(180_000) { 3 })
        File(files, "empty").writeBytes(byteArrayOf())
        val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(source, it)) }
        val events = mutableListOf<EmergencyArchiveProgress>()
        val zip = File(temporary.root, "progress.zip")
        val created = EmergencyArchive.create(roots, zip, metadata, events::add)
        assertEquals(2, created.schemaVersion)
        val stages = events.map { it.phase }.distinct()
        assertEquals(listOf("scan", "copy", "manifest", "rescan", "rehash", "final_scan", "verify", "sync", "complete"), stages)
        for (phase in listOf("scan", "rescan", "final_scan")) {
            val phaseEvents = events.filter { it.phase == phase }
            assertEquals(0L, phaseEvents.first().completedEntries)
            assertEquals(3L, phaseEvents.last().completedEntries)
            assertEquals(3L, phaseEvents.last().totalEntries)
        }
        for (phase in listOf("copy", "rehash", "verify")) {
            val phaseEvents = events.filter { it.phase == phase }
            assertTrue(phaseEvents.size >= 4)
            assertEquals(0L, phaseEvents.first().completedBytes)
            assertEquals(180_000L, phaseEvents.last().completedBytes)
            assertEquals(2L, phaseEvents.last().completedEntries)
            assertTrue(phaseEvents.zipWithNext().all { (a, b) -> a.completedBytes <= b.completedBytes })
        }
        ZipFile(zip).use { assertTrue(it.entries().asSequence().all { entry ->
            entry.name == EmergencyArchive.MANIFEST_ENTRY || entry.name.matches(Regex("payload/[0-9a-f]{64}\\.bin"))
        }) }
    }

    @Test fun cancellationDuringPreviouslySilentStagesNeverPublishesAndLeavesSourcesIntact() {
        for (phase in listOf("scan", "rescan", "rehash", "final_scan", "verify", "sync")) {
            val source = temporary.newFolder("source-$phase")
            val original = File(source, "files/one").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(source, it)) }
            val zip = File(temporary.root, "$phase.zip")
            assertThrows(IOException::class.java) {
                EmergencyArchive.create(roots, zip, metadata) { if (it.phase == phase) throw IOException("synthetic cancellation") }
            }
            assertFalse(zip.exists())
            assertArrayEquals(bytes, original.readBytes())
            assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".partial") })
        }
    }
}
