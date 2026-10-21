package me.rerere.rikkahub.data.orbis.voice

import java.io.File
import java.nio.ByteBuffer
import kotlinx.serialization.json.Json

/** Exact archive records only, never microphone buffers, credentials or uncommitted sidecars. */
internal object VoiceCallBackup {
    private val path = Regex("orbis-voice-calls/([A-Za-z0-9][A-Za-z0-9_-]{0,127})\\.json")
    private val json = Json { encodeDefaults = true }
    fun maxBytes(name: String): Int? = if (path.matches(name)) 32 * 1024 * 1024 else null
    private fun exact(root: File, name: String): File = File(root.canonicalFile, name).also {
        require(it.canonicalFile == it.absoluteFile && it.toPath().startsWith(root.canonicalFile.toPath())) { "voice_backup_path" }
    }
    private fun documents(payload: File): List<Pair<String, File>> {
        val root = File(payload, "files")
        val directory = exact(root, "orbis-voice-calls")
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "voice_backup_path" }
        return requireNotNull(directory.listFiles()).map { file ->
            val name = "orbis-voice-calls/${file.name}"
            require(maxBytes(name) != null && exact(root, name).isFile) { "voice_backup_path" }
            name to file
        }
    }
    private fun read(name: String, file: File): ByteArray {
        val limit = requireNotNull(maxBytes(name))
        require(file.isFile && file.canonicalFile == file.absoluteFile && file.length() in 1..limit.toLong()) { "voice_backup_invalid" }
        return file.inputStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= limit) { "voice_backup_invalid" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }
    fun decode(name: String, bytes: ByteArray): OrbisVoiceCallRecord {
        require(bytes.size in 1..requireNotNull(maxBytes(name))) { "voice_backup_invalid" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        require(text.length <= 16 * 1024 * 1024) { "voice_backup_invalid" }
        return json.decodeFromString<OrbisVoiceCallRecord>(text).also {
            require(it.id == path.matchEntire(name)!!.groupValues[1]) { "voice_backup_invalid" }
            OrbisVoiceCallRepository.validate(it)
        }
    }
    fun hasStaged(payload: File): Boolean = exact(File(payload, "files"), "orbis-voice-calls").exists()
    fun validateStaged(payload: File) { documents(payload).forEach { (name, file) -> decode(name, read(name, file)) } }
    fun validateOwnership(payload: File, owners: Map<String, String>) {
        documents(payload).forEach { (name, file) ->
            val record = decode(name, read(name, file))
            require(owners[record.conversationId] == record.assistantId) { "voice_backup_owner" }
        }
    }
    fun prepareBeforeJournal(payload: File, live: File) {
        validateStaged(payload)
        documents(payload).forEach { (name, incoming) ->
            val target = exact(live, name)
            val old = exact(live, "$name.bak").takeIf { it.exists() } ?: target
            if (old.exists()) require(decode(name, read(name, old)) == decode(name, read(name, incoming))) {
                "voice_backup_conflict"
            }
        }
    }
    fun sidecarPaths(paths: Set<String>): List<String> = paths.filter {
        it.startsWith("files/") && maxBytes(it.removePrefix("files/")) != null
    }.flatMap { listOf("$it.bak", "$it.new") }
}
