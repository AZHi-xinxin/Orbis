package me.rerere.rikkahub.data.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar

@Serializable
data class FileProtectionState(val version: Int = 1, val automatic: Set<String> = emptySet(),
    val overrides: Map<String, Boolean> = emptyMap()) {
    fun isLocked(path: String): Boolean = overrides[path] ?: (path in automatic)
}

/** Persist explicit locks and previously used avatars/wallpapers; never decode a broken file as empty. */
class FileProtection(private val file: File) {
    private val guard = guards.computeIfAbsent(file.canonicalPath) { Any() }
    companion object {
        private val guards = ConcurrentHashMap<String, Any>()
        const val PATH = "orbis-file-protection/locks-v1.json"
        const val MAX_BYTES = 2 * 1024 * 1024
        private val json = Json { encodeDefaults = true }
        fun validPath(path: String): Boolean = path.startsWith("upload/") &&
            path.substringAfter('/').let { it.isNotBlank() && it != "." && it != ".." &&
                it.none { c -> c == '/' || c == '\\' || c == ':' || Character.isISOControl(c) } }
        fun decode(raw: String): FileProtectionState = json.decodeFromString<FileProtectionState>(raw).also {
            require(raw.toByteArray().size <= MAX_BYTES && it.version == 1 &&
                it.automatic.size + it.overrides.size <= 20000 &&
                it.automatic.all(::validPath) && it.overrides.keys.all(::validPath)) { "file_protection_invalid" }
        }
        fun encode(value: FileProtectionState): String = json.encodeToString(FileProtectionState.serializer(), value).also(::decode)
        fun relativeUpload(root: File, location: String): String? = runCatching {
            val candidate = if (location.startsWith("file:")) File(URI(location)) else File(location)
            if (!candidate.isAbsolute) return@runCatching null
            val upload = File(root.canonicalFile, "upload")
            // Android may expose a trusted filesDir through /data/data or /data/user/0.
            // Permit only that root alias, never a child symlink or a traversed path.
            if (candidate.parentFile?.absoluteFile !in setOf(upload, File(root.absoluteFile, "upload")) ||
                candidate.canonicalFile != File(upload, candidate.name) || upload.canonicalFile != upload) return@runCatching null
            "upload/${candidate.name}".takeIf(::validPath)
        }.getOrNull()
    }

    fun snapshot(): FileProtectionState = synchronized(guard) {
        require(file.absoluteFile == file.canonicalFile) { "file_protection_unsafe_path" }
        if (!file.exists()) return@synchronized FileProtectionState()
        require(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "file_protection_invalid" }
        decode(file.readText())
    }
    fun rememberAutomatic(paths: Set<String>): FileProtectionState = synchronized(guard) {
        require(paths.all(::validPath))
        val before = snapshot()
        val next = before.copy(automatic = before.automatic + paths)
        if (next != before) write(next)
        next
    }
    fun setLocked(path: String, locked: Boolean) = synchronized(guard) {
        require(validPath(path))
        write(snapshot().let { it.copy(overrides = it.overrides + (path to locked)) })
    }
    /** One canonical-path guard across managers and settings writers, including the action. */
    internal fun <T> withAutomaticProtection(paths: Set<String>, action: (FileProtectionState) -> T): T =
        synchronized(guard) { action(rememberAutomatic(paths)) }

    /** Validate newly selected owned artwork before retaining it, under the cleaner's same guard. */
    internal fun protectAppearanceChange(root: File, before: Set<String>, after: Set<String>) =
        synchronized(guard) {
            require((before + after).all(::validPath))
            val canonicalRoot = root.canonicalFile
            check((after - before).all { path ->
                val selected = File(canonicalRoot, path)
                selected.absoluteFile == selected.canonicalFile && selected.isFile
            }) { "所选头像或壁纸已不可用，请重新选择；原设置未改动。" }
            rememberAutomatic(before + after)
        }
    private fun write(value: FileProtectionState) {
        val bytes = encode(value).toByteArray()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = File.createTempFile("locks-", ".tmp", file.parentFile)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}

internal fun protectedAppearancePaths(root: File, settings: Settings): Set<String> = buildList {
    (settings.displaySetting.userAvatar as? Avatar.Image)?.url?.let(::add)
    settings.assistants.forEach { assistant ->
        (assistant.avatar as? Avatar.Image)?.url?.let(::add)
        assistant.background?.let(::add)
    }
    settings.displaySetting.orbisAppearance.backgroundImage?.let(::add)
    settings.displaySetting.deepSeekAppearance.backgroundImage?.let(::add)
}.mapNotNull { FileProtection.relativeUpload(root, it) }.toSet()
