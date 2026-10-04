package me.rerere.rikkahub.data.recovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import me.rerere.rikkahub.data.orbis.privacy.validateEmergencyVaultCopy
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Offline, deliberately restricted restore of an emergency archive. This is NOT normal backup
 * import: pending work, prior approvals and platform worker databases are not installed. Private
 * vault ciphertext alone is restored behind a forced human recovery-code gate, never unlocked.
 * All archived bytes remain in raw/ and all previous live roots remain in originals/.
 * The caller owns the process fence and must keep it across prepare, commit and rollback.
 */
internal object EmergencyRestore {
    val rootNames = listOf("databases", "files", "shared_prefs", "no_backup")
    private val libraryNames = setOf("upload", "skills", "fonts", "orbis-gallery", "orbis-voice-calls")
    private const val PRIVATE_VAULT_ROOT = "no_backup/orbis-private-vaults"
    private val vaultOwner = Regex("[0-9a-f]{64}")
    private val vaultUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    const val JOURNAL = "restore-journal.json"

    @Serializable
    data class Journal(
        val schemaVersion: Int = 1,
        val status: String,
        val targetPaths: Map<String, String>,
        val hadOriginal: Map<String, Boolean>,
        val selectedFiles: List<String>,
        val retainedFiles: Int,
        val restoredPrivateVaults: Int = 0,
        val retainedPrivateVaults: Int = 0,
    )

    /** Validates and prepares only. The validator MUST validate settings and the staged SQLite copy. */
    fun prepare(
        archive: File,
        transaction: File,
        packageName: String,
        currentVersionCode: Long,
        liveRoots: Map<String, File>,
        assertQuiescent: () -> Unit,
        validatePrepared: (File) -> Unit,
    ): Journal {
        assertQuiescent()
        val roots = checkedRoots(liveRoots, transaction)
        require(!transaction.exists()) { "emergency_restore_destination_exists" }
        require(transaction.mkdirs()) { "emergency_restore_create_failed" }
        val manifest = EmergencyArchive.extractVerified(archive, File(transaction, "raw"))
        require(manifest.metadata.packageName == packageName) { "emergency_restore_package_mismatch" }
        require(manifest.metadata.versionCode <= currentVersionCode) { "emergency_restore_newer_version" }
        // Chat attachments can contain absolute file:// paths. Do not silently import broken links
        // into another Android user/profile or another package; the untouched raw copy remains usable.
        val originalFiles = manifest.roots.singleOrNull { it.name == "files" && it.present }
        require(originalFiles != null && File(originalFiles.originalPath).canonicalPath == roots.getValue("files").canonicalPath) {
            "emergency_restore_storage_path_mismatch"
        }
        val raw = File(transaction, "raw")
        val vaultOwners = manifest.directories.filter { it.startsWith("$PRIVATE_VAULT_ROOT/") }
            .map { it.removePrefix("$PRIVATE_VAULT_ROOT/").substringBefore('/') }.distinct()
            .filterNot { owner -> vaultOwner.matches(owner) &&
                isUninitializedPrivateVault(resolve(raw, "$PRIVATE_VAULT_ROOT/$owner")) }
        // Validation does not unwrap a key or decrypt anything. Malformed private-vault trees stay
        // in raw/ and must not prevent the separate chat/settings restore from being prepared.
        val usableVaultOwners = vaultOwners.filter { owner ->
            vaultOwner.matches(owner) && validateEmergencyVaultCopy(resolve(raw, "$PRIVATE_VAULT_ROOT/$owner"))
        }.toSet()
        val selected = manifest.files.map { it.path }.filter { path ->
            isRestoredPath(path) && (!path.startsWith("$PRIVATE_VAULT_ROOT/") ||
                path.removePrefix("$PRIVATE_VAULT_ROOT/").substringBefore('/') in usableVaultOwners)
        }.sorted()
        require("databases/rikka_hub" in selected && "files/datastore/settings.preferences_pb" in selected) {
            "emergency_restore_required_content_missing"
        }
        val prepared = File(transaction, "prepared")
        rootNames.forEach { require(File(prepared, it).mkdirs()) { "emergency_restore_create_failed" } }
        val selectedSet = selected.toHashSet()
        val selectedBytes = manifest.files.filter { it.path in selectedSet }.fold(0L) { total, file ->
            Math.addExact(total, file.size)
        }
        require(prepared.usableSpace >= Math.addExact(selectedBytes, minOf(selectedBytes, 64L * 1024 * 1024))) {
            "emergency_restore_insufficient_space"
        }
        for (path in selected) {
            assertQuiescent()
            val source = resolve(raw, path)
            require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "emergency_restore_invalid_file" }
            val destination = resolve(prepared, path)
            require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            source.inputStream().use { input -> FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            } }
        }
        usableVaultOwners.forEach { owner ->
            val directory = resolve(prepared, "$PRIVATE_VAULT_ROOT/$owner")
            check(validateEmergencyVaultCopy(directory)) { "emergency_restore_private_vault_invalid" }
            // Even on the original device, a restored envelope must never silently use its old
            // Keystore wrapping. The vault factory demands explicit human recovery-code rebind.
            FileOutputStream(File(directory, "recovery-required.marker")).use { output ->
                output.write("1".toByteArray(Charsets.US_ASCII)); output.fd.sync()
            }
        }
        assertQuiescent()
        validatePrepared(prepared)
        assertQuiescent()
        return Journal(
            status = "PREPARED",
            targetPaths = roots.mapValues { it.value.canonicalPath },
            hadOriginal = roots.mapValues { it.value.exists() },
            selectedFiles = selected,
            retainedFiles = manifest.files.size - selected.size,
            restoredPrivateVaults = usableVaultOwners.size,
            retainedPrivateVaults = vaultOwners.size - usableVaultOwners.size,
        ).also { writeJournal(transaction, it) }
    }

    /**
     * May be explicitly retried after interruption. Never calls app startup or removes the fence.
     * onMove is a test fault-injection hook; each move is an atomic same-filesystem directory rename.
     */
    fun commit(
        transaction: File,
        liveRoots: Map<String, File>,
        assertQuiescent: () -> Unit,
        onMove: (String) -> Unit = {},
    ): Journal {
        val roots = checkedRoots(liveRoots, transaction)
        var journal = readCheckedJournal(transaction, roots)
        require(journal.status in setOf("PREPARED", "INSTALLING", "COMMITTED")) { "emergency_restore_invalid_state" }
        if (journal.status == "COMMITTED") return journal // Do not replay a completed restore.
        assertQuiescent()
        journal = journal.copy(status = "INSTALLING").also { writeJournal(transaction, it) }
        for (name in rootNames) {
            assertQuiescent()
            val live = roots.getValue(name)
            val original = resolve(transaction, "originals/$name")
            val prepared = resolve(transaction, "prepared/$name")
            if (journal.hadOriginal.getValue(name) && !original.exists()) {
                require(live.isDirectory && prepared.isDirectory) { "emergency_restore_original_missing" }
                move(live, original)
                onMove("original:$name")
            }
            if (prepared.exists()) {
                require(!live.exists()) { "emergency_restore_live_conflict" }
                move(prepared, live)
                onMove("install:$name")
            } else {
                require(live.isDirectory) { "emergency_restore_installed_missing" }
            }
        }
        assertQuiescent()
        return journal.copy(status = "COMMITTED").also { writeJournal(transaction, it) }
    }

    /** Rollback is explicit, idempotent and preserves incoming content in prepared/ for inspection. */
    fun rollback(
        transaction: File,
        liveRoots: Map<String, File>,
        assertQuiescent: () -> Unit,
        onMove: (String) -> Unit = {},
    ): Journal {
        val roots = checkedRoots(liveRoots, transaction)
        var journal = readCheckedJournal(transaction, roots)
        require(journal.status in setOf("PREPARED", "INSTALLING", "ROLLING_BACK", "ROLLED_BACK")) {
            "emergency_restore_rollback_not_allowed"
        }
        if (journal.status == "ROLLED_BACK") return journal
        assertQuiescent()
        journal = journal.copy(status = "ROLLING_BACK").also { writeJournal(transaction, it) }
        for (name in rootNames.asReversed()) {
            assertQuiescent()
            val live = roots.getValue(name)
            val original = resolve(transaction, "originals/$name")
            val prepared = resolve(transaction, "prepared/$name")
            if (!prepared.exists()) {
                require(live.isDirectory) { "emergency_restore_installed_missing" }
                move(live, prepared)
                onMove("withdraw:$name")
            }
            if (original.exists()) {
                require(!live.exists()) { "emergency_restore_live_conflict" }
                move(original, live)
                onMove("restore:$name")
            } else if (journal.hadOriginal.getValue(name)) {
                require(live.isDirectory) { "emergency_restore_original_missing" }
            } else {
                require(!live.exists()) { "emergency_restore_live_conflict" }
            }
        }
        return journal.copy(status = "ROLLED_BACK").also { writeJournal(transaction, it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun readJournal(transaction: File): Journal = resolve(transaction, JOURNAL).inputStream().use { input ->
        Json.decodeFromStream(EmergencyJsonInput(input, 16L * 1024 * 1024))
    }

    /** Old read-only status checks could leave just an empty owner directory and runtime lock. */
    private fun isUninitializedPrivateVault(directory: File): Boolean {
        val children = directory.listFiles() ?: return false
        return children.all { it.name == ".lock" && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.length() <= 64 }
    }

    fun isRestoredPath(path: String): Boolean = when {
        !EmergencyArchivePaths.isMaterializable(path) -> false
        path in setOf("databases/rikka_hub", "databases/rikka_hub-wal", "databases/rikka_hub-journal") -> true
        path == "files/datastore/settings.preferences_pb" -> true
        path.startsWith("$PRIVATE_VAULT_ROOT/") -> isPrivateVaultPayloadPath(path)
        path.startsWith("files/") -> path.split('/').let { it.size >= 3 && it[1] in libraryNames }
        else -> false
    }

    /** Only cipher envelopes/records and inert index pointers, never arbitrary no_backup state. */
    private fun isPrivateVaultPayloadPath(path: String): Boolean {
        val pieces = path.removePrefix("$PRIVATE_VAULT_ROOT/").split('/')
        if (pieces.isEmpty() || !vaultOwner.matches(pieces[0])) return false
        if (pieces.size == 2) return pieces[1] == "CURRENT"
        if (pieces.size < 4 || pieces[1] != "generations" || !vaultUuid.matches(pieces[2])) return false
        if (pieces.size == 4) return pieces[3] == "HEAD"
        if (pieces.size != 5) return false
        return when (pieces[3]) {
            "states" -> pieces[4].endsWith(".vault") && vaultUuid.matches(pieces[4].removeSuffix(".vault"))
            "records" -> pieces[4].endsWith(".bin") && vaultUuid.matches(pieces[4].removeSuffix(".bin"))
            else -> false
        }
    }

    private fun checkedRoots(roots: Map<String, File>, transaction: File): Map<String, File> {
        require(roots.keys == rootNames.toSet()) { "emergency_restore_roots_invalid" }
        val directory = transaction.absoluteFile
        require(directory == directory.canonicalFile) { "emergency_restore_symlink" }
        val checked = roots.mapValues { (name, value) ->
            val root = value.absoluteFile
            require(root.name == name) { "emergency_restore_roots_invalid" }
            require(root == root.canonicalFile && !Files.isSymbolicLink(root.toPath())) { "emergency_restore_symlink" }
            require(!Files.exists(root.toPath(), NOFOLLOW_LINKS) || root.isDirectory) { "emergency_restore_roots_invalid" }
            require(directory != root && !directory.path.startsWith(root.path + File.separator) &&
                !root.path.startsWith(directory.path + File.separator)) { "emergency_restore_roots_overlap" }
            root
        }
        require(checked.values.map { it.parentFile }.distinct().size == 1) { "emergency_restore_roots_invalid" }
        require(directory.path.startsWith(checked.values.first().parentFile!!.path + File.separator)) {
            "emergency_restore_transaction_outside_app"
        }
        return checked
    }

    private fun readCheckedJournal(transaction: File, roots: Map<String, File>): Journal {
        val journal = readJournal(transaction)
        require(journal.schemaVersion == 1 && journal.targetPaths == roots.mapValues { it.value.canonicalPath } &&
            journal.hadOriginal.keys == rootNames.toSet()) { "emergency_restore_journal_invalid" }
        return journal
    }

    private fun resolve(root: File, relative: String): File {
        require(EmergencyArchivePaths.isMaterializable(relative)) { "emergency_restore_path_invalid" }
        val result = File(root, relative).absoluteFile
        require(result == result.canonicalFile && result.path.startsWith(root.canonicalPath + File.separator)) {
            "emergency_restore_path_invalid"
        }
        return result
    }

    private fun writeJournal(transaction: File, journal: Journal) {
        val temporary = File(transaction, "$JOURNAL.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(Json.encodeToString(journal).toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(transaction, JOURNAL).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun move(source: File, target: File) {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath()) && !target.exists()) {
            "emergency_restore_move_invalid"
        }
        require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
    }
}
