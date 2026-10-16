package me.rerere.rikkahub.ui.widget

import android.graphics.Bitmap
import me.rerere.rikkahub.data.orbis.character.CharacterFaceAnchor
import kotlin.math.sqrt

/** Display-only face layer; torso/hands and source PNGs remain unchanged. Bounded widget thumbnail. */
internal fun characterFaceComposite(base: Bitmap, face: Bitmap, target: CharacterFaceAnchor, source: CharacterFaceAnchor): Bitmap {
    require(target.group == source.group && base.width <= 1024 && base.height <= 1024)
    val result = base.copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(base.width * base.height)
    base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
    val left = (target.x * base.width).toInt().coerceAtLeast(0)
    val top = (target.y * base.height).toInt().coerceAtLeast(0)
    val right = ((target.x + target.width) * base.width).toInt().coerceAtMost(base.width)
    val bottom = ((target.y + target.height) * base.height).toInt().coerceAtMost(base.height)
    for (y in top until bottom) for (x in left until right) {
        val u = ((x + .5f) / base.width - target.x) / target.width
        val v = ((y + .5f) / base.height - target.y) / target.height
        val radius = sqrt((u * 2 - 1) * (u * 2 - 1) + (v * 2 - 1) * (v * 2 - 1))
        val weight = ((1f - radius) / .13f).coerceIn(0f, 1f)
        if (weight == 0f) continue
        val sample = face.getPixel(((source.x + u * source.width) * face.width).toInt().coerceIn(0, face.width - 1),
            ((source.y + v * source.height) * face.height).toInt().coerceIn(0, face.height - 1))
        val old = pixels[y * base.width + x]
        // Preserve the base silhouette; transparent imported patches never punch a hole in it.
        val mix = weight * ((sample ushr 24) / 255f)
        var color = old and -0x1000000
        for (shift in listOf(16, 8, 0)) {
            val a = (old ushr shift) and 255; val b = (sample ushr shift) and 255
            color = color or ((a + (b - a) * mix).toInt().coerceIn(0, 255) shl shift)
        }
        pixels[y * base.width + x] = color
    }
    result.setPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
    return result
}
