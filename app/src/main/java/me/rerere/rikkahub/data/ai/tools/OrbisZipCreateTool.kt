package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.GeneratedZipArchive
import org.koin.java.KoinJavaComponent.getKoin

internal data class SavedGeneratedZip(val id: Long, val document: UIMessagePart.Document)

internal fun createOrbisZipCreateTool(
    context: Context, owner: String, conversation: String, scopeCurrent: suspend () -> Boolean,
): Tool {
    val files = getKoin().get<FilesManager>()
    return createOrbisZipCreateTool(owner, conversation, scopeCurrent,
        save = { archive ->
            val saved = files.saveManagedFromBytes(FileFolders.UPLOAD, archive.bytes, archive.name, "application/zip")
            SavedGeneratedZip(saved.id, UIMessagePart.Document(files.getFile(saved).toUri().toString(), archive.name, "application/zip"))
        }, discard = { saved -> check(files.delete(saved.id)) { "new_zip_cleanup_failed" } })
}

/** The caller binds the current run's assistant/conversation, never whichever tab is selected later. */
internal fun createOrbisZipCreateTool(
    owner: String, conversation: String, scopeCurrent: suspend () -> Boolean,
    save: suspend (GeneratedZipArchive.Result) -> SavedGeneratedZip,
    discard: suspend (SavedGeneratedZip) -> Unit,
): Tool = Tool(
    name = "orbis_zip_create",
    description = "把你写好的文字文件打包为真实 ZIP 附件，成功后人类可在聊天中打开或保存。name 为 ZIP 文件名；files 为 [{path,text}]，path 仅为包内相对路径，支持 txt/md/html/json/code 等 UTF-8 文本，不读取手机路径，不执行内容、不上传、不自动写公共目录。最多128项、单项2MiB、总正文及ZIP各8MiB。成功后不用再贴文件正文或虚构下载链接。",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("name", buildJsonObject { put("type", "string"); put("maxLength", 128) })
        put("files", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", GeneratedZipArchive.MAX_ENTRIES)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string"); put("maxLength", 512) })
                    put("text", buildJsonObject { put("type", "string"); put("maxLength", GeneratedZipArchive.MAX_ENTRY_BYTES) })
                })
                put("required", buildJsonArray { add("path"); add("text") }); put("additionalProperties", false)
            })
        })
    }, required = listOf("name", "files")) },
    needsApproval = { false },
    hostApproval = HostToolApproval("orbis:zip_create:$owner:$conversation", "zip-text-artifact-v1", "生成文字 ZIP 附件"),
    execute = { arguments ->
        var saved: SavedGeneratedZip? = null
        try {
            currentCoroutineContext().ensureActive()
            check(owner.isNotBlank() && conversation.isNotBlank() && scopeCurrent()) { "zip_chat_scope_changed" }
            val args = arguments as? JsonObject ?: error("zip_invalid_arguments")
            require(args.keys == setOf("name", "files"))
            fun JsonElement?.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("zip_string_required")
            val entries = args["files"] as? JsonArray ?: error("zip_files_required")
            require(entries.size in 1..GeneratedZipArchive.MAX_ENTRIES)
            val archive = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                GeneratedZipArchive.create(args["name"].string(), entries.map { value ->
                    val entry = value as? JsonObject ?: error("zip_file_object_required")
                    require(entry.keys == setOf("path", "text"))
                    GeneratedZipArchive.TextFile(entry["path"].string(), entry["text"].string())
                }) { job.ensureActive() }
            }
            check(scopeCurrent()) { "zip_chat_scope_changed" }
            val committed = save(archive)
            saved = committed
            currentCoroutineContext().ensureActive()
            check(scopeCurrent()) { "zip_chat_scope_changed" }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "created"); put("name", archive.name); put("entries", archive.entryCount)
                put("bytes", archive.bytes.size); put("saved_to_public_directory", false)
            }.toString()), committed.document)
        } catch (error: Throwable) {
            saved?.let { item -> withContext(NonCancellable + Dispatchers.IO) { discard(item) } }
            if (error is CancellationException) throw error
            if (error !is Exception) throw error
            listOf(UIMessagePart.Text("ZIP 未生成或聊天归属已变化。请检查包内相对路径、重复名称、UTF-8 文本和大小限制；未改动旧附件，未自动重试。"))
        }
    },
)
