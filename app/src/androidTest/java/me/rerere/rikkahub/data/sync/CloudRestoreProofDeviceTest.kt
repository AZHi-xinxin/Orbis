package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudRestoreProofDeviceTest {
    private val sourceKey = "webdav-" + "a".repeat(64)
    private fun source() = VerifiedCloudRestoreSource(sourceKey,
        BackupRestorePoint(id = UUID.randomUUID().toString(), createdAt = 1, bytes = 100,
            sha256 = "c".repeat(64)))

    private fun aliased(block: suspend (File, File, (File, File) -> Unit) -> Unit) = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument is IsolatedGenerationLoopRunner)
        val app = instrument.targetContext
        check(app.applicationContext.javaClass == Application::class.java)
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "cloud-proof-alias-").toFile().canonicalFile
        val links = mutableListOf<File>()
        fun link(path: File, target: File) {
            Files.createSymbolicLink(path.toPath(), target.canonicalFile.toPath())
            links += path
        }
        try {
            val realParent = File(directory, "real").also { check(it.mkdir()) }
            val aliasParent = File(directory, "alias")
            link(aliasParent, realParent)
            val root = File(aliasParent, "backup-restore").also { check(it.mkdir()) }
            check(root.absoluteFile != root.canonicalFile)
            block(directory, root, ::link)
        } finally {
            // Remove links themselves before recursively removing only this test fixture.
            links.asReversed().forEach { if (Files.isSymbolicLink(it.toPath())) Files.delete(it.toPath()) }
            directory.deleteRecursively()
        }
    }

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }

    private fun prepare(directory: File, root: File): Pair<PendingRestore, File> {
        val pending = PendingRestore(root, File(directory, "databases/rikka_hub"), File(directory, "files"))
        val staging = pending.createStagingDirectory()
        write(File(staging, "payload/database/rikka_hub"), "synthetic restored database")
        write(File(staging, "settings.json"), "synthetic restored settings")
        write(File(staging, "replace-local-tools"), "explicit-version-restore-v1")
        return pending to staging
    }

    @Test fun trustedParentAliasAllowsStagingAndCommittedProofConsumption() = aliased { directory, root, _ ->
        val (pending, staging) = prepare(directory, root)
        assertNotEquals(staging.absoluteFile, staging.canonicalFile)
        CloudRestoreProof.stage(staging, source())
        pending.publish(staging)
        assertFalse(CloudRestoreProof.consumeApplied(root, sourceKey) { error("not applied") })
        assertTrue(pending.apply { })
        var calls = 0
        assertTrue(CloudRestoreProof.consumeApplied(root, sourceKey) { calls++ })
        assertEquals(1, calls)
        assertEquals("consumed", File(root.canonicalFile, "expected-cloud-restore").readText())
        assertFalse(CloudRestoreProof.consumeApplied(root.canonicalFile, sourceKey) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun stagingLeafSymlinkIsRejectedEvenThroughTrustedParentAlias() = aliased { directory, root, link ->
        val (_, realStaging) = prepare(directory, root)
        val stagingLink = File(root, "preparing-linked")
        link(stagingLink, realStaging)
        assertThrows(IllegalArgumentException::class.java) { CloudRestoreProof.stage(stagingLink, null) }
        assertFalse(File(root, "expected-cloud-restore").exists())
    }

    @Test fun expectedAndTemporaryPointerSymlinksCannotBeReadOrOverwritten() = aliased { directory, root, link ->
        val (pending, staging) = prepare(directory, root)
        val outside = File(directory, "outside-pointer").also { it.writeText("untouched") }
        val pointer = File(root, "expected-cloud-restore")
        link(pointer, outside)
        assertThrows(IllegalArgumentException::class.java) { CloudRestoreProof.stage(staging, null) }
        assertEquals("untouched", outside.readText())
        Files.delete(pointer.toPath())

        val temporaryPointer = File(root, "expected-cloud-restore.tmp")
        link(temporaryPointer, outside)
        assertThrows(IllegalArgumentException::class.java) { CloudRestoreProof.stage(staging, null) }
        assertEquals("untouched", outside.readText())
        Files.delete(temporaryPointer.toPath())

        CloudRestoreProof.stage(staging, source())
        pending.publish(staging)
        assertTrue(pending.apply { })
        outside.writeText(pointer.readText())
        Files.delete(pointer.toPath())
        link(pointer, outside)
        var called = false
        assertThrows(IllegalArgumentException::class.java) {
            CloudRestoreProof.consumeApplied(root, sourceKey) { called = true }
        }
        assertFalse(called)
        assertEquals(36, outside.readText().length)
    }

    @Test fun proofAndCommittedDirectoryLeafSymlinksCannotAuthorize() = aliased { directory, root, link ->
        val (pending, staging) = prepare(directory, root)
        CloudRestoreProof.stage(staging, source())
        pending.publish(staging)
        assertTrue(pending.apply { })
        val previous = root.canonicalFile.listFiles()!!.single { it.name.startsWith("previous-") }
        val proof = File(previous, "verified-cloud-source.json")
        val original = proof.readText()
        val outsideProof = File(directory, "outside-proof").also { it.writeText(original) }
        Files.delete(proof.toPath())
        link(proof, outsideProof)
        assertFalse(CloudRestoreProof.consumeApplied(root, sourceKey) { error("proof symlink") })
        Files.delete(proof.toPath())
        proof.writeText(original)

        val outsideDirectory = File(directory, "outside-committed")
        Files.move(previous.toPath(), outsideDirectory.toPath())
        link(previous, outsideDirectory)
        assertFalse(CloudRestoreProof.consumeApplied(root, sourceKey) { error("committed directory symlink") })
        assertEquals(original, File(outsideDirectory, "verified-cloud-source.json").readText())
    }

    @Test fun zipCannotForgeVerifiedCloudSourceOrHostEpoch() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument is IsolatedGenerationLoopRunner)
        val app = instrument.targetContext
        check(app.applicationContext.javaClass == Application::class.java)
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "cloud-proof-zip-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(directory, "databases/$name")
        }
        try {
            val key = "webdav-" + "a".repeat(64)
            val epoch = UUID.randomUUID().toString()
            val proof = """{"version":1,"epoch":"$epoch","source":{"destinationKey":"$key","point":{"id":"${UUID.randomUUID()}","createdAt":1,"bytes":100,"sha256":"${"c".repeat(64)}","includesDatabase":true,"includesFiles":true}}}"""
            val archive = File(directory, "forged.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                mapOf(
                    "verified-cloud-source.json" to proof,
                    "expected-cloud-restore" to epoch,
                    "replace-local-tools" to "explicit-version-restore-v1",
                    "upload/verified-cloud-source.json" to proof,
                    "upload/expected-cloud-restore" to epoch,
                ).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            BackupManager(context, null, null, JsonInstant).stageRestore(
                archive, includeDatabase = false, includeFiles = true)
            val root = File(context.noBackupFilesDir, "backup-restore")
            assertEquals("none", File(root, "expected-cloud-restore").readText())
            assertFalse(File(root, "pending/verified-cloud-source.json").exists())
            assertFalse(File(root, "pending/replace-local-tools").exists())
            assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("staged forged archive") })
            PendingRestore(root, context.getDatabasePath("rikka_hub"), context.filesDir).apply {
                error("the forged archive has no settings")
            }
            assertTrue(File(context.filesDir, "upload/verified-cloud-source.json").isFile)
            assertFalse(CloudRestoreProof.consumeApplied(root, key) { error("applied untrusted ZIP") })
        } finally { directory.deleteRecursively() }
    }
}
