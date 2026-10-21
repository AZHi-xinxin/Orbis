package me.rerere.rikkahub.data.sync

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** The implementation must atomically replace the ONE committed object, or leave it unchanged.
 * Null means the destination must not exist; a UUID must match its current embedded generation.
 * requireEmpty additionally forbids any strict v1 completion marker under the same mutation lock.
 * No implementation may emulate replacement by deleting the committed object first.
 */
interface AtomicBackupObjectStore : BackupObjectStore {
    suspend fun readPrefix(name: String, maxBytes: Int): ByteArray
    suspend fun replaceLatest(temporaryName: String, expectedGeneration: String?, requireEmpty: Boolean = false)
}

/** Generated names only. Remote hrefs and model-supplied paths never become object names. */
object OverwriteBackupNames {
    const val LATEST = "orbis_latest_v2.backup"
    const val MAX_HEADER_BYTES = 4108
    const val MAX_BUNDLE_BYTES = BackupRestorePoint.MAX_BYTES + 4108L
    private const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
    private val generation = Regex(UUID_PATTERN)
    private val temporary = Regex("orbis_upload_($UUID_PATTERN)\\.tmp")
    private val legacy = Regex("orbis_v1_$UUID_PATTERN\\.(payload|complete\\.json)")

    fun isGeneration(value: String): Boolean = generation.matches(value)
    fun isTemporary(name: String): Boolean = temporary.matches(name)
    fun isAllowed(name: String): Boolean = name == LATEST || isTemporary(name) || legacy.matches(name)
    fun requireAllowed(name: String): String = name.also { require(isAllowed(it)) { "backup_object_name" } }
    fun temporary(id: String): String {
        require(isGeneration(id)) { "backup_generation" }
        return "orbis_upload_$id.tmp"
    }
}

/** One indivisible object: 8-byte magic, big-endian u32 JSON length, JSON, then exact original ZIP.
 * The digest and byte count describe the INNER ZIP, so the existing restore validator is unchanged.
 */
object OverwriteBackupBundle {
    private val magic = "ORBISB2\n".toByteArray(Charsets.US_ASCII)
    private const val MAX_JSON_BYTES = 4096
    private val json = Json { encodeDefaults = true }
    private data class Header(val point: BackupRestorePoint, val size: Int)

    fun readPrefix(bytes: ByteArray): BackupRestorePoint {
        require(bytes.size <= OverwriteBackupNames.MAX_HEADER_BYTES) { "backup_header_size" }
        return header(ByteArrayInputStream(bytes)).point
    }

    private fun validate(point: BackupRestorePoint) {
        point.validate()
        require(point.version == 2 && OverwriteBackupNames.isGeneration(point.id)) { "backup_bundle_version" }
    }

    private fun header(input: InputStream): Header {
        val data = DataInputStream(input)
        val actualMagic = ByteArray(magic.size)
        data.readFully(actualMagic)
        require(actualMagic.contentEquals(magic)) { "backup_bundle_magic" }
        // A u32 outside 1..4096 also fails when interpreted as a negative signed Int.
        val length = data.readInt()
        require(length in 1..MAX_JSON_BYTES) { "backup_header_size" }
        val text = ByteArray(length)
        data.readFully(text)
        val point = json.decodeFromString<BackupRestorePoint>(text.decodeToString(throwOnInvalidSequence = true))
        validate(point)
        return Header(point, magic.size + 4 + length)
    }

    internal suspend fun write(archive: File, destination: File, point: BackupRestorePoint) {
        validate(point)
        require(archive.isFile && archive.length() == point.bytes) { "backup_archive_changed" }
        val metadata = json.encodeToString(point).toByteArray(Charsets.UTF_8)
        require(metadata.size in 1..MAX_JSON_BYTES) { "backup_header_size" }
        ArchiveCapacity.requireSpace(destination.parentFile!!.usableSpace, point.bytes + metadata.size + 12L)
        val coroutine = currentCoroutineContext()
        FileOutputStream(destination).use { output ->
            val head = DataOutputStream(output)
            head.write(magic); head.writeInt(metadata.size); head.write(metadata)
            // The existing disk/output budget still counts only ZIP bytes; do not enlarge it.
            val zip = NativeBackupBudget.Output(output) { destination.parentFile!!.usableSpace }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            archive.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= point.bytes) { "backup_archive_changed" }
                    digest.update(buffer, 0, size); zip.write(buffer, 0, size)
                }
            }
            require(count == point.bytes && hex(digest) == point.sha256) { "backup_archive_changed" }
            zip.flush(); output.fd.sync()
        }
    }

    /** Verification never writes the destination ZIP or invokes any restore operation. */
    internal suspend fun verify(file: File, expected: BackupRestorePoint): BackupRestorePoint {
        validate(expected)
        require(file.isFile && file.length() in 1..OverwriteBackupNames.MAX_BUNDLE_BYTES) { "backup_bundle_size" }
        val coroutine = currentCoroutineContext()
        file.inputStream().buffered().use { input ->
            val header = header(input)
            require(header.point == expected) { "backup_generation_changed" }
            require(file.length() == header.size.toLong() + expected.bytes) { "backup_bundle_size" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutine.ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                require(count <= expected.bytes) { "backup_bundle_size" }
                digest.update(buffer, 0, size)
            }
            require(count == expected.bytes && hex(digest) == expected.sha256) { "backup_checksum_mismatch" }
        }
        return expected
    }

    /** Rechecks the immutable downloaded bundle while extracting, then atomically exposes the ZIP. */
    internal suspend fun extract(file: File, expected: BackupRestorePoint, destination: File) {
        validate(expected)
        require(!Files.isSymbolicLink(destination.toPath())) { "backup_destination_symlink" }
        val directory = requireNotNull(destination.absoluteFile.parentFile)
        require(directory.isDirectory) { "backup_destination_directory" }
        ArchiveCapacity.requireSpace(directory.usableSpace, expected.bytes)
        val prepared = File.createTempFile("orbis-extract-", ".zip", directory)
        try {
            val coroutine = currentCoroutineContext()
            file.inputStream().buffered().use { input ->
                val head = header(input)
                require(head.point == expected && file.length() == head.size.toLong() + expected.bytes) {
                    "backup_generation_changed"
                }
                FileOutputStream(prepared).use { output ->
                    val zip = NativeBackupBudget.Output(output) { directory.usableSpace }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= expected.bytes) { "backup_bundle_size" }
                        digest.update(buffer, 0, size); zip.write(buffer, 0, size)
                    }
                    require(count == expected.bytes && hex(digest) == expected.sha256) { "backup_checksum_mismatch" }
                    zip.flush(); output.fd.sync()
                }
            }
            coroutine.ensureActive()
            Files.move(prepared.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { prepared.delete() }
    }

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }
}

/** One fixed completed object. Upload objects are temporary work, never visible restore versions. */
class OverwriteBackup(private val remote: AtomicBackupObjectStore, private val temporaryDirectory: File) {
    private val legacy = VersionedBackup(remote, temporaryDirectory)

    suspend fun list(): List<BackupRestorePoint> {
        return if (OverwriteBackupNames.LATEST in remote.listNames()) listOf(readCurrent()) else legacy.list()
    }

    suspend fun publish(archive: File, connectionSecrets: Boolean, now: Long = System.currentTimeMillis(),
        requireEmpty: Boolean = false): BackupRestorePoint {
        require(archive.isFile && archive.length() in 1..BackupRestorePoint.MAX_BYTES) { "backup_archive_size" }
        // A fresh installation's earlier empty listing is not an overwrite grant. Preserve that
        // absence condition until the server commits, even if another writer publishes meanwhile.
        val expectedGeneration = if (!requireEmpty && OverwriteBackupNames.LATEST in remote.listNames()) readCurrent().id else null
        val point = BackupRestorePoint(version = 2, id = UUID.randomUUID().toString(), createdAt = now,
            bytes = archive.length(), sha256 = VersionedBackup.digest(archive), includesConnectionSecrets = connectionSecrets)
        point.validate()
        val temporaryName = OverwriteBackupNames.temporary(point.id)
        val bundle = temp()
        var check: File? = null
        var uploadAttempted = false
        try {
            val verification = temp().also { check = it }
            OverwriteBackupBundle.write(archive, bundle, point)
            currentCoroutineContext().ensureActive()
            uploadAttempted = true
            remote.upload(temporaryName, bundle)
            verifyRemote(temporaryName, verification, point)
            currentCoroutineContext().ensureActive()
            try {
                remote.replaceLatest(temporaryName, expectedGeneration, requireEmpty)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (uncertain: Exception) {
                // A server may commit replacement and lose its response. Read once; never repeat MOVE/PUT.
                try { verifyRemote(OverwriteBackupNames.LATEST, verification, point) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (notConfirmed: Exception) { uncertain.addSuppressed(notConfirmed); throw uncertain }
                return point
            }
            verifyRemote(OverwriteBackupNames.LATEST, verification, point)
            return point
        } finally {
            bundle.delete(); check?.delete()
            if (uploadAttempted) cleanupTemporary(temporaryName)
        }
    }

    suspend fun download(point: BackupRestorePoint, destination: File) {
        point.validate()
        if (point.version == 1) { legacy.download(point, destination); return }
        val bundle = temp()
        try {
            verifyRemote(OverwriteBackupNames.LATEST, bundle, point)
            OverwriteBackupBundle.extract(bundle, point, destination)
        } finally { bundle.delete() }
    }

    /** One-time format migration only, AFTER the current fixed object is fully reverified.
     * Ignore unmarked/in-progress objects and malformed or unverifiable legacy pairs. Never
     * remove newer legacy snapshots made concurrently by an older installation.
     */
    suspend fun cleanupLegacyAfterPublished(point: BackupRestorePoint) {
        require(point.version == 2) { "backup_bundle_version" }
        if (remote.listNames().none(BackupRestorePoint::isMarker)) return
        val check = temp()
        try {
            verifyRemote(OverwriteBackupNames.LATEST, check, point)
            for (old in legacy.list().filter { it.createdAt <= point.createdAt }) {
                currentCoroutineContext().ensureActive()
                try { legacy.download(old, check) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                // Both exact generated names came from a valid marker and a verified payload.
                remote.delete(old.markerName)
                remote.delete(old.payloadName)
            }
        } finally { check.delete() }
    }

    private suspend fun readCurrent(): BackupRestorePoint = OverwriteBackupBundle.readPrefix(
        remote.readPrefix(OverwriteBackupNames.LATEST, OverwriteBackupNames.MAX_HEADER_BYTES))

    private suspend fun verifyRemote(name: String, destination: File, point: BackupRestorePoint) {
        point.validate()
        require(point.version == 2 && OverwriteBackupNames.isGeneration(point.id)) { "backup_bundle_version" }
        remote.download(name, destination, point.bytes + OverwriteBackupNames.MAX_HEADER_BYTES)
        OverwriteBackupBundle.verify(destination, point)
    }

    private suspend fun cleanupTemporary(name: String) = withContext(NonCancellable) {
        try { withTimeout(5_000L) { remote.delete(name) } }
        catch (_: Exception) { /* Only this attempt's temporary object; never hide the publication result. */ }
    }

    private fun temp(): File = File.createTempFile("orbis-overwrite-", ".tmp", temporaryDirectory)
}
