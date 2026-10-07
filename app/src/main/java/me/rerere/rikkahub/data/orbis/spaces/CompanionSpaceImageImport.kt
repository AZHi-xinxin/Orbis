package me.rerere.rikkahub.data.orbis.spaces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Picker-only import: copy into the shelf, bound decode size, and do not retain location/EXIF metadata. */
internal suspend fun importCompanionSpaceImage(context: Context, store: OrbisCompanionSpacesStore, uri: Uri): SpaceMedia = withContext(Dispatchers.IO) {
    require(uri.scheme in setOf("content", "file")) { "space_image_invalid" }
    val source = context.contentResolver.openInputStream(uri)?.use { it.spaceReadBounded(CompanionSpaceLimits.MEDIA_BYTES) }
        ?: error("space_image_invalid")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    require(bounds.outWidth in 1..32000 && bounds.outHeight in 1..32000) { "space_image_invalid" }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("space_image_invalid")
    var upright = bitmap
    try {
        val orientation = runCatching { ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        if (!matrix.isIdentity) upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val output = ByteArrayOutputStream()
        require(upright.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "space_image_invalid" }
        store.addMedia(output.toByteArray(), "image/jpeg")
    } finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
}
