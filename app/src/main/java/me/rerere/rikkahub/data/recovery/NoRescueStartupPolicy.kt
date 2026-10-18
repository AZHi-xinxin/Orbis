package me.rerere.rikkahub.data.recovery

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Read-only variant-transition check. No settings, SQL, crash flag or recovery-request read.
 * Removing crash interception cannot make half-renamed databases safe to open. Fail visibly
 * on unfinished or uninspectable old file operations; preserve originals for support.
 * Neither current edition offers legacy repair or rollback actions. */
internal fun requireSafeNoRescueStartup(root: File) {
    fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }
    fun directory(path: Path): Boolean {
        val attr = attributes(path) ?: return false
        check(attr.isDirectory && !attr.isSymbolicLink) { "legacy_recovery_state_unverified" }
        return true
    }
    // Android may alias the app data parent (/data/user/0 -> /data/data). Allow that
    // platform-owned alias, but never follow a link at the recovery root or below it.
    val absolute = root.absoluteFile
    val base = absolute.parentFile.canonicalFile.toPath().resolve(absolute.name).normalize()
    if (!directory(base)) return
    val permissions = base.resolve("mount-permissions")
    if (directory(permissions)) {
        check(attributes(permissions.resolve("pending.json")) == null) { "legacy_permission_restore_pending" }
    }
    val transactions = base.resolve("transactions")
    if (!directory(transactions)) return
    Files.newDirectoryStream(transactions).use { entries ->
        var count = 0
        for (transaction in entries) {
            check(++count <= 4096 && directory(transaction)) { "legacy_recovery_state_unverified" }
            val journal = transaction.resolve(EmergencyRestore.JOURNAL)
            val attr = attributes(journal)
            check(attributes(transaction.resolve(EmergencyRestore.JOURNAL + ".tmp")) == null) {
                "legacy_restore_pending"
            }
            if (attr == null) {
                // Failed preparation has not touched live files. Missing journal plus originals
                // cannot prove that, so do not initialize data writers in that case.
                check(attributes(transaction.resolve("originals")) == null) { "legacy_recovery_state_unverified" }
                continue
            }
            check(attr.isRegularFile && !attr.isSymbolicLink && attr.size() <= 16L * 1024 * 1024) {
                "legacy_recovery_state_unverified"
            }
            val state = EmergencyRestore.readJournal(transaction.toFile())
            check(state.schemaVersion == 1 && state.status in setOf("COMMITTED", "ROLLED_BACK")) {
                "legacy_restore_pending"
            }
        }
    }
}
