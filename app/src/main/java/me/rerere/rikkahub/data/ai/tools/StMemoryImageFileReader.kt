package me.rerere.rikkahub.data.ai.tools

import android.graphics.BitmapFactory
import me.rerere.rikkahub.data.files.FilesManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption

/** Uses the selected attachment's original bytes, never the provider's JPEG image encoder. */
internal suspend fun readStMemoryImageOriginal(url: String, uploadDirectory: File, files: FilesManager): StImageOriginal {
    val file = resolveStImageUploadFile(url, uploadDirectory)
    val relative = "upload/" + file.relativeTo(uploadDirectory.canonicalFile).invariantSeparatorsPath
    require(files.getByRelativePath(relative) != null) { "st_image_unmanaged_attachment" }
    val original = readBoundedStImageOriginal(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original.bytes, 0, original.bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000L &&
        bounds.outMimeType == original.mimeType) { "st_image_invalid_dimensions_or_format" }
    // ST additionally performs full decoding and rejects animation before acknowledging staging.
    return original
}

internal fun resolveStImageUploadFile(url: String, uploadDirectory: File): File {
    val uri = runCatching { URI(url) }.getOrElse { error("st_image_invalid_attachment") }
    require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
        "st_image_invalid_attachment"
    }
    val root = uploadDirectory.canonicalFile
    val original = File(uri).absoluteFile
    val file = original.canonicalFile
    require(file.isFile && file.path.startsWith(root.path + File.separator) &&
        !Files.isSymbolicLink(original.toPath())) { "st_image_attachment_outside_uploads" }
    return file
}

internal fun readBoundedStImageOriginal(file: File): StImageOriginal {
    require(file.length() in 1L..ST_IMAGE_MAX_BYTES.toLong()) { "st_image_size_limit" }
    val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
        val output = ByteArrayOutputStream()
        val block = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(block, 0, minOf(block.size, ST_IMAGE_MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(block, 0, count)
            require(output.size() <= ST_IMAGE_MAX_BYTES) { "st_image_size_limit" }
        }
        output.toByteArray()
    }
    require(bytes.isNotEmpty()) { "st_image_size_limit" }
    fun prefix(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all {
        (bytes[it].toInt() and 255) == expected[it]
    }
    val mime = when {
        prefix(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        prefix(255, 216, 255) -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> "image/webp"
        else -> error("st_image_format_unsupported")
    }
    return StImageOriginal(bytes, mime)
}
