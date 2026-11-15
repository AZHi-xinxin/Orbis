package me.rerere.rikkahub.data.orbis.activities

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable internal data class PetArtFile(val sha256: String, val bytes: Long)
@Serializable internal data class PetArtPackage(val sha256: String, val bytes: Long,
    val files: Map<String, PetArtFile>, val legacy: Map<String, String> = emptyMap())
@Serializable internal data class PetArtCatalog(val version: Int, val baseUrl: String,
    val packages: Map<String, PetArtPackage>,
    val previousPackages: Map<String, List<PetArtPackage>> = emptyMap())

/** Only optional, reproducible art lives here. Pet state and custom imports are elsewhere. */
internal class PetArtStore(private val root: File, val catalog: PetArtCatalog) {
    init { require(catalog.version == 1) }
    private fun checkedRoot(): File {
        require(root.canonicalFile == root.absoluteFile) { "pet_art_path" }
        require(root.isDirectory || root.mkdirs())
        return root
    }
    fun directory(id: String): File {
        val pack = catalog.packages.getValue(id)
        require(id.matches(Regex("[a-z0-9-]+")) && pack.sha256.matches(Regex("[a-f0-9]{64}")))
        val file = File(checkedRoot(), "$id-${pack.sha256.take(12)}")
        require(file.canonicalFile == file.absoluteFile) { "pet_art_path" }
        return file
    }
    fun ready(id: String): Boolean = runCatching {
        val dir = directory(id); val pack = catalog.packages.getValue(id)
        File(dir, ".complete").readText() == pack.sha256 && pack.files.all { (name, spec) ->
            val f = File(dir, name)
            f.canonicalFile == f.absoluteFile && f.isFile && f.length() == spec.bytes
        }
    }.getOrDefault(false)

    private fun previousStores(id: String) = catalog.previousPackages[id].orEmpty()
        .filter { it.sha256 != catalog.packages.getValue(id).sha256 }
        .map { PetArtStore(root, PetArtCatalog(1, catalog.baseUrl, mapOf(id to it))) }

    /** Offline upgrades keep the last verified installed version until replacement succeeds. */
    fun availableDirectory(id: String): File? = if(ready(id)) directory(id)
        else previousStores(id).firstOrNull { it.ready(id) }?.directory(id)

    @Synchronized fun removeSuperseded(keep: Set<String>): Long = catalog.packages.keys.sumOf { id ->
        previousStores(id).sumOf { old ->
            // A failed/interrupted new download must never remove an adopted animal's old art.
            if(id !in keep || ready(id)) old.removeUnused(emptySet()) else 0L
        }
    }

    /** Caller verifies the archive digest before extraction; every entry is verified again here. */
    @Synchronized fun install(id: String, archive: File): File {
        val pack = catalog.packages.getValue(id)
        require(archive.length() == pack.bytes && archive.inputStream().use(::petArtSha256) == pack.sha256) { "pet_art_hash" }
        val destination = directory(id)
        if (ready(id)) return destination
        require(!destination.exists()) { "pet_art_incomplete_existing" }
        val staging = File(checkedRoot(), ".install-${UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            val seen = mutableSetOf<String>(); var total = 0L
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name.matches(Regex("[a-z0-9-]+\\.(webp|json)")) && seen.add(entry.name)) { "pet_art_entry" }
                    val spec = pack.files[entry.name] ?: error("pet_art_unknown_entry")
                    require(spec.bytes in 1..8_388_608L && seen.size <= 24)
                    val file = File(staging, entry.name)
                    var count = 0L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            count += n; total += n
                            require(count <= spec.bytes && total <= 32 * 1024 * 1024L) { "pet_art_too_large" }
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                    require(count == spec.bytes && file.inputStream().use(::petArtSha256) == spec.sha256) { "pet_art_entry_hash" }
                    zip.closeEntry()
                }
            }
            require(seen == pack.files.keys) { "pet_art_missing_entry" }
            File(staging, ".complete").writeText(pack.sha256)
            check(staging.renameTo(destination)) { "pet_art_commit" }
            check(ready(id))
            return destination
        } finally { staging.deleteRecursively() }
    }

    /** No recursive discovery: only exact current official packs and their known children. */
    @Synchronized fun removeUnused(keep: Set<String>): Long {
        var removed = 0L
        catalog.packages.filterKeys { it !in keep }.forEach { (id, pack) ->
            val dir = directory(id)
            if (!ready(id)) return@forEach
            val expected = pack.files.keys + ".complete"
            val children = dir.listFiles() ?: return@forEach
            if (children.map { it.name }.toSet() != expected || children.any {
                    it.canonicalFile != it.absoluteFile || !it.isFile }) return@forEach
            // Modified files may be user artwork. Never classify those as disposable downloads.
            if (pack.files.any { (name, spec) -> File(dir, name).inputStream().use(::petArtSha256) != spec.sha256 }) return@forEach
            children.forEach { f -> val bytes = f.length(); if (f.delete()) removed += bytes }
            dir.delete()
        }
        return removed
    }
}

internal fun petArtSha256(input: InputStream): String {
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
