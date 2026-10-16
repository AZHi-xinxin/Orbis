package me.rerere.workspace

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Descriptor-based file access. Implementations must never follow child symlinks. */
interface WorkspaceNativeDirectory : Closeable {
    fun directory(name: String, create: Boolean): WorkspaceNativeDirectory
    fun read(name: String): WorkspaceNativeRead
    fun writeAtomic(name: String, bytes: ByteArray, overwrite: Boolean, expectedRevision: String?): WorkspaceNativeStat
}

interface WorkspaceNativeRead : Closeable {
    fun stat(): WorkspaceNativeStat
    fun readAt(offset: Long, length: Int): ByteArray
}

data class WorkspaceNativeStat(val size: Long, val updatedAt: Long, val revision: String)
data class WorkspaceFileChunk(
    val text: String, val offsetBytes: Long, val nextOffsetBytes: Long,
    val totalBytes: Long, val eof: Boolean, val revision: String,
)
data class WorkspaceTextUpdate(val before: String, val after: String, val stat: WorkspaceNativeStat)
class WorkspaceWriteOutcomeUnknownException(cause: Throwable) : java.io.IOException("workspace_write_committed_verification_unknown", cause)

/** The commit callback must end at the atomic rename; no fallible work follows it there. */
internal fun <T> finishWorkspaceWrite(commit: () -> Unit, verify: () -> T, cleanup: () -> Unit): T {
    var committed = false
    try {
        commit()
        committed = true
        runCatching(cleanup)
        return verify()
    } catch (failure: Exception) {
        if (committed) throw WorkspaceWriteOutcomeUnknownException(failure)
        throw failure
    } finally { if (!committed) runCatching(cleanup) }
}

/**
 * No process, cwd, rootfs, or shell dependency. The opener pins a trusted app-private
 * directory, and every following component is opened relative to a held descriptor.
 * One instance serializes native reads/edits/writes, including the entire edit transaction.
 */
class WorkspaceNativeFiles(
    private val anchor: File,
    private val open: (File) -> WorkspaceNativeDirectory = AndroidWorkspaceDirectory::openRoot,
) {
    @Synchronized
    fun readChunk(path: List<String>, offset: Long = 0, maxBytes: Int = DEFAULT_CHUNK_BYTES,
                  expectedRevision: String? = null): WorkspaceFileChunk {
        require(offset >= 0) { "workspace_invalid_offset" }
        require(maxBytes in 4..MAX_CHUNK_BYTES) { "workspace_invalid_chunk_size" }
        return withParent(path, false) { parent, name -> parent.read(name).use { input ->
            val before = input.stat()
            require(offset <= before.size) { "workspace_offset_past_eof" }
            require(expectedRevision == null || before.revision == expectedRevision) { "workspace_file_changed" }
            val bytes = input.readAt(offset, minOf(maxBytes.toLong() + 3, before.size - offset).toInt())
            require(bytes.isEmpty() || bytes[0].toInt() and 0xc0 != 0x80) { "workspace_offset_not_utf8_boundary" }
            var end = minOf(bytes.size, maxBytes)
            // If the next byte continues a codepoint, leave that whole codepoint for next_offset.
            while (end < bytes.size && end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
            val text = decodeUtf8(bytes.copyOf(end))
            val after = input.stat()
            check(before == after && bytes.size == minOf(maxBytes.toLong() + 3, before.size - offset).toInt()) {
                "workspace_file_changed"
            }
            WorkspaceFileChunk(text, offset, offset + end, before.size, offset + end == before.size, before.revision)
        } }
    }

    @Synchronized
    fun readBytes(path: List<String>, maxBytes: Int = MAX_FILE_BYTES): ByteArray = withParent(path, false) { parent, name ->
        parent.read(name).use { input ->
            val before = input.stat()
            require(before.size in 0..maxBytes.toLong()) { "workspace_file_too_large_use_byte_chunks" }
            val bytes = input.readAt(0, before.size.toInt())
            check(bytes.size.toLong() == before.size && input.stat() == before) { "workspace_file_changed" }
            bytes
        }
    }

    @Synchronized
    fun writeText(path: List<String>, text: String, overwrite: Boolean): WorkspaceNativeStat {
        val bytes = encodeUtf8(text)
        require(bytes.size <= MAX_FILE_BYTES) { "workspace_write_too_large" }
        return withParent(path, true) { parent, name -> parent.writeAtomic(name, bytes, overwrite, null) }
    }

    @Synchronized
    fun updateText(path: List<String>, update: (String) -> String): WorkspaceTextUpdate = withParent(path, false) { parent, name ->
        val (beforeText, beforeStat) = parent.read(name).use { input ->
            val stat = input.stat()
            require(stat.size in 0..MAX_FILE_BYTES.toLong()) { "workspace_file_too_large_use_byte_chunks" }
            val bytes = input.readAt(0, stat.size.toInt())
            check(bytes.size.toLong() == stat.size && input.stat() == stat) { "workspace_file_changed" }
            decodeUtf8(bytes) to stat
        }
        val after = update(beforeText)
        val bytes = encodeUtf8(after)
        require(bytes.size <= MAX_FILE_BYTES) { "workspace_write_too_large" }
        val stat = parent.writeAtomic(name, bytes, true, beforeStat.revision)
        WorkspaceTextUpdate(beforeText, after, stat)
    }

    private fun <T> withParent(path: List<String>, create: Boolean, block: (WorkspaceNativeDirectory, String) -> T): T {
        require(path.isNotEmpty() && path.all(::validWorkspaceComponent)) { "workspace_path_denied" }
        val handles = mutableListOf<WorkspaceNativeDirectory>()
        try {
            var directory = open(anchor).also(handles::add)
            path.dropLast(1).forEach { directory = directory.directory(it, create).also(handles::add) }
            return block(directory, path.last())
        } finally { handles.asReversed().forEach { runCatching { it.close() } } }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 64 * 1024
        const val MAX_CHUNK_BYTES = 256 * 1024
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        fun encodeUtf8(text: String): ByteArray {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
    }
}

fun validWorkspaceComponent(name: String): Boolean = name.isNotEmpty() && name !in setOf(".", "..") &&
    name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
