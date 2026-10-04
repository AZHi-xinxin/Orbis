package me.rerere.rikkahub.data.recovery

import java.util.Locale

/**
 * Conservative destination policy for a rescue file that must survive uninstalling Orbis.
 * The Activity supplies DocumentsContract.getDocumentId(uri), already decoded by Android.
 * No third-party/cloud provider is trusted merely because it honors EXTRA_LOCAL_ONLY.
 * This policy does not replace writing, closing and fully reading back the selected file.
 */
internal fun emergencyExportDestinationRejection(
    authority: String?,
    documentId: String?,
    packageName: String,
): String? {
    if (authority == packageName || authority?.startsWith("$packageName.") == true) {
        return "app_private_directory"
    }
    if (authority !in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")) {
        return "not_local_provider"
    }
    if (documentId.isNullOrBlank() || documentId.any { it.code < 32 || it.code == 127 } ||
        '\\' in documentId || Regex("%(?:00|25|2e|2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(documentId)
    ) return "invalid_document_id"

    if (authority == "com.android.externalstorage.documents") {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return "invalid_document_id"
        val volume = documentId.substring(0, separator)
        if (!volume.matches(Regex("[A-Za-z0-9_-]+"))) return "invalid_document_id"
        val relative = documentId.substring(separator + 1)
        return validateDestinationPath(relative, absolute = false)
    }

    // Official Downloads may return a MediaStore file/directory id or its legacy numeric id.
    if (documentId.matches(Regex("(?:msf:|msd:)?[0-9]+"))) return null
    if (!documentId.startsWith("raw:")) return "invalid_document_id"
    val absolute = documentId.removePrefix("raw:")
    validateDestinationPath(absolute, absolute = true)?.let { return it }
    if (!(absolute.startsWith("/storage/") || absolute.startsWith("/sdcard/") ||
                absolute.startsWith("/mnt/media_rw/"))) return "app_private_directory"
    return null
}

private fun validateDestinationPath(path: String, absolute: Boolean): String? {
    if (path.isBlank() || ':' in path || path.startsWith('/') != absolute) return "invalid_document_id"
    val segments = path.removePrefix("/").split('/')
    if (segments.any { it.isEmpty() || it.trim() in setOf(".", "..") }) return "invalid_document_id"
    if (segments.zipWithNext().any { (parent, child) ->
            parent.lowercase(Locale.ROOT) == "android" && child.lowercase(Locale.ROOT) in setOf("data", "obb", "media")
        }) return "app_private_directory"
    return null
}
