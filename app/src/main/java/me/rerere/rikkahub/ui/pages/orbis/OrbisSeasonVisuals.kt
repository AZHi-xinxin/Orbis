package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBackgroundStyle
import kotlin.math.sin

/**
 * Presentation only: the persisted theme ID selects a season, never a date or user record.
 * Design research: https://github.com/catppuccin/catppuccin (MIT) for soft surface hierarchy.
 * Palettes and vector scenery below are original; no third-party code/images are embedded.
 */
enum class OrbisSeason(val themeId: String, val title: String) {
    NONE("orbis", "星夜与奶油"), SPRING("orbis-spring", "春 · 樱信"),
    SUMMER("orbis-summer", "夏 · 萤夏"), AUTUMN("orbis-autumn", "秋 · 枫笺"),
    WINTER("orbis-winter", "冬 · 雪灯")
}

val LocalOrbisSeason = staticCompositionLocalOf { OrbisSeason.NONE }

fun orbisSeasonColors(season: OrbisSeason, dark: Boolean): OrbisColors {
    val base = if (dark) OrbisPalette.Dark else OrbisPalette.Light
    if (season == OrbisSeason.NONE) return base
    val accent = when (season) {
        OrbisSeason.SPRING -> if (dark) Color(0xFFE7ADC4) else Color(0xFF8F4766)
        OrbisSeason.SUMMER -> if (dark) Color(0xFFA2DCCB) else Color(0xFF256C60)
        OrbisSeason.AUTUMN -> if (dark) Color(0xFFF0C08D) else Color(0xFF8B512B)
        else -> if (dark) Color(0xFFB4CEEA) else Color(0xFF476984)
    }
    val page = when (season) {
        OrbisSeason.SPRING -> if (dark) Color(0xFF251D2B) else Color(0xFFFFF5F5)
        OrbisSeason.SUMMER -> if (dark) Color(0xFF142828) else Color(0xFFF0F8F3)
        OrbisSeason.AUTUMN -> if (dark) Color(0xFF2B221E) else Color(0xFFFFF6E9)
        else -> if (dark) Color(0xFF192536) else Color(0xFFF0F6FC)
    }
    val tint = when (season) {
        OrbisSeason.SPRING -> if (dark) Color(0xFF3D2A3B) else Color(0xFFF5DFE8)
        OrbisSeason.SUMMER -> if (dark) Color(0xFF253D38) else Color(0xFFDDEEE7)
        OrbisSeason.AUTUMN -> if (dark) Color(0xFF433226) else Color(0xFFF3E3CD)
        else -> if (dark) Color(0xFF2B3B52) else Color(0xFFDFEAF5)
    }
    return base.copy(page = page, pageTop = tint, panel = page, tintedPanel = tint,
        raisedPanel = if (dark) tint else Color.White, accent = accent,
        onAccent = if (dark) Color(0xFF20242B) else Color.White,
        border = accent.copy(alpha = .25f), indigo = accent, homeButton = tint,
        dock = if (dark) tint else page, onDock = base.ink,
        star = if (dark) Color(0xFFFFE3AE) else accent)
}

internal fun usesSeasonWallpaper(season: OrbisSeason, appearance: OrbisAppearance): Boolean =
    season != OrbisSeason.NONE && (!appearance.backgroundEnabled ||
        (appearance.backgroundStyle == OrbisBackgroundStyle.PAPER && appearance.backgroundImage.isNullOrBlank()))

/** Original vector scenery. No downloaded imagery, network fetch or extra image decode. */
@Composable
internal fun OrbisSeasonWallpaper(season: OrbisSeason, dark: Boolean, modifier: Modifier = Modifier) {
    val c = orbisSeasonColors(season, dark)
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(c.pageTop, c.page, c.page)))
        val w = size.width; val h = size.height
        drawCircle(c.accent.copy(alpha = if (dark) .10f else .07f), w * .43f, Offset(w * .95f, h * .17f))
        drawCircle(c.star.copy(alpha = .13f), w * .13f, Offset(w * .8f, h * .18f))
        // The quiet centre is intentionally left clear for chat readability.
        val ridge = Path().apply {
            moveTo(0f, h); lineTo(0f, h * .88f)
            cubicTo(w * .25f, h * .79f, w * .38f, h * .98f, w * .65f, h * .88f)
            cubicTo(w * .8f, h * .82f, w * .9f, h * .87f, w, h * .83f)
            lineTo(w, h); close()
        }
        drawPath(ridge, c.accent.copy(alpha = .07f))
        repeat(16) { i ->
            val x = if (i % 2 == 0) w * ((i * 7 % 19) / 100f) else w * (1f - (i * 7 % 19) / 100f)
            val y = h * (.12f + i * .049f)
            drawSeasonMotif(season, Offset(x, y), w * (.012f + (i % 3) * .005f), c.accent.copy(alpha = .14f), i * 31f)
        }
    }
}

internal fun DrawScope.drawSeasonMotif(season: OrbisSeason, centre: Offset, radius: Float, color: Color, angle: Float) {
    rotate(angle, centre) {
        when (season) {
            OrbisSeason.SPRING -> {
                repeat(5) { petal -> rotate(petal * 72f, centre) {
                    drawOval(color, Offset(centre.x - radius * .42f, centre.y - radius), Size(radius * .84f, radius * 1.2f))
                } }
                drawCircle(color.copy(alpha = color.alpha * .8f), radius * .18f, centre)
            }
            OrbisSeason.AUTUMN -> {
                drawOval(color, Offset(centre.x - radius * .45f, centre.y - radius), Size(radius * .9f, radius * 2f))
                drawLine(color.copy(alpha = color.alpha * .7f), centre.copy(y = centre.y - radius), centre.copy(y = centre.y + radius * 1.3f), radius * .09f)
            }
            OrbisSeason.WINTER -> repeat(3) { spoke -> rotate(spoke * 60f, centre) {
                drawLine(color, centre.copy(y = centre.y - radius), centre.copy(y = centre.y + radius), radius * .14f)
            } }
            else -> {
                drawCircle(color.copy(alpha = color.alpha * .18f), radius * 2.7f, centre)
                drawCircle(color, radius * .42f, centre)
            }
        }
    }
}

internal fun DrawScope.drawSeasonFloat(season: OrbisSeason, phase: Float, color: Color) {
    repeat(18) { i ->
        val initialY = ((i * 53 + 17) % 103) / 103f
        val y = ((initialY - phase + 1f) % 1f) * size.height
        val x = (((i * 37 + 11) % 101) / 101f) * size.width + sin(phase * 6.28f + i) * 9f
        drawSeasonMotif(season, Offset(x, y), (2f + i % 3) * density,
            color.copy(alpha = if (season == OrbisSeason.SUMMER) .55f else .25f), i * 23f + phase * 120f)
    }
}
