package me.rerere.rikkahub.data.orbis.activities

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

internal object ActivityFileLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun lock(file: File): Any = locks.getOrPut(file.absolutePath) { Any() }
}

/** Three local documents per owner. Art can be reimported without replacing identities. */
internal object ActivitiesBackup {
    private val path = Regex("orbis-activities/([A-Za-z0-9_-]{1,80})/(pet|study|body|desires)\\.json")
    private val json = Json { encodeDefaults = true }
    fun maxBytes(name: String): Int? = if(path.matches(name)) 16 * 1024 * 1024 else null
    private fun exact(root: File, name: String): File = File(root.canonicalFile, name).also {
        require(it.canonicalFile == it.absoluteFile && it.toPath().startsWith(root.canonicalFile.toPath())) { "activity_backup_path" }
    }
    private fun documents(root: File): List<Pair<String, File>> {
        val folder = exact(root, "orbis-activities")
        if(!folder.exists()) return emptyList()
        require(folder.isDirectory) { "activity_backup_path" }
        return folder.listFiles().orEmpty().flatMap { owner ->
            require(owner.isDirectory && owner.name.matches(Regex("[A-Za-z0-9_-]{1,80}")) && owner.canonicalFile == owner.absoluteFile) { "activity_backup_path" }
            listOf("pet", "study", "body", "desires").mapNotNull { kind ->
                val name = "orbis-activities/${owner.name}/$kind.json"
                val target = exact(root, name)
                if(target.exists() || File(target.path + ".bak").exists()) name to target else null
            }
        }
    }
    private fun committed(file: File): File = File(file.path + ".bak").takeIf { it.exists() } ?: file
    private fun read(file: File, limit: Int): ByteArray {
        require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..limit.toLong()) { "activity_backup_invalid" }
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while(true) { val n = input.read(chunk); if(n < 0) break
                require(out.size().toLong() + n <= limit); out.write(chunk, 0, n) }
            out.toByteArray()
        }
    }
    fun validate(name: String, bytes: ByteArray) {
        require(bytes.size in 1..requireNotNull(maxBytes(name))) { "activity_backup_invalid" }
        try {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            if(name.endsWith("/pet.json")) PetRules.validate(json.decodeFromString<PetState>(text))
            else if(name.endsWith("/desires.json")) me.rerere.rikkahub.data.orbis.desire.LocalDesireRepository.validate(json.decodeFromString<me.rerere.rikkahub.data.orbis.desire.LocalDesireState>(text))
            else if(name.endsWith("/body.json")) {
                require(bytes.size <= me.rerere.rikkahub.data.orbis.body.BodyRules.MAX_BYTES)
                me.rerere.rikkahub.data.orbis.body.BodyRules.validate(json.decodeFromString<me.rerere.rikkahub.data.orbis.body.BodyState>(text))
            } else StudyRepository.validate(json.decodeFromString<StudyState>(text))
        } catch(_: Exception) { error("activity_backup_invalid") }
    }
    fun stageSnapshot(filesDir: File, destination: File, checkCancelled: () -> Unit = {}, beforeFile: (String, Long) -> Unit = { _, _ -> }): List<Pair<String, File>> =
        documents(filesDir).map { (name, source) ->
            checkCancelled()
            val bytes = synchronized(ActivityFileLocks.lock(source)) { read(committed(source), requireNotNull(maxBytes(name))) }
            validate(name, bytes); beforeFile(name, bytes.size.toLong())
            val target = exact(destination, name); require(!target.exists()); require(target.parentFile.isDirectory || target.parentFile.mkdirs())
            ArchiveCapacity.requireSpace(target.parentFile.usableSpace, bytes.size.toLong())
            FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
            name to target
        }
    fun validateStaged(payload: File) {
        documents(File(payload, "files")).forEach { (name, file) -> validate(name, read(file, requireNotNull(maxBytes(name)))) }
    }
    fun prepareBeforeJournal(payload: File, live: File) {
        validateStaged(payload)
        // Identical existing documents are harmless; differing identities/progress never silently overwrite newer local care or answers.
        documents(File(payload, "files")).forEach { (name, incoming) ->
            val existing = committed(exact(live, name))
            if(existing.exists()) {
                val limit = requireNotNull(maxBytes(name)); val bytes = read(existing, limit); validate(name, bytes)
                require(json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) == json.parseToJsonElement(read(incoming, limit).toString(Charsets.UTF_8))) { "activity_backup_conflict" }
            }
        }
    }
    fun sidecarPaths(paths: Set<String>): List<String> = paths.filter { it.startsWith("files/") && maxBytes(it.removePrefix("files/")) != null }
        .flatMap { listOf("$it.bak", "$it.new") }
}
