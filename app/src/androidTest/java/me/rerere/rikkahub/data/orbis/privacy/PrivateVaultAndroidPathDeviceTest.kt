package me.rerere.rikkahub.data.orbis.privacy

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Synthetic paths only. No models, real rooms or recovery codes are involved. */
@RunWith(AndroidJUnit4::class)
class PrivateVaultAndroidPathDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val links = mutableListOf<File>()

    @Before fun isolatedPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        context = instrumentation.targetContext.applicationContext
        root = File(context.cacheDir.canonicalFile, "private-path-${UUID.randomUUID()}")
        check(root.mkdir())
    }

    private fun link(target: File, location: File) {
        check(target.mkdirs() || target.isDirectory)
        check(location.parentFile.mkdirs() || location.parentFile.isDirectory)
        Os.symlink(target.path, location.path)
        links += location
    }

    private fun withPaths(app: File, noBackup: () -> File): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = app
        override fun getNoBackupFilesDir(): File = noBackup()
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("expected unsafe path") } catch (error: PrivateVaultException) {
            assertEquals("unsafe_path", error.reasonCode)
        }
    }

    @Test fun trustedSystemAncestorAliasMapsToSamePhysicalBase() {
        val physical = File(root, "physical")
        val alias = File(root, "system-alias")
        link(physical, alias)
        val app = File(alias, "synthetic-app")
        val noBackup = File(app, "no_backup")
        assertEquals(File(physical, "synthetic-app/no_backup/orbis-private-vaults"),
            resolveAndroidPrivateVaultBase(app, noBackup))
        assertFalse(File(physical, "synthetic-app").exists())
    }

    @Test fun noBackupChildLinkRemainsRejected() {
        val app = File(root, "app")
        val target = File(root, "outside")
        val noBackup = File(app, "no_backup")
        link(target, noBackup)
        val repository = AndroidPrivateVaults.open(withPaths(app) { noBackup }, "synthetic")
        assertEquals(PrivateVaultAvailability.UNREADABLE, repository.status().availability)
        rejects { repository.create() }
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun vaultBaseChildLinkRemainsRejected() {
        val app = File(root, "app")
        val noBackup = File(app, "no_backup")
        val target = File(root, "outside")
        link(target, File(noBackup, "orbis-private-vaults"))
        val repository = AndroidPrivateVaults.open(withPaths(app) { noBackup }, "synthetic")
        assertEquals(PrivateVaultAvailability.UNREADABLE, repository.status().availability)
        rejects { repository.create() }
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun emptyOwnerChildLinkIsUnreadableNotAnAbsentRoom() {
        val app = File(root, "app")
        val noBackup = File(app, "no_backup")
        val target = File(root, "outside")
        link(target, File(noBackup, "orbis-private-vaults/${vaultDigest("synthetic")}"))
        val repository = AndroidPrivateVaults.open(withPaths(app) { noBackup }, "synthetic")
        assertEquals(PrivateVaultAvailability.UNREADABLE, repository.status().availability)
        rejects { repository.create() }
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun noBackupOutsideTrustedAppRootIsRejected() {
        rejects { resolveAndroidPrivateVaultBase(File(root, "app"), File(root, "outside")) }
        rejects { resolveAndroidPrivateVaultBase(File(root, "app"), File(root, "app/no_backup/../../outside")) }
        rejects { resolveAndroidPrivateVaultBase(root, root) }
    }

    @Test fun unavailableNoBackupGetterDoesNotCrashOpeningTheFactory() {
        val repository = AndroidPrivateVaults.open(withPaths(File(root, "app")) {
            throw IOException("SYNTHETIC-PATH-NOT-FOR-LOGGING")
        }, "synthetic")
        assertEquals(PrivateVaultAvailability.UNREADABLE, repository.status().availability)
        try { repository.create(); fail("expected fixed failure") } catch (error: PrivateVaultException) {
            assertEquals("storage_failed", error.reasonCode)
            assertNull(error.cause)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @After fun cleanOnlySyntheticPaths() {
        if (!::root.isInitialized) return
        check(root.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("private-path-"))
        links.asReversed().forEach { check(Files.isSymbolicLink(it.toPath())); Files.delete(it.toPath()) }
        val entries = Files.walk(root.toPath()).use { stream -> stream.toArray().map { it as Path } }
        check(entries.none(Files::isSymbolicLink))
        entries.sortedByDescending { it.nameCount }.forEach(Files::delete)
    }
}
