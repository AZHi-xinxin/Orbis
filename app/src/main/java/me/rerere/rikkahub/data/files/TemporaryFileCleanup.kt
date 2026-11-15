package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class TemporaryFileKind { BACKUP_TRANSFER, DUPLICATE_REPAIR }

internal data class TemporaryFileStamp(val name: String, val size: Long, val modified: Long, val key: String)
internal data class TemporaryFileCandidate(
    val stamp: TemporaryFileStamp,
    val kind: TemporaryFileKind,
    val keeper: TemporaryFileStamp? = null,
    val digest: String? = null,
)
internal data class TemporaryFilePreview(
    val root: String,
    val rootKey: String,
    val candidates: List<TemporaryFileCandidate>,
) {
    fun bytes(kinds: Set<TemporaryFileKind>) = candidates.filter { it.kind in kinds }.sumOf { it.stamp.size }
    fun count(kinds: Set<TemporaryFileKind>) = candidates.count { it.kind in kinds }
}
internal data class TemporaryFileReceipt(val deleted: Int, val bytes: Long, val skipped: Int, val backupBusy: Boolean)

/** Owns ONLY aged, app-generated files directly in cacheDir. No tree walk, settings or DB access. */
internal class TemporaryFileCleanup(
    cacheDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val canClean: () -> Boolean = { true },
) {
    private val inputRoot = cacheDirectory.toPath()
    private val root = cacheDirectory.canonicalFile.toPath()

    fun scan(checkCancelled: () -> Unit = {}): TemporaryFilePreview {
        val key = rootKey()
        val files = Files.newDirectoryStream(root).use { entries ->
            val found = mutableListOf<TemporaryFileStamp>()
            var visited = 0
            for (path in entries) {
                checkCancelled()
                if (++visited > 10_000) error("临时文件过多，本次未生成清理清单。")
                val name = path.fileName.toString()
                if (!temporary.matches(name) && !repair.matches(name)) continue
                stamp(name)?.takeIf(::aged)?.let(found::add)
            }
            found
        }
        val candidates = files.filter { temporary.matches(it.name) }
            .map { TemporaryFileCandidate(it, TemporaryFileKind.BACKUP_TRANSFER) }.toMutableList()
        // Only hash same-size old repair archives. Unique recovery copies are never candidates.
        for (sameSize in files.filter { repair.matches(it.name) }.groupBy { it.size }.values) {
            if (sameSize.size < 2) continue
            val byDigest = sameSize.mapNotNull { file ->
                checkCancelled()
                digest(file, checkCancelled)?.let { it to file }
            }.groupBy({ it.first }, { it.second })
            for ((hash, copies) in byDigest) {
                val ordered = copies.sortedWith(compareBy<TemporaryFileStamp> { it.modified }.thenBy { it.name })
                val keep = ordered.first()
                for (extra in ordered.drop(1)) {
                    // Two links to one inode are not extra storage; never promise that space.
                    if (extra.key != keep.key) candidates += TemporaryFileCandidate(extra,
                        TemporaryFileKind.DUPLICATE_REPAIR, keep, hash)
                }
            }
        }
        check(rootKey() == key) { "缓存目录已变化，请重新扫描。" }
        return TemporaryFilePreview(root.toString(), key, candidates.sortedBy { it.stamp.name })
    }

    /** Delete exactly the approved snapshot, never files appearing after the preview. */
    fun clean(preview: TemporaryFilePreview, kinds: Set<TemporaryFileKind>, checkCancelled: () -> Unit = {}): TemporaryFileReceipt {
        require(preview.root == root.toString() && preview.rootKey == rootKey()) { "清理目录已变化，请重新扫描。" }
        val selected = preview.candidates.filter { it.kind in kinds }
        var deleted = 0
        var bytes = 0L
        var skipped = 0
        for ((index, candidate) in selected.withIndex()) {
            checkCancelled()
            if (!canClean()) return TemporaryFileReceipt(deleted, bytes, skipped + selected.size - index, true)
            val original = candidate.stamp
            val allowedName = when (candidate.kind) {
                TemporaryFileKind.BACKUP_TRANSFER -> temporary.matches(original.name)
                TemporaryFileKind.DUPLICATE_REPAIR -> repair.matches(original.name)
            }
            if (!allowedName || !aged(original) || stamp(original.name) != original) { skipped++; continue }
            if (candidate.kind == TemporaryFileKind.DUPLICATE_REPAIR) {
                val keep = candidate.keeper
                if (keep == null || keep.name == original.name || !repair.matches(keep.name) ||
                    keep.key == original.key || candidate.digest == null ||
                    digest(keep, checkCancelled) != candidate.digest ||
                    digest(original, checkCancelled) != candidate.digest || stamp(keep.name) != keep) {
                    skipped++; continue
                }
            }
            checkCancelled()
            if (!canClean()) return TemporaryFileReceipt(deleted, bytes, skipped + selected.size - index, true)
            if (rootKey() != preview.rootKey || stamp(original.name) != original) { skipped++; continue }
            try {
                if (Files.deleteIfExists(root.resolve(original.name))) { deleted++; bytes += original.size }
                else skipped++
            } catch (_: java.io.IOException) { skipped++ }
        }
        return TemporaryFileReceipt(deleted, bytes, skipped, false)
    }

    private fun rootKey(): String {
        check(!Files.isSymbolicLink(inputRoot)) { "缓存目录异常，未清理。" }
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "缓存目录异常，未清理。" }
        return checkNotNull(attributes.fileKey()) { "无法核对缓存目录身份，未清理。" }.toString()
    }

    private fun aged(file: TemporaryFileStamp) = file.size > 0 && file.modified > 0 &&
        file.modified <= now() - TimeUnit.HOURS.toMillis(24)

    private fun stamp(name: String): TemporaryFileStamp? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in setOf(".", "..")) return null
        return try {
            val attributes = Files.readAttributes(root.resolve(name), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() == null) null
            else TemporaryFileStamp(name, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey().toString())
        } catch (_: java.io.IOException) { null }
    }

    private fun digest(file: TemporaryFileStamp, checkCancelled: () -> Unit): String? {
        if (stamp(file.name) != file) return null
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            FileChannel.open(root.resolve(file.name), READ, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(64 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > file.size) return null
                    buffer.flip(); hash.update(buffer)
                }
            }
        } catch (_: java.io.IOException) { return null }
        if (stamp(file.name) != file) return null
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val temporary = Regex("(?:backup_[0-9]{8}_[0-9]{6}_[0-9]+\\.zip|(?:portable-backup|restore|version-restore|checkpoint-recovery|orbis-rikka-import|orbis-extract)-[0-9]+\\.zip|(?:orbis-overwrite|orbis-version)-[0-9]+\\.tmp|temp_restore_[0-9]+\\.zip)")
        val repair = Regex("orbis-stage[0-9]+-backup(?:-[A-Za-z0-9._-]+)?\\.tar")
    }
}
