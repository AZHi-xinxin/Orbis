package me.rerere.rikkahub.data.sync

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AutoBackupConfig(
    val enabled: Boolean = false,
    val localEnabled: Boolean = true,
    val target: String = "webdav",
    val intervalHours: Int = 4,
    val allowMetered: Boolean = false,
    val keepVersions: Int = 7,
    val includeConnectionSecrets: Boolean = false,
    val archiveDirectoryUri: String? = null,
    // Ownership of AI-initiated backup operations; not an upload credential or an approval grant.
    val controllerAssistant: String? = null,
    val controllerWorkspace: String? = null,
    val controllerDestination: String? = null,
) {
    fun validate() {
        require(target in setOf("webdav", "s3"))
        require(intervalHours in setOf(1, 4, 12, 24) && keepVersions in 3..100)
        require(archiveDirectoryUri == null || (archiveDirectoryUri.startsWith("content://") && archiveDirectoryUri.length <= 8192))
    }
}

/** A marker is published LAST. Payload objects alone are never offered as restore points. */
@Serializable
data class BackupRestorePoint(
    val version: Int = 1,
    val id: String,
    val createdAt: Long,
    val bytes: Long,
    val sha256: String,
    val includesDatabase: Boolean = true,
    val includesFiles: Boolean = true,
    val includesConnectionSecrets: Boolean = false,
) {
    val payloadName get() = "orbis_v1_${id}.payload"
    val markerName get() = "orbis_v1_${id}.complete.json"
    fun validate() {
        require(version in 1..2 && id.matches(Regex("[a-f0-9-]{36}")) && createdAt >= 0)
        require(bytes in 1..MAX_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
    }
    companion object {
        const val MAX_BYTES = me.rerere.rikkahub.data.sync.importer.ArchiveCapacity.MAX_ZIP_BYTES
        const val MAX_MARKER_BYTES = 4096L
        fun isMarker(name: String) = name.matches(Regex("orbis_v1_[a-f0-9-]{36}\\.complete\\.json"))
    }
}

/** Names are app-generated, never raw remote hrefs. Implementations must bound downloads. */
interface BackupObjectStore {
    suspend fun listNames(): List<String>
    suspend fun upload(name: String, file: File)
    suspend fun download(name: String, file: File, maxBytes: Long)
    suspend fun delete(name: String)
}

class VersionedBackup(private val remote: BackupObjectStore, private val temporaryDirectory: File) {
    private val json = Json { encodeDefaults = true }

    suspend fun publish(archive: File, connectionSecrets: Boolean, now: Long = System.currentTimeMillis()): BackupRestorePoint {
        val point = BackupRestorePoint(id = UUID.randomUUID().toString(), createdAt = now,
            bytes = archive.length(), sha256 = digest(archive), includesConnectionSecrets = connectionSecrets)
        point.validate()
        val check = temp()
        val marker = temp()
        try {
            remote.upload(point.payloadName, archive)
            // An HTTP success or an ETag is not proof of the bytes stored remotely.
            remote.download(point.payloadName, check, point.bytes)
            verifyPayload(point, check)
            marker.writeText(json.encodeToString(point), Charsets.UTF_8)
            currentCoroutineContext().ensureActive()
            remote.upload(point.markerName, marker)
            remote.download(point.markerName, check, BackupRestorePoint.MAX_MARKER_BYTES)
            require(readMarker(check, point.markerName) == point) { "backup_marker_mismatch" }
            return point
        } finally {
            check.delete(); marker.delete()
            // Unknown upload outcomes are NOT retried or deleted here. The next snapshot has a new id.
        }
    }

    suspend fun list(): List<BackupRestorePoint> {
        val names = remote.listNames().filter(BackupRestorePoint::isMarker).distinct()
        require(names.size <= 10_000) { "backup_listing_too_large" }
        val file = temp()
        try {
            return names.mapNotNull { name ->
                currentCoroutineContext().ensureActive()
                remote.download(name, file, BackupRestorePoint.MAX_MARKER_BYTES)
                // Invalid/half-written markers are not a successful backup. Transport errors still surface.
                try { readMarker(file, name) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { null }
            }.sortedWith(compareByDescending<BackupRestorePoint> { it.createdAt }.thenBy { it.id })
        } finally { file.delete() }
    }

    suspend fun download(point: BackupRestorePoint, destination: File) {
        point.validate()
        require(point.version == 1) { "backup_legacy_version_required" }
        val marker = temp()
        try {
            remote.download(point.markerName, marker, BackupRestorePoint.MAX_MARKER_BYTES)
            require(readMarker(marker, point.markerName) == point) { "backup_marker_changed" }
            remote.download(point.payloadName, destination, point.bytes)
            verifyPayload(point, destination)
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally { marker.delete() }
    }

    /** Only after a verified publication; always keep the new snapshot plus at least two others. */
    suspend fun pruneAfterPublished(point: BackupRestorePoint, keep: Int) {
        require(point.version == 1) { "backup_legacy_version_required" }
        require(keep in 3..100)
        val points = list()
        require(points.any { it == point }) { "backup_not_published" }
        val retained = (listOf(point) + points.filterNot { it.id == point.id }).take(keep).map { it.id }.toSet()
        for (old in points.filterNot { it.id in retained }) {
            currentCoroutineContext().ensureActive()
            // Remove visibility first; a failed payload cleanup cannot expose a known-missing archive.
            remote.delete(old.markerName)
            remote.delete(old.payloadName)
        }
    }

    private fun readMarker(file: File, expectedName: String): BackupRestorePoint {
        require(file.length() in 1..BackupRestorePoint.MAX_MARKER_BYTES)
        return json.decodeFromString<BackupRestorePoint>(file.readText(Charsets.UTF_8)).also {
            it.validate(); require(it.version == 1) { "backup_legacy_version_required" }
            require(it.markerName == expectedName) { "backup_marker_name_mismatch" }
        }
    }
    private fun temp(): File = File.createTempFile("orbis-version-check-", ".tmp", temporaryDirectory)
    private suspend fun verifyPayload(point: BackupRestorePoint, file: File) {
        require(file.length() == point.bytes && digest(file) == point.sha256) { "backup_checksum_mismatch" }
    }
    companion object {
        suspend fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val coroutine = currentCoroutineContext()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
