package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.Job
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Exact-owned cache only. A kernel lease protects live imports, including those in another process. */
internal object ChatImportStaging {
    internal const val ROOT = "orbis-chat-imports-v1"
    internal const val MAX_IDLE_MILLIS = 24L * 60 * 60 * 1000
    private const val MARKER = "orbis-chat-import-v1"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val payloadNames = setOf("archive.bin", "kelivo-chat-snapshot.db")
    // Operit's immutable preview copy lives beside archive.bin. Recognize only our exact
    // createTempFile naming pattern, never arbitrary JSON files from an unowned directory.
    private val operitSnapshot = Regex("orbis-operit-[0-9]+\\.json")

    class Lease internal constructor(val directory: File, val payload: File,
        private val channel: FileChannel, private val lock: FileLock) : Closeable {
        private var closed = false
        fun releaseOnCompletion(job: Job) { job.invokeOnCompletion { close() } }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            // Never recurse or follow a link. Unexpected objects are retained for diagnosis.
            val removed = runCatching { removePayload(directory) }.getOrDefault(false)
            runCatching { lock.release() }
            runCatching { channel.close() }
            if (removed) runCatching { File(directory, "lease").delete(); directory.delete() }
        }
    }

    fun create(cache: File): Lease {
        prune(cache)
        val root = root(cache, create = true) ?: error("import_cache_unavailable")
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "import_cache_collision" }
        val marker = File(directory, "lease")
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            check(marker.createNewFile())
            marker.writeText(MARKER, Charsets.US_ASCII)
            val opened = FileChannel.open(marker.toPath(), READ, WRITE, NOFOLLOW_LINKS)
            channel = opened
            val acquired = opened.tryLock() ?: error("import_cache_busy")
            lock = acquired
            val payload = File(directory, "payload")
            check(payload.mkdir())
            return Lease(directory, payload, opened, acquired)
        } catch (failure: Throwable) {
            runCatching { lock?.release() }; runCatching { channel?.close() }
            // This exact newly created directory has no source data yet.
            File(directory, "payload").delete(); marker.delete(); directory.delete()
            throw failure
        }
    }

    /** Invoked when the import screen opens and before staging, not an unrestricted cache sweep. */
    fun prune(cache: File, now: Long = System.currentTimeMillis()) {
        val root = root(cache, create = false) ?: return
        root.listFiles()?.forEach { directory ->
            if (!uuid.matches(directory.name) || !regularDirectory(directory) ||
                directory.lastModified() <= 0 || now - directory.lastModified() < MAX_IDLE_MILLIS) return@forEach
            val marker = File(directory, "lease")
            if (!Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) || marker.length() != MARKER.length.toLong()) return@forEach
            runCatching {
                if (marker.readText(Charsets.US_ASCII) != MARKER) return@runCatching
                val removed = FileChannel.open(marker.toPath(), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val lock = runCatching { channel.tryLock() }.getOrNull() ?: return@use false
                    try { removePayload(directory) } finally { lock.release() }
                }
                if (removed == true) { marker.delete(); directory.delete() }
            }
        }
    }

    fun copyArchive(input: InputStream, target: File, limit: Long, checkCancelled: () -> Unit,
        availableBytes: () -> Long = { target.parentFile!!.usableSpace }) {
        RikkaChatArchive.requireExtractionSpace(availableBytes(), 0)
        check(target.createNewFile()) { "import_cache_collision" }
        try {
            target.outputStream().use { output ->
                RikkaChatArchive.copyLimited(input, output, limit, checkCancelled) {
                    RikkaChatArchive.requireExtractionSpace(availableBytes(), it)
                }
            }
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    private fun root(cache: File, create: Boolean): File? {
        if (!regularDirectory(cache)) return null
        val root = File(cache, ROOT)
        if (!root.exists() && create) root.mkdir()
        return root.takeIf { regularDirectory(it) && it.canonicalFile.parentFile == cache.canonicalFile }
    }
    private fun regularDirectory(file: File) = Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)
    private fun removePayload(directory: File): Boolean {
        if (!regularDirectory(directory)) return false
        val entries = directory.listFiles() ?: return false
        if (entries.any { it.name !in setOf("lease", "payload") }) return false
        val payload = File(directory, "payload")
        if (payload.exists()) {
            if (!regularDirectory(payload)) return false
            val files = payload.listFiles() ?: return false
            if (files.any { (it.name !in payloadNames && !operitSnapshot.matches(it.name)) ||
                    !Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) return false
            if (files.any { !it.delete() } || !payload.delete()) return false
        }
        return true
    }
}
