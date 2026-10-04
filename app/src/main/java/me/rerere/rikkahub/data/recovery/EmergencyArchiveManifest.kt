package me.rerere.rikkahub.data.recovery

import java.io.File
import kotlinx.serialization.Serializable

/** Only the caller may establish the no-writers condition; this component never opens a database. */
data class EmergencyArchiveRoot(val name: String, val directory: File)

@Serializable
data class EmergencyArchiveMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
data class EmergencyArchiveSourceRoot(
    val name: String,
    val originalPath: String,
    val present: Boolean,
)

@Serializable
data class EmergencyArchiveFileRecord(
    /** Relative to the original app data directory, e.g. databases/rikkahub.db-wal. */
    val path: String,
    val size: Long,
    val sha256: String,
    val lastModifiedEpochMs: Long,
)

@Serializable
data class EmergencyArchiveLinkRecord(val path: String, val target: String)

@Serializable
data class EmergencyArchiveSpecialFileRecord(val path: String, val kind: String)

@Serializable
data class EmergencyArchiveManifest(
    // Keep the decoder default at 1 for old manifests which omitted this field. New writers
    // explicitly emit schema 2; its ZIP payload names are opaque IDs, not original filenames.
    val schemaVersion: Int = 1,
    val status: String = "complete",
    val metadata: EmergencyArchiveMetadata,
    val roots: List<EmergencyArchiveSourceRoot>,
    val files: List<EmergencyArchiveFileRecord>,
    val directories: List<String>,
    /** Links are evidence only. Neither export nor extraction follows or recreates them. */
    val links: List<EmergencyArchiveLinkRecord> = emptyList(),
    /** FIFOs, sockets and device nodes are runtime objects, never read or recreated. */
    val specialFiles: List<EmergencyArchiveSpecialFileRecord> = emptyList(),
)

/** A zero total is unknown/empty, never a claim that scanning is already finished. */
data class EmergencyArchiveProgress(
    val phase: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val completedEntries: Long = 0L,
    val totalEntries: Long = 0L,
)
