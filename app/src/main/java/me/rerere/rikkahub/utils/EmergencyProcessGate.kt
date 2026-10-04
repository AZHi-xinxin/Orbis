package me.rerere.rikkahub.utils

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A process-lifetime, cooperative barrier for cold emergency snapshots.
 *
 * Keep this directory outside every archived/restored data root. Every business Android
 * process must acquire its shared lease from Application.attachBaseContext, before any
 * providers or application initialization. The independent recovery process takes an
 * exclusive lease only after the human has requested recovery and business processes
 * have stopped. The durable request remains set if recovery crashes.
 *
 * This does NOT terminate processes and does NOT prove that native workspace children
 * have stopped: the recovery caller must additionally check those non-cooperative writers.
 * I/O exceptions are deliberately propagated: callers must fail closed, not initialize
 * business services or claim that a snapshot is safe when the barrier cannot be inspected.
 */
class EmergencyProcessGate(directory: File) {
    private val directory = directory.canonicalFile
    private val requestFile = File(this.directory, "recovery-requested")
    private val lockFile = File(this.directory, "business-processes.lock")

    /** A non-file at the request path is also a request: malformed state fails closed. */
    fun isRecoveryRequested(): Boolean = requestAttributes() != null

    /** Call only after the human explicitly agrees to stop all Orbis activity. */
    fun requestRecovery(): Boolean {
        ensureDirectory()
        // CREATE_NEW refuses to follow or overwrite existing links. Its presence, not
        // its contents, is authoritative even if the process/disk fails during the write.
        try {
            FileChannel.open(requestFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val bytes = ByteBuffer.wrap("Orbis emergency recovery requested\n".toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) output.write(bytes)
                output.force(true)
            }
        } catch (_: FileAlreadyExistsException) {
            // Another process may have requested recovery already. Never replace it.
        }
        return requestAttributes()?.isRegularFile == true
    }

    /** Hold the returned lease until this business process exits. Null means do not start. */
    fun tryAcquireBusinessLease(): Lease? {
        if (isRecoveryRequested()) return null
        val lease = tryAcquire(shared = true) ?: return null
        // Close the race where recovery was requested between the first check and lock.
        if (isRecoveryRequested()) {
            lease.close()
            return null
        }
        return lease
    }

    /**
     * Null means a participating business process still owns a lease. The caller must not
     * export until it owns this lease AND has independently excluded native child writers.
     */
    fun tryAcquireRecoveryLease(): Lease? {
        if (!isRecoveryRequested()) return null
        val lease = tryAcquire(shared = false) ?: return null
        if (!isRecoveryRequested()) {
            lease.close()
            return null
        }
        return lease
    }

    /** Explicit human exit only; close the lease before relaunching the normal app. */
    fun clearRecoveryRequest(lease: Lease): Boolean {
        require(lease.ownerDirectory == directory && !lease.shared && lease.isValid) {
            "An active exclusive recovery lease belonging to this gate is required"
        }
        val attributes = requestAttributes() ?: return true
        // Never recursively delete unexpected paths. Preserve a malformed fence so it can
        // be inspected, instead of silently authorizing business activity.
        return attributes.isRegularFile && requestFile.delete()
    }

    private fun tryAcquire(shared: Boolean): Lease? = synchronized(localLeases) {
        // Closing ANY descriptor for a locked file can release that process's POSIX
        // locks, even if another channel owns them. Do not open a second descriptor
        // merely to discover OverlappingFileLockException in this JVM.
        if (localLeases[directory]?.isValid == true) return@synchronized null
        ensureDirectory()
        val file = RandomAccessFile(lockFile, "rw")
        try {
            val lock = try {
                file.channel.tryLock(0L, Long.MAX_VALUE, shared)
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                file.close()
                return@synchronized null
            }
            // Some platforms may silently promote a shared lock to exclusive. That is
            // conservative: other business processes will not start concurrently.
            return@synchronized Lease(directory, shared, file, lock).also {
                localLeases[directory] = it
            }
        } catch (error: Throwable) {
            runCatching { file.close() }
            throw error
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Emergency recovery control directory is unavailable")
        }
    }

    private fun requestAttributes(): BasicFileAttributes? = try {
        Files.readAttributes(requestFile.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    class Lease internal constructor(
        internal val ownerDirectory: File,
        internal val shared: Boolean,
        private val file: RandomAccessFile,
        private val lock: FileLock,
    ) : Closeable {
        val isValid: Boolean get() = lock.isValid && file.channel.isOpen

        override fun close() = synchronized(localLeases) {
            // Closing a channel releases its locks even if an explicit release fails.
            try {
                if (lock.isValid) lock.release()
            } finally {
                try {
                    file.close()
                } finally {
                    if (localLeases[ownerDirectory] === this) localLeases.remove(ownerDirectory)
                }
            }
        }
    }

    companion object {
        private val localLeases = mutableMapOf<File, Lease>()
    }
}
