package me.rerere.rikkahub.data.orbis.theme

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** Explicit FILES backup only. Pending applications and auto-apply permission never travel. */
internal object ThemeBackup {
    private val path = Regex("orbis-themes/([A-Za-z0-9_-]{1,80})/(themes\\.json|[a-f0-9]{64}\\.(png|jpg|webp))")
    fun maxBytes(name: String): Int? = path.matchEntire(name)?.let { if (it.groupValues[2] == "themes.json") 2 * 1024 * 1024 else 10 * 1024 * 1024 }
    private fun exact(root: File, name: String): File = File(root.canonicalFile, name).also {
        require(it.canonicalFile == it.absoluteFile && it.toPath().startsWith(root.canonicalFile.toPath())) { "theme_backup_path" }
    }
    private fun owners(root: File): List<File> {
        val folder = exact(root, "orbis-themes")
        if (!folder.exists()) return emptyList()
        require(folder.isDirectory) { "theme_backup_path" }
        return folder.listFiles().orEmpty().map { owner ->
            require(owner.isDirectory && owner.name.matches(Regex("[A-Za-z0-9_-]{1,80}")) && owner.canonicalFile == owner.absoluteFile) { "theme_backup_path" }
            owner
        }
    }
    private fun read(file: File, name: String): ByteArray {
        require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..requireNotNull(maxBytes(name)).toLong()) { "theme_backup_invalid" }
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(out.size().toLong() + n <= requireNotNull(maxBytes(name))) { "theme_backup_invalid" }; out.write(buffer, 0, n) }
            out.toByteArray()
        }
    }
    fun validate(name: String, bytes: ByteArray) {
        require(bytes.size in 1..requireNotNull(maxBytes(name))) { "theme_backup_invalid" }
        if (!name.endsWith("/themes.json")) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(name.substringAfterLast('/').substringBefore('.') == hash) { "theme_backup_invalid" }
        } else {
            val state = themeJson.decodeFromString<ThemeState>(bytes.toString(Charsets.UTF_8))
            require(state.schema == 1 && state.entries.size <= 30 && state.entries.map { it.id }.distinct().size == state.entries.size) { "theme_backup_invalid" }
            state.entries.forEach { e ->
                require(e.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && e.authorRole in setOf("ai", "human") && e.revision >= 0 && e.versions.size <= 20) { "theme_backup_invalid" }
                e.draft?.validate(); e.versions.forEach { it.body.validate() }
            }
            (listOfNotNull(state.active,state.pending) + state.previous.filterNotNull()).forEach { require(state.resolve(it) != null) { "theme_backup_invalid" } }
        }
    }
    private fun validateOwner(root: File, owner: File): List<Pair<String, File>> {
        val store = ThemeRepository(owner); val state = store.snapshot()
        val assets = state.entries.flatMap { e -> e.versions.flatMap { it.body.assets } }.toSet()
        assets.forEach { require(store.asset(it).isFile) { "theme_backup_asset_missing" } }
        return owner.listFiles().orEmpty().filter { it.name == "themes.json" || store.assets().contains(it.name) }.map { file ->
            val name = "orbis-themes/${owner.name}/${file.name}"; val exact = exact(root, name)
            validate(name, read(exact, name)); name to exact
        }
    }
    fun stageSnapshot(filesDir: File, destination: File, checkCancelled: () -> Unit = {}, beforeFile: (String, Long) -> Unit = { _, _ -> }): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String,File>>()
        owners(filesDir).forEach { owner ->
            ThemeRepository(owner).withLock {
                validateOwner(filesDir, owner).forEach { (name, source) ->
                    checkCancelled()
                    val bytes = if (source.name == "themes.json") themeJson.encodeToString(ThemeRepository(owner).snapshot().copy(autoApply = emptySet(), pending = null, allowAiApply = false)).toByteArray() else read(source,name)
                    beforeFile(name, bytes.size.toLong())
                    val target = exact(destination,name); require(!target.exists()); require(target.parentFile.isDirectory || target.parentFile.mkdirs())
                    ArchiveCapacity.requireSpace(target.parentFile.usableSpace, bytes.size.toLong())
                    FileOutputStream(target).use { it.write(bytes); it.fd.sync() }; result += name to target
                }
            }
        }
        return result
    }
    fun validateStaged(payload: File) { val root=File(payload,"files"); owners(root).forEach { validateOwner(root,it) } }
    fun prepareBeforeJournal(payload: File, live: File) {
        validateStaged(payload)
        val root=File(payload,"files"); val prepared=mutableListOf<Pair<File,ByteArray>>()
        val preservedAssets=mutableListOf<Pair<File,ByteArray>>()
        owners(root).forEach { incoming ->
            val source = ThemeRepository(incoming).snapshot()
            val old = ThemeRepository(exact(live,"orbis-themes/${incoming.name}")).snapshot()
            val byId = old.entries.associateBy { it.id }
            require(source.entries.all { byId[it.id] == null || byId[it.id] == it }) { "theme_backup_conflict" }
            val merged = old.copy(entries = old.entries + source.entries.filter { it.id !in byId })
            require(merged.entries.size <= 30) { "theme_backup_conflict" }
            val restored = if (old.entries.isEmpty()) source.copy(autoApply=emptySet(), pending=null, allowAiApply=false) else merged
            old.entries.flatMap { e -> e.versions.flatMap { it.body.assets } + e.draft?.assets.orEmpty() }.distinct().forEach { asset ->
                val name = "orbis-themes/${incoming.name}/$asset"
                val destination = exact(root,name)
                if (!destination.exists()) {
                    val sourceFile = exact(live,name)
                    if (sourceFile.exists()) { val bytes=read(sourceFile,name); validate(name,bytes); preservedAssets += destination to bytes }
                }
            }
            prepared += exact(incoming,"themes.json") to themeJson.encodeToString(restored).toByteArray()
            validateOwner(root,incoming).filter { !it.first.endsWith("/themes.json") }.forEach { (name,file) ->
                val target=exact(live,name)
                if (target.exists()) require(read(target,name).contentEquals(read(file,name))) { "theme_backup_conflict" }
            }
        }
        preservedAssets.forEach { (file,bytes) -> ArchiveCapacity.requireSpace(file.parentFile.usableSpace, bytes.size.toLong()); FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
        prepared.forEach { (file,bytes) -> FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
    }
    fun stripRestoredPermissions(payload: File) {
        owners(File(payload,"files")).forEach { owner ->
            val state=ThemeRepository(owner).snapshot()
            FileOutputStream(exact(owner,"themes.json")).use { it.write(themeJson.encodeToString(state.copy(autoApply=emptySet(),pending=null,allowAiApply=false)).toByteArray());it.fd.sync() }
        }
    }
}
