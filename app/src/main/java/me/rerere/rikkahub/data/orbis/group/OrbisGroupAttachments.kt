package me.rerere.rikkahub.data.orbis.group

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.OrbisStickerImage
import me.rerere.rikkahub.data.orbis.OrbisStickers
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import kotlin.uuid.Uuid

/** Owned immutable group copies: no raw content/file URL is serialized in the group database. */
internal class OrbisGroupAttachments(
    context: Context,
    private val stickerSource: suspend (String) -> OrbisStickerImage = { id ->
        OrbisStickers.open(context.applicationContext).imageForSend(id)
    },
) {
    private val app = context.applicationContext
    // Android may expose /data/user/0 via /data/data. Trust the OS-provided app directory,
    // canonicalize THAT anchor, then reject redirects inside our owned attachment namespace.
    private val root = File(app.filesDir.canonicalFile, "orbis-group-attachments")

    fun file(value: OrbisGroupAttachment): File {
        groupCheck(validGroupAttachment(value), "invalid_attachment")
        val target = File(root, value.id)
        groupCheck(root.canonicalFile == root.absoluteFile && target.canonicalFile == target.absoluteFile &&
            target.isFile && target.length() == value.bytes, "invalid_attachment")
        return target
    }

    suspend fun import(uri: Uri, imageRequested: Boolean = false): OrbisGroupAttachment =
        withContext(Dispatchers.IO) {
            groupCheck(uri.scheme == "content", "invalid_attachment")
            // Display metadata is optional in SAF. A readable granted stream must not fail just
            // because a provider omits the display-name column, throws on query, or returns no MIME.
            val displayName = runCatching { app.contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            } }.getOrNull() ?: "附件"
            val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
            copyOwned(displayName, mime, imageRequested) { app.contentResolver.openInputStream(uri) }
        }

    /** StickerPanel returns a stable library ID, NOT a URI. The library validates membership/hash. */
    suspend fun importSticker(id: String): OrbisGroupAttachment = withContext(Dispatchers.IO) {
        groupCheck(id.matches(Regex("st[0-9]{6}")) && id != "st000000", "invalid_attachment")
        val source = stickerSource(id)
        groupCheck(source.sticker.id == id && source.bytes.size == source.sticker.byteSize, "invalid_attachment")
        copyOwned("表情包-$id.${if (source.sticker.format == "jpeg") "jpg" else source.sticker.format}",
            "image/${source.sticker.format}", true) { ByteArrayInputStream(source.bytes) }
    }

    private fun copyOwned(displayName: String, mime: String?, imageRequested: Boolean,
        open: () -> InputStream?): OrbisGroupAttachment {
            val name = displayName.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
                .joinToString("").take(160).ifBlank { "附件" }
            val claimedMime = mime?.substringBefore(';')?.trim()?.lowercase()
                ?.takeIf { it.length in 1..128 && it.none(Char::isISOControl) } ?: "application/octet-stream"
            groupCheck(root.canonicalFile == root.absoluteFile, "invalid_attachment")
            groupCheck(root.mkdirs() || root.isDirectory, "invalid_attachment")
            groupCheck(root.canonicalFile == root.absoluteFile, "invalid_attachment")
            val target = File(root, Uuid.random().toString())
            groupCheck(target.canonicalFile == target.absoluteFile && target.createNewFile(), "invalid_attachment")
            try {
                val bytes = open()?.use { input ->
                    FileOutputStream(target).use { output ->
                        copyGroupAttachment(input, output).also { output.fd.sync() }
                    }
                } ?: throw OrbisGroupException("invalid_attachment")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // Inspect actual bytes even when picked through Files or the provider says octet-stream.
                BitmapFactory.decodeFile(target.path, options)
                val image = options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
                    options.outWidth.toLong() * options.outHeight <= 32_000_000 &&
                    options.outMimeType in GROUP_IMAGE_MIMES
                if (imageRequested || claimedMime.startsWith("image/")) groupCheck(image, "invalid_attachment")
                if (image) {
                    // Bounds alone can accept a truncated header. Validate a tiny first frame now,
                    // before storage/send, rather than letting the shared encoder omit a bad image.
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 256) sample *= 2
                    val bitmap = BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: throw OrbisGroupException("invalid_attachment")
                    bitmap.recycle()
                }
                val textLike = claimedMime.startsWith("text/") || claimedMime in setOf("application/json", "application/xml", "application/yaml") || name.substringAfterLast('.', "").lowercase() in
                    setOf("txt", "md", "csv", "json", "yaml", "yml", "xml", "log", "kt", "py", "js", "html")
                val text = if (!image && textLike && bytes <= GROUP_ATTACHMENT_TEXT_BYTES) {
                    runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(target.readBytes())).toString() }.getOrNull()
                } else if (!image) extractGroupDocument(target, name, claimedMime) else null
                return OrbisGroupAttachment(target.name, name, if (image) options.outMimeType!! else claimedMime,
                    bytes, image, text).also { groupCheck(validGroupAttachment(it), "invalid_attachment") }
            } catch (error: Throwable) {
                target.delete() // Only our newly-created, uncommitted copy, never the selected original.
                throw error
            }
    }

    fun validate(values: List<OrbisGroupAttachment>) {
        validateGroupAttachments(values)
        values.forEach(::file)
    }
}

// Same local image formats supported by the shared private-chat encoder; HEIF/AVIF are decoded
// and normalized there before upload. Never send the device-only original format to the provider.
internal val GROUP_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp",
    "image/heic", "image/heif", "image/avif")

internal fun validGroupAttachment(value: OrbisGroupAttachment): Boolean =
    value.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) &&
        value.name.length in 1..160 && value.name.none { it.isISOControl() || it == '/' || it == '\\' } &&
        value.mime.length in 1..128 && value.mime.none { it.isISOControl() } &&
        value.bytes in 1..GROUP_ATTACHMENT_BYTES.toLong() &&
        (value.extractedText?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= GROUP_ATTACHMENT_TEXT_BYTES &&
        (!value.image || (value.extractedText == null && value.mime in GROUP_IMAGE_MIMES))

internal fun validateGroupAttachments(values: List<OrbisGroupAttachment>) {
    groupCheck(values.size <= GROUP_ATTACHMENT_LIMIT && values.all(::validGroupAttachment) &&
        values.distinctBy { it.id }.size == values.size && values.sumOf { it.bytes } <= GROUP_ATTACHMENT_TOTAL_BYTES &&
        values.sumOf { it.extractedText?.toByteArray(Charsets.UTF_8)?.size ?: 0 } <= GROUP_ATTACHMENT_TEXT_BYTES,
        "invalid_attachment")
}

internal fun copyGroupAttachment(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(8192)
    var size = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) continue
        groupCheck(size + n <= GROUP_ATTACHMENT_BYTES, "invalid_attachment")
        output.write(buffer, 0, n); size += n
    }
    groupCheck(size > 0, "invalid_attachment")
    return size
}
