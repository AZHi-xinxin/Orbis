package me.rerere.rikkahub.data.orbis.character

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class CharacterDraft(val pack: CharacterPack, val interactions: CharacterInteractions, val png: ByteArray,
    val presentation: CharacterPresentation? = null, val assets: Map<String, ByteArray> = emptyMap())

/** App-private, owner-scoped, atomic metadata. Device/widget identity never travels in a pack. */
class CharacterStore(base: File, owner: String) {
    private val root: File
    private val lock: Any
    init {
        require(CharacterRules.owner(owner)) { "character_owner" }
        val namespace = File(base.canonicalFile, "orbis-characters")
        root = File(namespace, owner)
        require(namespace.canonicalFile == namespace && root.canonicalFile == root) { "character_path" }
        lock = locks.computeIfAbsent(root.path) { Any() }
    }
    private fun file(name: String): File = File(root, name).also {
        require(it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())) { "character_path" }
    }
    fun read(): CharacterProfile = synchronized(lock) {
        val f = file("profile.json")
        if (!f.exists()) CharacterProfile() else json.decodeFromString<CharacterProfile>(f.inputStream().use { it.characterReadBounded(CharacterRules.JSON_BYTES) }.toString(Charsets.UTF_8)).also(CharacterRules::validate)
    }
    fun imageFile(pack: CharacterPack): File = file("${pack.sha256}.png").also { require(it.isFile && it.length() in 1..CharacterRules.IMAGE_BYTES.toLong()) { "character_image" } }
    fun imageBytes(pack: CharacterPack): ByteArray = synchronized(lock) {
        imageFile(pack).inputStream().use { it.characterReadBounded(CharacterRules.IMAGE_BYTES) }.also { require(characterSha(it) == pack.sha256) { "character_image" } }
    }
    fun assetBytes(asset: CharacterAsset): ByteArray = synchronized(lock) {
        file("${asset.sha256}.png").inputStream().use { it.characterReadBounded(CharacterRules.IMAGE_BYTES) }
            .also { require(characterSha(it) == asset.sha256) { "character_image" } }
    }
    fun save(profile: CharacterProfile, expectedRevision: Long): CharacterProfile = synchronized(lock) {
        require(read().revision == expectedRevision) { "character_conflict" }
        val next = profile.copy(revision = expectedRevision + 1)
        CharacterRules.validate(next)
        val metadata = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(metadata.size <= CharacterRules.JSON_BYTES) { "character_too_large" }
        next.pack?.let { imageBytes(it) }
        next.presentation.assets.distinctBy { it.sha256 }.forEach { assetBytes(it) }
        atomic("profile.json", metadata)
        next
    }
    fun install(draft: CharacterDraft, expectedRevision: Long): CharacterProfile = synchronized(lock) {
        val old = read()
        require(old.revision == expectedRevision) { "character_conflict" }
        val candidate = old.copy(pack = draft.pack, interactions = draft.interactions,
            presentation = draft.presentation ?: old.presentation)
        CharacterRules.validate(candidate)
        val referenced = candidate.presentation.assets.map { it.sha256 }.toSet()
        require(draft.assets.keys.all { it in referenced }) { "character_assets" }
        require(draft.assets.values.sumOf { it.size.toLong() } + draft.png.size <= CharacterArchive.TOTAL_BYTES) { "character_too_large" }
        draft.assets.forEach { (sha, bytes) -> require(bytes.size in 8..CharacterRules.IMAGE_BYTES && characterSha(bytes) == sha) { "character_image" } }
        require(draft.png.size in 8..CharacterRules.IMAGE_BYTES && characterSha(draft.png) == draft.pack.sha256) { "character_image" }
        // Keep the last committed image for recovery; clean up only our unreferenced images after commit.
        atomic("${draft.pack.sha256}.png", draft.png)
        draft.assets.forEach { (sha, bytes) -> atomic("$sha.png", bytes) }
        val next = save(candidate, expectedRevision)
        val keep = next.assetHashes() + old.assetHashes()
        root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.png")) && it.name.removeSuffix(".png") !in keep }
            .forEach { if (it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())) it.delete() }
        next
    }
    /** Only an unconfigured owner receives the starter art. Never replaces a private portrait/gallery. */
    internal fun installDefaultIfEmpty(load: () -> CharacterDraft): CharacterProfile = synchronized(lock) {
        val old = read()
        if (old.pack != null || old.presentation.assets.isNotEmpty()) return@synchronized old
        val draft = load()
        val presentation = requireNotNull(draft.presentation).copy(
            card = old.presentation.card, scene = old.presentation.scene,
            greeting = old.presentation.greeting, colors = old.presentation.colors)
        install(draft.copy(presentation = presentation, interactions = old.interactions), old.revision)
    }
    fun export(out: OutputStream, includeLines: Boolean) = synchronized(lock) {
        val profile = read(); val pack = requireNotNull(profile.pack) { "character_missing" }
        val presentation = profile.presentation.copy(greeting = if (includeLines) profile.presentation.greeting else "", expressionPolicy = null)
        CharacterArchive.write(out, CharacterDraft(pack, if (includeLines) profile.interactions else CharacterInteractions(), imageBytes(pack), presentation,
            presentation.assets.distinctBy { it.sha256 }.associate { it.sha256 to assetBytes(it) }), includeLines)
    }
    /** A normal backup snapshots only committed metadata and its references under the owner lock. */
    internal fun withBackupSnapshot(directoryEntries: (File) -> Array<File>? = { it.listFiles() },
        consume: (CharacterProfile, Map<String, File>) -> Unit) = synchronized(lock) {
        if (Files.notExists(root.parentFile.toPath())) return@synchronized
        require(root.parentFile.isDirectory) { "character_backup_path" }
        if (Files.notExists(root.toPath())) return@synchronized
        require(root.isDirectory) { "character_backup_path" }
        // Preserve legitimate unconfigured owners, but never silently omit unreadable ones.
        val entries = requireNotNull(directoryEntries(root)) { "character_backup_invalid" }
        if (entries.none { it.name == "profile.json" }) return@synchronized
        require(file("profile.json").isFile) { "character_backup_invalid" }
        val profile = read()
        // A restored backup must not reinstate an old temporary AI expression authorization.
        val portable = profile.copy(presentation = profile.presentation.copy(expressionPolicy = null))
        consume(portable, portable.assetHashes().associateWith { hash -> file("$hash.png") })
    }
    private fun atomic(name: String, bytes: ByteArray) {
        require(root.isDirectory || root.mkdirs()) { "character_storage" }
        require(root.canonicalFile == root.absoluteFile) { "character_path" }
        val temporary = file("pending-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file(name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object {
        internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

object CharacterArchive {
    const val TOTAL_BYTES = 128L * 1024 * 1024
    /** No entry paths are extracted; only fixed metadata and content-addressed PNG names. */
    fun read(input: InputStream): CharacterDraft {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < 68 && !entry.isDirectory && (entry.name in setOf("manifest.json", "portrait.png", "interactions.json", "presentation.json") || entry.name.matches(Regex("asset-[a-f0-9]{64}\\.png"))) && entry.name !in entries) { "character_archive" }
                val limit = if (entry.name.endsWith(".png")) CharacterRules.IMAGE_BYTES else CharacterRules.JSON_BYTES
                val bytes = zip.characterReadBounded(minOf(limit.toLong(), TOTAL_BYTES - total).toInt())
                total += bytes.size
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val manifest = requireNotNull(entries["manifest.json"]) { "character_archive" }
        val pack = CharacterStore.json.decodeFromString<CharacterPack>(manifest.toString(Charsets.UTF_8))
        val lines = entries["interactions.json"]?.let { CharacterStore.json.decodeFromString<CharacterInteractions>(it.toString(Charsets.UTF_8)) } ?: CharacterInteractions()
        val presentation = entries["presentation.json"]?.let { CharacterStore.json.decodeFromString<CharacterPresentation>(it.toString(Charsets.UTF_8)) }
        CharacterRules.validate(CharacterProfile(pack = pack, interactions = lines, presentation = presentation ?: CharacterPresentation()))
        val assets = presentation?.assets.orEmpty().distinctBy { it.sha256 }.associate { asset ->
            val bytes = requireNotNull(entries["asset-${asset.sha256}.png"]) { "character_archive" }
            require(characterSha(bytes) == asset.sha256) { "character_image" }
            asset.sha256 to bytes
        }
        require(entries.keys.count { it.startsWith("asset-") } == assets.size) { "character_archive" }
        val image = requireNotNull(entries["portrait.png"]) { "character_archive" }
        require(characterSha(image) == pack.sha256) { "character_image" }
        return CharacterDraft(pack, lines, image, presentation, assets) // Android verifies pixels before install.
    }
    fun write(out: OutputStream, draft: CharacterDraft, includeLines: Boolean) {
        CharacterRules.validate(CharacterProfile(pack = draft.pack, interactions = draft.interactions, presentation = draft.presentation ?: CharacterPresentation()))
        require(characterSha(draft.png) == draft.pack.sha256) { "character_image" }
        require(draft.assets.keys == draft.presentation?.assets.orEmpty().map { it.sha256 }.toSet()) { "character_assets" }
        require(draft.assets.values.sumOf { it.size.toLong() } + draft.png.size <= TOTAL_BYTES) { "character_too_large" }
        draft.assets.forEach { (sha, bytes) -> require(bytes.size <= CharacterRules.IMAGE_BYTES && characterSha(bytes) == sha) { "character_image" } }
        ZipOutputStream(out).use { zip ->
            val entries = linkedMapOf("manifest.json" to CharacterStore.json.encodeToString(draft.pack).toByteArray(), "portrait.png" to draft.png)
            if (includeLines) entries["interactions.json"] = CharacterStore.json.encodeToString(draft.interactions).toByteArray()
            draft.presentation?.let { entries["presentation.json"] = CharacterStore.json.encodeToString(it.copy(greeting = if (includeLines) it.greeting else "", expressionPolicy = null)).toByteArray() }
            draft.assets.forEach { (sha, bytes) -> entries["asset-$sha.png"] = bytes }
            require(entries.values.sumOf { it.size.toLong() } <= TOTAL_BYTES) { "character_too_large" }
            require(entries.filterKeys { it.endsWith(".json") }.values.all { it.size <= CharacterRules.JSON_BYTES }) { "character_too_large" }
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
}

internal fun InputStream.characterReadBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(result.size().toLong() + n <= limit) { "character_too_large" }; result.write(buffer, 0, n) }
    return result.toByteArray()
}
fun characterSha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
