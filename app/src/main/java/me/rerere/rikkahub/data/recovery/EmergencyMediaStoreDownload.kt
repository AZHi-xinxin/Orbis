package me.rerere.rikkahub.data.recovery

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.io.OutputStream

/** Android-owned local Downloads collection; does not use or trust the failed picker provider. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class EmergencyMediaStoreDownload(private val resolver: ContentResolver, private val name: String) : EmergencyDownloadDestination {
    private var created: Uri? = null
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relative = "${Environment.DIRECTORY_DOWNLOADS}/Orbis Backups/"

    override fun openNew(): OutputStream {
        check(created == null && name.matches(Regex("Orbis-emergency-[A-Za-z0-9_.-]+\\.zip"))) { "emergency_download_name" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "emergency_download_destination_unavailable" }
        check(uri.scheme == collection.scheme && uri.authority == collection.authority &&
            uri.pathSegments.dropLast(1) == collection.pathSegments && uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } == true) {
            "emergency_download_destination_invalid"
        }
        created = uri
        return checkNotNull(resolver.openOutputStream(uri, "wt")) { "emergency_download_output_unavailable" }
    }

    override fun readBack(): InputStream = checkNotNull(resolver.openInputStream(checkNotNull(created))) {
        "emergency_download_readback_unavailable"
    }

    override fun publish(receipt: EmergencyArchiveExport.Receipt): String {
        val uri = checkNotNull(created)
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
            "emergency_download_publish_failed"
        }
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE), null, null, null).use { rows ->
            check(rows != null && rows.moveToFirst()) { "emergency_download_publish_failed" }
            val displayName = rows.getString(0)
            check(displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName &&
                rows.getString(1) == relative && rows.getInt(2) == 0 && rows.getLong(3) == receipt.sizeBytes && !rows.moveToNext()) {
                "emergency_download_publish_failed"
            }
            displayName
        }
    }

    override fun discardIncomplete() {
        created?.let { resolver.delete(it, null, null) }
        created = null
    }
}
