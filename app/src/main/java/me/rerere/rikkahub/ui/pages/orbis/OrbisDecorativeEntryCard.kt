package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class OrbisEntryArtwork { ARCADE, SEALED_LETTER, PRIVATE_ROOM }

/** The caller owns routing and permission checks; this card never fetches or reveals content. */
@Composable
fun OrbisDecorativeEntryCard(
    kind: OrbisEntryArtwork,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    if (compact) {
        OrbisToolEntryCard(title, subtitle, onClick, modifier, enabled) {
            OrbisEntryArt(kind, Modifier.fillMaxSize())
        }
        return
    }
    val c = OrbisTheme.colors
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth()
        .semantics(mergeDescendants = true) {}, shape = RoundedCornerShape(24.dp),
        color = c.panel, contentColor = c.ink, border = BorderStroke(1.dp, c.border)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OrbisEntryArt(kind, Modifier.fillMaxWidth().height(88.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.mutedInk)
        }
    }
}

/** One grid frame for both illustrations and line icons, including equal text baselines. */
@Composable
internal fun OrbisToolEntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    artwork: @Composable BoxScope.() -> Unit,
) {
    val colors = OrbisTheme.colors
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(20.dp), color = colors.raisedPanel,
        contentColor = colors.ink, border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center, content = artwork)
            Text(title, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink, textAlign = TextAlign.Center,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, modifier = Modifier.fillMaxWidth(), fontSize = 10.sp, lineHeight = 15.sp,
                color = colors.mutedInk, textAlign = TextAlign.Center,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Original Canvas illustrations: star arcade, wax-sealed envelope and a private observatory.
 * Linework research: https://github.com/lucide-icons/lucide (ISC); no glyph paths copied.
 */
@Composable
fun OrbisEntryArt(kind: OrbisEntryArtwork, modifier: Modifier = Modifier) {
    val c = OrbisTheme.colors
    Canvas(modifier) {
        val factor = minOf(size.width / 220f, size.height / 88f)
        if (factor <= 0f) return@Canvas
        scale(factor, factor, pivot = Offset.Zero) {
            val cx = size.width / factor / 2f
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = .13f), c.accent.copy(alpha = 0f)),
                Offset(cx, 44f), 86f), 86f, Offset(cx, 44f))
            when (kind) {
                OrbisEntryArtwork.ARCADE -> {
                    drawRoundRect(c.accent, Offset(cx - 48f, 14f), Size(96f, 62f), CornerRadius(17f))
                    drawRoundRect(c.page, Offset(cx - 28f, 21f), Size(56f, 31f), CornerRadius(7f))
                    drawLine(c.onAccent, Offset(cx - 30f, 63f), Offset(cx - 16f, 63f), 4f)
                    drawLine(c.onAccent, Offset(cx - 23f, 56f), Offset(cx - 23f, 70f), 4f)
                    drawCircle(c.star, 4f, Offset(cx + 22f, 61f)); drawCircle(c.star, 3f, Offset(cx + 33f, 66f))
                }
                OrbisEntryArtwork.SEALED_LETTER -> {
                    drawRoundRect(c.sand, Offset(cx - 49f, 20f), Size(98f, 56f), CornerRadius(7f))
                    val fold = Path().apply { moveTo(cx - 48f, 21f); lineTo(cx, 55f); lineTo(cx + 48f, 21f) }
                    drawPath(fold, c.onSand.copy(alpha = .5f), style = Stroke(1.2f))
                    drawLine(c.onSand.copy(alpha = .2f), Offset(cx - 47f, 74f), Offset(cx - 13f, 45f), 1f)
                    drawLine(c.onSand.copy(alpha = .2f), Offset(cx + 47f, 74f), Offset(cx + 13f, 45f), 1f)
                    drawCircle(c.accent, 11f, Offset(cx, 51f))
                }
                OrbisEntryArtwork.PRIVATE_ROOM -> {
                    drawRoundRect(c.indigo, Offset(cx - 31f, 17f), Size(62f, 62f), CornerRadius(28f, 28f))
                    drawRoundRect(c.tintedPanel, Offset(cx - 21f, 28f), Size(42f, 51f), CornerRadius(19f, 19f))
                    drawLine(c.indigo.copy(alpha = .4f), Offset(cx, 35f), Offset(cx, 79f), 1f)
                    drawCircle(c.accent, 2.5f, Offset(cx + 12f, 59f))
                    drawLine(c.accent.copy(alpha = .5f), Offset(cx - 39f, 80f), Offset(cx + 39f, 80f), 2f)
                }
            }
            val starY = when (kind) { OrbisEntryArtwork.ARCADE -> 36f; OrbisEntryArtwork.SEALED_LETTER -> 51f; else -> 46f }
            fun star(x: Float, y: Float, r: Float) {
                val p = Path()
                repeat(10) { i ->
                    val angle = -Math.PI / 2 + i * Math.PI / 5
                    val radius = if (i % 2 == 0) r else r * .44f
                    val px = x + cos(angle).toFloat() * radius; val py = y + sin(angle).toFloat() * radius
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close(); drawPath(p, c.star)
            }
            star(cx, starY, 7f)
            star(cx - 68f, 30f, 4f); star(cx + 66f, 20f, 5f)
            drawCircle(c.accent.copy(alpha = .28f), 2f, Offset(cx + 65f, 64f))
            drawCircle(c.accent.copy(alpha = .3f), 1.5f, Offset(cx - 66f, 63f))
        }
    }
}
