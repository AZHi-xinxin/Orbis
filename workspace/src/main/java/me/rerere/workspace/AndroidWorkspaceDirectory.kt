package me.rerere.workspace

import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Same pinned-directory approach as consultation references; never falls back to shell/path IO. */
internal class AndroidWorkspaceDirectory private constructor(private val descriptor: ParcelFileDescriptor) : WorkspaceNativeDirectory {
    private fun child(name: String): String {
        require(validWorkspaceComponent(name)) { "workspace_path_denied" }
        return "/proc/self/fd/${descriptor.fd}/$name"
    }

    override fun directory(name: String, create: Boolean): WorkspaceNativeDirectory {
        val path = child(name)
        if (create) try { Os.mkdir(path, 448) } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw failure
        }
        return AndroidWorkspaceDirectory(openDescriptor(path, true))
    }

    override fun read(name: String): WorkspaceNativeRead = Read(openDescriptor(child(name), false))

    override fun writeAtomic(name: String, bytes: ByteArray, overwrite: Boolean, expectedRevision: String?): WorkspaceNativeStat {
        val target = child(name)
        fun checkTarget() {
            val stat = try { Os.lstat(target) } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ENOENT) null else throw failure
            }
            require(stat == null || OsConstants.S_ISREG(stat.st_mode)) { "workspace_target_not_regular" }
            require(overwrite || stat == null) { "workspace_file_exists" }
            if (expectedRevision != null) {
                require(stat != null) { "workspace_file_changed" }
                require(read(name).use { it.stat().revision } == expectedRevision) { "workspace_file_changed" }
            }
        }
        checkTarget()
        val temporaryName = ".orbis-write-${UUID.randomUUID()}.tmp"
        val temporary = child(temporaryName)
        val fd = Os.open(temporary, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or O_CLOEXEC, 384)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(fd, bytes, offset, bytes.size - offset)
                check(written > 0) { "workspace_write_failed" }
                offset += written
            }
            Os.fsync(fd)
            val written = Os.fstat(fd)
            return finishWorkspaceWrite(commit = {
                checkTarget()
                if (overwrite) Os.rename(temporary, target)
                else WorkspaceAtomicRename.noReplace(descriptor.fd, temporaryName, name)
            }, verify = {
                val saved = Os.lstat(target)
                check(OsConstants.S_ISREG(saved.st_mode) && saved.st_dev == written.st_dev && saved.st_ino == written.st_ino) {
                    "workspace_file_changed"
                }
                Os.fsync(descriptor.fileDescriptor)
                read(name).use { it.stat() }
            }, cleanup = { Os.remove(temporary) })
        } finally {
            // Cleanup cannot erase the evidence that rename already committed.
            runCatching { Os.close(fd) }
            runCatching { Os.remove(temporary) }
        }
    }

    override fun close() = descriptor.close()

    private class Read(private val descriptor: ParcelFileDescriptor) : WorkspaceNativeRead {
        override fun stat(): WorkspaceNativeStat {
            val before = Os.fstat(descriptor.fileDescriptor)
            check(OsConstants.S_ISREG(before.st_mode)) { "workspace_target_not_regular" }
            val snapshot = before.snapshot()
            if (Build.VERSION.SDK_INT >= 27) return snapshot
            // API 26 exposes only second-resolution timestamps. Add a bounded streaming
            // fingerprint instead of claiming equivalent revision detection from those fields.
            require(before.st_size in 0..(256L * 1024 * 1024)) { "workspace_legacy_revision_size_limit" }
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            while (offset < before.st_size) {
                val bytes = readAt(offset, minOf(64L * 1024, before.st_size - offset).toInt())
                check(bytes.isNotEmpty()) { "workspace_file_changed" }
                digest.update(bytes); offset += bytes.size
            }
            check(Os.fstat(descriptor.fileDescriptor).snapshot() == snapshot) { "workspace_file_changed" }
            return snapshot.copy(revision = snapshot.revision + ":" + digest.digest().joinToString("") { "%02x".format(it) })
        }
        override fun readAt(offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var count = 0
            while (count < length) {
                val read = Os.pread(descriptor.fileDescriptor, bytes, count, length - count, offset + count)
                if (read == 0) break
                check(read > 0) { "workspace_read_failed" }
                count += read
            }
            return if (count == length) bytes else bytes.copyOf(count)
        }
        override fun close() = descriptor.close()
    }

    companion object {
        private const val O_CLOEXEC = 0x80000
        // Linux UAPI fcntl.h, verified against bundled NDK 28.2; usable on minSdk 26.
        private const val F_DUPFD_CLOEXEC = 1030
        fun openRoot(anchor: File): WorkspaceNativeDirectory {
            return AndroidWorkspaceDirectory(openDescriptor(anchor.absolutePath, true))
        }
        private fun openDescriptor(path: String, directory: Boolean): ParcelFileDescriptor {
            val raw = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            try {
                val stat = Os.fstat(raw)
                check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)) {
                    "workspace_target_not_regular"
                }
                // Do not use pre-29 PFD.dup, which may leave a process-spawn race.
                return ParcelFileDescriptor.adoptFd(Os.fcntlInt(raw, F_DUPFD_CLOEXEC, 0))
            } finally { runCatching { Os.close(raw) } }
        }
        private fun StructStat.snapshot(): WorkspaceNativeStat {
            val nanos = if (Build.VERSION.SDK_INT >= 27) ":${st_mtim.tv_nsec}:${st_ctim.tv_nsec}" else ""
            return WorkspaceNativeStat(st_size, st_mtime * 1000, "$st_dev:$st_ino:$st_size:$st_mtime:$st_ctime$nanos")
        }
    }
}
