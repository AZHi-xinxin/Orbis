package me.rerere.rikkahub.data.orbis.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Every transaction rereads disk under the owner's lock, including after a backup restore. */
internal class ThemeRepository(private val root: File) {
    private val lock = locks.getOrPut(root.absolutePath) { Any() }
    val changes: StateFlow<Long> get() = signals.getOrPut(root.absolutePath) { MutableStateFlow(0) }
    init { require(root.parentFile.name == "orbis-themes" && root.name.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val file = File(root, name)
        check(root.canonicalFile == root.absoluteFile && file.canonicalFile == file.absoluteFile) { "theme_path_changed" }
        return file
    }
    fun <T> withLock(block: () -> T): T = synchronized(lock, block)
    fun snapshot(): ThemeState = synchronized(lock) {
        val f = file("themes.json")
        if (!f.exists()) return@synchronized ThemeState()
        check(f.length() <= 2 * 1024 * 1024) { "theme_store_invalid" }
        themeJson.decodeFromString<ThemeState>(f.readText()).also { state ->
            check(state.schema == 1 && state.entries.size <= 30 && state.entries.map { it.id }.distinct().size == state.entries.size) { "theme_store_invalid" }
            state.entries.forEach { e ->
                check(e.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && e.authorRole in setOf("ai", "human") && e.versions.size <= 20 && e.revision >= 0) { "theme_store_invalid" }
                e.draft?.validate(); e.versions.forEach { it.body.validate() }
            }
            (listOfNotNull(state.active, state.pending) + state.previous.filterNotNull()).forEach { check(state.resolve(it) != null) { "theme_version_missing" } }
        }
    }
    private fun save(next: ThemeState) {
        val bytes = themeJson.encodeToString(next).toByteArray()
        require(bytes.size <= 2 * 1024 * 1024) { "theme_store_full" }
        val target = file("themes.json"); check(root.isDirectory || root.mkdirs())
        val tmp = file("pending-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        signals.getOrPut(root.absolutePath) { MutableStateFlow(0) }.value++
    }
    fun write(text: String, id: String? = null, expected: Int = 0): ThemeEntry = synchronized(lock) {
        val body = parseTheme(text); val state = snapshot()
        val old = id?.let { key -> state.entries.firstOrNull { it.id == key } ?: error("theme_not_found") }
        check(old == null && expected == 0 || old?.authorRole == "ai" && old.revision == expected) { "theme_revision_or_owner_conflict" }
        check(old != null || state.entries.size < 30) { "theme_store_full" }
        // Draft revision is also guarded, so two writers cannot silently replace each other.
        val entry = (old ?: ThemeEntry(UUID.randomUUID().toString())).copy(revision = expected + 1, draft = body)
        save(state.copy(entries = state.entries.filterNot { it.id == entry.id } + entry))
        entry
    }
    fun publish(id: String, expected: Int): ThemeEntry = synchronized(lock) {
        val state = snapshot(); val old = editable(state, id, expected)
        val body = old.draft ?: error("theme_draft_missing"); body.validate()
        body.assets.forEach { check(asset(it).isFile) { "theme_asset_missing" } }
        check(old.versions.size < 20) { "theme_versions_full" }
        val next = old.copy(revision = old.revision + 1, draft = null, versions = old.versions + ThemeVersion(old.revision + 1, body))
        save(state.copy(entries = state.entries.map { if (it.id == id) next else it }))
        next
    }
    private fun editable(state: ThemeState, id: String, expected: Int): ThemeEntry {
        val entry = state.entries.firstOrNull { it.id == id } ?: error("theme_not_found")
        check(entry.authorRole == "ai" && entry.revision == expected) { "theme_revision_or_owner_conflict" }
        return entry
    }
    /** AI can request only a published immutable version. Human grants never come from JSON. */
    fun requestApply(ref: ThemeRef): Boolean = synchronized(lock) {
        val state = snapshot(); check(state.resolve(ref) != null) { "theme_version_missing" }
        if (ref.id in state.autoApply) { applyLocked(state, ref); true }
        else { save(state.copy(pending = ref)); false }
    }
    fun confirm(ref: ThemeRef) = synchronized(lock) {
        val state = snapshot(); check(state.resolve(ref) != null) { "theme_version_missing" }; applyLocked(state, ref)
    }
    private fun applyLocked(state: ThemeState, ref: ThemeRef) {
        val body = state.resolve(ref) ?: error("theme_version_missing")
        body.assets.forEach { check(asset(it).isFile) { "theme_asset_missing" } }
        save(state.copy(active = ref, pending = null, previous = if (ref == state.active) state.previous else (state.previous + state.active).takeLast(20)))
    }
    fun dismissPending() = synchronized(lock) { save(snapshot().copy(pending = null)) }
    fun allowAuto(id: String, allowed: Boolean) = synchronized(lock) {
        val state = snapshot(); check(state.entries.any { it.id == id }) { "theme_not_found" }
        save(state.copy(autoApply = if (allowed) state.autoApply + id else state.autoApply - id))
    }
    fun revert() = synchronized(lock) {
        val state = snapshot(); check(state.previous.isNotEmpty()) { "theme_no_previous" }
        save(state.copy(active = state.previous.last(), previous = state.previous.dropLast(1), pending = null))
    }
    fun renderFailed(ref: ThemeRef) = synchronized(lock) {
        val state = snapshot()
        if (state.active == ref) save(state.copy(active = null, pending = null, autoApply = state.autoApply - ref.id))
    }
    fun useOriginal() = synchronized(lock) {
        val state = snapshot(); save(state.copy(active = null, pending = null, previous = if (state.active != null) (state.previous + state.active).takeLast(20) else state.previous))
    }
    fun delete(id: String, expected: Int) = synchronized(lock) {
        val state = snapshot(); editable(state, id, expected)
        save(state.copy(entries = state.entries.filterNot { it.id == id }, active = state.active?.takeUnless { it.id == id },
            pending = state.pending?.takeUnless { it.id == id }, previous = state.previous.filter { it?.id != id }, autoApply = state.autoApply - id))
    }
    fun asset(name: String): File { require(name.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)"))) { "theme_asset_invalid" }; return file(name) }
    fun assets(): List<String> = synchronized(lock) { file("themes.json"); root.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)")) }.map { it.name }.sorted() }
    /** Host-validated raster only, with a content-addressed name. No tool accepts paths or URLs. */
    fun importAsset(bytes: ByteArray, extension: String): String = synchronized(lock) {
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024 && extension in setOf("png", "jpg", "webp")) { "theme_asset_invalid" }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "$hash.$extension"; val target = asset(name)
        if (!target.exists()) {
            check(assets().size < 30) { "theme_asset_limit" }; check(root.isDirectory || root.mkdirs())
            val tmp = file("asset-${UUID.randomUUID()}.tmp")
            try { FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }; Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            finally { tmp.delete() }
        }
        signals.getOrPut(root.absolutePath) { MutableStateFlow(0) }.value++
        name
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>(); private val signals = ConcurrentHashMap<String, MutableStateFlow<Long>>() }
}
