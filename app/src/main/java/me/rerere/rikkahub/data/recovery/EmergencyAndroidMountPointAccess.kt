package me.rerere.rikkahub.data.recovery

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

private const val EMERGENCY_O_PATH = 0x200000 // Linux O_PATH, ABI-stable on Android ABIs.

/** Android-specific inode pinning; intentionally no chmod(user-supplied-path) fallback. */
internal class EmergencyAndroidMountPointAccess(
    private val filesRoot: File,
    override val ownerUid: Int = Process.myUid(),
) : EmergencyMountPointAccess {
    private val rootPath = filesRoot.toPath().toAbsolutePath().normalize()

    private fun resolve(relative: String): String {
        if (relative.isNotEmpty() && (relative.startsWith('/') || '\\' in relative || ':' in relative ||
                relative.split('/').any { it.isEmpty() || it == "." || it == ".." })) {
            throw IOException("emergency_mount_not_allowed")
        }
        return if (relative.isEmpty()) rootPath.toString() else rootPath.resolve(relative).toString()
    }

    override fun metadata(path: String): EmergencyMountIdentity? = try {
        identity(Os.lstat(resolve(path)))
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw IOException("emergency_mount_metadata_unreadable", error)
    }

    override fun children(path: String): List<String> = Files.newDirectoryStream(File(resolve(path)).toPath()).use {
        val result = ArrayList<String>()
        for (item in it) {
            if (result.size >= 4096) throw IOException("emergency_mount_too_many_workspaces")
            result += item.fileName.toString()
        }
        result
    }

    override fun pin(path: String, expected: EmergencyMountIdentity): EmergencyMountPointAccess.Pinned {
        if (!isEmergencyMountPoint(path)) throw IOException("emergency_mount_not_allowed")
        // Linux O_PATH is ABI-stable (010000000) but not exposed by every Android SDK.
        // O_PATH does not open a device/FIFO for I/O, and O_NOFOLLOW pins a final symlink
        // itself. fstat then rejects EVERY non-directory before any chmod/read operation.
        // Do not hard-code O_DIRECTORY: its value is architecture-dependent and it is not
        // exposed by this SDK. An intermediate race cannot bypass the exact inode check.
        val descriptor = try {
            Os.open(resolve(path), EMERGENCY_O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (error: Exception) { throw IOException("emergency_mount_pin_unavailable", error) }
        val duplicate = try {
            val actual = identity(Os.fstat(descriptor))
            if (actual != expected || !actual.directory || actual.uid != ownerUid || actual.gid != ownerUid) {
                throw IOException("emergency_mount_source_changed")
            }
            val pinned = ParcelFileDescriptor.dup(descriptor)
            try {
                Os.fcntlInt(pinned.fileDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
                pinned
            } catch (error: Throwable) { pinned.close(); throw error }
        } finally { Os.close(descriptor) }
        return object : EmergencyMountPointAccess.Pinned {
            private var readable: FileDescriptor? = null
            override fun identity(): EmergencyMountIdentity = this@EmergencyAndroidMountPointAccess.identity(Os.fstat(duplicate.fileDescriptor))
            override fun setMode(mode: Int) {
                if (mode !in setOf(0, 320) || !identity().sameObject(expected)) {
                    throw IOException("emergency_mount_source_changed")
                }
                // fchmod(O_PATH) is EBADF. This kernel-owned magic link identifies OUR held
                // descriptor, not an archive/workspace symlink. It cannot be redirected by a
                // pathname swap. Unsupported procfs/SELinux environments fail closed.
                try {
                    val pinnedPath = "/proc/self/fd/${duplicate.fd}"
                    if (mode == 320) Os.chmod(pinnedPath, mode)
                    if (readable == null && identity().mode == 320) {
                        try {
                            // The held inode has already been proven to be a directory; its
                            // object type cannot change when its original pathname is raced.
                            val opened = Os.open(pinnedPath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                            if (!this@EmergencyAndroidMountPointAccess.identity(Os.fstat(opened)).sameObject(expected)) {
                                Os.close(opened)
                                throw IOException("emergency_mount_source_changed")
                            }
                            readable = opened
                        } catch (error: Exception) {
                            // Closing permissions still takes priority if a platform refuses
                            // the readable descriptor. The durable intent remains available.
                            if (mode == 0) Os.chmod(pinnedPath, 0)
                            throw error
                        }
                    }
                    if (mode == 0) {
                        val opened = readable
                        if (opened != null) Os.fchmod(opened, 0) else Os.chmod(pinnedPath, 0)
                    }
                    // With a successful grant we retain this descriptor throughout copying,
                    // so restored metadata can be synced AFTER fchmod even when now unreadable.
                    readable?.let { Os.fsync(it) }
                } catch (error: Exception) { throw IOException("emergency_mount_permission_unavailable", error) }
                val after = identity()
                if (!after.sameObject(expected) || after.mode != mode) throw IOException("emergency_mount_source_changed")
            }
            override fun close() {
                try { readable?.let { Os.close(it) } } finally { readable = null; duplicate.close() }
            }
        }
    }

    private fun identity(stat: StructStat) = EmergencyMountIdentity(
        device = stat.st_dev, inode = stat.st_ino, uid = stat.st_uid, gid = stat.st_gid,
        mode = stat.st_mode and 0xfff, directory = OsConstants.S_ISDIR(stat.st_mode),
    )

}

/** Pin and prove a real owned directory BEFORE opening it for sync; no O_DIRECTORY ABI guess. */
internal fun syncEmergencyMountJournalDirectory(directory: File) {
    val descriptor = Os.open(directory.path, EMERGENCY_O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
    try {
        val original = Os.fstat(descriptor)
        if (!OsConstants.S_ISDIR(original.st_mode) || original.st_uid != Process.myUid()) {
            throw IOException("emergency_mount_journal_invalid")
        }
        ParcelFileDescriptor.dup(descriptor).use { pinned ->
            Os.fcntlInt(pinned.fileDescriptor, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            val readable = Os.open("/proc/self/fd/${pinned.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                val actual = Os.fstat(readable)
                if (!OsConstants.S_ISDIR(actual.st_mode) || actual.st_dev != original.st_dev ||
                    actual.st_ino != original.st_ino || actual.st_uid != original.st_uid) {
                    throw IOException("emergency_mount_journal_invalid")
                }
                Os.fsync(readable)
            } finally { Os.close(readable) }
        }
    } finally { Os.close(descriptor) }
}

/** All journals are outside the four backed-up roots, with no plaintext chat/settings content. */
@OptIn(ExperimentalSerializationApi::class)
internal class EmergencyFileMountPointJournal(
    private val directory: File,
    private val syncDirectory: (File) -> Unit,
) : EmergencyMountPointJournal {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private fun journalPath() = directory.toPath().resolve("pending.json")

    private fun requireDirectory() {
        val path = directory.toPath().toAbsolutePath().normalize()
        val parent = path.parent ?: throw IOException("emergency_mount_journal_invalid")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || parent.toRealPath() != parent) {
            throw IOException("emergency_mount_journal_invalid")
        }
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) {
            throw IOException("emergency_mount_journal_invalid")
        }
    }

    override fun read(): EmergencyMountJournalValue? {
        requireDirectory()
        val path = journalPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() > MAX_BYTES) throw IOException("emergency_mount_journal_invalid")
        return try {
            Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
                json.decodeFromStream<EmergencyMountJournalValue>(EmergencyJsonInput(stream, MAX_BYTES))
            }
        } catch (error: Exception) { throw IOException("emergency_mount_journal_invalid", error) }
    }

    override fun create(value: EmergencyMountJournalValue) {
        requireDirectory()
        if (Files.exists(journalPath(), NOFOLLOW_LINKS)) throw IOException("emergency_mount_restore_required")
        val temporary = directory.toPath().resolve(".intent-${UUID.randomUUID()}.tmp")
        FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val stream = Channels.newOutputStream(channel)
            json.encodeToStream(value, stream)
            stream.flush()
            if (channel.size() > MAX_BYTES) throw IOException("emergency_mount_journal_invalid")
            channel.force(true)
        }
        Files.move(temporary, journalPath()) // No replacement, never delete another pending intent.
        syncDirectory(directory)
        // Also persist the newly created journal directory in its trusted parent.
        syncDirectory(directory.parentFile)
    }

    override fun clear(expected: EmergencyMountJournalValue) {
        if (read() != expected) throw IOException("emergency_mount_journal_invalid")
        // Keep the original durable intent as evidence even after mode 000 is observed.
        // A restart can find an already-zero inode without a readable fd to sync; never
        // reopen that inode just to retire the active marker or trap the human in rescue.
        Files.move(journalPath(), directory.toPath().resolve("observed-restored-${UUID.randomUUID()}.json"))
        syncDirectory(directory)
    }

    companion object { private const val MAX_BYTES = 512L * 1024 }
}

internal fun emergencyMountPointLease(context: Context): EmergencyMountPointLease {
    val access = EmergencyAndroidMountPointAccess(context.filesDir)
    val journal = EmergencyFileMountPointJournal(File(EmergencyAndroidRuntime.directory(context), "mount-permissions"),
        ::syncEmergencyMountJournalDirectory)
    return EmergencyMountPointLease(access, journal) { EmergencyAndroidRuntime.checkPaused(context) }
}
