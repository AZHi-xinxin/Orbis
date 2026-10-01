package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.orbis.OrbisKaomojiRepository
import me.rerere.rikkahub.data.orbis.OrbisKaomojiState
import me.rerere.rikkahub.data.orbis.schedule.ORBIS_SCHEDULE_MAX_BYTES
import me.rerere.rikkahub.data.orbis.schedule.OrbisScheduleSnapshot
import me.rerere.rikkahub.data.orbis.schedule.validOrbisScheduleText
import me.rerere.rikkahub.data.orbis.schedule.validateOrbisScheduleSnapshot

/** Exact, credential-free addition to FILES; never walk or copy a whole application directory. */
internal object OrbisLocalToolBackup {
    const val SCHEDULE = "orbis-schedule/schedule-v1.json"
    const val KAOMOJI = "orbis-kaomoji/library-v1.json"
    val paths: List<String> = listOf(SCHEDULE, KAOMOJI)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun maxBytes(path: String): Int? = when (path) {
        SCHEDULE -> ORBIS_SCHEDULE_MAX_BYTES
        KAOMOJI -> OrbisKaomojiRepository.MAX_STORAGE_CHARS * 4
        else -> null
    }

    fun encodeSchedule(value: OrbisScheduleSnapshot): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8).also { validate(SCHEDULE, it) }
    fun encodeKaomoji(value: OrbisKaomojiState): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8).also { validate(KAOMOJI, it) }

    fun validate(path: String, bytes: ByteArray) {
        when (path) {
            SCHEDULE -> schedule(bytes)
            KAOMOJI -> kaomoji(bytes)
            else -> error("local_tool_backup_unknown_path")
        }
    }

    private fun text(path: String, bytes: ByteArray, keys: Set<String>): String {
        require(bytes.size in 1..requireNotNull(maxBytes(path))) { invalid(path) }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { error(invalid(path)) }
        val document = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        require(document != null && document.keys == keys) { invalid(path) }
        return text
    }

    private fun schedule(bytes: ByteArray): OrbisScheduleSnapshot = try {
        json.decodeFromString<OrbisScheduleSnapshot>(text(SCHEDULE, bytes, setOf("version", "revision", "entries")))
            .also(::validateOrbisScheduleSnapshot)
    } catch (_: Exception) { error(invalid(SCHEDULE)) }

    private fun kaomoji(bytes: ByteArray): OrbisKaomojiState = try {
        val source = text(KAOMOJI, bytes, setOf("version", "entries"))
        require(source.length <= OrbisKaomojiRepository.MAX_STORAGE_CHARS)
        json.decodeFromString<OrbisKaomojiState>(source).also { value ->
            require(value.version == 1 && value.entries.size <= OrbisKaomojiRepository.MAX_ENTRIES)
            require(value.entries.map { it.id }.distinct().size == value.entries.size)
            require(value.entries.map { it.text }.distinct().size == value.entries.size)
            value.entries.forEach {
                require(OrbisKaomojiRepository.normalize(it) == it)
                require(validOrbisScheduleText(it.label, 40) && validOrbisScheduleText(it.text, 160) &&
                    it.tags.all { tag -> validOrbisScheduleText(tag, 20) })
            }
        }
    } catch (_: Exception) { error(invalid(KAOMOJI)) }

    /** Preserve local-only records; identical IDs skip, differing contents reject the entire restore. */
    fun merge(path: String, current: ByteArray?, incoming: ByteArray): ByteArray {
        validate(path, incoming)
        if (current == null) return incoming.copyOf()
        validate(path, current)
        return when (path) {
            SCHEDULE -> {
                val before = schedule(current)
                val backup = schedule(incoming)
                val indexed = before.entries.associateBy { it.id }
                require(backup.entries.all { indexed[it.id] == null || indexed[it.id] == it }) { conflict(path) }
                val additions = backup.entries.filter { it.id !in indexed }
                if (additions.isEmpty()) current.copyOf() else {
                    val revision = maxOf(before.revision, backup.revision)
                    require(revision < Int.MAX_VALUE) { conflict(path) }
                    encodeSchedule(before.copy(revision = revision + 1, entries = before.entries + additions))
                }
            }
            KAOMOJI -> {
                val before = kaomoji(current)
                val backup = kaomoji(incoming)
                val ids = before.entries.associateBy { it.id }
                val texts = before.entries.associateBy { it.text }
                require(backup.entries.all { (ids[it.id] == null || ids[it.id] == it) &&
                    (texts[it.text] == null || texts[it.text] == it) }) { conflict(path) }
                val additions = backup.entries.filter { it.id !in ids }
                if (additions.isEmpty()) current.copyOf() else encodeKaomoji(before.copy(entries = before.entries + additions))
            }
            else -> error("local_tool_backup_unknown_path")
        }
    }

    /** Used on private staging before publication. Bad local-tool data cannot stage a partial restore. */
    fun validateStaged(payload: File) {
        paths.forEach { path ->
            val source = exactFile(File(payload, "files"), path)
            if (source.exists()) validate(path, readBounded(path, source))
        }
    }

    /** Startup only, before any repository opens; prepare all merges before changing any payload file. */
    fun prepareBeforeJournal(payload: File, liveFiles: File) {
        val prepared = paths.mapNotNull { path ->
            val source = exactFile(File(payload, "files"), path)
            if (!source.exists()) null else {
                val target = exactFile(liveFiles, path)
                val backup = exactFile(liveFiles, "$path.bak")
                val unfinished = exactFile(liveFiles, "$path.new")
                // Match AtomicFile's committed choice without openRead (which could mutate live files).
                val committed = when { backup.exists() -> backup; target.exists() -> target; else -> null }
                require(committed != null || !unfinished.exists()) { invalid(path) }
                source to merge(path, committed?.let { readBounded(path, it) }, readBounded(path, source))
            }
        }
        prepared.forEach { (file, bytes) -> PendingRestore.writeDurably(file, bytes.toString(Charsets.UTF_8)) }
    }

    /** Sidecars are journalled with originals, not blindly deleted or restored from an archive. */
    fun sidecarPaths(installedPaths: Set<String>): List<String> = paths.filter { "files/$it" in installedPaths }
        .flatMap { listOf("files/$it.bak", "files/$it.new") }

    private fun exactFile(root: File, path: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, path)
        require(file.absoluteFile == file.canonicalFile && (!file.exists() || file.isFile)) { "local_tool_backup_unsafe_path" }
        require(file.canonicalPath.startsWith(canonicalRoot.path + File.separator)) { "local_tool_backup_unsafe_path" }
        return file
    }

    private fun readBounded(path: String, file: File): ByteArray {
        require(file.isFile && file.length() in 1..requireNotNull(maxBytes(path)).toLong()) { invalid(path) }
        return file.inputStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size().toLong() + count <= requireNotNull(maxBytes(path))) { invalid(path) }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }

    private fun invalid(path: String) = if (path == SCHEDULE) "local_tool_backup_invalid_schedule" else "local_tool_backup_invalid_kaomoji"
    private fun conflict(path: String) = if (path == SCHEDULE) "local_tool_backup_conflict_schedule" else "local_tool_backup_conflict_kaomoji"

    fun publicError(code: String?): String? = when (code) {
        "local_tool_backup_invalid_schedule" -> "课表与日程备份格式、大小或本机原文件有问题；未覆盖本机数据，请保留原备份并核对。"
        "local_tool_backup_invalid_kaomoji" -> "颜文字备份格式、大小或本机原文件有问题；未覆盖本机数据，请保留原备份并核对。"
        "local_tool_backup_conflict_schedule" -> "课表与日程存在同一编号但内容不同的记录，整次恢复已停止并保留原数据；请先在原设备核对并重新备份。"
        "local_tool_backup_conflict_kaomoji" -> "颜文字存在编号或文字冲突，整次恢复已停止并保留原数据；请先在原设备核对并重新备份。"
        "local_tool_backup_unsafe_path" -> "本地工具资料路径异常，整次恢复已停止并保留原数据。"
        else -> null
    }
}
