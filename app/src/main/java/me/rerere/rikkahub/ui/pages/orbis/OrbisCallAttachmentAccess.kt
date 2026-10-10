package me.rerere.rikkahub.ui.pages.orbis

import java.io.File
import java.net.URI
import java.io.OutputStream

/** Imported/model-provided Document URLs must not expose arbitrary private app files. */
internal fun resolveCallUploadFile(url: String, uploadDirectory: File): File {
    val uri = URI(url)
    require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null)
    val root = uploadDirectory.canonicalFile
    val candidate = File(uri).canonicalFile
    require(candidate.isFile && candidate.path.startsWith(root.path + File.separator)) {
        "call_attachment_outside_uploads"
    }
    return candidate
}

/** Exact bytes only, after a human chooses a destination. No network/content URI delegation. */
internal fun exportCallDocument(url: String, uploadDirectory: File, output: OutputStream) {
    val file = resolveCallUploadFile(url, uploadDirectory)
    val limit = 32L * 1024 * 1024
    require(file.length() <= limit) { "document_export_too_large" }
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= limit - copied) { "document_export_too_large" }
            output.write(buffer, 0, count)
            copied += count
        }
    }
}
