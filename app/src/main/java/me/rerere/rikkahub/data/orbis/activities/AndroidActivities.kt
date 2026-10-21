package me.rerere.rikkahub.data.orbis.activities

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.OrbisStickerImages

internal class AndroidActivityStorage(files: File, owner: String, kind: String) : ActivityStorage {
    private val file: AtomicFile
    private var loaded = false
    private var expectedHash: String? = null
    init {
        require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}")) && kind in setOf("pet", "study"))
        val base = files.canonicalFile
        val target = File(base, "orbis-activities/$owner/$kind.json")
        require(target.canonicalFile == target.absoluteFile)
        file = AtomicFile(target)
    }
    override fun read(): String? = synchronized(ActivityFileLocks.lock(file.baseFile)) {
        val value = readCommitted()
        expectedHash = value?.let { OrbisStickerImages.sha256(it.toByteArray()) }
        loaded = true
        value
    }
    private fun readCommitted(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { it.bounded(16 * 1024 * 1024).toString(Charsets.UTF_8) }
    }
    override fun write(text: String): Unit = synchronized(ActivityFileLocks.lock(file.baseFile)) {
        val bytes = text.toByteArray(); require(bytes.size <= 16 * 1024 * 1024)
        // A restored backup must not be overwritten by an old in-memory page/repository.
        check(loaded && readCommitted()?.let { OrbisStickerImages.sha256(it.toByteArray()) } == expectedHash) { "activity_storage_changed_reload" }
        val out = file.startWrite()
        try { out.write(bytes); out.fd.sync(); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        check(readCommitted() == text) { "activity_storage_verify_failed" }
        expectedHash = OrbisStickerImages.sha256(bytes)
    }
}
internal fun InputStream.bounded(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size().toLong() + n <= max); out.write(buffer, 0, n) }
    return out.toByteArray()
}

/** Called only inside an enabled area or an explicitly invoked tool. No startup/background work. */
object OrbisActivities {
    private val pets = mutableMapOf<String, PetRepository>()
    private val studies = mutableMapOf<String, StudyRepository>()
    @Synchronized fun pet(context: Context, owner: String): PetRepository = pets.getOrPut(owner) {
        PetRepository(AndroidActivityStorage(context.applicationContext.filesDir, owner, "pet"))
    }
    @Synchronized fun study(context: Context, owner: String): StudyRepository = studies.getOrPut(owner) {
        StudyRepository(AndroidActivityStorage(context.applicationContext.filesDir, owner, "study"))
    }
}

/** Explicit optional art-pack import. Never decode/download assets while the module is disabled. */
internal object PetAssets {
    val names = setOf("cat.png", "tidy.png", "messy.png")
    /** Deduplicated bundled artwork: 40 animals and 8 rooms, no download/import needed. */
    fun model(appearance: String, name: String, imported: File?): Any {
        require(name in names)
        if(imported != null) return File(imported, name)
        val pet = PetCatalog.find(appearance)
        val asset = when(name) {
            "cat.png" -> "${pet.id}.png"
            "messy.png" -> "${pet.family}-room-messy-${if(pet.family == "cat") "v2" else "v1"}.png"
            else -> "${pet.family}-room-tidy-v1.png"
        }
        return "file:///android_asset/orbis-pets/$asset"
    }
    fun directory(context: Context, appearance: String): File {
        // Android may expose filesDir through an OS-owned alias. Normalize the trusted
        // base first; comparing an unnormalized base to its canonical child rejects it.
        val base = context.filesDir.canonicalFile
        val root = File(base, "orbis-pet-assets")
        val target = File(root, PetCatalog.find(appearance).id)
        require(root.canonicalFile == root.absoluteFile && target.canonicalFile == target.absoluteFile) { "invalid_pet_path" }
        return target
    }
    fun ready(context: Context, appearance: String): Boolean = try {
        val root = directory(context, appearance)
        names.all { File(root, it).let { file -> file.canonicalFile == file.absoluteFile && file.isFile } }
    } catch (_: Exception) { false }
    fun import(context: Context, input: InputStream, appearance: String) {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in names + "manifest.json" && entry.name !in entries && !entry.isDirectory) { "invalid_pet_pack" }
                entries[entry.name] = zip.bounded(if (entry.name == "manifest.json") 4096 else 8 * 1024 * 1024)
                zip.closeEntry()
            }
        }
        require(entries.keys == names + "manifest.json") { "incomplete_pet_pack" }
        val manifest = Json.parseToJsonElement(entries.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        PetCatalog.find(appearance)
        require(manifest["id"]?.jsonPrimitive?.content == appearance) { "pet_pack_appearance_mismatch" }
        require(manifest["version"]?.jsonPrimitive?.int == 1) { "invalid_pet_pack" }
        names.forEach { name ->
            val bytes = entries.getValue(name)
            require(OrbisStickerImages.sha256(bytes) == manifest.getValue("sha256").jsonObject[name]?.jsonPrimitive?.content)
            val info = OrbisStickerImages.inspect(bytes); require(info.format == "png" && info.width <= 2048 && info.height <= 2048)
        }
        val root = directory(context, appearance)
        // Immutable pack. Reimporting identical files is harmless; never overwrite another identity.
        names.forEach { name -> val file = File(root, name)
            require(file.canonicalFile == file.absoluteFile) { "invalid_pet_path" }
            if (file.exists()) require(file.readBytes().contentEquals(entries.getValue(name))) { "different_pet_pack" }
        }
        require(root.isDirectory || root.mkdirs())
        names.forEach { name ->
            val file = AtomicFile(File(root, name)); val out = file.startWrite()
            try { out.write(entries.getValue(name)); out.fd.sync(); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        }
    }
}
