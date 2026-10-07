package me.rerere.rikkahub.data.orbis.spaces

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Only the exact private space namespace; no picker URI, chat file, credential or arbitrary path. */
internal object CompanionSpacesBackup {
    private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val path = Regex("orbis-companion-spaces/($UUID_PATTERN)/(index\\.json|$UUID_PATTERN\\.image)")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun maxBytes(name: String): Int? = path.matchEntire(name)?.let { if (it.groupValues[2] == "index.json") CompanionSpaceLimits.INDEX_BYTES else CompanionSpaceLimits.MEDIA_BYTES }

    private fun exact(root: File, relative: String): File {
        val base = root.canonicalFile; val target = File(base, relative)
        require(target.absoluteFile == target.canonicalFile && target.path.startsWith(base.path + File.separator)) { "space_backup_path" }
        return target
    }

    private fun shelves(filesDir: File): List<File> {
        val root = exact(filesDir, "orbis-companion-spaces")
        if (!root.exists()) return emptyList()
        require(root.isDirectory) { "space_backup_invalid" }
        return root.listFiles().orEmpty().map { shelf ->
            require(shelf.isDirectory && CompanionSpaceLimits.id(shelf.name) && shelf.absoluteFile == shelf.canonicalFile) { "space_backup_path" }; shelf
        }
    }

    fun stageSnapshot(filesDir: File, destination: File, checkCancelled: () -> Unit = {}, beforeFile: (String, Long) -> Unit = { _, _ -> }): List<Pair<String, File>> {
        require(destination.isDirectory || destination.mkdirs())
        val entries = mutableListOf<Pair<String, File>>()
        shelves(filesDir).forEach { shelf -> OrbisCompanionSpacesStore(shelf).withExportFiles(checkCancelled) { _, files ->
            files.forEach { source ->
                checkCancelled(); val relative = "orbis-companion-spaces/${shelf.name}/${source.name}"
                beforeFile(relative, source.length()); val target = exact(destination, relative)
                require(!target.exists()) { "space_backup_target_exists" }
                copy(source, target, requireNotNull(maxBytes(relative)), checkCancelled)
                entries += relative to target
            }
        } }
        return entries
    }

    private fun copy(source: File, target: File, limit: Int, checkCancelled: () -> Unit = {}) {
        require(source.length() in 1..limit.toLong()) { "space_backup_invalid" }
        require(target.parentFile.isDirectory || target.parentFile.mkdirs())
        ArchiveCapacity.requireSpace(target.parentFile.usableSpace, source.length())
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            var total = 0L; val buffer = ByteArray(65536)
            while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= limit); ArchiveCapacity.requireSpace(target.parentFile.usableSpace, count.toLong()); output.write(buffer, 0, count) }
            output.fd.sync()
        } }
    }

    fun validateStaged(payload: File) {
        shelves(File(payload, "files")).forEach { shelf -> OrbisCompanionSpacesStore(shelf).withExportFiles { _, files ->
            require(files.isNotEmpty() && shelf.listFiles().orEmpty().toSet() == files.toSet()) { "space_backup_unreferenced" }
        } }
    }

    /**
     * A raw emergency export may catch an unpublished media file after a crash. Only exclude an
     * unreferenced, correctly named image from the disposable prepared copy; raw/live are untouched.
     * Missing or damaged referenced files still fail before any exclusion is performed.
     */
    fun excludeEmergencyOrphans(prepared: File): Set<String> {
        val candidates = mutableListOf<Pair<String, File>>()
        shelves(File(prepared, "files")).forEach { shelf -> OrbisCompanionSpacesStore(shelf).withExportFiles { _, referenced ->
            require(referenced.isNotEmpty()) { "space_backup_invalid" }
            val known = referenced.toSet()
            shelf.listFiles().orEmpty().filter { it !in known }.forEach { file ->
                val relative = "orbis-companion-spaces/${shelf.name}/${file.name}"
                require(file.isFile && file.canonicalFile == file.absoluteFile && file.name.endsWith(".image") && maxBytes(relative) != null) { "space_backup_unreferenced" }
                candidates += "files/$relative" to file
            }
        } }
        candidates.forEach { (_, file) -> check(file.delete()) { "space_backup_staging_cleanup_failed" } }
        return candidates.map { it.first }.toSet()
    }

    /** Merge in staging, not in the live app; conflicting IDs are never silently overwritten. */
    fun prepareBeforeJournal(payload: File, liveFiles: File) {
        validateStaged(payload)
        val prepared = mutableListOf<Pair<File, ByteArray>>()
        for (incoming in shelves(File(payload, "files"))) {
            val live = exact(liveFiles, "orbis-companion-spaces/${incoming.name}")
            if (!live.exists()) continue
            OrbisCompanionSpacesStore(live).withExportFiles { old, originalFiles ->
                val repo = OrbisCompanionSpacesStore(incoming); val backup = repo.snapshot()
                fun <T> merge(a: List<T>, b: List<T>, key: (T) -> String): List<T> {
                    val previous = a.associateBy(key)
                    require(b.all { previous[key(it)] == null || previous[key(it)] == it }) { "space_backup_conflict" }
                    return a + b.filter { key(it) !in previous }
                }
                val merged = old.copy(
                    revision = maxOf(old.revision, backup.revision) + 1,
                    stories = merge(old.stories, backup.stories) { it.id },
                    posts = merge(old.posts, backup.posts) { it.id },
                    photos = merge(old.photos, backup.photos) { it.id },
                    media = merge(old.media, backup.media) { it.id },
                    videoReceipts = merge(old.videoReceipts, backup.videoReceipts) { "${it.callId}\u0000${it.frameId}" },
                    coverMediaId = old.coverMediaId ?: backup.coverMediaId,
                )
                repo.validateSnapshot(merged)
                val bytes = json.encodeToString(merged).toByteArray()
                require(bytes.size <= CompanionSpaceLimits.INDEX_BYTES) { "space_backup_invalid" }
                incoming.listFiles().orEmpty().filter { it.name != "index.json" }.forEach { source ->
                    val target = exact(live, source.name)
                    if (target.exists()) require(source.length() == target.length() && hash(source) == hash(target)) { "space_backup_conflict" }
                }
                // Include local-only referenced media in staging so the merged restore is self-contained.
                originalFiles.filter { it.name != "index.json" }.forEach { source ->
                    val target = exact(incoming, source.name)
                    if (!target.exists()) copy(source, target, CompanionSpaceLimits.MEDIA_BYTES)
                }
                prepared += File(incoming, "index.json") to bytes
            }
        }
        prepared.forEach { (target, bytes) ->
            ArchiveCapacity.requireSpace(target.parentFile.usableSpace, bytes.size.toLong())
            FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
        }
        validateStaged(payload)
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.inputStream().use { it.spaceReadBounded(CompanionSpaceLimits.MEDIA_BYTES) }).toList()
    fun sidecarPaths(installedPaths: Set<String>): List<String> = installedPaths.filter { it.startsWith("files/") && maxBytes(it.removePrefix("files/")) != null }.map { "$it.new" }
}
