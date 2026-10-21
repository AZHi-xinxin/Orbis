package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverwriteBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    private class Remote : AtomicBackupObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        val uploads = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        val expectedGenerations = mutableListOf<String?>()
        val emptyRequirements = mutableListOf<Boolean>()
        var replaceCalls = 0
        var failUpload: Exception? = null
        var failReplace: Exception? = null
        var loseReplaceResponse = false
        var failDelete = false
        var slowDelete = false
        var failLatestRead = false
        var corruptTemporaryRead = false
        var corruptLatestRead = false
        var beforeReplace: (() -> Unit)? = null
        var afterReplace: (() -> Unit)? = null
        var fixedReads = 0
        var prefixCalls = 0

        override suspend fun listNames() = objects.keys.toList()
        override suspend fun upload(name: String, file: File) {
            OverwriteBackupNames.requireAllowed(name)
            uploads += name
            val content = file.readBytes()
            failUpload?.let {
                objects[name] = content.copyOf(content.size / 2)
                throw it
            }
            objects[name] = content
        }
        override suspend fun download(name: String, file: File, maxBytes: Long) {
            OverwriteBackupNames.requireAllowed(name)
            downloads += name
            if (name == OverwriteBackupNames.LATEST) {
                fixedReads++
                if (failLatestRead) throw IOException("synthetic unavailable")
            }
            val bytes = objects[name]?.copyOf() ?: throw IOException("synthetic absent")
            require(bytes.size.toLong() <= maxBytes)
            if ((corruptTemporaryRead && OverwriteBackupNames.isTemporary(name)) ||
                (corruptLatestRead && name == OverwriteBackupNames.LATEST)) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
        }
        override suspend fun readPrefix(name: String, maxBytes: Int): ByteArray {
            OverwriteBackupNames.requireAllowed(name)
            prefixCalls++
            return requireNotNull(objects[name]).take(maxBytes).toByteArray()
        }
        override suspend fun replaceLatest(temporaryName: String, expectedGeneration: String?, requireEmpty: Boolean) {
            require(OverwriteBackupNames.isTemporary(temporaryName))
            replaceCalls++
            expectedGenerations += expectedGeneration
            emptyRequirements += requireEmpty
            beforeReplace?.invoke()
            failReplace?.let { throw it }
            if (requireEmpty) require(expectedGeneration == null &&
                OverwriteBackupNames.LATEST !in objects && objects.keys.none {
                    OverwriteBackupNames.isAllowed(it) && it.endsWith(".complete.json")
                }) { "synthetic destination no longer empty" }
            val current = objects[OverwriteBackupNames.LATEST]?.let {
                OverwriteBackupBundle.readPrefix(it.take(OverwriteBackupNames.MAX_HEADER_BYTES).toByteArray()).id
            }
            require(current == expectedGeneration) { "synthetic generation conflict" }
            val content = requireNotNull(objects.remove(temporaryName))
            objects[OverwriteBackupNames.LATEST] = content
            afterReplace?.invoke()
            if (loseReplaceResponse) throw IOException("synthetic response lost after commit")
        }
        override suspend fun delete(name: String) {
            OverwriteBackupNames.requireAllowed(name)
            deletes += name
            if (slowDelete) delay(60_000)
            if (failDelete) throw IOException("synthetic cleanup unavailable")
            objects.remove(name)
        }
    }

    private fun archive(text: String = "synthetic ZIP bytes without private data") = temporary.newFile().apply { writeText(text) }
    private fun point(bytes: ByteArray, version: Int = 2, time: Long = 42) = BackupRestorePoint(
        version = version, id = UUID.randomUUID().toString(), createdAt = time, bytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun envelope(point: BackupRestorePoint, bytes: ByteArray, metadata: String = json.encodeToString(point)): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.write("ORBISB2\n".toByteArray(Charsets.US_ASCII))
            val header = metadata.toByteArray(Charsets.UTF_8)
            it.writeInt(header.size); it.write(header); it.write(bytes)
        }
        return output.toByteArray()
    }

    private fun seed(remote: Remote, text: String = "old complete", time: Long = 1): BackupRestorePoint {
        val bytes = text.toByteArray()
        val point = point(bytes, time = time)
        remote.objects[OverwriteBackupNames.LATEST] = envelope(point, bytes)
        return point
    }

    private suspend fun legacy(remote: Remote, time: Long = 1): BackupRestorePoint {
        return VersionedBackup(remote, temporary.root).publish(archive("legacy $time"), false, time)
    }

    @Test fun publishesOneObjectAndReturnsOriginalZipFromExactEnvelope() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        val source = archive()
        val point = store.publish(source, true, 101)
        assertEquals(2, point.version)
        assertEquals(101L, point.createdAt)
        assertEquals(source.length(), point.bytes)
        assertTrue(point.includesConnectionSecrets)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
        assertEquals(listOf<String?>(null), remote.expectedGenerations)
        assertTrue(remote.uploads.single().let(OverwriteBackupNames::isTemporary))
        assertEquals(listOf(point), store.list())
        val destination = temporary.newFile()
        store.download(point, destination)
        assertArrayEquals(source.readBytes(), destination.readBytes())
        assertTrue(remote.deletes.none { it == OverwriteBackupNames.LATEST })
        assertArrayEquals("ORBISB2\n".toByteArray(), remote.objects.getValue(OverwriteBackupNames.LATEST).copyOfRange(0, 8))
    }

    @Test fun nextPublicationReplacesFixedObjectWithoutKeepingNewHistory() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        val first = store.publish(archive("first"), false, 1)
        val secondSource = archive("second different snapshot")
        val second = store.publish(secondSource, false, 2)
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(null, first.id), remote.expectedGenerations)
        assertEquals(listOf(false, false), remote.emptyRequirements)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
        assertEquals(listOf(second), store.list())
        val restored = temporary.newFile(); store.download(second, restored)
        assertArrayEquals(secondSource.readBytes(), restored.readBytes())
        assertEquals(2, remote.replaceCalls)
    }

    @Test fun listingReadsOnlyPrefixAndHidesLegacyAfterFixedObjectExists() = runBlocking {
        val remote = Remote(); legacy(remote)
        val latest = seed(remote)
        remote.downloads.clear()
        val result = OverwriteBackup(remote, temporary.root).list()
        assertEquals(listOf(latest), result)
        assertEquals(1, remote.prefixCalls)
        assertTrue(remote.downloads.isEmpty())
    }

    @Test fun legacyCatalogAndDownloadRemainCompatibleWithoutFixedObject() = runBlocking {
        val remote = Remote(); val old = legacy(remote)
        val store = OverwriteBackup(remote, temporary.root)
        assertEquals(listOf(old), store.list())
        val output = temporary.newFile(); store.download(old, output)
        assertEquals("legacy 1", output.readText())
        assertEquals(0, remote.prefixCalls)
    }

    @Test fun malformedFixedObjectDoesNotSilentlyFallbackOrOverwriteIt() = runBlocking {
        val remote = Remote(); legacy(remote)
        val invalid = "broken but preserved".toByteArray()
        remote.objects[OverwriteBackupNames.LATEST] = invalid.copyOf()
        val store = OverwriteBackup(remote, temporary.root)
        assertTrue(runCatching { store.list() }.isFailure)
        assertTrue(runCatching { store.publish(archive(), false) }.isFailure)
        assertArrayEquals(invalid, remote.objects.getValue(OverwriteBackupNames.LATEST))
        assertEquals(0, remote.replaceCalls)
    }

    @Test fun halfUploadedTemporaryNeverDamagesOldCompleteObject() = runBlocking {
        val remote = Remote(); val old = seed(remote)
        val before = remote.objects.getValue(OverwriteBackupNames.LATEST).copyOf()
        remote.failUpload = IOException("synthetic interrupted upload")
        val store = OverwriteBackup(remote, temporary.root)
        assertTrue(runCatching { store.publish(archive(), false) }.isFailure)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
        assertArrayEquals(before, remote.objects.getValue(OverwriteBackupNames.LATEST))
        assertEquals(listOf(old), store.list())
        assertEquals(0, remote.replaceCalls)
        assertTrue(remote.deletes.all(OverwriteBackupNames::isTemporary))
    }

    @Test fun cancelledUploadStillCleansOnlyOwnedTemporary() = runBlocking {
        val remote = Remote(); seed(remote)
        val foreign = OverwriteBackupNames.temporary(UUID.randomUUID().toString())
        remote.objects[foreign] = byteArrayOf(9)
        remote.failUpload = CancellationException("synthetic cancellation")
        val failure = runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(setOf(OverwriteBackupNames.LATEST, foreign), remote.objects.keys)
        assertTrue(remote.deletes.none { it == foreign || it == OverwriteBackupNames.LATEST })
    }

    @Test fun temporaryReadbackCorruptionNeverReplacesOldObject() = runBlocking {
        val remote = Remote(); seed(remote)
        val before = remote.objects.getValue(OverwriteBackupNames.LATEST).copyOf()
        remote.corruptTemporaryRead = true
        assertTrue(runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.isFailure)
        assertArrayEquals(before, remote.objects.getValue(OverwriteBackupNames.LATEST))
        assertEquals(0, remote.replaceCalls)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
    }

    @Test fun replaceFailureDoesNotDeleteOrRewriteTheOldCommittedObject() = runBlocking {
        val remote = Remote(); seed(remote)
        val before = remote.objects.getValue(OverwriteBackupNames.LATEST).copyOf()
        val failure = IOException("synthetic MOVE unavailable")
        remote.failReplace = failure
        assertSame(failure, runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.exceptionOrNull())
        assertArrayEquals(before, remote.objects.getValue(OverwriteBackupNames.LATEST))
        assertEquals(1, remote.replaceCalls)
        assertEquals(1, remote.uploads.size)
        assertTrue(remote.deletes.all(OverwriteBackupNames::isTemporary))
    }

    @Test fun staleGenerationCannotOverwriteConcurrentCommittedSnapshot() = runBlocking {
        val remote = Remote(); val first = seed(remote)
        var competing: BackupRestorePoint? = null
        remote.beforeReplace = { competing = seed(remote, "other writer", 99) }
        val store = OverwriteBackup(remote, temporary.root)
        assertTrue(runCatching { store.publish(archive(), false, 100) }.isFailure)
        assertEquals(listOf(first.id), remote.expectedGenerations)
        assertEquals(listOf(competing), store.list())
        assertEquals(1, remote.replaceCalls)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
    }

    @Test fun absentExpectationCannotClobberConcurrentFirstBackup() = runBlocking {
        val remote = Remote()
        var competing: BackupRestorePoint? = null
        remote.beforeReplace = { competing = seed(remote, "concurrent first") }
        val store = OverwriteBackup(remote, temporary.root)
        assertTrue(runCatching { store.publish(archive(), false) }.isFailure)
        assertEquals(listOf<String?>(null), remote.expectedGenerations)
        assertEquals(listOf(competing), store.list())
        assertEquals(1, remote.replaceCalls)
    }

    @Test fun freshPublicationPassesEmptyRequirementThroughToAtomicCommit() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        assertTrue(store.list().isEmpty())
        val published = store.publish(archive(), false, requireEmpty = true)
        assertEquals(listOf<String?>(null), remote.expectedGenerations)
        assertEquals(listOf(true), remote.emptyRequirements)
        assertEquals(0, remote.prefixCalls)
        assertEquals(listOf(published), store.list())
    }

    @Test fun freshPublicationDoesNotAdoptFixedGenerationAppearingAfterEmptyReview() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        assertTrue(store.list().isEmpty())
        val competing = seed(remote, "another device published after review", 99)
        val before = remote.objects.getValue(OverwriteBackupNames.LATEST).copyOf()
        assertTrue(runCatching { store.publish(archive(), false, requireEmpty = true) }.isFailure)
        assertEquals(listOf<String?>(null), remote.expectedGenerations)
        assertEquals(listOf(true), remote.emptyRequirements)
        assertEquals(0, remote.prefixCalls)
        assertArrayEquals(before, remote.objects.getValue(OverwriteBackupNames.LATEST))
        assertEquals(listOf(competing), store.list())
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
        assertEquals(1, remote.replaceCalls)
    }

    @Test fun freshPublicationRejectsLegacyCompletionAppearingAtCommit() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        assertTrue(store.list().isEmpty())
        val bytes = "concurrent legacy device".toByteArray()
        val competing = point(bytes, version = 1)
        remote.beforeReplace = {
            remote.objects[competing.payloadName] = bytes
            remote.objects[competing.markerName] = json.encodeToString(competing).toByteArray()
        }
        assertTrue(runCatching { store.publish(archive(), false, requireEmpty = true) }.isFailure)
        assertEquals(listOf<String?>(null), remote.expectedGenerations)
        assertEquals(listOf(true), remote.emptyRequirements)
        assertEquals(setOf(competing.markerName, competing.payloadName), remote.objects.keys)
        assertArrayEquals(bytes, remote.objects.getValue(competing.payloadName))
        assertEquals(listOf(competing), store.list())
        assertTrue(remote.deletes.all(OverwriteBackupNames::isTemporary))
    }

    @Test fun freshPublicationDoesNotTreatUncommittedPayloadsOrUnrelatedFilesAsCompletedBackup() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        val unmarked = "orbis_v1_${UUID.randomUUID()}.payload"
        val unrelated = "personal.complete.json"
        remote.objects[unmarked] = byteArrayOf(1)
        remote.objects[unrelated] = byteArrayOf(2)
        assertTrue(store.list().isEmpty())
        val published = store.publish(archive(), false, requireEmpty = true)
        assertEquals(listOf(true), remote.emptyRequirements)
        assertEquals(setOf(OverwriteBackupNames.LATEST, unmarked, unrelated), remote.objects.keys)
        assertEquals(listOf(published), store.list())
    }

    @Test fun committedReplaceWithLostResponseIsReconciledWithoutSecondMutation() = runBlocking {
        val remote = Remote(); seed(remote)
        remote.loseReplaceResponse = true
        val store = OverwriteBackup(remote, temporary.root)
        val point = store.publish(archive(), false)
        assertEquals(listOf(point), store.list())
        assertEquals(1, remote.replaceCalls)
        assertEquals(1, remote.uploads.size)
        assertEquals(1, remote.fixedReads)
        assertEquals(setOf(OverwriteBackupNames.LATEST), remote.objects.keys)
    }

    @Test fun lostResponseWithoutFinalReadProofIsNotReportedSuccessful() = runBlocking {
        val remote = Remote(); seed(remote)
        remote.loseReplaceResponse = true; remote.failLatestRead = true
        assertTrue(runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.isFailure)
        assertEquals(1, remote.replaceCalls)
        assertEquals(1, remote.fixedReads)
        assertTrue(remote.objects.containsKey(OverwriteBackupNames.LATEST))
        assertTrue(remote.deletes.none { it == OverwriteBackupNames.LATEST })
    }

    @Test fun finalReadbackCorruptionIsNeverReportedSuccessful() = runBlocking {
        val remote = Remote(); seed(remote)
        remote.corruptLatestRead = true
        assertTrue(runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.isFailure)
        assertEquals(1, remote.replaceCalls)
        assertTrue(remote.deletes.none { it == OverwriteBackupNames.LATEST })
    }

    @Test fun cleanupFailureDoesNotHideVerifiedPublicationResult() = runBlocking {
        val remote = Remote(); remote.failDelete = true
        val store = OverwriteBackup(remote, temporary.root)
        val point = store.publish(archive(), false)
        assertEquals(listOf(point), store.list())
        assertEquals(1, remote.replaceCalls)
    }

    @Test fun cleanupFailureDoesNotReplaceTheOriginalPublicationError() = runBlocking {
        val remote = Remote(); val error = IOException("synthetic original failure")
        remote.failUpload = error; remote.failDelete = true
        assertSame(error, runCatching { OverwriteBackup(remote, temporary.root).publish(archive(), false) }.exceptionOrNull())
    }

    @Test(timeout = 8_000) fun cleanupIsBoundedEvenIfStoreDeleteNeverFinishes() = runBlocking {
        val remote = Remote(); remote.slowDelete = true
        val point = OverwriteBackup(remote, temporary.root).publish(archive(), false)
        assertEquals(2, point.version)
        assertEquals(1, remote.deletes.size)
    }

    @Test fun selectedOlderFixedGenerationIsRejectedBeforeDestinationReplacement() = runBlocking {
        val remote = Remote(); val selected = seed(remote)
        seed(remote, "newest differs", 2)
        val destination = archive("keep caller data")
        assertTrue(runCatching { OverwriteBackup(remote, temporary.root).download(selected, destination) }.isFailure)
        assertEquals("keep caller data", destination.readText())
    }

    @Test fun malformedOrDamagedDownloadNeverReplacesCallerDestination() = runBlocking {
        val bytes = "valid original ZIP".toByteArray()
        val expected = point(bytes)
        val complete = envelope(expected, bytes)
        val cases = listOf(
            complete.copyOf(complete.size - 1),
            complete + byteArrayOf(0),
            complete.copyOf().also { it[it.lastIndex] = 0 },
            envelope(expected.copy(sha256 = "0".repeat(64)), bytes),
            envelope(expected.copy(bytes = bytes.size.toLong() + 1), bytes),
        )
        for (bad in cases) {
            val remote = Remote(); remote.objects[OverwriteBackupNames.LATEST] = bad
            val destination = archive("original destination")
            assertTrue(runCatching { OverwriteBackup(remote, temporary.root).download(expected, destination) }.isFailure)
            assertEquals("original destination", destination.readText())
        }
    }

    @Test fun headerRejectsBadMagicUnsignedLengthTruncationInvalidUtf8AndUnknownVersion() {
        val bytes = "data".toByteArray(); val expected = point(bytes)
        val valid = envelope(expected, bytes)
        val short = (0..11).map { valid.copyOf(it) }
        val sizes = listOf(0, -1, 4097, Int.MAX_VALUE).map { size ->
            ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
                it.write("ORBISB2\n".toByteArray()); it.writeInt(size)
            } }.toByteArray()
        }
        val badUtf8 = ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.write("ORBISB2\n".toByteArray()); it.writeInt(2); it.write(byteArrayOf(0xc3.toByte(), 0x28))
        } }.toByteArray()
        val cases = short + sizes + listOf(
            valid.copyOf().also { it[0] = 0 }, badUtf8,
            envelope(expected.copy(version = 1), bytes),
            envelope(expected.copy(version = 3), bytes),
            envelope(expected.copy(id = "-".repeat(36)), bytes),
            envelope(expected.copy(bytes = BackupRestorePoint.MAX_BYTES + 1), bytes),
            envelope(expected.copy(bytes = 0), bytes),
            envelope(expected.copy(createdAt = -1), bytes),
            envelope(expected.copy(sha256 = "INVALID"), bytes),
            envelope(expected, bytes, "{}"),
            envelope(expected, bytes, json.encodeToString(expected).dropLast(1) + ",\"unexpected\":true}"),
            valid.copyOf(12 + 2),
            ByteArray(OverwriteBackupNames.MAX_HEADER_BYTES + 1),
        )
        for ((index, invalid) in cases.withIndex()) {
            assertTrue("header case $index", runCatching { OverwriteBackupBundle.readPrefix(invalid) }.isFailure)
        }
    }

    @Test fun headerHasExactlyBigEndianByteLengthAndAcceptsBoundedPrefixWithZipBytes() {
        val body = "some tiny ZIP".toByteArray(); val expected = point(body)
        val headerText = json.encodeToString(expected)
        val bytes = envelope(expected, body)
        val length = java.nio.ByteBuffer.wrap(bytes, 8, 4).int
        assertEquals(headerText.toByteArray().size, length)
        assertEquals(expected, OverwriteBackupBundle.readPrefix(bytes))
        assertEquals(expected, OverwriteBackupBundle.readPrefix(bytes.copyOf(12 + length)))
    }

    @Test fun sourceChangingOrOversizedPointCannotPublishAnyTemporary() = runBlocking {
        val source = archive("source changed")
        val expected = point("other content!".toByteArray())
        val destination = temporary.newFile()
        assertTrue(runCatching { OverwriteBackupBundle.write(source, destination, expected) }.isFailure)
        assertTrue(runCatching { OverwriteBackupBundle.write(source, destination,
            expected.copy(bytes = BackupRestorePoint.MAX_BYTES + 1)) }.isFailure)
        val empty = archive(""); val remote = Remote()
        assertTrue(runCatching { OverwriteBackup(remote, temporary.root).publish(empty, false) }.isFailure)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun namesRejectTraversalNonCanonicalUuidAndOtherFixedFiles() {
        val id = "1a234567-89ab-4cde-8fab-123456789abc"
        assertTrue(OverwriteBackupNames.isAllowed(OverwriteBackupNames.LATEST))
        assertTrue(OverwriteBackupNames.isTemporary(OverwriteBackupNames.temporary(id)))
        assertTrue(OverwriteBackupNames.isAllowed("orbis_v1_$id.payload"))
        assertTrue(OverwriteBackupNames.isAllowed("orbis_v1_$id.complete.json"))
        for (name in listOf("../${OverwriteBackupNames.LATEST}", "/${OverwriteBackupNames.LATEST}",
            "orbis_latest_v1.backup", "orbis_upload_${"-".repeat(36)}.tmp", "orbis_upload_$id.tmp/child",
            "https://example.invalid/${OverwriteBackupNames.LATEST}", "backup.zip", "orbis_upload_${id.uppercase()}.tmp")) {
            assertFalse(name, OverwriteBackupNames.isAllowed(name))
            assertTrue(runCatching { OverwriteBackupNames.requireAllowed(name) }.isFailure)
        }
    }

    @Test fun migrationOnlyRemovesVerifiedOldCompletePairsMarkerFirst() = runBlocking {
        val remote = Remote(); val old = legacy(remote, 1)
        val corrupted = legacy(remote, 2)
        remote.objects[corrupted.payloadName] = byteArrayOf(1)
        val newer = legacy(remote, 999)
        val unmarked = "orbis_v1_${UUID.randomUUID()}.payload"
        remote.objects[unmarked] = byteArrayOf(2)
        val inProgress = OverwriteBackupNames.temporary(UUID.randomUUID().toString())
        remote.objects[inProgress] = byteArrayOf(3)
        val unrelated = "personal-manual.zip"
        remote.objects[unrelated] = byteArrayOf(4)
        val store = OverwriteBackup(remote, temporary.root)
        val current = store.publish(archive(), false, 100)
        remote.deletes.clear()
        store.cleanupLegacyAfterPublished(current)
        assertEquals(listOf(old.markerName, old.payloadName), remote.deletes)
        assertTrue(listOf(corrupted.markerName, corrupted.payloadName, newer.markerName, newer.payloadName,
            unmarked, inProgress, unrelated, OverwriteBackupNames.LATEST).all { it in remote.objects })
        assertEquals(listOf(current), store.list())
    }

    @Test fun migrationNeverDeletesLegacyIfCurrentGenerationCannotBeVerified() = runBlocking {
        val remote = Remote(); val old = legacy(remote)
        val store = OverwriteBackup(remote, temporary.root)
        val current = store.publish(archive(), false, 100)
        remote.corruptLatestRead = true; remote.deletes.clear()
        assertTrue(runCatching { store.cleanupLegacyAfterPublished(current) }.isFailure)
        assertTrue(remote.deletes.isEmpty())
        assertTrue(old.markerName in remote.objects && old.payloadName in remote.objects)
    }

    @Test fun migrationWithNoLegacyDoesNotDownloadFixedObjectAgain() = runBlocking {
        val remote = Remote(); val store = OverwriteBackup(remote, temporary.root)
        val current = store.publish(archive(), false, 100)
        remote.downloads.clear()
        store.cleanupLegacyAfterPublished(current)
        assertTrue(remote.downloads.isEmpty())
    }

    @Test fun legacyProtocolDoesNotAcceptV2PointInV1MarkerOrDownload() = runBlocking {
        val remote = Remote(); val old = legacy(remote)
        remote.objects[old.markerName] = json.encodeToString(old.copy(version = 2)).toByteArray()
        val store = VersionedBackup(remote, temporary.root)
        assertTrue(store.list().isEmpty())
        assertTrue(runCatching { store.download(old.copy(version = 2), temporary.newFile()) }.isFailure)
        assertTrue(runCatching { store.pruneAfterPublished(old.copy(version = 2), 3) }.isFailure)
    }
}
