package me.rerere.rikkahub.data.sync.importer

import androidx.core.net.toUri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File

internal data class DeepSeekStoredAttachment(val id: Long, val url: String)
internal interface DeepSeekAttachmentStore {
    val cacheDir: File
    suspend fun save(file: File, name: String): DeepSeekStoredAttachment
    suspend fun delete(id: Long)
}
internal class ManagedDeepSeekAttachmentStore(private val files: FilesManager, override val cacheDir: File) : DeepSeekAttachmentStore {
    override suspend fun save(file: File, name: String): DeepSeekStoredAttachment {
        val entity = files.saveUploadFromUri(file.toUri(), name)
        return DeepSeekStoredAttachment(entity.id, files.getFile(entity).toUri().toString())
    }
    override suspend fun delete(id: Long) { files.delete(id) }
}

/** One session per imported conversation, so deletion of another window cannot break its files. */
internal class DeepSeekAttachmentSession(private val archive: DeepSeekArchive.ArchiveReader,
    private val store: DeepSeekAttachmentStore?) {
    private val created = mutableListOf<Long>()
    suspend fun rollback() { created.forEach { store?.delete(it) } }
    suspend fun restore(nodes: List<DeepSeekNode>, checkCancelled: () -> Unit): Map<String, UIMessagePart> {
        val storage = store ?: return emptyMap()
        val result = linkedMapOf<String, UIMessagePart>()
        val checked = hashSetOf<String>()
        for (node in nodes) for (fragment in node.message?.fragments.orEmpty()) {
            if (fragment.type != "FILE") continue
            for (entry in (fragment.raw["files"] as? JsonArray).orEmpty()) {
                checkCancelled()
                val data = entry.jsonObject
                val path = data["export_path"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                if (path.isNullOrBlank() || !checked.add(path)) continue
                ArchiveCapacity.requireSpace(storage.cacheDir.usableSpace,
                    (data["file_size"]?.jsonPrimitive?.longOrNull ?: 0).coerceAtLeast(0) * 2)
                val extension = path.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }.orEmpty()
                check(storage.cacheDir.mkdirs() || storage.cacheDir.isDirectory)
                val temporary = File.createTempFile("deepseek-import-", ".$extension", storage.cacheDir)
                try {
                    val present = temporary.outputStream().use { archive.copyAttachment(path, it) }
                    if (!present) continue
                    val display = data["file_name"]?.jsonPrimitive?.content.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                        .filterNot(Char::isISOControl).take(255).ifBlank { "attachment.$extension" }
                    // Account for created managed files even if cancellation arrives while saving.
                    val saved = withContext(NonCancellable) { storage.save(temporary, display).also { created += it.id } }
                    val metadata = buildJsonObject { put("import_source", "deepseek"); put("source_file", data) }
                    result[path] = if (data["is_image"] == JsonPrimitive(true) && extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp"))
                        UIMessagePart.Image(saved.url, metadata)
                    else UIMessagePart.Document(saved.url, display, deepSeekMime(display), metadata)
                } finally { temporary.delete() }
            }
        }
        return result
    }
}
