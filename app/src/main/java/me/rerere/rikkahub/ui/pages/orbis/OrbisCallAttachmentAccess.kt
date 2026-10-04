package me.rerere.rikkahub.ui.pages.orbis

import java.io.File
import java.net.URI

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
