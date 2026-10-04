package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** Exact gallery namespace, no arbitrary files or symlinks. Ordinary backup envelopes provide aggregate disk admission. */
internal object GalleryBackup {
    private val path = Regex("orbis-gallery/([A-Za-z0-9_-]{1,80})/(index\\.json|[A-Za-z0-9_-]{1,80}-r[1-9][0-9]{0,3}\\.txt|answer-[A-Za-z0-9_-]{1,80}\\.json)")
    fun maxBytes(name: String): Int? = path.matchEntire(name)?.let { if (it.groupValues[2] == "index.json") GalleryLimits.INDEX_BYTES else GalleryLimits.BODY_BYTES }

    private fun exact(root: File, relative: String): File {
        val base = root.canonicalFile
        val file = File(base, relative)
        require(file.absoluteFile == file.canonicalFile && file.path.startsWith(base.path + File.separator)) { "gallery_backup_path" }
        return file
    }
    private fun shelves(filesDir: File): List<File> {
        val root = exact(filesDir, "orbis-gallery")
        if (!root.exists()) return emptyList()
        require(root.isDirectory)
        return root.listFiles().orEmpty().map { directory ->
            require(directory.isDirectory && GalleryLimits.id(directory.name) && directory.canonicalFile == directory.absoluteFile) { "gallery_backup_path" }
            directory
        }
    }
    fun stageSnapshot(filesDir: File, destination: File, checkCancelled: () -> Unit = {},
        beforeFile: (String, Long) -> Unit = { _, _ -> }): List<Pair<String, File>> {
        require(destination.isDirectory || destination.mkdirs())
        val entries = mutableListOf<Pair<String, File>>()
        for (shelf in shelves(filesDir)) GalleryRepository(shelf).withExportFiles(checkCancelled) { _, files ->
            for (source in files) {
                checkCancelled()
                val relative = "orbis-gallery/${shelf.name}/${source.name}"
                beforeFile(relative, source.length())
                val target = exact(destination, relative)
                require(!target.exists()) { "gallery_backup_target_exists" }
                copy(source, target, requireNotNull(maxBytes(relative)), checkCancelled)
                entries += relative to target
            }
        }
        return entries
    }
    private fun copy(source: File, target: File, limit: Int, checkCancelled: () -> Unit) {
        require(source.length() in 1..limit.toLong()) { "gallery_backup_invalid" }
        require(target.parentFile.isDirectory || target.parentFile.mkdirs())
        ArchiveCapacity.requireSpace(target.parentFile.usableSpace, source.length())
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            var total = 0L; val buffer = ByteArray(65536)
            while (true) {
                checkCancelled(); val count = input.read(buffer); if (count == -1) break
                total += count; require(total <= limit)
                ArchiveCapacity.requireSpace(target.parentFile.usableSpace, count.toLong()); output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
    }
    fun validateStaged(payload: File) {
        shelves(File(payload, "files")).forEach { shelf ->
            GalleryRepository(shelf).withExportFiles { _, referenced ->
                require(referenced.isNotEmpty()) { "gallery_backup_invalid" }
                require(shelf.listFiles().orEmpty().toSet() == referenced.toSet()) { "gallery_backup_unreferenced" }
            }
        }
    }
    /** Prepare all merges in staging before the app's restore journal touches any live file. */
    fun prepareBeforeJournal(payload: File, liveFiles: File) {
        validateStaged(payload)
        val prepared = mutableListOf<Pair<File, ByteArray>>()
        for (incoming in shelves(File(payload, "files"))) {
            val live = exact(liveFiles, "orbis-gallery/${incoming.name}")
            if (!live.exists()) continue
            GalleryRepository(live).withExportFiles { old, originals ->
                val backup = GalleryRepository(incoming).snapshot()
                val ids = old.items.associateBy { it.id }
                val answers = old.answers.associateBy { it.id }
                require(backup.items.all { ids[it.id] == null || ids[it.id] == it }) { "gallery_backup_conflict" }
                require(backup.answers.all { answers[it.id] == null || answers[it.id] == it }) { "gallery_backup_conflict" }
                val merged = old.copy(items = old.items + backup.items.filter { it.id !in ids }, answers = old.answers + backup.answers.filter { it.id !in answers },
                    customName = old.customName.ifBlank { backup.customName })
                val bytes = galleryJson.encodeToString(merged).toByteArray()
                require(bytes.size <= GalleryLimits.INDEX_BYTES && merged.items.size <= GalleryLimits.ITEMS && merged.answers.size <= GalleryLimits.ANSWERS) { "gallery_backup_invalid" }
                // Existing entries absent in the archive remain on disk and in the merged catalogue.
                // Referenced incoming files that collide must have identical bytes, never overwrite differing content.
                incoming.listFiles().orEmpty().filter { it.name != "index.json" }.forEach { file ->
                    val target = exact(live, file.name)
                    if (target.exists()) require(target.length() == file.length() && target.inputStream().use { galleryHash(it.galleryReadBounded(GalleryLimits.BODY_BYTES)) } ==
                        file.inputStream().use { galleryHash(it.galleryReadBounded(GalleryLimits.BODY_BYTES)) }) { "gallery_backup_conflict" }
                }
                require(originals.isNotEmpty() || old.items.isEmpty())
                prepared += File(incoming, "index.json") to bytes
            }
        }
        prepared.forEach { (file, bytes) -> FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
    }
    fun sidecarPaths(installedPaths: Set<String>) = installedPaths.filter { it.startsWith("files/") && maxBytes(it.removePrefix("files/")) != null }.map { "$it.new" }
}
