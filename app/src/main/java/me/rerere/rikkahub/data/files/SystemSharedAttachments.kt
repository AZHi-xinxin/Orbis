package me.rerere.rikkahub.data.files

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.isAllowedFileType

internal enum class SharedAttachmentKind { ZIP, IMAGE, VIDEO, AUDIO, DOCUMENT, UNSUPPORTED }

internal data class SharedAttachmentMetadata(val name: String, val mime: String)

internal fun sharedAttachmentKind(metadata: SharedAttachmentMetadata): SharedAttachmentKind = when {
    ZipAttachmentArchive.isZip(metadata.name, metadata.mime) -> SharedAttachmentKind.ZIP
    metadata.mime.startsWith("image/") -> SharedAttachmentKind.IMAGE
    metadata.mime.startsWith("video/") -> SharedAttachmentKind.VIDEO
    metadata.mime.startsWith("audio/") -> SharedAttachmentKind.AUDIO
    isAllowedFileType(metadata.name, metadata.mime) -> SharedAttachmentKind.DOCUMENT
    else -> SharedAttachmentKind.UNSUPPORTED
}

/** One item owns its metadata and copy result: missing MIME or one failure cannot shift later items.
 * This only prepares the human's draft. It never sends a message, extracts ZIPs, or invokes a tool. */
internal suspend fun <Source> prepareSystemSharedAttachments(
    sources: List<Source>,
    describe: suspend (Source) -> SharedAttachmentMetadata,
    save: suspend (Source, SharedAttachmentMetadata, SharedAttachmentKind) -> String,
    onRejected: (String) -> Unit,
): List<UIMessagePart> = buildList {
    for ((index, source) in sources.withIndex()) {
        try {
            val metadata = describe(source).let { metadata ->
                metadata.copy(
                    name = metadata.name.ifBlank { "file" },
                    mime = metadata.mime.substringBefore(';').trim().lowercase().ifBlank { "application/octet-stream" },
                )
            }
            val kind = sharedAttachmentKind(metadata)
            if (kind == SharedAttachmentKind.UNSUPPORTED) {
                onRejected("第 ${index + 1} 个分享附件暂不支持此格式，未复制；可改用支持的文档或 ZIP。")
                continue
            }
            val url = save(source, metadata, kind)
            add(when (kind) {
                SharedAttachmentKind.ZIP -> UIMessagePart.Document(url, metadata.name, "application/zip")
                SharedAttachmentKind.IMAGE -> UIMessagePart.Image(url)
                SharedAttachmentKind.VIDEO -> UIMessagePart.Video(url)
                SharedAttachmentKind.AUDIO -> UIMessagePart.Audio(url)
                SharedAttachmentKind.DOCUMENT -> UIMessagePart.Document(url, metadata.name, metadata.mime)
                SharedAttachmentKind.UNSUPPORTED -> error("unsupported_shared_attachment")
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val reason = when ((error as? ZipAttachmentException)?.code) {
                "zip_archive_too_large", "zip_archive_size_or_type" -> "ZIP 最大 32 MiB，且必须是有效 ZIP。"
                "zip_filename_encoding_unsupported" -> "ZIP 文件名需使用 UTF-8 编码，请重新打包。"
                null -> "无法读取文件，请检查来源授权后重新分享。"
                else -> "ZIP 损坏、加密或超出安全限额，不会展开压缩包。"
            }
            // Neither private URI nor arbitrary provider exception text enters a toast/log.
            onRejected("第 ${index + 1} 个分享附件未添加：$reason")
        }
    }
}

internal suspend fun importSystemSharedAttachments(
    sources: List<Uri>,
    filesManager: FilesManager,
    onRejected: (String) -> Unit,
): List<UIMessagePart> = prepareSystemSharedAttachments(
    sources = sources,
    describe = { uri ->
        withContext(Dispatchers.IO) {
            SharedAttachmentMetadata(
                filesManager.getFileNameFromUri(uri) ?: "file",
                filesManager.getFileMimeType(uri) ?: "application/octet-stream",
            )
        }
    },
    save = { uri, metadata, kind ->
        val saved = if (kind == SharedAttachmentKind.ZIP) {
            // Bounded copy + archive validation happens before the managed record is committed.
            filesManager.saveManagedZipFromUri(uri, metadata.name)
        } else {
            filesManager.saveManagedFromUri(FileFolders.UPLOAD, uri, metadata.name, metadata.mime)
        }
        filesManager.getFile(saved).toUri().toString()
    },
    onRejected = onRejected,
)
