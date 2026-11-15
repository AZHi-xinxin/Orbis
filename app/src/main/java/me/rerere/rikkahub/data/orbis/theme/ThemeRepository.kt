package me.rerere.rikkahub.data.orbis.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
            check(state.materialTextColors.size <= MaterialThemePreset.entries.size) { "theme_store_invalid" }
            state.materialTextColors.forEach { (id, colors) ->
                check(MaterialThemePreset.fromId(id) != null && state.entries.any { it.id == id && it.authorRole == "human" }) { "theme_store_invalid" }
                colors.validate()
            }
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
        requireAssets(body)
        // Draft revision is also guarded, so two writers cannot silently replace each other.
        val entry = (old ?: ThemeEntry(UUID.randomUUID().toString())).copy(revision = expected + 1, draft = body)
        save(state.copy(entries = if(old == null) state.entries + entry else state.entries.map { if(it.id == entry.id) entry else it }))
        entry
    }
    /** Merge only the supplied fields into this theme; absent fields remain exactly as authored. */
    fun patch(text: String, id: String, expected: Int): ThemeEntry = synchronized(lock) {
        require(text.toByteArray().size <= 32 * 1024) { "theme_package_too_large" }
        val old = editable(snapshot(),id,expected)
        val base = old.draft ?: old.versions.lastOrNull()?.body ?: error("theme_draft_missing")
        val delta = themeJson.parseToJsonElement(text) as? JsonObject ?: error("theme_patch_invalid")
        fun merge(base: JsonObject, patch: JsonObject, depth: Int): JsonObject {
            require(depth <= 12) { "theme_patch_invalid" }
            val result=base.toMutableMap()
            patch.forEach { (k,v) ->
                if(v==JsonNull) result.remove(k)
                else result[k]=if(v is JsonObject) merge(result[k] as? JsonObject ?: JsonObject(emptyMap()),v,depth+1) else v
            }
            return JsonObject(result)
        }
        var merged = merge(themeJson.encodeToJsonElement(base).jsonObject,delta,0)
        // Replacing/removing an image in a draft updates its manifest too. Published
        // versions and actual files stay intact for active themes and undo.
        if ("assets" !in delta) {
            val next = themeJson.decodeFromJsonElement<ThemePackage>(merged)
            val refs = next.assetReferences().values.toSet()
            val removed = base.assetReferences().values.toSet() - refs
            val assets = (next.assets.filterNot { it in removed } + refs).distinct()
            merged = JsonObject(merged + ("assets" to JsonArray(assets.map(::JsonPrimitive))))
        }
        write(merged.toString(),id,expected)
    }
    fun publish(id: String, expected: Int): ThemeEntry = synchronized(lock) {
        val state = snapshot(); val old = editable(state, id, expected)
        val body = old.draft ?: error("theme_draft_missing"); body.validate()
        requireAssets(body)
        // A small edit must not force a new theme after twenty publications. Keep the
        // active and pending versions plus recent revisions; prune only old undo refs.
        val newVersion = ThemeVersion(old.revision + 1, body)
        val protected = listOfNotNull(state.active,state.pending).filter { it.id==id }.map { it.revision }.toSet()
        val keep = (listOf(newVersion) + old.versions.filter { it.revision in protected } + old.versions.reversed())
            .distinctBy { it.revision }.take(20).sortedBy { it.revision }
        val next = old.copy(revision = old.revision + 1, draft = null, versions = keep)
        save(state.copy(entries = state.entries.map { if (it.id == id) next else it },
            previous=state.previous.filter { it==null || it.id!=id || keep.any { v -> v.revision==it.revision } }))
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
        if (state.allowAiApply || ref.id in state.autoApply) { applyLocked(state, ref); true }
        else { save(state.copy(pending = ref)); false }
    }
    fun confirm(ref: ThemeRef) = synchronized(lock) {
        val state = snapshot(); check(state.resolve(ref) != null) { "theme_version_missing" }; applyLocked(state, ref)
    }
    private fun applyLocked(state: ThemeState, ref: ThemeRef) {
        val body = state.resolve(ref) ?: error("theme_version_missing")
        requireAssets(body)
        save(state.copy(active = ref, pending = null, previous = if (ref == state.active) state.previous else (state.previous + state.active).takeLast(20)))
    }
    fun dismissPending() = synchronized(lock) { save(snapshot().copy(pending = null)) }
    /** Native human control only; never exposed as a tool action or accepted theme property. */
    fun followHumanFont(enabled: Boolean) = synchronized(lock) { save(snapshot().copy(followHumanFont = enabled)) }
    fun allowAiApply(allowed: Boolean) = synchronized(lock) {
        save(snapshot().copy(allowAiApply = allowed, autoApply = if(allowed) snapshot().autoApply else emptySet()))
    }
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
        if (state.active == ref) save(state.copy(active = null, pending = null, autoApply = state.autoApply - ref.id, allowAiApply = false))
    }
    fun useOriginal() = synchronized(lock) {
        val state = snapshot(); save(state.copy(active = null, pending = null, previous = if (state.active != null) (state.previous + state.active).takeLast(20) else state.previous))
    }
    fun delete(id: String, expected: Int) = synchronized(lock) {
        val state = snapshot(); editable(state, id, expected)
        save(state.copy(entries = state.entries.filterNot { it.id == id }, active = state.active?.takeUnless { it.id == id },
            pending = state.pending?.takeUnless { it.id == id }, previous = state.previous.filter { it?.id != id }, autoApply = state.autoApply - id))
    }
    /** Native human controls: built-ins are separate entries, never edits of an AI's design. */
    fun prepareMaterialPreset(preset: MaterialThemePreset, fallbackWallpaper: String): ThemeRef = synchronized(lock) {
        val state = snapshot()
        val existing = state.entries.firstOrNull { it.id == preset.id }
        if (existing != null) {
            check(existing.authorRole == "human") { "theme_revision_or_owner_conflict" }
            val ref = ThemeRef(existing.id,existing.versions.last().revision)
            requireAssets(state.resolve(ref) ?: error("theme_version_missing"))
            return@synchronized ref
        }
        check(state.entries.size < 30) { "theme_store_full" }
        // First use keeps the user's current wallpaper, if any; the built-in image is a fallback.
        val wallpaper = state.resolve()?.background?.asset?.takeIf { asset(it).isFile } ?: fallbackWallpaper
        val body = materialThemePreset(preset,wallpaper); requireAssets(body)
        val entry = ThemeEntry(preset.id,revision=1,authorRole="human",versions=listOf(ThemeVersion(1,body)))
        // A first static variant keeps the current human ink choices; later visits keep its own edits.
        val inheritedInk = if(preset==MaterialThemePreset.LIQUID_STILL) state.materialTextColors[state.active?.id] else null
        save(state.copy(entries=state.entries+entry,materialTextColors=if(inheritedInk!=null)
            state.materialTextColors+(preset.id to inheritedInk) else state.materialTextColors))
        ThemeRef(entry.id,1)
    }
    fun setMaterialBackground(expected: ThemeRef, wallpaper: String): ThemeRef = synchronized(lock) {
        val state = snapshot()
        check(state.active == expected) { "theme_revision_or_owner_conflict" }
        check(MaterialThemePreset.fromId(expected.id) != null) { "theme_not_material_preset" }
        val old = state.entries.first { it.id == expected.id }
        check(old.authorRole == "human") { "theme_revision_or_owner_conflict" }
        val body = state.resolve(expected) ?: error("theme_version_missing")
        val changed = body.copy(background=ThemeBackground("image",wallpaper,dim=0f))
        val nextBody = changed.copy(assets=changed.assetReferences().values.distinct()).also { it.validate();requireAssets(it) }
        val ref = ThemeRef(old.id,old.revision+1)
        val keep = (listOf(ThemeVersion(ref.revision,nextBody)) + old.versions.filter { it.revision == expected.revision } + old.versions.reversed())
            .distinctBy { it.revision }.take(20).sortedBy { it.revision }
        val next = old.copy(revision=ref.revision,versions=keep)
        val previous = (state.previous + state.active).filter {
            it == null || it.id != old.id || keep.any { v -> v.revision == it.revision }
        }.takeLast(20)
        save(state.copy(entries=state.entries.map { if(it.id==old.id)next else it },active=ref,pending=null,previous=previous))
        ref
    }

    /** Explicit native editor only. Does not grant AI access or change published versions. */
    fun setMaterialTextColor(expected: ThemeRef, target: MaterialTextTarget, color: String?) = synchronized(lock) {
        val state = snapshot()
        check(state.active == expected && MaterialThemePreset.fromId(expected.id) != null &&
            state.entries.any { it.id == expected.id && it.authorRole == "human" }) { "theme_revision_or_owner_conflict" }
        val next = (state.materialTextColors[expected.id] ?: MaterialTextColors()).with(target, color)
        val preferences = if (next == MaterialTextColors()) state.materialTextColors - expected.id
            else state.materialTextColors + (expected.id to next)
        save(state.copy(materialTextColors = preferences))
    }

    fun asset(name: String): File { require(name.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)"))) { "theme_asset_invalid" }; return file(name) }
    fun missingAssetFields(body: ThemePackage): List<String> = synchronized(lock) {
        body.assets.filterNot { asset(it).isFile }.flatMap { missing ->
            body.assetReferences().filterValues { it == missing }.keys.toList().ifEmpty { listOf("assets[${body.assets.indexOf(missing)}]") }
        }
    }
    private fun requireAssets(body: ThemePackage) {
        missingAssetFields(body).firstOrNull()?.let { throw ThemeAssetException(it) }
    }
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
