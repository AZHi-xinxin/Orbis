package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EmergencyRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val roots: Map<String, File>, val archive: File, val transaction: File)

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }

    private fun fixture(packageName: String = "orbis.test", version: Long = 250, missingSettings: Boolean = false,
        beforeArchive: (Map<String, File>) -> Unit = {},
    ): Fixture {
        val app = temporary.newFolder()
        val roots = EmergencyRestore.rootNames.associateWith { File(app, it).apply { mkdirs() } }
        write(File(roots.getValue("databases"), "rikka_hub"), "backup database")
        write(File(roots.getValue("databases"), "rikka_hub-wal"), "backup wal")
        write(File(roots.getValue("databases"), "rikka_hub-shm"), "unsafe shared memory")
        write(File(roots.getValue("databases"), "androidx.work.workdb"), "pending worker")
        if (!missingSettings) write(File(roots.getValue("files"), "datastore/settings.preferences_pb"), "backup settings")
        write(File(roots.getValue("files"), "upload/photo.jpg"), "backup attachment")
        write(File(roots.getValue("files"), "orbis-gallery/assistant/index.json"), "gallery")
        write(File(roots.getValue("files"), "orbis-voice-calls/call.json"), "voice history")
        write(File(roots.getValue("files"), "unrecognized/runtime.json"), "unrecognized")
        write(File(roots.getValue("no_backup"), "backup-restore/pending/journal.json"), "must not run")
        write(File(roots.getValue("no_backup"), "orbis-tool-approvals-v1.json"), "approvals")
        write(File(roots.getValue("shared_prefs"), "runtime.xml"), "old flags")
        beforeArchive(roots)
        val archive = File(temporary.root, "archive-${app.name}.zip")
        EmergencyArchive.create(roots.map { EmergencyArchiveRoot(it.key, it.value) }, archive,
            EmergencyArchiveMetadata(packageName, "2.6.3", version))
        roots.forEach { (name, root) -> write(File(root, "current-only.txt"), "current $name") }
        write(File(roots.getValue("databases"), "rikka_hub"), "current database")
        return Fixture(roots, archive, File(app, "orbis-emergency/transactions/new"))
    }

    private fun prepare(fixture: Fixture, validator: (File) -> Unit = {}): EmergencyRestore.Journal =
        EmergencyRestore.prepare(fixture.archive, fixture.transaction, "orbis.test", 250,
            fixture.roots, {}, validator)

    @Test fun prepareNeverTouchesLiveAndRetainsEntireOriginalArchivePayload() {
        val f = fixture()
        val plan = prepare(f) {
            assertEquals("backup database", File(it, "databases/rikka_hub").readText())
            assertEquals("backup wal", File(it, "databases/rikka_hub-wal").readText())
            assertFalse(File(it, "databases/rikka_hub-shm").exists())
        }
        assertEquals("PREPARED", plan.status)
        assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        assertTrue(File(f.transaction, "raw/no_backup/backup-restore/pending/journal.json").isFile)
        assertTrue(plan.retainedFiles >= 5)
    }

    @Test fun restoresOnlySafeLibrariesAndPreservesEveryPreviousRoot() {
        val f = fixture()
        prepare(f)
        val journal = EmergencyRestore.commit(f.transaction, f.roots, {})
        assertEquals("COMMITTED", journal.status)
        assertEquals("backup database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        assertFalse(File(f.roots.getValue("databases"), "androidx.work.workdb").exists())
        assertFalse(File(f.roots.getValue("files"), "unrecognized/runtime.json").exists())
        assertTrue(File(f.roots.getValue("files"), "orbis-gallery/assistant/index.json").isFile)
        assertTrue(File(f.roots.getValue("files"), "orbis-voice-calls/call.json").isFile)
        assertTrue(f.roots.getValue("no_backup").listFiles()!!.isEmpty())
        assertTrue(f.roots.getValue("shared_prefs").listFiles()!!.isEmpty())
        f.roots.keys.forEach { name ->
            assertEquals("current $name", File(f.transaction, "originals/$name/current-only.txt").readText())
        }
        assertEquals("current database", File(f.transaction, "originals/databases/rikka_hub").readText())
    }

    @Test fun committedRestoreCannotReplayOrRollbackLaterUserChanges() {
        val f = fixture()
        prepare(f)
        EmergencyRestore.commit(f.transaction, f.roots, {})
        val database = File(f.roots.getValue("databases"), "rikka_hub")
        database.writeText("new user messages")
        EmergencyRestore.commit(f.transaction, f.roots, {})
        assertEquals("new user messages", database.readText())
        assertThrows(IllegalArgumentException::class.java) { EmergencyRestore.rollback(f.transaction, f.roots, {}) }
        assertEquals("new user messages", database.readText())
    }

    @Test fun eachInterruptedRenameCanBeExplicitlyResumed() {
        for (point in 1..8) {
            val f = fixture()
            prepare(f)
            var moves = 0
            assertThrows(IOException::class.java) {
                EmergencyRestore.commit(f.transaction, f.roots, {}, { if (++moves == point) throw IOException("power cut") })
            }
            assertEquals("INSTALLING", EmergencyRestore.readJournal(f.transaction).status)
            EmergencyRestore.commit(f.transaction, f.roots, {})
            assertEquals("backup database", File(f.roots.getValue("databases"), "rikka_hub").readText())
            assertEquals("current database", File(f.transaction, "originals/databases/rikka_hub").readText())
        }
    }

    @Test fun eachInterruptedRenameCanBeRolledBackWithoutDeletingIncomingData() {
        for (point in 1..8) {
            val f = fixture()
            prepare(f)
            var moves = 0
            assertThrows(IOException::class.java) {
                EmergencyRestore.commit(f.transaction, f.roots, {}, { if (++moves == point) throw IOException("power cut") })
            }
            EmergencyRestore.rollback(f.transaction, f.roots, {})
            EmergencyRestore.rollback(f.transaction, f.roots, {})
            assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
            assertEquals("backup database", File(f.transaction, "prepared/databases/rikka_hub").readText())
            f.roots.keys.forEach { name -> assertTrue(File(f.roots.getValue(name), "current-only.txt").isFile) }
        }
    }

    @Test fun interruptedRollbackIsResumable() {
        val f = fixture()
        prepare(f)
        assertThrows(IOException::class.java) {
            EmergencyRestore.commit(f.transaction, f.roots, {}, { if (it == "install:shared_prefs") throw IOException() })
        }
        assertThrows(IOException::class.java) {
            EmergencyRestore.rollback(f.transaction, f.roots, {}, { if (it == "restore:shared_prefs") throw IOException() })
        }
        EmergencyRestore.rollback(f.transaction, f.roots, {})
        assertEquals("ROLLED_BACK", EmergencyRestore.readJournal(f.transaction).status)
        assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
    }

    @Test fun validationFailureLeavesAllLiveRootsAndRawEvidenceUntouched() {
        val f = fixture()
        assertThrows(IllegalStateException::class.java) { prepare(f) { error("corrupt staged database") } }
        assertFalse(File(f.transaction, EmergencyRestore.JOURNAL).exists())
        assertTrue(File(f.transaction, "raw/databases/rikka_hub").isFile)
        assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
    }

    @Test fun wrongPackageNewerVersionAndMissingRequiredFilesAreRejected() {
        listOf(fixture(packageName = "another.app"), fixture(version = 251), fixture(missingSettings = true)).forEach { f ->
            assertThrows(IllegalArgumentException::class.java) { prepare(f) }
            assertFalse(File(f.transaction, EmergencyRestore.JOURNAL).exists())
            assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        }
    }

    @Test fun cannotSilentlyMigrateAbsoluteAttachmentPathsToDifferentProfile() {
        val f = fixture()
        val other = temporary.newFolder()
        val roots = EmergencyRestore.rootNames.associateWith { File(other, it) }
        assertThrows(IllegalArgumentException::class.java) {
            EmergencyRestore.prepare(f.archive, File(other, "restore/new"), "orbis.test", 250, roots, {}, {})
        }
        assertFalse(roots.getValue("databases").exists())
    }

    @Test fun processFenceFailurePreventsFirstLiveRename() {
        val f = fixture()
        prepare(f)
        assertThrows(IllegalStateException::class.java) { EmergencyRestore.commit(f.transaction, f.roots, { error("writer active") }) }
        assertEquals("PREPARED", EmergencyRestore.readJournal(f.transaction).status)
        assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
    }

    @Test fun freshInstallWithAbsentRootsCanCommitAndInterruptedInstallCanRollback() {
        val fresh = fixture()
        fresh.roots.values.forEach { assertTrue(it.deleteRecursively()) }
        prepare(fresh)
        EmergencyRestore.commit(fresh.transaction, fresh.roots, {})
        assertEquals("backup database", File(fresh.roots.getValue("databases"), "rikka_hub").readText())
        assertFalse(File(fresh.transaction, "originals").exists())

        val interrupted = fixture()
        interrupted.roots.values.forEach { assertTrue(it.deleteRecursively()) }
        prepare(interrupted)
        assertThrows(IOException::class.java) {
            EmergencyRestore.commit(interrupted.transaction, interrupted.roots, {}, {
                if (it == "install:files") throw IOException("interruption")
            })
        }
        EmergencyRestore.rollback(interrupted.transaction, interrupted.roots, {})
        interrupted.roots.values.forEach { assertFalse(it.exists()) }
        assertEquals("backup database", File(interrupted.transaction, "prepared/databases/rikka_hub").readText())
    }

    @Test fun noQueueOrRuntimeNamespaceCanPassRestoreSelection() {
        listOf("no_backup/orbis-event-inbox-v1.json", "no_backup/backup-restore/pending/journal.json",
            "shared_prefs/anything.xml", "databases/androidx.work.workdb", "databases/rikka_hub-shm",
            "files/datastore/settings.preferences_pb.tmp", "files/upload/../../escape", "files/upload\\escape")
            .forEach { assertFalse(it, EmergencyRestore.isRestoredPath(it)) }
    }

    @Test fun privateVaultSelectionOnlyAllowsCipherRecordsAndPointersNeverRecoveryCodesOrGrants() {
        val owner = "a".repeat(64)
        val generation = "bb03122d-adff-4f64-846e-bb83e0242dbd"
        val record = "dd03122d-adff-4f64-846e-bb83e0242dbd"
        val base = "no_backup/orbis-private-vaults/$owner"
        listOf("$base/CURRENT", "$base/generations/$generation/HEAD",
            "$base/generations/$generation/states/$record.vault",
            "$base/generations/$generation/records/$record.bin")
            .forEach { assertTrue(it, EmergencyRestore.isRestoredPath(it)) }
        listOf("$base/recovery-code.txt", "$base/device-key.bin", "$base/grants.json",
            "$base/recovery-required.marker", "$base/generations/$generation/states/../$record.vault",
            "$base/generations/$generation/records/$record.bin.tmp", "$base/generations/$generation/run.sh",
            "no_backup/orbis-private-vaults/not-an-owner/CURRENT", "no_backup/orbis-private-vaults/${owner.uppercase()}/CURRENT",
            "$base/generations/not-a-generation/HEAD")
            .forEach { assertFalse(it, EmergencyRestore.isRestoredPath(it)) }
    }

    @Test fun malformedPrivateVaultStaysInRawEvidenceWithoutBlockingValidChatRestore() {
        val owner = "a".repeat(64)
        val generation = "bb03122d-adff-4f64-846e-bb83e0242dbd"
        val state = "dd03122d-adff-4f64-846e-bb83e0242dbd"
        val f = fixture { roots ->
            val vault = File(roots.getValue("no_backup"), "orbis-private-vaults/$owner")
            write(File(vault, "CURRENT"), generation)
            write(File(vault, "generations/$generation/HEAD"), state)
            write(File(vault, "generations/$generation/states/$state.vault"), "not a valid cipher envelope")
        }
        val plan = prepare(f)
        assertEquals(0, plan.restoredPrivateVaults)
        assertEquals(1, plan.retainedPrivateVaults)
        assertFalse(File(f.transaction, "prepared/no_backup/orbis-private-vaults").exists())
        assertEquals("not a valid cipher envelope", File(f.transaction,
            "raw/no_backup/orbis-private-vaults/$owner/generations/$generation/states/$state.vault").readText())
        EmergencyRestore.commit(f.transaction, f.roots, {})
        assertEquals("backup database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        assertTrue(f.roots.getValue("no_backup").listFiles()!!.isEmpty())
        assertTrue(File(f.transaction, "originals/no_backup/orbis-private-vaults/$owner/CURRENT").isFile)
    }

    @Test fun uninitializedPrivateVaultDirectoriesDoNotBlockChatOrClaimRestorableRooms() {
        val emptyOwner = "a".repeat(64)
        val lockOwner = "b".repeat(64)
        val f = fixture { roots ->
            File(roots.getValue("no_backup"), "orbis-private-vaults/$emptyOwner").mkdirs()
            write(File(roots.getValue("no_backup"), "orbis-private-vaults/$lockOwner/.lock"), "")
        }
        val plan = prepare(f)
        assertEquals(0, plan.restoredPrivateVaults)
        assertEquals(0, plan.retainedPrivateVaults)
        assertTrue(File(f.transaction, "raw/no_backup/orbis-private-vaults/$emptyOwner").isDirectory)
        assertTrue(File(f.transaction, "raw/no_backup/orbis-private-vaults/$lockOwner/.lock").isFile)
        assertFalse(File(f.transaction, "prepared/no_backup/orbis-private-vaults").exists())
        EmergencyRestore.commit(f.transaction, f.roots, {})
        assertEquals("backup database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        assertTrue(File(f.transaction, "originals/no_backup/orbis-private-vaults/$lockOwner/.lock").isFile)
    }

    @Test fun missingCurrentWithActualPayloadIsRetainedAsUnrecoverableRatherThanAnEmptyRoom() {
        val owner = "c".repeat(64)
        val f = fixture { roots ->
            write(File(roots.getValue("no_backup"), "orbis-private-vaults/$owner/orphan-cipher.bin"), "synthetic ciphertext")
        }
        val plan = prepare(f)
        assertEquals(0, plan.restoredPrivateVaults)
        assertEquals(1, plan.retainedPrivateVaults)
        assertEquals("synthetic ciphertext", File(f.transaction,
            "raw/no_backup/orbis-private-vaults/$owner/orphan-cipher.bin").readText())
    }

    @Test fun deeplyNestedRestoreJournalCannotMoveLiveDataOrOverflowTheStack() {
        val f = fixture()
        prepare(f)
        val journal = File(f.transaction, EmergencyRestore.JOURNAL)
        journal.writeText("{\"selectedFiles\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}")
        assertThrows(IOException::class.java) { EmergencyRestore.commit(f.transaction, f.roots, {}) }
        assertEquals("current database", File(f.roots.getValue("databases"), "rikka_hub").readText())
        assertFalse(File(f.transaction, "originals").exists())
        assertTrue(File(f.transaction, "raw/databases/rikka_hub").isFile)
    }
}
