package me.rerere.rikkahub.data.recovery

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.serialization.Serializable

/** Only this exact historical PRoot bind-point shape may receive temporary read/search access. */
internal fun isEmergencyMountPoint(path: String): Boolean = path.matches(Regex(
    "workspaces/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/linux/(workspace|skills)"))

@Serializable
internal data class EmergencyMountIdentity(
    val device: Long,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    /** Permission bits only, including special bits. No group/world access is added. */
    val mode: Int,
    val directory: Boolean,
) {
    fun sameObject(other: EmergencyMountIdentity): Boolean = copy(mode = 0) == other.copy(mode = 0)
}

@Serializable
internal data class EmergencyMountRecord(val path: String, val original: EmergencyMountIdentity)

@Serializable
internal data class EmergencyMountJournalValue(
    val schema: Int = 1,
    val sourceRoot: EmergencyMountIdentity,
    val records: List<EmergencyMountRecord>,
)

/** Production pinning MUST bind an inode before chmod; a path-based chmod fallback is forbidden. */
internal interface EmergencyMountPointAccess {
    val ownerUid: Int
    fun metadata(path: String): EmergencyMountIdentity?
    fun children(path: String): List<String>
    fun pin(path: String, expected: EmergencyMountIdentity): Pinned

    interface Pinned : Closeable {
        fun identity(): EmergencyMountIdentity
        fun setMode(mode: Int)
    }
}

internal interface EmergencyMountPointJournal {
    fun read(): EmergencyMountJournalValue?
    /** Returns only after both the file and its parent-directory entry are durable. */
    fun create(value: EmergencyMountJournalValue)
    /** Retire the active intent only after mode 000 was observed on every original inode.
     * Preserve it as evidence; an already-zero inode after restart is never reopened merely
     * to obtain an fsync descriptor. This is not a claim of universal power-loss atomicity.
     */
    fun clear(expected: EmergencyMountJournalValue)
}

/**
 * Cold rescue only; ordinary exports never use this permission exception. Actual child files
 * are copied by EmergencyArchive, not assumed absent just because PRoot normally binds here.
 * A durable intent is written before any permission change. Original mode is always exactly 0.
 * No recursive chmod, link following, file repair, original deletion, or process killing occurs.
 */
internal class EmergencyMountPointLease(
    private val access: EmergencyMountPointAccess,
    private val journal: EmergencyMountPointJournal,
    private val checkWritersStopped: () -> Unit,
) {
    private fun failure(code: String): Nothing = throw IOException(code)
    private fun requireOwned(info: EmergencyMountIdentity) {
        if (!info.directory || info.uid != access.ownerUid || info.gid != access.ownerUid) {
            failure("emergency_mount_not_allowed")
        }
    }

    private fun root(): EmergencyMountIdentity? = access.metadata("")?.also(::requireOwned)

    private fun checkRoot(expected: EmergencyMountIdentity) {
        val current = root() ?: failure("emergency_mount_source_changed")
        if (!current.sameObject(expected)) failure("emergency_mount_source_changed")
    }

    private fun checkPath(record: EmergencyMountRecord, modes: Set<Int>): EmergencyMountIdentity {
        if (!isEmergencyMountPoint(record.path)) failure("emergency_mount_not_allowed")
        val segments = record.path.split('/')
        for (length in 1 until segments.size) {
            requireOwned(access.metadata(segments.take(length).joinToString("/"))
                ?: failure("emergency_mount_source_changed"))
        }
        val current = access.metadata(record.path) ?: failure("emergency_mount_source_changed")
        requireOwned(current)
        if (!current.sameObject(record.original) || current.mode !in modes) failure("emergency_mount_source_changed")
        return current
    }

    private fun validate(value: EmergencyMountJournalValue) {
        if (value.schema != 1 || value.records.isEmpty() || value.records.size > 1024 ||
            value.records.map { it.path }.toSet().size != value.records.size) failure("emergency_mount_journal_invalid")
        requireOwned(value.sourceRoot)
        value.records.forEach {
            if (!isEmergencyMountPoint(it.path) || it.original.mode != 0) failure("emergency_mount_journal_invalid")
            requireOwned(it.original)
        }
        checkRoot(value.sourceRoot)
    }

    private fun candidates(): EmergencyMountJournalValue? {
        val sourceRoot = root() ?: return null
        val workspaces = access.metadata("workspaces") ?: return null
        // A link/foreign object is not made traversable. The ordinary archive policy still
        // handles it (or fails) without this helper widening anything.
        if (!workspaces.directory) return null
        requireOwned(workspaces)
        val names = access.children("workspaces")
        if (names.size > 4096) failure("emergency_mount_too_many_workspaces")
        val records = mutableListOf<EmergencyMountRecord>()
        for (name in names.sorted()) {
            checkInterrupted()
            if (!isEmergencyMountPoint("workspaces/$name/linux/workspace")) continue
            val workspace = access.metadata("workspaces/$name") ?: continue
            if (!workspace.directory) continue
            requireOwned(workspace)
            val linux = access.metadata("workspaces/$name/linux") ?: continue
            if (!linux.directory) continue
            requireOwned(linux)
            for (leaf in listOf("workspace", "skills")) {
                val path = "workspaces/$name/linux/$leaf"
                val info = access.metadata(path) ?: continue
                if (!info.directory || info.mode != 0) continue
                requireOwned(info)
                val record = EmergencyMountRecord(path, info)
                checkPath(record, setOf(0))
                records += record
                if (records.size > 1024) failure("emergency_mount_too_many_workspaces")
            }
        }
        checkRoot(sourceRoot)
        return if (records.isEmpty()) null else EmergencyMountJournalValue(sourceRoot = sourceRoot, records = records)
    }

    /**
     * Run before allowing exit from rescue or another archive/restore. A crash after chmod
     * leaves the intent. Never chmod a replacement inode or an unrecognized journal path.
     * Closing permissions intentionally does NOT depend on the no-writers condition.
     */
    fun recoverInterrupted() {
        val value = journal.read() ?: return
        val interrupted = Thread.interrupted()
        try {
            try {
                validate(value)
                var failed: Throwable? = null
                value.records.forEach { record ->
                    try {
                        val current = checkPath(record, setOf(0, READ_MODE))
                        access.pin(record.path, current).use { pin ->
                            restorePinned(pin, record)
                            checkPath(record, setOf(0))
                        }
                    } catch (error: Throwable) {
                        if (failed == null) failed = error else failed!!.addSuppressed(error)
                    }
                }
                failed?.let { throw it }
                checkRoot(value.sourceRoot)
                journal.clear(value)
            } catch (error: Throwable) {
                throw IOException("emergency_mount_restore_required", error)
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun <T> withReadAccess(block: () -> T): T {
        recoverInterrupted()
        checkInterrupted()
        checkWritersStopped()
        val value = candidates() ?: return block()
        val pins = mutableListOf<Pair<EmergencyMountRecord, EmergencyMountPointAccess.Pinned>>()
        var intentCreated = false
        var operationError: Throwable? = null
        try {
            value.records.forEach { record ->
                checkInterrupted()
                checkWritersStopped()
                checkRoot(value.sourceRoot)
                val current = checkPath(record, setOf(0))
                pins += record to access.pin(record.path, current)
            }
            journal.create(value)
            intentCreated = true
            pins.forEach { (record, pin) ->
                checkInterrupted()
                checkWritersStopped()
                checkRoot(value.sourceRoot)
                checkPath(record, setOf(0))
                checkPinned(pin, record, setOf(0))
                pin.setMode(READ_MODE)
                checkPinned(pin, record, setOf(READ_MODE))
                checkPath(record, setOf(READ_MODE))
            }
            checkWritersStopped()
            return block().also {
                checkInterrupted()
                checkWritersStopped()
                checkRoot(value.sourceRoot)
                value.records.forEach { checkPath(it, setOf(READ_MODE)) }
            }
        } catch (error: Throwable) {
            operationError = error
            throw error
        } finally {
            // Java channel interruption must not prevent closing an already-open permission.
            val interrupted = Thread.interrupted()
            var restorationError: Throwable? = null
            try {
                if (intentCreated) {
                    pins.forEach { (record, pin) ->
                        try {
                            // Pinned old inode is closed even when its pathname was replaced.
                            restorePinned(pin, record)
                            checkPath(record, setOf(0))
                        } catch (error: Throwable) {
                            if (restorationError == null) restorationError = error else restorationError!!.addSuppressed(error)
                        }
                    }
                    if (restorationError == null) try {
                        checkRoot(value.sourceRoot)
                        journal.clear(value)
                    } catch (error: Throwable) { restorationError = error }
                }
            } finally {
                pins.forEach { (_, pin) ->
                    try { pin.close() } catch (error: Throwable) {
                        if (restorationError == null) restorationError = error else restorationError!!.addSuppressed(error)
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            restorationError?.let {
                throw IOException("emergency_mount_restore_required", it).also { error -> operationError?.let(error::addSuppressed) }
            }
        }
    }

    private fun checkPinned(pin: EmergencyMountPointAccess.Pinned, record: EmergencyMountRecord, modes: Set<Int>) {
        val current = pin.identity()
        if (!current.sameObject(record.original) || current.mode !in modes) failure("emergency_mount_source_changed")
    }

    private fun restorePinned(pin: EmergencyMountPointAccess.Pinned, record: EmergencyMountRecord) {
        checkPinned(pin, record, setOf(0, READ_MODE))
        if (pin.identity().mode != 0) pin.setMode(0)
        checkPinned(pin, record, setOf(0))
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("emergency_mount_interrupted")
    }

    companion object { private const val READ_MODE = 320 /* 0500 */ }
}

/** Publish a resumable .zip only AFTER original permissions have been restored and checked. */
internal fun createEmergencyArchiveWithMountAccess(
    lease: EmergencyMountPointLease,
    roots: List<EmergencyArchiveRoot>,
    destination: File,
    metadata: EmergencyArchiveMetadata,
    onProgress: (EmergencyArchiveProgress) -> Unit = {},
): EmergencyArchiveManifest {
    val output = destination.toPath().toAbsolutePath().normalize()
    if (Files.exists(output, NOFOLLOW_LINKS)) throw IOException("Backup destination already exists")
    val pending = output.parent.resolve(".orbis-permission-pending-${UUID.randomUUID()}").toFile()
    val manifest = lease.withReadAccess {
        EmergencyArchive.create(roots, pending, metadata) { progress ->
            if (progress.phase != "complete") onProgress(progress)
        }
    }
    // If restoration/cancellation fails, the pending raw package remains as evidence but is
    // not shown by the Activity's '*.zip' resume picker and cannot masquerade as success.
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("emergency_mount_interrupted")
    Files.move(pending.toPath(), output)
    val total = manifest.files.sumOf { it.size }
    onProgress(EmergencyArchiveProgress("complete", total, total))
    return manifest
}
