package me.rerere.rikkahub.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** RemoteViews supports no custom drawing view. Compose a bounded display bitmap,
 * not a modified source asset. The complete card is always a separate layer below it.
 * Caller owns the returned bitmap; if a new one is returned, caller also owns source.
 */
internal fun characterWidgetSprite(source: Bitmap, stageHeightDp: Int, trimBodyAtCardEdge: Boolean): Bitmap {
    require(stageHeightDp in 150..180)
    if (!trimBodyAtCardEdge) return source // Hands in other poses may still hang over the edge.
    val width = 148 * 2
    val height = stageHeightDp * 2
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
    val left = (width - source.width * scale) / 2f
    val top = (height - source.height * scale) / 2f
    Canvas(result).apply {
        // Matches the XML card's 16dp bottom inset, independent of source aspect ratio.
        clipRect(0f, 0f, width.toFloat(), (stageHeightDp - 16) * 2f)
        drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    return result
}
