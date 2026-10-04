package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmergencyMountPointLeaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val prefix = "workspaces/ffe7c07b-c272-465b-a908-1e8df6545565/linux"
    private val workspace = "$prefix/workspace"
    private val skills = "$prefix/skills"

    private class Node(var value: EmergencyMountIdentity)
    private inner class Access : EmergencyMountPointAccess {
        override val ownerUid = 10476
        val nodes = linkedMapOf<String, Node>()
        val changes = mutableListOf<Pair<Long, Int>>()
        var beforePin: ((String) -> Unit)? = null
        var beforeSet: ((String, Int) -> Unit)? = null
        var closed = 0
        fun directory(path: String, mode: Int = 448, inode: Long = nodes.size + 1L): Node =
            Node(EmergencyMountIdentity(65, inode, ownerUid, ownerUid, mode, true)).also { nodes[path] = it }
        init {
            listOf("", "workspaces", prefix.substringBeforeLast('/'), prefix).forEach { directory(it) }
            directory(workspace, 0)
            directory(skills, 0)
        }
        override fun metadata(path: String) = nodes[path]?.value
        override fun children(path: String) = nodes.keys.filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }.distinct()
        override fun pin(path: String, expected: EmergencyMountIdentity): EmergencyMountPointAccess.Pinned {
            beforePin?.invoke(path)
            val pinned = nodes[path] ?: throw IOException("missing")
            check(pinned.value == expected)
            return object : EmergencyMountPointAccess.Pinned {
                override fun identity() = pinned.value
                override fun setMode(mode: Int) {
                    beforeSet?.invoke(path, mode)
                    changes += pinned.value.inode to mode
                    pinned.value = pinned.value.copy(mode = mode)
                }
                override fun close() { closed++ }
            }
        }
    }

    private class Journal : EmergencyMountPointJournal {
        var pending: EmergencyMountJournalValue? = null
        var failCreate = false
        var failClear = false
        override fun read() = pending
        override fun create(value: EmergencyMountJournalValue) {
            if (failCreate) throw IOException("synthetic_journal_full")
            check(pending == null)
            pending = value
        }
        override fun clear(expected: EmergencyMountJournalValue) {
            if (failClear) throw IOException("synthetic_fsync_failure")
            check(pending == expected)
            pending = null
        }
    }

    @After fun clearInterrupt() { Thread.interrupted() }

    @Test fun onlyTwoWhitelistedZeroModeDirectoriesReceiveReadOnlyAccessThenRestore() {
        val access = Access()
        val journal = Journal()
        access.directory("ordinary-private", 0)
        access.directory("$prefix/bin", 0)
        val result = EmergencyMountPointLease(access, journal) {}.withReadAccess {
            assertNotNull(journal.pending)
            assertEquals(320, access.metadata(workspace)!!.mode)
            assertEquals(320, access.metadata(skills)!!.mode)
            assertEquals(0, access.metadata("ordinary-private")!!.mode)
            assertEquals(0, access.metadata("$prefix/bin")!!.mode)
            "copied"
        }
        assertEquals("copied", result)
        assertEquals(listOf(320, 320, 0, 0), access.changes.map { it.second })
        assertNull(journal.pending)
        assertEquals(2, access.closed)
    }

    @Test fun noJournalDurabilityMeansNoPermissionChanges() {
        val access = Access()
        val journal = Journal().apply { failCreate = true }
        assertThrows(IOException::class.java) { EmergencyMountPointLease(access, journal) {}.withReadAccess { fail("not reached") } }
        assertTrue(access.changes.isEmpty())
        assertEquals(0, access.metadata(workspace)!!.mode)
    }

    @Test fun cancellationRestoresBothDirectoriesAndKeepsOriginalException() {
        val access = Access()
        val journal = Journal()
        assertThrows(CancellationException::class.java) {
            EmergencyMountPointLease(access, journal) {}.withReadAccess { throw CancellationException("synthetic cancel") }
        }
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertEquals(0, access.metadata(skills)!!.mode)
        assertNull(journal.pending)
    }

    @Test fun threadInterruptionStillRestoresPermissionsAndPreservesInterruptFlag() {
        val access = Access()
        val journal = Journal()
        assertThrows(InterruptedIOException::class.java) {
            EmergencyMountPointLease(access, journal) {}.withReadAccess { Thread.currentThread().interrupt() }
        }
        assertTrue(Thread.currentThread().isInterrupted)
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertNull(journal.pending)
    }

    @Test fun writerAppearingDuringCopyDoesNotPreventClosingPermissions() {
        val access = Access()
        val journal = Journal()
        var paused = true
        val lease = EmergencyMountPointLease(access, journal) { check(paused) }
        assertThrows(IllegalStateException::class.java) { lease.withReadAccess { paused = false } }
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertEquals(0, access.metadata(skills)!!.mode)
        assertNull(journal.pending)
    }

    @Test fun restoreFailureKeepsIntentAndAttemptsOtherTargetThenNextEntryRecovers() {
        val access = Access()
        val journal = Journal()
        access.beforeSet = { path, mode -> if (path == workspace && mode == 0) throw IOException("synthetic restore failure") }
        val failure = assertThrows(IOException::class.java) {
            EmergencyMountPointLease(access, journal) {}.withReadAccess { "archive completed" }
        }
        assertEquals("emergency_mount_restore_required", failure.message)
        assertEquals(320, access.metadata(workspace)!!.mode)
        assertEquals(0, access.metadata(skills)!!.mode)
        assertNotNull(journal.pending)
        access.beforeSet = null
        // Restart recovery must not rely on the writer check, nor grant any new read access.
        EmergencyMountPointLease(access, journal) { fail("restore must not grant") }.recoverInterrupted()
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertNull(journal.pending)
    }

    @Test fun crashAfterDurableIntentBeforeAnyChangeIsSafeToRecover() {
        val access = Access()
        val journal = Journal().apply {
            pending = EmergencyMountJournalValue(sourceRoot = access.metadata("")!!,
                records = listOf(workspace, skills).map { EmergencyMountRecord(it, access.metadata(it)!!) })
        }
        EmergencyMountPointLease(access, journal) {}.recoverInterrupted()
        assertTrue(access.changes.isEmpty())
        assertNull(journal.pending)
    }

    @Test fun targetReplacedBeforePinNeverChangesReplacement() {
        val access = Access()
        val journal = Journal()
        access.beforePin = { path -> if (path == workspace) access.directory(path, 0, inode = 999) }
        assertThrows(IllegalStateException::class.java) { EmergencyMountPointLease(access, journal) {}.withReadAccess { fail() } }
        assertTrue(access.changes.isEmpty())
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertNull(journal.pending)
    }

    @Test fun targetReplacedDuringCopyClosesPinnedOriginalNotReplacementAndBlocksSuccess() {
        val access = Access()
        val journal = Journal()
        val original = access.nodes.getValue(workspace)
        val failure = assertThrows(IOException::class.java) {
            EmergencyMountPointLease(access, journal) {}.withReadAccess { access.directory(workspace, 448, 999) }
        }
        assertEquals("emergency_mount_restore_required", failure.message)
        assertEquals(0, original.value.mode)
        assertEquals(448, access.metadata(workspace)!!.mode)
        assertFalse(access.changes.any { it.first == 999L })
        assertNotNull(journal.pending)
    }

    @Test fun symlinkAndForeignOwnerAreNeverChmoded() {
        val access = Access()
        val journal = Journal()
        access.nodes.getValue(workspace).value = access.metadata(workspace)!!.copy(directory = false)
        EmergencyMountPointLease(access, journal) {}.withReadAccess { Unit }
        assertFalse(access.changes.any { it.first == access.metadata(workspace)!!.inode })
        access.nodes.getValue(skills).value = access.metadata(skills)!!.copy(uid = 999)
        access.changes.clear()
        assertThrows(IOException::class.java) { EmergencyMountPointLease(access, journal) {}.withReadAccess { fail() } }
        assertTrue(access.changes.isEmpty())
    }

    @Test fun nonZeroRestrictedModeIsNotWidened() {
        val access = Access()
        access.nodes.getValue(workspace).value = access.metadata(workspace)!!.copy(mode = 256)
        val journal = Journal()
        EmergencyMountPointLease(access, journal) {}.withReadAccess { assertEquals(256, access.metadata(workspace)!!.mode) }
        assertEquals(256, access.metadata(workspace)!!.mode)
    }

    @Test fun unrecognizedOrTraversalJournalCannotAuthorizeChmod() {
        listOf("../outside", "$prefix/bin", "workspaces/not-a-uuid/linux/workspace", "workspaces/abc/../linux/skills").forEach { path ->
            val access = Access()
            val journal = Journal().apply {
                pending = EmergencyMountJournalValue(sourceRoot = access.metadata("")!!,
                    records = listOf(EmergencyMountRecord(path, access.metadata(workspace)!!)))
            }
            assertThrows(IOException::class.java) { EmergencyMountPointLease(access, journal) {}.recoverInterrupted() }
            assertTrue(access.changes.isEmpty())
            assertNotNull(journal.pending)
        }
    }

    @Test fun recoveryRejectsDifferentRootOrTargetIdentity() {
        for (replaceRoot in listOf(true, false)) {
            val access = Access()
            val journal = Journal().apply {
                pending = EmergencyMountJournalValue(sourceRoot = access.metadata("")!!,
                    records = listOf(EmergencyMountRecord(workspace, access.metadata(workspace)!!)))
            }
            access.directory(if (replaceRoot) "" else workspace, 320, 999)
            assertThrows(IOException::class.java) { EmergencyMountPointLease(access, journal) {}.recoverInterrupted() }
            assertTrue(access.changes.isEmpty())
        }
    }

    @Test fun failedJournalClearDoesNotClaimSuccessAndCanBeRetriedWithoutRegranting() {
        val access = Access()
        val journal = Journal().apply { failClear = true }
        assertThrows(IOException::class.java) { EmergencyMountPointLease(access, journal) {}.withReadAccess { Unit } }
        assertEquals(0, access.metadata(workspace)!!.mode)
        assertNotNull(journal.pending)
        access.changes.clear()
        journal.failClear = false
        EmergencyMountPointLease(access, journal) {}.recoverInterrupted()
        assertTrue(access.changes.isEmpty())
        assertNull(journal.pending)
    }

    @Test fun archiveIsNotPublishedAsResumableZipUntilPermissionRestoreSucceeds() {
        val access = Access()
        val journal = Journal()
        val source = temporary.newFolder("source")
        File(source, "files").mkdirs()
        File(source, "files/content").writeText("synthetic only")
        val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(source, it)) }
        access.beforeSet = { _, mode -> if (mode == 0) throw IOException("synthetic cleanup failure") }
        val output = File(temporary.root, "published.zip")
        assertThrows(IOException::class.java) {
            createEmergencyArchiveWithMountAccess(EmergencyMountPointLease(access, journal) {}, roots, output,
                EmergencyArchiveMetadata("test.orbis", "test", 1))
        }
        assertFalse(output.exists())
        val pending = temporary.root.listFiles()!!.single { it.name.startsWith(".orbis-permission-pending-") }
        assertFalse(pending.name.endsWith(".zip"))
        assertEquals(1, EmergencyArchive.verify(pending).files.size)
    }
}
