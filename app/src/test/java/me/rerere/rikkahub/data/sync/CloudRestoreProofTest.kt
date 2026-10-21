package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudRestoreProofTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "restore")
    private val database get() = File(temporary.root, "databases/rikka_hub")
    private val files get() = File(temporary.root, "files")
    private val key = "webdav-" + "a".repeat(64)
    private val otherKey = "s3-" + "b".repeat(64)
    private fun source(destination: String = key, backupVersion: Int = 1) = VerifiedCloudRestoreSource(destination,
        BackupRestorePoint(version = backupVersion, id = UUID.randomUUID().toString(), createdAt = 1, bytes = 100,
            sha256 = "c".repeat(64)))
    private fun restore() = PendingRestore(root, database, files)
    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }
    private fun prepare(proof: VerifiedCloudRestoreSource? = source()): Pair<PendingRestore, File> {
        val pending = restore()
        val staging = pending.createStagingDirectory()
        write(File(staging, "payload/database/rikka_hub"), "restored database")
        write(File(staging, "settings.json"), "restored settings")
        write(File(staging, "replace-local-tools"), "explicit-version-restore-v1")
        CloudRestoreProof.stage(staging, proof)
        return pending to staging
    }

    @Test fun stagedOrPublishedButNotAppliedDoesNotAuthorizeUploading() = runBlocking {
        val (pending, staging) = prepare()
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("must not acknowledge staged") })
        pending.publish(staging)
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("must not acknowledge pending") })
        assertFalse(database.exists())
        pending.apply { }
        assertTrue(CloudRestoreProof.consumeApplied(root, key) { })
    }

    @Test fun appliedRestoreOnlyClearsMatchingReviewOnceWithoutFabricatingAnUpload() = runBlocking {
        val (pending, staging) = prepare()
        pending.publish(staging)
        pending.apply { }
        assertFalse(CloudRestoreProof.consumeApplied(root, otherKey) { error("wrong server") })
        var ledger = BackupChangeLedger().failed(key, "old versions", true).failed(otherKey, "other", true)
        var calls = 0
        assertTrue(CloudRestoreProof.consumeApplied(root, key) { calls++; ledger = ledger.allowExisting(key) })
        assertEquals(1, calls)
        assertFalse(ledger.targets.getValue(key).needsReview)
        assertEquals(0L, ledger.targets.getValue(key).completedAt)
        assertTrue(ledger.pending(key))
        assertTrue(ledger.targets.getValue(otherKey).needsReview)
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { calls++ })
        assertEquals(1, calls)
        assertEquals("consumed", File(root, "expected-cloud-restore").readText())
    }

    @Test fun overwriteBackupProofRequiresAppliedRestoreAndOnlyAcknowledgesOnce() = runBlocking {
        val (pending, staging) = prepare(source(backupVersion = 2))
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("not applied") })
        pending.publish(staging)
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("pending only") })
        pending.apply { }
        assertFalse(CloudRestoreProof.consumeApplied(root, otherKey) { error("wrong destination") })
        var calls = 0
        assertTrue(CloudRestoreProof.consumeApplied(root, key) { calls++ })
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun overwriteBackupProofStillRejectsPartialOrUnsupportedBackupMetadata() {
        for (invalid in listOf(source(backupVersion = 2).let { it.copy(point = it.point.copy(includesFiles = false)) },
            source(backupVersion = 3))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
        assertFalse(File(root, "expected-cloud-restore").exists())
    }

    @Test fun aLaterLocalRestoreInvalidatesAnAppliedOverwriteBackupProof() = runBlocking {
        val (cloud, cloudStaging) = prepare(source(backupVersion = 2))
        cloud.publish(cloudStaging)
        cloud.apply { }
        val (local, localStaging) = prepare(null)
        local.publish(localStaging)
        local.apply { }
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("superseded cloud proof") })
    }

    @Test fun rollbackAfterSettingsFailureDoesNotAcknowledgeCloudHistory() = runBlocking {
        write(database, "original")
        val (pending, staging) = prepare()
        pending.publish(staging)
        try {
            pending.apply { throw IOException("synthetic failure") }
            fail("restore should fail")
        } catch (_: RestoreFailedException) { }
        assertEquals("original", database.readText())
        assertTrue(root.listFiles()!!.any { it.name.startsWith("failed-") })
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("failed restore") })
    }

    @Test fun failedProofWriteCannotPublishOrCreateAnAcknowledgement() {
        val pending = restore()
        val staging = pending.createStagingDirectory()
        write(File(staging, "replace-local-tools"), "explicit-version-restore-v1")
        // Atomic-file temporary output cannot replace a directory.
        assertTrue(File(staging, "verified-cloud-source.json.tmp").mkdir())
        assertThrows(IOException::class.java) { CloudRestoreProof.stage(staging, source()) }
        assertFalse(File(root, "pending").exists())
        assertFalse(File(root, "expected-cloud-restore").exists())
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("failed staging") })
    }

    @Test fun laterLocalRestoreInvalidatesUnconsumedHistoricalCloudProof() = runBlocking {
        val (first, oldStaging) = prepare()
        first.publish(oldStaging)
        first.apply { }
        val (local, localStaging) = prepare(null)
        assertEquals("none", File(root, "expected-cloud-restore").readText())
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("superseded") })
        local.publish(localStaging)
        local.apply { }
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("old cloud restore") })
    }

    @Test fun newerCloudEpochDoesNotUnlockAnOlderAppliedProofBeforeOrAfterNewApply() = runBlocking {
        val (first, oldStaging) = prepare()
        first.publish(oldStaging)
        first.apply { }
        val (second, newStaging) = prepare(source(otherKey))
        second.publish(newStaging)
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("old epoch") })
        assertFalse(CloudRestoreProof.consumeApplied(root, otherKey) { error("not applied yet") })
        second.apply { }
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("wrong committed source") })
        assertTrue(CloudRestoreProof.consumeApplied(root, otherKey) { })
    }

    @Test fun failedAcknowledgementCanRetryButCannotAcknowledgeAnotherDestination() = runBlocking {
        val (pending, staging) = prepare()
        pending.publish(staging)
        pending.apply { }
        assertThrows(IOException::class.java) {
            CloudRestoreProof.consumeApplied(root, key) { throw IOException("synthetic persist failure") }
        }
        assertFalse(CloudRestoreProof.consumeApplied(root, otherKey) { error("wrong server") })
        assertTrue(CloudRestoreProof.consumeApplied(root, key) { })
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("already consumed") })
    }

    @Test fun archivePayloadAndForgedStagingMetadataCannotSupplyTheHostEpoch() = runBlocking {
        val (first, firstStaging) = prepare()
        val forgedProof = File(firstStaging, "verified-cloud-source.json").readText()
        val forgedEpoch = File(root, "expected-cloud-restore").readText()
        // Simulate arbitrary ZIP contents being materialized as allowed attachment files:
        // these never share the noBackup/backup-restore root or its host-generated pointer.
        File(firstStaging, "verified-cloud-source.json").delete()
        CloudRestoreProof.stage(firstStaging, null)
        write(File(firstStaging, "payload/files/upload/verified-cloud-source.json"), forgedProof)
        write(File(firstStaging, "payload/files/upload/expected-cloud-restore"), forgedEpoch)
        // Even an additional forged top-level marker lacks a live host epoch.
        write(File(firstStaging, "verified-cloud-source.json"), forgedProof)
        first.publish(firstStaging)
        first.apply { }
        assertTrue(File(files, "upload/verified-cloud-source.json").isFile)
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("archive supplied proof") })
    }

    @Test fun malformedProofOrNonCommittedDirectoryNeverAuthorizes() = runBlocking {
        val (pending, staging) = prepare()
        pending.publish(staging)
        pending.apply { }
        val previous = root.listFiles()!!.single { it.name.startsWith("previous-") }
        File(previous, "verified-cloud-source.json").writeText("private-key-sentinel invalid JSON")
        assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("malformed") })
        assertThrows(IllegalArgumentException::class.java) {
            source("https://private.invalid/secret").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            source().copy(point = source().point.copy(includesFiles = false)).validate()
        }
        val nonStaging = File(temporary.root, "not-staging").also { it.mkdir() }
        assertThrows(IllegalArgumentException::class.java) { CloudRestoreProof.stage(nonStaging, source()) }
        Unit
    }
}
