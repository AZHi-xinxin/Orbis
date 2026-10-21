package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceNativeFilesTest {
    private class MemoryDisk {
        val files = mutableMapOf<String, ByteArray>()
        val revisions = mutableMapOf<String, Long>()
        var maxRead = 0
        var failBeforeCommit = false
        var changeAfterRead = false
        var opened = 0
        val createdDirectories = mutableListOf<String>()
        fun put(name: String, bytes: ByteArray) { files[name] = bytes; revisions[name] = (revisions[name] ?: 0) + 1 }
        fun stat(name: String) = WorkspaceNativeStat(files.getValue(name).size.toLong(), 1, revisions.getValue(name).toString())
        fun directory(prefix: String = ""): WorkspaceNativeDirectory = object : WorkspaceNativeDirectory {
            override fun directory(name: String, create: Boolean): WorkspaceNativeDirectory {
                if (create) createdDirectories += "$prefix$name/"
                return directory("$prefix$name/")
            }
            override fun read(name: String): WorkspaceNativeRead {
                val key = prefix + name
                require(files.containsKey(key))
                return object : WorkspaceNativeRead {
                    override fun stat() = this@MemoryDisk.stat(key)
                    override fun readAt(offset: Long, length: Int): ByteArray {
                        maxRead = maxOf(maxRead, length)
                        val bytes = files.getValue(key)
                        val result = bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
                        if (changeAfterRead) revisions[key] = revisions.getValue(key) + 1
                        return result
                    }
                    override fun close() = Unit
                }
            }
            override fun writeAtomic(name: String, bytes: ByteArray, overwrite: Boolean, expectedRevision: String?): WorkspaceNativeStat {
                val key = prefix + name
                require(overwrite || !files.containsKey(key))
                require(expectedRevision == null || stat(key).revision == expectedRevision)
                if (failBeforeCommit) throw IOException("injected before commit")
                put(key, bytes)
                return stat(key)
            }
            override fun close() = Unit
        }
        fun engine() = WorkspaceNativeFiles(File("unused")) { opened++; directory() }
    }

    @Test fun defaultChunkDoesNotReadWholeLargeJson() {
        val disk = MemoryDisk()
        disk.put("large.json", ("[\"" + "x".repeat(10 * 1024 * 1024) + "\"]").toByteArray())
        val chunk = disk.engine().readChunk(listOf("large.json"))
        assertEquals(10L * 1024 * 1024 + 4, chunk.totalBytes)
        assertEquals(65536L, chunk.nextOffsetBytes)
        assertFalse(chunk.eof)
        assertTrue(disk.maxRead <= 65539)
    }

    @Test fun nativeDirectoryPreparationCreatesOnlyValidatedComponentsAndNoFiles() {
        val disk = MemoryDisk()
        val native = disk.engine()
        native.ensureDirectory(listOf("workspaces", "owner", "files"))
        assertEquals(listOf("workspaces/", "workspaces/owner/", "workspaces/owner/files/"), disk.createdDirectories)
        assertTrue(disk.files.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { native.ensureDirectory(listOf("..")) }
        assertThrows(IllegalArgumentException::class.java) { native.ensureDirectory(emptyList()) }
        assertEquals(1, disk.opened)
    }

    @Test fun utf8ChunksReassembleWithoutLoss() {
        val text = "甲😀乙ab𐐷丙\n".repeat(8)
        val disk = MemoryDisk().apply { put("中文.json", text.toByteArray()) }
        val engine = disk.engine()
        val result = StringBuilder()
        var offset = 0L
        var revision: String? = null
        do {
            val chunk = engine.readChunk(listOf("中文.json"), offset, 5, revision)
            assertTrue(chunk.nextOffsetBytes > offset)
            assertTrue(chunk.nextOffsetBytes - offset <= 5)
            result.append(chunk.text)
            offset = chunk.nextOffsetBytes; revision = chunk.revision
        } while (!chunk.eof)
        assertEquals(text, result.toString())
        assertEquals(text.toByteArray().size.toLong(), offset)
    }

    @Test fun offsetInsideUtf8CharacterIsRejected() {
        val disk = MemoryDisk().apply { put("a", "😀x".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { disk.engine().readChunk(listOf("a"), 1, 4) }
    }

    @Test fun eofAndEmptyFilesAreExplicit() {
        val disk = MemoryDisk().apply { put("a", byteArrayOf()); put("b", "abc".toByteArray()) }
        assertTrue(disk.engine().readChunk(listOf("a")).eof)
        assertEquals("", disk.engine().readChunk(listOf("b"), 3).text)
        assertThrows(IllegalArgumentException::class.java) { disk.engine().readChunk(listOf("b"), 4) }
    }

    @Test fun invalidRangesRejectBeforeOpeningDirectory() {
        val disk = MemoryDisk(); val engine = disk.engine()
        assertThrows(IllegalArgumentException::class.java) { engine.readChunk(listOf("a"), -1) }
        assertThrows(IllegalArgumentException::class.java) { engine.readChunk(listOf("a"), 0, 262145) }
        assertThrows(IllegalArgumentException::class.java) { engine.readChunk(listOf("a"), 0, 3) }
        assertEquals(0, disk.opened)
    }

    @Test fun traversalAndAmbiguousComponentsAreRejected() {
        val disk = MemoryDisk(); val engine = disk.engine()
        listOf("..", ".", "", "a/b", "a\\b", "a\u0000b", "a\nb").forEach {
            assertThrows(IllegalArgumentException::class.java) { engine.writeText(listOf(it, "a"), "no", true) }
        }
        assertEquals(0, disk.opened)
    }

    @Test fun revisionChangesBetweenChunksAreRejected() {
        val disk = MemoryDisk().apply { put("a", "oldvalue".toByteArray()) }; val engine = disk.engine()
        val first = engine.readChunk(listOf("a"), 0, 4)
        disk.put("a", "newvalue".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { engine.readChunk(listOf("a"), first.nextOffsetBytes, 4, first.revision) }
    }

    @Test fun changedDuringReadIsRejected() {
        val disk = MemoryDisk().apply { put("a", "abc".toByteArray()); changeAfterRead = true }
        assertThrows(IllegalStateException::class.java) { disk.engine().readChunk(listOf("a")) }
    }

    @Test fun invalidUtf8AndUnpairedSurrogatesAreNotSilentlyReplaced() {
        val disk = MemoryDisk().apply { put("a", byteArrayOf(0xff.toByte())) }
        assertThrows(CharacterCodingException::class.java) { disk.engine().readChunk(listOf("a")) }
        assertThrows(CharacterCodingException::class.java) { disk.engine().writeText(listOf("b"), "\uD800", true) }
        assertFalse(disk.files.containsKey("b"))
    }

    @Test fun failedAtomicWritePreservesOriginal() {
        val disk = MemoryDisk().apply { put("a", "original".toByteArray()); failBeforeCommit = true }
        assertThrows(IOException::class.java) { disk.engine().writeText(listOf("a"), "replacement", true) }
        assertEquals("original", disk.files.getValue("a").toString(Charsets.UTF_8))
    }

    @Test fun exclusiveWriteRefusesOverwrite() {
        val disk = MemoryDisk().apply { put("a", "original".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { disk.engine().writeText(listOf("a"), "new", false) }
        assertEquals("original", disk.files.getValue("a").toString(Charsets.UTF_8))
    }

    @Test fun updateTransactionPreservesOriginalOnTransformError() {
        val disk = MemoryDisk().apply { put("a", "original".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { disk.engine().updateText(listOf("a")) { error("no match") } }
        assertEquals("original", disk.files.getValue("a").toString(Charsets.UTF_8))
    }

    @Test fun concurrentNativeEditsDoNotLoseUpdates() {
        val disk = MemoryDisk().apply { put("a", "0".toByteArray()) }; val engine = disk.engine()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..40).map { pool.submit { engine.updateText(listOf("a")) { (it.toInt() + 1).toString() } } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("40", disk.files.getValue("a").toString(Charsets.UTF_8))
        } finally { pool.shutdownNow() }
    }

    @Test fun postCommitVerificationFailureRemainsUnknownAndDoesNotReplay() {
        var commits = 0
        val failure = assertThrows(WorkspaceWriteOutcomeUnknownException::class.java) {
            finishWorkspaceWrite({ commits++ }, { throw IOException("dir fsync failed") }, {})
        }
        assertEquals(1, commits)
        assertEquals("workspace_write_committed_verification_unknown", failure.message)
    }

    @Test fun cleanupFailureAfterLinkDoesNotEraseSuccess() {
        var committed = false
        val result = finishWorkspaceWrite({ committed = true }, { "saved" }, { throw IOException("unlink failed") })
        assertTrue(committed); assertEquals("saved", result)
    }

    @Test fun failedCommitIsNotMislabelledAsCommitted() {
        assertThrows(IllegalArgumentException::class.java) {
            finishWorkspaceWrite({ throw IllegalArgumentException("exists") }, { "never" }, {})
        }
    }

    @Test fun unsupportedShellResultIsExplicitAndNotReplayed() {
        val result = WorkspaceCommandResult(1, "", "bash: cd: /workspace: Function not implemented")
        val classified = classifyWorkspaceShellResult(result)
        assertEquals("workspace_proot_cwd_unsupported", classified.capabilityError)
        assertEquals(result.stderr, classified.stderr)
        assertEquals(null, classifyWorkspaceShellResult(WorkspaceCommandResult(0, "hello", "")).capabilityError)
    }
}
