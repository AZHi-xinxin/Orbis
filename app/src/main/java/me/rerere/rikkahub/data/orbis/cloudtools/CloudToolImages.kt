package me.rerere.rikkahub.data.orbis.cloudtools

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes

internal class ManagedCloudOutputStore(private val files: FilesManager) : CloudOutputStore {
    override suspend fun convert(content: List<CloudOutput>): List<UIMessagePart> {
        val created = mutableListOf<Long>()
        try {
            return content.map { block -> when (block) {
                is CloudOutput.Text -> UIMessagePart.Text(block.value)
                is CloudOutput.Image -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 16L * 1024 * 1024) { "invalid_image_dimensions" }
                    val extension = when (block.mimeType) {
                        "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "gif"
                    }
                    val entity = files.saveUploadFromBytes(block.bytes, "cloud_tool_image.$extension", block.mimeType)
                    created += entity.id
                    UIMessagePart.Image(files.getFile(entity).toUri().toString())
                }
            } }
        } catch (error: Throwable) {
            withContext(NonCancellable) { created.forEach { id -> runCatching { files.delete(id) } } }
            throw error
        }
    }
}
