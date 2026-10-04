package me.rerere.rikkahub.data.recovery

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/** Only random synthetic subtrees under the isolated runner's files directory, never live data. */
@RunWith(AndroidJUnit4::class)
class EmergencyMountPointDeviceTest {
    private lateinit var directory: File
    private lateinit var fixtureParent: File
    private lateinit var filesRoot: File
    private lateinit var mount: File
    private lateinit var journals: File
    private val relative = "workspaces/ffe7c07b-c272-465b-a908-1e8df6545565/linux/workspace"

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        // Android cacheDir has an app-cache GID and setgid inheritance. A fixture there is
        // deliberately NOT eligible for the production same-UID/GID files-root whitelist.
        // Use a random child of this synthetic runner's filesDir instead; never weaken the
        // production ownership check merely to accommodate the wrong fixture parent.
        fixtureParent = instrumentation.targetContext.filesDir.canonicalFile
        check(fixtureParent.isDirectory || fixtureParent.mkdirs())
        val parentStat = Os.lstat(fixtureParent.path)
        check(parentStat.st_uid == Process.myUid() && parentStat.st_gid == Process.myUid())
        directory = Files.createTempDirectory(fixtureParent.toPath(), "emergency-mount-device-").toFile().canonicalFile
        check(directory.parentFile == fixtureParent)
        filesRoot = File(directory, "files").apply { mkdirs() }
        mount = File(filesRoot, relative).apply { mkdirs() }
        val mountStat = Os.lstat(mount.path)
        check(mountStat.st_uid == Process.myUid() && mountStat.st_gid == Process.myUid())
        File(mount, "retained.txt").writeText("synthetic mount data must not be skipped")
        journals = File(directory, "journals")
        Os.chmod(mount.path, 0)
    }

    private fun journal() = EmergencyFileMountPointJournal(journals, ::syncEmergencyMountJournalDirectory)

    private fun lease() = EmergencyMountPointLease(EmergencyAndroidMountPointAccess(filesRoot), journal()) {}

    @After fun tearDown() {
        Thread.interrupted()
        if (::directory.isInitialized) {
            check(directory.canonicalFile.parentFile == fixtureParent)
            check(directory.name.startsWith("emergency-mount-device-"))
            // Exact known synthetic inode paths only, so disposal does not leave inaccessible
            // fixture folders. The raced-path test uses the other fixed fixture basename.
            val targets = if (::mount.isInitialized) listOf(mount, File(mount.parentFile, "pinned-original")) else emptyList()
            for (target in targets) {
                if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    OsConstants.S_ISDIR(Os.lstat(target.path).st_mode)) Os.chmod(target.path, 448)
            }
            check(directory.deleteRecursively())
        }
    }

    @Test fun actualZeroModeDirectoryCopiesChildrenAndRestoresPermissionsBeforePublishing() {
        val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(directory, it)) }
        val output = File(directory, "complete.zip")
        val result = createEmergencyArchiveWithMountAccess(lease(), roots, output,
            EmergencyArchiveMetadata("synthetic.orbis", "fixture", 1))
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertTrue(result.files.any { it.path == "files/$relative/retained.txt" })
        assertEquals(result, EmergencyArchive.verify(output))
        assertNull(journal().read())
        assertTrue(journals.listFiles()!!.any { it.name.startsWith("observed-restored-") })
    }

    @Test fun hundredsOfLinuxColonNamesAndZeroModeMountRoundTripWithoutRenamingOrActivatingRuntime() {
        val database = File(directory, "databases/rikka_hub").apply { parentFile.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE fixture (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO fixture VALUES ('synthetic original')")
        }
        val settings = File(filesRoot, "datastore/settings.preferences_pb").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
        }
        val attachment = File(filesRoot, "upload/目录:01/附件:02.bin").apply {
            parentFile.mkdirs(); writeText("original colon attachment")
        }
        File(attachment.parentFile, "附件_02.bin").writeText("distinct underscore attachment")
        val dpkg = File(filesRoot, "workspaces/ffe7c07b-c272-465b-a908-1e8df6545565/linux/rootfs/var/lib/dpkg/info").apply { mkdirs() }
        repeat(274) { File(dpkg, "libsample$it:arm64.list").writeText("synthetic package $it") }
        // Only this isolated fixture is deliberately reopened to populate the closed directory.
        Os.chmod(mount.path, 448)
        try { File(mount, "保留:01.txt").writeText("closed mount colon bytes") }
        finally { Os.chmod(mount.path, 0) }
        val originalDatabase = database.readBytes()
        val originalSettings = settings.readBytes()
        val roots = EmergencyArchive.ROOT_NAMES.associateWith { File(directory, it) }
        val archive = File(directory, "colon-complete.zip")
        val result = createEmergencyArchiveWithMountAccess(lease(), roots.map { EmergencyArchiveRoot(it.key, it.value) }, archive,
            EmergencyArchiveMetadata("synthetic.orbis", "fixture", 1))
        assertEquals(2, result.schemaVersion)
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertEquals(274, result.files.count { it.path.endsWith(":arm64.list") })
        ZipFile(archive).use { zip -> assertTrue(zip.entries().asSequence().all {
            it.name == EmergencyArchive.MANIFEST_ENTRY || it.name.matches(Regex("payload/[0-9a-f]{64}\\.bin"))
        }) }
        assertEquals(result, EmergencyArchive.verify(archive))
        val isolated = File(directory, "isolated-extract")
        EmergencyArchive.extractVerified(archive, isolated)
        repeat(274) {
            val relativePath = "files/${dpkg.relativeTo(filesRoot).invariantSeparatorsPath}/libsample$it:arm64.list"
            assertEquals("synthetic package $it", File(isolated, relativePath).readText())
            assertEquals("synthetic package $it", File(dpkg, "libsample$it:arm64.list").readText())
        }
        assertEquals("closed mount colon bytes", File(isolated, "files/$relative/保留:01.txt").readText())
        val transaction = File(directory, "orbis-emergency/transactions/colon")
        EmergencyRestore.prepare(archive, transaction, "synthetic.orbis", 1, roots, {}, { prepared ->
            SQLiteDatabase.openDatabase(File(prepared, "databases/rikka_hub").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM fixture", null).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals("synthetic original", rows.getString(0))
                }
            }
            assertArrayEquals(originalSettings, File(prepared, "files/datastore/settings.preferences_pb").readBytes())
            assertEquals("original colon attachment", File(prepared, "files/upload/目录:01/附件:02.bin").readText())
            assertEquals("distinct underscore attachment", File(prepared, "files/upload/目录:01/附件_02.bin").readText())
            assertFalse(File(prepared, "files/workspaces").exists())
        })
        assertArrayEquals(originalDatabase, database.readBytes())
        assertArrayEquals(originalSettings, settings.readBytes())
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertEquals("original colon attachment", attachment.readText())
    }

    @Test fun actualCancellationClosesZeroModeAndDoesNotLeaveAnActiveIntent() {
        assertThrows(CancellationException::class.java) { lease().withReadAccess { throw CancellationException("synthetic cancel") } }
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertNull(journal().read())
    }

    @Test fun kernelDescriptorPinsOriginalWhenDirectoryPathIsReplaced() {
        val access = EmergencyAndroidMountPointAccess(filesRoot)
        val original = access.metadata(relative)!!
        access.pin(relative, original).use { pinned ->
            val moved = File(mount.parentFile, "pinned-original")
            check(mount.renameTo(moved))
            check(mount.mkdir())
            Os.chmod(mount.path, 448)
            pinned.setMode(320)
            assertEquals(448, Os.lstat(mount.path).st_mode and 0xfff)
            assertEquals(320, Os.lstat(moved.path).st_mode and 0xfff)
            pinned.setMode(0)
            assertEquals(0, Os.lstat(moved.path).st_mode and 0xfff)
            assertEquals(448, Os.lstat(mount.path).st_mode and 0xfff)
        }
    }

    @Test fun actualRestartClosesAnInterruptedGrantAndRetiresJournal() {
        val access = EmergencyAndroidMountPointAccess(filesRoot)
        val original = access.metadata(relative)!!
        journal().create(EmergencyMountJournalValue(sourceRoot = access.metadata("")!!,
            records = listOf(EmergencyMountRecord(relative, original))))
        // Simulate a process being lost after the durable intent and grant, without killing
        // any process. Close only this fixture's descriptor; startup recovery uses a new one.
        access.pin(relative, original).use { it.setMode(320) }
        assertEquals(320, Os.lstat(mount.path).st_mode and 0xfff)
        lease().recoverInterrupted()
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertNull(journal().read())
    }

    @Test fun alreadyZeroAfterRestartNeverReopensAndDoesNotTrapRecovery() {
        val access = EmergencyAndroidMountPointAccess(filesRoot)
        journal().create(EmergencyMountJournalValue(sourceRoot = access.metadata("")!!,
            records = listOf(EmergencyMountRecord(relative, access.metadata(relative)!!))))
        lease().recoverInterrupted()
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
        assertNull(journal().read())
        // A subsequent ordinary lease must be possible, without altering any original data.
        lease().withReadAccess { assertEquals("synthetic mount data must not be skipped", File(mount, "retained.txt").readText()) }
        assertEquals(0, Os.lstat(mount.path).st_mode and 0xfff)
    }
}
