package me.rerere.rikkahub.data.orbis.screenshare

import kotlin.math.roundToInt

/** Pixel geometry only; no permission, model, microphone, or Android window side effects. */
internal data class ScreenShareOverlayArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(1)
    val height get() = (bottom - top).coerceAtLeast(1)
}

internal data class ScreenShareOverlaySize(val width: Int, val height: Int, val replyHeight: Int)
internal data class ScreenShareOverlayPosition(val x: Int, val y: Int)

internal fun screenShareOverlaySize(density: Float, area: ScreenShareOverlayArea, collapsed: Boolean): ScreenShareOverlaySize {
    val scale = density.takeIf { it.isFinite() && it > 0 } ?: 1f
    fun px(dp: Int) = (dp * scale).roundToInt().coerceAtLeast(1)
    if (collapsed) return ScreenShareOverlaySize(minOf(px(62), area.width), minOf(px(32), area.height), 0)
    val width = minOf(px(204), area.width)
    // Header 28 + editor 34 + controls 38 + padding/gaps 16. The body cannot grow with text.
    val height = minOf(px(204), area.height)
    val reply = (height - px(116)).coerceIn(0, px(88))
    return ScreenShareOverlaySize(width, height, reply)
}

internal fun clampScreenShareOverlay(position: ScreenShareOverlayPosition, size: ScreenShareOverlaySize,
    area: ScreenShareOverlayArea): ScreenShareOverlayPosition = ScreenShareOverlayPosition(
    position.x.coerceIn(area.left, maxOf(area.left, area.right - size.width)),
    position.y.coerceIn(area.top, maxOf(area.top, area.bottom - size.height)),
)

internal fun snapScreenShareOverlayToEdge(position: ScreenShareOverlayPosition, size: ScreenShareOverlaySize,
    area: ScreenShareOverlayArea): ScreenShareOverlayPosition {
    val bounded = clampScreenShareOverlay(position, size, area)
    val left = bounded.x + size.width / 2 <= area.left + area.width / 2
    return bounded.copy(x = if (left) area.left else maxOf(area.left, area.right - size.width))
}

/** Cap only this compact overlay's text scaling. Full chat keeps the user's system font setting. */
internal fun screenShareOverlayFontScale(systemScale: Float): Float =
    systemScale.takeIf { it.isFinite() }?.coerceIn(1f, 1.15f) ?: 1f

internal fun screenShareOverlayReply(latestReply: String): String =
    latestReply.ifBlank { "回复会显示在这里" }
