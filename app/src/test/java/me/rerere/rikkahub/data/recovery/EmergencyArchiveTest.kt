package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmergencyArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val metadata = EmergencyArchiveMetadata("test.orbis", "2.6.3", 236L, 1234567L)
    private val json = Json { encodeDefaults = true }

    private fun roots(base: File) = EmergencyArchive.ROOT_NAMES.map {
        EmergencyArchiveRoot(it, File(base, it))
    }

    private fun write(base: File, relative: String, bytes: ByteArray): File = File(base, relative).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    @Test fun temporaryVideoFramesAreExcludedButNearbyNoBackupDataRemainsByteExact() {
        val source = temporary.newFolder("video-source")
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val current = write(source, "no_backup/orbis-video-frames/call/current.jpg", jpeg)
        val expired = write(source, "no_backup/orbis-video-frames/call/expired.jpg", jpeg)
        assertTrue(expired.setLastModified(1))
        write(source, "no_backup/orbis-video-frames/call.json", "synthetic index".toByteArray())
        val preserved = mapOf(
            "no_backup/orbis-private-vaults/synthetic/record" to "ciphertext".toByteArray(),
            "no_backup/orbis-generation-journal-v1/receipt" to "receipt".toByteArray(),
            "no_backup/orbis-video-frames-other/keep" to "nearby".toByteArray(),
            "files/orbis-companion-spaces/synthetic/saved.image" to jpeg,
            "files/orbis-video-frames/ordinary-file" to "different root".toByteArray(),
        )
        preserved.forEach { (path, bytes) -> write(source, path, bytes) }
        val output = File(temporary.root, "video-excluded.zip")
        val progress = mutableListOf<EmergencyArchiveProgress>()
        val manifest = EmergencyArchive.create(roots(source), output, metadata) { progress.add(it) }
        assertEquals(preserved.keys, manifest.files.map { it.path }.toSet())
        assertFalse(manifest.directories.any { it == "no_backup/orbis-video-frames" || it.startsWith("no_backup/orbis-video-frames/") })
        assertTrue(manifest.links.isEmpty())
        assertEquals(manifest, EmergencyArchive.verify(output))
        val extracted = File(temporary.root, "video-extracted")
        EmergencyArchive.extractVerified(output, extracted)
        assertFalse(File(extracted, "no_backup/orbis-video-frames").exists())
        preserved.forEach { (path, bytes) -> assertArrayEquals(bytes, File(extracted, path).readBytes()) }
        assertArrayEquals(jpeg, current.readBytes())
        assertArrayEquals(jpeg, expired.readBytes()) // Export never deletes even expired originals.
        assertEquals("complete", progress.last().phase)
        assertEquals(preserved.size.toLong(), progress.last().totalEntries)
        assertEquals(preserved.size.toLong(), progress.last().completedEntries)
        assertEquals(preserved.values.sumOf { it.size.toLong() }, progress.last().totalBytes)
        assertEquals(progress.last().totalBytes, progress.last().completedBytes)
    }

    @Test fun rawRoundTripPreservesInvalidDatabaseSettingsAndAllSidecarsWithoutParsing() {
        val source = temporary.newFolder("source")
        val values = mapOf(
            "databases/rikkahub.db" to byteArrayOf(0, -1, 1, 2),
            "databases/rikkahub.db-wal" to byteArrayOf(3, 0, 4),
            "databases/rikkahub.db-shm" to byteArrayOf(-3, 8),
            "databases/rikkahub.db-journal" to byteArrayOf(9, 9),
            "files/datastore/settings.preferences_pb" to byteArrayOf(-1, -2, -3),
            "files/upload/中文 图片.bin" to ByteArray(180_000) { (it % 251).toByte() },
            "shared_prefs/broken.xml" to "<deliberately broken".toByteArray(),
            "no_backup/retained.bin" to byteArrayOf(12, 13),
        )
        values.forEach { (path, bytes) -> write(source, path, bytes) }
        File(source, "files/empty/deeper").mkdirs()
        write(source, "cache/temporary", byteArrayOf(55))
        write(source, "code_cache/temporary", byteArrayOf(56))
        write(source, "orbis-emergency/old.zip", byteArrayOf(57))
        val archive = File(temporary.root, "rescue.zip")
        val phases = mutableListOf<String>()
        val manifest = EmergencyArchive.create(roots(source), archive, metadata) { phases += it.phase }
        assertEquals(metadata, manifest.metadata)
        assertEquals(values.keys, manifest.files.map { it.path }.toSet())
        assertEquals("complete", phases.last())
        assertEquals(manifest, EmergencyArchive.verify(archive))
        val restored = File(temporary.root, "isolated")
        assertEquals(manifest, EmergencyArchive.extractVerified(archive, restored))
        values.forEach { (path, bytes) ->
            assertArrayEquals(bytes, File(restored, path).readBytes())
            assertArrayEquals(bytes, File(source, path).readBytes())
        }
        assertTrue(File(restored, "files/empty/deeper").isDirectory)
        assertFalse(File(restored, "cache").exists())
        assertFalse(File(restored, "orbis-emergency").exists())
    }

    @Test fun missingRootsAreRecordedRatherThanCreatedOrSilentlyAssumedPresent() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        val archive = File(temporary.root, "missing-roots.zip")
        val manifest = EmergencyArchive.create(roots(source), archive, metadata)
        assertEquals(setOf("files"), manifest.roots.filter { it.present }.map { it.name }.toSet())
        assertFalse(File(source, "databases").exists())
        assertEquals(manifest, EmergencyArchive.verify(archive))
    }

    @Test fun outputInsideAnyPersistentRootIsRefusedBeforeWriting() {
        val source = temporary.newFolder("source")
        val outputParent = File(source, "files/export").apply { mkdirs() }
        val output = File(outputParent, "backup.zip")
        assertThrows(IOException::class.java) { EmergencyArchive.create(roots(source), output, metadata) }
        assertEquals(emptyList<String>(), outputParent.list()!!.toList())
    }

    @Test fun preexistingOutputIsNeverOverwritten() {
        val source = temporary.newFolder("source")
        val output = temporary.newFile("keep.zip").apply { writeText("previous backup") }
        assertThrows(IOException::class.java) { EmergencyArchive.create(roots(source), output, metadata) }
        assertEquals("previous backup", output.readText())
    }

    @Test fun sourceContentChangeWithSameLengthAndTimestampIsDetectedAndNoFinalIsPublished() {
        val source = temporary.newFolder("source")
        val original = write(source, "databases/rikkahub.db", byteArrayOf(1, 2, 3))
        val stamp = Files.getLastModifiedTime(original.toPath())
        val output = File(temporary.root, "changed.zip")
        assertThrows(IOException::class.java) {
            EmergencyArchive.createInternal(roots(source), output, metadata) {
                original.writeBytes(byteArrayOf(3, 2, 1))
                Files.setLastModifiedTime(original.toPath(), stamp)
            }
        }
        assertFalse(output.exists())
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".partial") })
        assertArrayEquals(byteArrayOf(3, 2, 1), original.readBytes())
    }

    @Test fun aNewWalAppearingDuringBackupInvalidatesWholeSnapshot() {
        val source = temporary.newFolder("source")
        write(source, "databases/rikkahub.db", byteArrayOf(1, 2))
        val output = File(temporary.root, "changed-wal.zip")
        assertThrows(IOException::class.java) {
            EmergencyArchive.createInternal(roots(source), output, metadata) {
                write(source, "databases/rikkahub.db-wal", byteArrayOf(3))
            }
        }
        assertFalse(output.exists())
        assertTrue(File(source, "databases/rikkahub.db-wal").exists())
    }

    @Test fun readOrStorageFailureCannotReportCompleteAndPartialIsRemoved() {
        val source = temporary.newFolder("source")
        val original = write(source, "files/one", ByteArray(80_000) { 5 })
        val output = File(temporary.root, "failed.zip")
        val phases = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            EmergencyArchive.create(roots(source), output, metadata) {
                phases += it.phase
                throw IOException("injected storage failure")
            }
        }
        assertFalse(output.exists())
        assertFalse(phases.contains("complete"))
        assertEquals(80_000L, original.length())
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".partial") })
    }

    @Test fun symbolicLinksAreRecordedButNeverFollowedOrRecreated() {
        val source = temporary.newFolder("source")
        val outside = temporary.newFile("outside-sensitive").apply { writeText("must not be copied") }
        val runtime = File(source, "files/rootfs").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(runtime, "escape").toPath(), outside.toPath())
            Files.createSymbolicLink(File(runtime, "loop").toPath(), runtime.toPath())
        } catch (e: Exception) {
            assumeNoException("Host does not allow test symbolic links", e)
        }
        val output = File(temporary.root, "links.zip")
        val manifest = EmergencyArchive.create(roots(source), output, metadata)
        assertEquals(2, manifest.links.size)
        assertEquals(0, manifest.files.size)
        val isolated = File(temporary.root, "link-extract")
        EmergencyArchive.extractVerified(output, isolated)
        assertFalse(Files.exists(File(isolated, "files/rootfs/escape").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(isolated, "files/rootfs/loop").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("must not be copied", outside.readText())
    }

    @Test fun sourceRootSymlinkIsRejectedRatherThanClaimingTheWrongRootWasSaved() {
        val source = temporary.newFolder("source")
        val outside = temporary.newFolder("outside-root")
        try {
            Files.createSymbolicLink(File(source, "files").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Host does not allow test symbolic links", e)
        }
        assertThrows(IOException::class.java) {
            EmergencyArchive.create(roots(source), File(temporary.root, "root-link.zip"), metadata)
        }
    }

    @Test fun extractionWillNotTouchAnExistingDirectory() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        val output = File(temporary.root, "existing-target.zip")
        EmergencyArchive.create(roots(source), output, metadata)
        val live = temporary.newFolder("pretend-live")
        val existing = File(live, "keep").apply { writeText("keep me") }
        assertThrows(IOException::class.java) { EmergencyArchive.extractVerified(output, live) }
        assertEquals("keep me", existing.readText())
        assertFalse(File(live, "files").exists())
    }

    @Test fun corruptedPayloadAndMissingPayloadCannotPassVerification() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1, 2, 3))
        val output = File(temporary.root, "original.zip")
        val manifest = EmergencyArchive.create(roots(source), output, metadata)
        val corrupt = File(temporary.root, "corrupt.zip")
        writeZip(corrupt, manifest, mapOf(EmergencyArchive.payloadEntryName(manifest.schemaVersion, "files/one") to byteArrayOf(3, 2, 1)))
        assertThrows(IOException::class.java) { EmergencyArchive.verify(corrupt) }
        val missing = File(temporary.root, "missing.zip")
        writeZip(missing, manifest, emptyMap())
        assertThrows(IOException::class.java) { EmergencyArchive.verify(missing) }
    }

    @Test fun traversalAbsoluteBackslashAndAlternateStreamPathsAreRefusedBeforeExtraction() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        val output = File(temporary.root, "path-base.zip")
        val base = EmergencyArchive.create(roots(source), output, metadata).copy(schemaVersion = 1)
        listOf("files/../escape", "/files/absolute", "files\\escape", "files/file:stream", "files//one").forEachIndexed { index, path ->
            val hostile = File(temporary.root, "hostile-$index.zip")
            writeZip(hostile, base.copy(files = listOf(base.files.single().copy(path = path))), mapOf("payload/$path" to byteArrayOf(1)))
            val destination = File(temporary.root, "hostile-target-$index")
            assertThrows(IOException::class.java) { EmergencyArchive.extractVerified(hostile, destination) }
            assertFalse(destination.exists())
        }
        assertFalse(File(temporary.root, "escape").exists())
    }

    @Test fun unexpectedZipEntriesAndIncompleteManifestAreRejected() {
        val source = temporary.newFolder("source")
        val original = File(temporary.root, "empty-original.zip")
        val base = EmergencyArchive.create(roots(source), original, metadata)
        val extra = File(temporary.root, "extra.zip")
        writeZip(extra, base, mapOf("not-listed" to byteArrayOf(1)))
        assertThrows(IOException::class.java) { EmergencyArchive.verify(extra) }
        val partial = File(temporary.root, "partial-status.zip")
        writeZip(partial, base.copy(status = "partial"), emptyMap())
        assertThrows(IOException::class.java) { EmergencyArchive.verify(partial) }
    }

    @Test fun duplicateManifestPathsAndLengthOverflowAreRejected() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        val original = File(temporary.root, "duplicate-base.zip")
        val base = EmergencyArchive.create(roots(source), original, metadata)
        val duplicate = File(temporary.root, "duplicate-record.zip")
        writeZip(duplicate, base.copy(files = base.files + base.files), mapOf(EmergencyArchive.payloadEntryName(base.schemaVersion, "files/one") to byteArrayOf(1)))
        assertThrows(IOException::class.java) { EmergencyArchive.verify(duplicate) }
        val overflow = File(temporary.root, "overflow.zip")
        writeZip(overflow, base.copy(files = listOf(
            base.files.single().copy(size = Long.MAX_VALUE),
            base.files.single().copy(path = "files/two", size = 1),
        )), mapOf(EmergencyArchive.payloadEntryName(base.schemaVersion, "files/one") to byteArrayOf(1),
            EmergencyArchive.payloadEntryName(base.schemaVersion, "files/two") to byteArrayOf(1)))
        assertThrows(IOException::class.java) { EmergencyArchive.verify(overflow) }
    }

    @Test fun duplicateZipMemberNamesCannotHideASecondPayload() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        write(source, "files/two", byteArrayOf(2))
        val original = File(temporary.root, "duplicate-zip.zip")
        val manifest = EmergencyArchive.create(roots(source), original, metadata)
        // ZipOutputStream refuses duplicate names itself. Rename the equal-length local and
        // central directory names after writing, without modifying payloads or their checksums.
        val bytes = original.readBytes()
        val from = EmergencyArchive.payloadEntryName(manifest.schemaVersion, "files/two").toByteArray()
        val to = EmergencyArchive.payloadEntryName(manifest.schemaVersion, "files/one").toByteArray()
        var replaced = 0
        for (index in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[index + it] == from[it] }) {
                to.copyInto(bytes, index)
                replaced++
            }
        }
        assertEquals(2, replaced)
        original.writeBytes(bytes)
        assertThrows(IOException::class.java) { EmergencyArchive.verify(original) }
    }

    @Test fun arbitraryCallerRootListsCannotOmitOneStoreOrReadOverlappingTrees() {
        val source = temporary.newFolder("source")
        val missing = roots(source).dropLast(1)
        assertThrows(IOException::class.java) {
            EmergencyArchive.create(missing, File(temporary.root, "missing-store.zip"), metadata)
        }
        val overlap = roots(source).map {
            if (it.name == "no_backup") it.copy(directory = File(source, "files/nested")) else it
        }
        assertThrows(IOException::class.java) {
            EmergencyArchive.create(overlap, File(temporary.root, "overlap.zip"), metadata)
        }
    }

    @Test fun excessiveManifestArraysAreRefusedEvenWhenCompressedVerySmall() {
        val source = temporary.newFolder("source")
        val original = File(temporary.root, "guard-base.zip")
        val base = EmergencyArchive.create(roots(source), original, metadata)
        val excessive = File(temporary.root, "guard-excessive.zip")
        writeZip(excessive, base.copy(directories = List(100_001) { "files" }), emptyMap())
        assertThrows(IOException::class.java) { EmergencyArchive.verify(excessive) }
    }

    @Test fun runtimeSpecialNodesAreEvidenceOnlyAndNeverExtracted() {
        val source = temporary.newFolder("source")
        write(source, "files/rootfs/regular", byteArrayOf(4, 5))
        val original = File(temporary.root, "special-base.zip")
        val base = EmergencyArchive.create(roots(source), original, metadata)
        val declared = base.copy(specialFiles = listOf(
            EmergencyArchiveSpecialFileRecord("files/rootfs/socket", "non_regular_runtime_entry"),
            EmergencyArchiveSpecialFileRecord("files/rootfs/fifo", "non_regular_runtime_entry"),
        ))
        val archive = File(temporary.root, "special-records.zip")
        writeZip(archive, declared, mapOf(EmergencyArchive.payloadEntryName(declared.schemaVersion, "files/rootfs/regular") to byteArrayOf(4, 5)))
        assertEquals(declared, EmergencyArchive.verify(archive))
        val target = File(temporary.root, "special-extracted")
        assertEquals(declared, EmergencyArchive.extractVerified(archive, target))
        assertArrayEquals(byteArrayOf(4, 5), File(target, "files/rootfs/regular").readBytes())
        assertFalse(File(target, "files/rootfs/socket").exists())
        assertFalse(File(target, "files/rootfs/fifo").exists())
    }

    @Test fun invalidOrCollidingSpecialMetadataCannotPassVerification() {
        val source = temporary.newFolder("source")
        write(source, "files/one", byteArrayOf(1))
        val original = File(temporary.root, "bad-special-base.zip")
        val base = EmergencyArchive.create(roots(source), original, metadata)
        listOf(
            EmergencyArchiveSpecialFileRecord("files/one", "non_regular_runtime_entry"),
            EmergencyArchiveSpecialFileRecord("files/two", "execute_me"),
            EmergencyArchiveSpecialFileRecord("files/../escape", "non_regular_runtime_entry"),
        ).forEachIndexed { index, record ->
            val archive = File(temporary.root, "bad-special-$index.zip")
            writeZip(archive, base.copy(specialFiles = listOf(record)), mapOf(EmergencyArchive.payloadEntryName(base.schemaVersion, "files/one") to byteArrayOf(1)))
            assertThrows(IOException::class.java) { EmergencyArchive.verify(archive) }
        }
    }

    @Test fun deeplyNestedManifestFailsBeforeExtractionWithoutStackOverflow() {
        val archive = File(temporary.root, "deep-manifest.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(EmergencyArchive.MANIFEST_ENTRY))
            zip.write(("{\"files\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}").toByteArray())
            zip.closeEntry()
        }
        val destination = File(temporary.root, "deep-target")
        val failure = assertThrows(IOException::class.java) { EmergencyArchive.extractVerified(archive, destination) }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { "deeply nested" in it.message.orEmpty() })
        assertFalse(destination.exists())
        assertTrue(archive.isFile)
    }

    private fun writeZip(file: File, manifest: EmergencyArchiveManifest, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(EmergencyArchive.MANIFEST_ENTRY))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
