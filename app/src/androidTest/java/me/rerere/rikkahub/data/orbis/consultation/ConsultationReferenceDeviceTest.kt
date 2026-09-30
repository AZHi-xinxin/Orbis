package me.rerere.rikkahub.data.orbis.consultation

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Comparator

/** Synthetic sandbox only; the isolated runner prevents application/service recovery. */
class ConsultationReferenceDeviceTest {
    private lateinit var sandbox: File
    private lateinit var filesRoot: File
    private lateinit var library: File
    private val args get() = buildJsonObject { put("path", CONSULTATION_REFERENCE_PREFIX + "00-index.md") }

    @Before fun createSandbox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner) { "isolated_runner_required" }
        val cache = instrumentation.targetContext.cacheDir.canonicalFile
        sandbox = Files.createTempDirectory(cache.toPath(), "consultation-reference-").toFile()
        check(sandbox.canonicalFile.parentFile == cache)
        filesRoot = File(sandbox, "app-files/workspaces/owner/files").also { check(it.mkdirs()) }
        library = File(filesRoot, CONSULTATION_REFERENCE_DIRECTORY).also { check(it.mkdir()) }
    }

    @After fun removeOnlySyntheticSandbox() {
        if (!::sandbox.isInitialized) return
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(sandbox.canonicalFile.parentFile == cache && sandbox.name.startsWith("consultation-reference-"))
        // Files.walk does not follow links. Never traverse a synthetic symlink target.
        Files.walk(sandbox.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun read(opener: (File) -> ConsultationReferenceDirectory = AndroidConsultationReferenceDirectory::openTrustedRoot): String =
        readConsultationReference(filesRoot, args, opener)
    private fun content(output: String) = Json.parseToJsonElement(output).jsonObject.getValue("text").jsonPrimitive.content

    @Test fun readsRealAndroidDescriptorWithBoundedUtf8() {
        val chapter = File(library, "00-index.md")
        chapter.writeText("# 合成章节\n只作为资料。\n")
        assertEquals(chapter.readText(), content(read()))
        chapter.writeText("a".repeat(CONSULTATION_REFERENCE_MAX_BYTES))
        assertEquals(CONSULTATION_REFERENCE_MAX_BYTES, content(read()).length)
        chapter.appendText("b")
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test fun malformedUtf8AndOversizedEnvelopeNeverReturnPartialText() {
        val chapter = File(library, "00-index.md")
        chapter.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertTrue(runCatching { read() }.isFailure)
        chapter.writeText("\u0001".repeat(4000))
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test fun rejectsLeafSymlinkWithRealAndroidNoFollow() {
        val target = File(sandbox, "synthetic-outside.md").also { it.writeText("outside") }
        Os.symlink(target.absolutePath, File(library, "00-index.md").absolutePath)
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test fun rejectsLibraryDirectorySymlinkWithRealAndroidNoFollow() {
        val target = File(sandbox, "synthetic-library").also { check(it.mkdir()) }
        File(target, "00-index.md").writeText("outside")
        check(library.delete())
        Os.symlink(target.absolutePath, library.absolutePath)
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test fun rejectsIntermediateWorkspaceDirectorySymlink() {
        val workspace = filesRoot.parentFile!!
        val moved = File(sandbox, "synthetic-workspace")
        check(workspace.renameTo(moved))
        Os.symlink(moved.absolutePath, workspace.absolutePath)
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test fun rejectsNonRegularLeafWithoutReadingIt() {
        check(File(library, "00-index.md").mkdir())
        assertTrue(runCatching { read() }.isFailure)
    }

    @Test(timeout = 3000) fun rejectsRealFifoWithoutWaitingForWriter() {
        Os.mkfifo(File(library, "00-index.md").absolutePath, 0x180) // Owner read/write (0600).
        val failure = runCatching { read() }.exceptionOrNull()
        assertEquals("consultation_reference_file_type_denied", failure?.message)
    }

    @Test fun directorySymlinkSwapThenRestorationCannotReadSameSizeOutsideContent() {
        File(library, "00-index.md").writeText("inside!")
        val outside = File(sandbox, "synthetic-outside").also { check(it.mkdir()) }
        File(outside, "00-index.md").writeText("outside") // Same byte length as the safe file.
        val held = File(filesRoot, "held-library")
        var swapped = false
        var restored = false
        fun restore() {
            if (swapped && !restored) {
                Files.delete(library.toPath()) // Delete the known synthetic link, never its target.
                Os.rename(held.absolutePath, library.absolutePath)
                restored = true
            }
        }
        fun wrap(handle: ConsultationReferenceDirectory): ConsultationReferenceDirectory = object : ConsultationReferenceDirectory {
            override fun directory(name: String): ConsultationReferenceDirectory {
                val child = handle.directory(name)
                try {
                    if (name == CONSULTATION_REFERENCE_DIRECTORY) {
                        Os.rename(library.absolutePath, held.absolutePath)
                        Os.symlink(outside.absolutePath, library.absolutePath)
                        swapped = true
                    }
                    return wrap(child)
                } catch (error: Throwable) { child.close(); throw error }
            }
            override fun readRegularFile(name: String, maxBytes: Int): ByteArray =
                try { handle.readRegularFile(name, maxBytes) } finally { restore() }
            override fun close() = handle.close()
        }
        try {
            assertEquals("inside!", content(read { wrap(AndroidConsultationReferenceDirectory.openTrustedRoot(it)) }))
            assertTrue(swapped)
            assertTrue(restored)
            assertEquals("inside!", File(library, "00-index.md").readText())
        } finally { restore() }
    }
}
