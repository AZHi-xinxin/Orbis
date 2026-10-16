package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic app-cache subtree only. No user workspace, network, shell, or PRoot execution. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNativeFilesDeviceTest {
    @Test(timeout = 30_000) fun nativeChineseReadWriteAndEditWorkWithoutRootfsOrShell() = isolated { anchor ->
        val manager = manager(anchor)
        val entry = manager.writeNativeText("owner", "/workspace/资料/中文😀.md", "你好😀\n", false)
        assertFalse(manager.hasRootfs("owner"))
        assertEquals("/workspace/资料/中文😀.md", entry.path)
        assertEquals("你好😀\n", manager.readNativeChunk("owner", entry.path, 0, 65536).text)
        manager.updateNativeText("owner", entry.path) { it.replace("你好", "晚安") }
        assertEquals("晚安😀\n", manager.readNativeChunk("owner", entry.path, 0, 65536).text)
        assertThrows(IllegalArgumentException::class.java) { manager.writeNativeText("owner", entry.path, "no", false) }
        assertEquals("晚安😀\n", manager.readNativeChunk("owner", entry.path, 0, 65536).text)
    }

    @Test(timeout = 30_000) fun largeFileIsReadWithBoundedByteChunks() = isolated { anchor ->
        val manager = manager(anchor)
        val target = File(anchor, "workspaces/owner/files/large.json").apply { parentFile!!.mkdirs() }
        target.outputStream().use { output ->
            output.write('['.code)
            repeat(160) { output.write(" ".repeat(64 * 1024).toByteArray()) }
            output.write(']'.code)
        }
        val first = manager.readNativeChunk("owner", "/workspace/large.json", 0, 65536)
        assertTrue(first.totalBytes > 8L * 1024 * 1024)
        assertEquals(65536L, first.nextOffsetBytes)
        assertFalse(first.eof)
        val last = manager.readNativeChunk("owner", "/workspace/large.json", first.totalBytes - 1, 65536, first.revision)
        assertEquals("]", last.text); assertTrue(last.eof)
    }

    @Test(timeout = 30_000) fun childSymlinksAndTraversalCannotReadOrOverwriteOutsideFile() = isolated { anchor ->
        val manager = manager(anchor)
        manager.writeNativeText("owner", "/workspace/normal.txt", "safe", false)
        val outside = File(anchor, "outside.txt").apply { writeText("private") }
        val files = File(anchor, "workspaces/owner/files")
        Files.createSymbolicLink(File(files, "link.txt").toPath(), outside.toPath())
        assertThrows(Exception::class.java) { manager.readNativeChunk("owner", "/workspace/link.txt", 0, 100) }
        assertThrows(Exception::class.java) { manager.writeNativeText("owner", "/workspace/link.txt", "changed", true) }
        assertThrows(IllegalArgumentException::class.java) { manager.writeNativeText("owner", "/workspace/../outside.txt", "changed", true) }
        Files.createSymbolicLink(File(files, "linked-dir").toPath(), anchor.toPath())
        assertThrows(Exception::class.java) { manager.writeNativeText("owner", "/workspace/linked-dir/outside.txt", "changed", true) }
        assertEquals("private", outside.readText())
    }

    @Test(timeout = 30_000) fun pinnedParentSurvivesDirectoryReplacementWithoutFollowingSymlink() = isolated { anchor ->
        File(anchor, "original").mkdir()
        File(anchor, "outside").mkdir()
        AndroidWorkspaceDirectory.openRoot(anchor).use { root ->
            root.directory("original", false).use { pinned ->
                assertTrue(File(anchor, "original").renameTo(File(anchor, "moved")))
                Files.createSymbolicLink(File(anchor, "original").toPath(), File(anchor, "outside").toPath())
                pinned.writeAtomic("saved.txt", "safe".toByteArray(), false, null)
            }
        }
        assertEquals("safe", File(anchor, "moved/saved.txt").readText())
        assertFalse(File(anchor, "outside/saved.txt").exists())
    }

    @Test(timeout = 30_000) fun fileRevisionDetectsSameLengthInPlaceChangesAndMountsStayReadOnly() = isolated { anchor ->
        val upload = File(anchor, "upload").apply { mkdirs() }
        File(upload, "reference.txt").writeText("reference")
        val manager = manager(anchor, listOf(WorkspaceBindMount(upload, "/upload")))
        manager.writeNativeText("owner", "/workspace/a.txt", "first", false)
        val first = manager.readNativeChunk("owner", "/workspace/a.txt", 0, 4)
        val target = File(anchor, "workspaces/owner/files/a.txt")
        target.writeText("other")
        target.setLastModified(first.revision.split(':').getOrNull(3)?.toLongOrNull()?.times(1000) ?: 1000)
        assertThrows(IllegalArgumentException::class.java) {
            manager.readNativeChunk("owner", "/workspace/a.txt", first.nextOffsetBytes, 4, first.revision)
        }
        assertEquals("reference", manager.readNativeChunk("owner", "/upload/reference.txt", 0, 100).text)
        assertThrows(IllegalArgumentException::class.java) { manager.writeNativeText("owner", "/upload/reference.txt", "changed", true) }
        assertEquals("reference", File(upload, "reference.txt").readText())
    }

    @Test(timeout = 30_000) fun nativeNoReplacePreservesExistingTargetAndRejectsInvalidDescriptor() = isolated { anchor ->
        val source = File(anchor, ".orbis-write-synthetic.tmp").apply { writeText("new") }
        val target = File(anchor, "原文😀.txt").apply { writeText("original") }
        ParcelFileDescriptor.open(anchor, ParcelFileDescriptor.MODE_READ_ONLY).use { directory ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                WorkspaceAtomicRename.noReplace(directory.fd, source.name, target.name)
            }
            assertEquals("workspace_file_exists", failure.message)
        }
        assertEquals("original", target.readText())
        assertEquals("new", source.readText())
        val unavailable = assertThrows(java.io.IOException::class.java) {
            WorkspaceAtomicRename.noReplace(-100, source.name, "absent.txt")
        }
        assertEquals("workspace_atomic_create_unavailable", unavailable.message)
        assertFalse(File(anchor, "absent.txt").exists())
    }

    @Test(timeout = 30_000) fun concurrentExclusiveCreatesHaveExactlyOneWinner() = isolated { anchor ->
        val first = manager(anchor)
        val second = manager(anchor)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first to "first", second to "second").map { (manager, value) ->
                pool.submit<Throwable?> {
                    check(gate.await(5, TimeUnit.SECONDS))
                    try { manager.writeNativeText("owner", "/workspace/race😀.txt", value, false); null }
                    catch (failure: Exception) { failure }
                }
            }
            gate.countDown()
            val failures = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, failures.count { it == null })
            assertEquals("workspace_file_exists", failures.filterNotNull().single().message)
            assertTrue(first.readNativeChunk("owner", "/workspace/race😀.txt", 0, 100).text in setOf("first", "second"))
        } finally { pool.shutdownNow() }
    }

    private fun manager(anchor: File, mounts: List<WorkspaceBindMount> = emptyList()) = WorkspaceManager(
        File(anchor, "workspaces"), shellRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("A file operation must never execute shell")
        }, bindMounts = mounts,
    )

    private fun isolated(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val temporary = Files.createTempDirectory(cache.toPath(), "workspace-native-synthetic-").toFile()
        check(temporary.canonicalFile.parentFile == cache)
        try { block(temporary) } finally {
            check(temporary.canonicalFile.parentFile == cache)
            // Files.walk does not follow links. Only remove this newly created test subtree.
            Files.walk(temporary.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
