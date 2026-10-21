package me.rerere.rikkahub.data.sync

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Host-created only, after VersionedBackup.download has verified this complete point. */
@Serializable
data class VerifiedCloudRestoreSource internal constructor(
    val destinationKey: String,
    val point: BackupRestorePoint,
) {
    internal fun validate() {
        require(destinationKey.matches(Regex("(webdav|s3)-[a-f0-9]{64}"))) { "invalid_restore_destination" }
        point.validate()
        require(point.includesDatabase && point.includesFiles) { "incomplete_restore_point" }
    }
}

/**
 * A staged download is not an applied restore. The existing PendingRestore commit atomically
 * renames pending to previous-UUID; only that committed directory can satisfy this proof.
 * An epoch, replaced for EVERY restore and consumed once, prevents historical proofs from
 * authorizing a later unrelated restore/binding. No URLs, credentials or chat content are stored.
 */
internal object CloudRestoreProof {
    private const val PROOF = "verified-cloud-source.json"
    private const val EXPECTED = "expected-cloud-restore"
    private const val MAX_BYTES = 4096L
    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    @Serializable
    private data class Envelope(val version: Int = 1, val epoch: String, val source: VerifiedCloudRestoreSource)

    /** Call only after all extraction/validation, immediately before publishing the staging dir. */
    fun stage(staging: File, source: VerifiedCloudRestoreSource?) = synchronized(lock) {
        // Android may expose trusted storage through /data/user/0 or /data/data. Normalize
        // that parent alias, but never follow a replaceable staging/proof leaf symlink.
        val root = requireNotNull(staging.parentFile).canonicalFile
        val canonicalStaging = staging.canonicalFile
        require(!Files.isSymbolicLink(staging.toPath()) && staging.isDirectory &&
            canonicalStaging.parentFile == root && canonicalStaging.name == staging.name &&
            staging.name.startsWith("preparing-")) { "invalid_restore_staging" }
        val pointer = child(root, EXPECTED)
        if (source == null) {
            // A local or ordinary archive restore must invalidate an older cloud continuation.
            write(pointer, "none")
        } else {
            source.validate()
            val replacement = child(canonicalStaging, "replace-local-tools")
            require(replacement.isFile && replacement.length() == 27L &&
                replacement.readText() == "explicit-version-restore-v1") { "version_restore_required" }
            val epoch = UUID.randomUUID().toString()
            val text = json.encodeToString(Envelope(epoch = epoch, source = source))
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            write(child(canonicalStaging, PROOF), text)
            // If this fails nothing is published. A crash before publish only invalidates the
            // previous permission; it cannot make the uncommitted payload a completed restore.
            write(pointer, epoch)
        }
    }

    /**
     * The callback persists reviewed=true and clears needsReview, but never marks a new upload
     * completed or enables backup. A failed callback keeps the token available for retry.
     */
    fun consumeApplied(root: File, destinationKey: String, acknowledge: () -> Unit): Boolean = synchronized(lock) {
        val canonicalRoot = root.canonicalFile
        if (!destinationKey.matches(Regex("(webdav|s3)-[a-f0-9]{64}")) || !canonicalRoot.isDirectory) return false
        val pointer = child(canonicalRoot, EXPECTED)
        if (!pointer.isFile || pointer.length() != 36L) return false
        val epoch = pointer.readText()
        if (!uuid.matches(epoch)) return false
        val committed = canonicalRoot.listFiles().orEmpty().asSequence().filter {
            it.name.startsWith("previous-") && uuid.matches(it.name.removePrefix("previous-")) &&
                !Files.isSymbolicLink(it.toPath()) && it.isDirectory && it.canonicalFile.parentFile == canonicalRoot
        }.firstOrNull { directory ->
            runCatching {
                val proof = child(directory, PROOF)
                if (!proof.isFile || proof.length() !in 1..MAX_BYTES ||
                    !child(directory, "journal.json").isFile) return@runCatching false
                val envelope = json.decodeFromString<Envelope>(proof.readText(Charsets.UTF_8))
                envelope.source.validate()
                envelope.version == 1 && envelope.epoch == epoch && envelope.source.destinationKey == destinationKey
            }.getOrDefault(false)
        } ?: return false
        check(committed.canonicalFile.parentFile == canonicalRoot)
        acknowledge()
        // Atomic tombstone, not deletion: stale filesystem/cache state cannot resurrect a token.
        write(pointer, "consumed")
        true
    }

    private fun write(file: File, text: String) {
        child(requireNotNull(file.parentFile), file.name + ".tmp")
        PendingRestore.writeDurably(file, text)
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "invalid_restore_proof_path"
        }
        val canonicalParent = parent.canonicalFile
        return File(canonicalParent, name).also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == canonicalParent &&
                it.canonicalFile.name == name) { "invalid_restore_proof_path" }
        }
    }
}
