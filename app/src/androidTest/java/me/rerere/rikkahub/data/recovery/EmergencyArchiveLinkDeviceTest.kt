package me.rerere.rikkahub.data.recovery

import android.app.Application
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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Real kernel symlinks, confined to a random cache subtree of the synthetic test runner. */
@RunWith(AndroidJUnit4::class)
class EmergencyArchiveLinkDeviceTest {
    private lateinit var fixtureParent: File
    private lateinit var directory: File
    private val metadata = EmergencyArchiveMetadata("synthetic.orbis", "fixture", 1L)

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        fixtureParent = instrumentation.targetContext.cacheDir.canonicalFile
        check(fixtureParent.isDirectory || fixtureParent.mkdirs())
        directory = Files.createTempDirectory(fixtureParent.toPath(), "emergency-link-device-").toFile().canonicalFile
        check(directory.parentFile == fixtureParent)
    }

    @After fun tearDown() {
        if (!::directory.isInitialized) return
        check(directory.canonicalFile.parentFile == fixtureParent)
        check(directory.name.startsWith("emergency-link-device-"))
        // The default walk does NOT follow symbolic links, including the deliberate cycle.
        // Delete only the exact random fixture tree, never a link's target by traversal.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun roots(source: File) = EmergencyArchive.ROOT_NAMES.map {
        EmergencyArchiveRoot(it, File(source, it))
    }

    @Test fun unreadableOutsideTargetsAndCycleAreRecordedWithoutReadingOrRecreatingLinks() {
        val source = File(directory, "source").apply { mkdirs() }
        val runtime = File(source, "files/rootfs").apply { mkdirs() }
        File(runtime, "regular.txt").writeText("ordinary synthetic payload")
        val outsideFile = File(directory, "outside-file").apply { writeText("external synthetic file must not be read") }
        val outsideDirectory = File(directory, "outside-directory").apply { mkdirs() }
        val outsideChild = File(outsideDirectory, "secret.txt").apply { writeText("external synthetic directory must not be read") }
        val targets = mapOf("external-file" to outsideFile.path, "external-directory" to outsideDirectory.path, "cycle" to runtime.path)
        targets.forEach { (name, target) -> Os.symlink(target, File(runtime, name).path) }
        val archive = File(directory, "links.zip")
        val extracted = File(directory, "isolated-extracted")
        try {
            // Following either external link would make export fail with EACCES. The export
            // must succeed without temporarily opening these targets or reading their bytes.
            Os.chmod(outsideFile.path, 0)
            Os.chmod(outsideDirectory.path, 0)
            assertThrows(IOException::class.java) { outsideFile.inputStream().use { it.read() } }
            assertThrows(IOException::class.java) { outsideChild.inputStream().use { it.read() } }
            val manifest = EmergencyArchive.create(roots(source), archive, metadata)
            assertEquals(setOf("files/rootfs/regular.txt"), manifest.files.map { it.path }.toSet())
            assertEquals(targets.mapKeys { "files/rootfs/${it.key}" }, manifest.links.associate { it.path to it.target })
            assertEquals(manifest, EmergencyArchive.verify(archive))
            assertEquals(manifest, EmergencyArchive.extractVerified(archive, extracted))
            assertEquals("ordinary synthetic payload", File(extracted, "files/rootfs/regular.txt").readText())
            targets.forEach { (name, target) ->
                assertFalse(Files.exists(File(extracted, "files/rootfs/$name").toPath(), NOFOLLOW_LINKS))
                assertTrue(Files.isSymbolicLink(File(runtime, name).toPath()))
                assertEquals(target, Os.readlink(File(runtime, name).path))
            }
            assertEquals(0, Os.lstat(outsideFile.path).st_mode and 0xfff)
            assertEquals(0, Os.lstat(outsideDirectory.path).st_mode and 0xfff)
            assertFalse(File(extracted, "outside-file").exists())
            assertFalse(File(extracted, "outside-directory").exists())
        } finally {
            // Exact fixture objects only; restores test readability for checks and disposal.
            Os.chmod(outsideFile.path, 384)
            Os.chmod(outsideDirectory.path, 448)
        }
        assertEquals("external synthetic file must not be read", outsideFile.readText())
        assertEquals("external synthetic directory must not be read", outsideChild.readText())
    }

    @Test fun symlinkInAnyOfTheFourPersistentRootPositionsIsRejectedWithoutPublishing() {
        for (rootName in EmergencyArchive.ROOT_NAMES) {
            val source = File(directory, "source-$rootName").apply { mkdirs() }
            val outside = File(directory, "outside-$rootName").apply { mkdirs() }
            val sentinel = File(outside, "keep.txt").apply { writeText("synthetic outside $rootName") }
            EmergencyArchive.ROOT_NAMES.filter { it != rootName }.forEach { File(source, it).mkdirs() }
            val link = File(source, rootName)
            Os.symlink(outside.path, link.path)
            val archive = File(directory, "refused-$rootName.zip")
            assertThrows(IOException::class.java) { EmergencyArchive.create(roots(source), archive, metadata) }
            assertFalse(archive.exists())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(outside.path, Os.readlink(link.path))
            assertEquals("synthetic outside $rootName", sentinel.readText())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".partial") })
        }
    }
}
