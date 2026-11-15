@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package me.rerere.rikkahub.ui.components.message

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.blur.*
import dev.chrisbanes.haze.glass.*
import me.rerere.rikkahub.data.orbis.theme.ThemeBubble
import me.rerere.rikkahub.data.orbis.theme.themeArgb
import kotlin.math.PI
import kotlin.math.sin

/** Only the wallpaper is registered here. Never reuse the chat-list/composer HazeState. */
internal val LocalBubbleBackdrop = staticCompositionLocalOf<HazeState?> { null }

@Composable internal fun BoxScope.ThemeBubbleMaterialBackground(bubble: ThemeBubble, shape: Shape) {
    val material = bubble.material ?: return
    val source = LocalBubbleBackdrop.current
    val color = Color(themeArgb(bubble.bg))
    val clock = LocalThemeMotionClock.current
    val details = LocalLiquidGlassDetails.current
    val detailPresentation = Modifier.drawWithContent {
        if (details?.outlineOnly == true) {
            // A deliberate transparent reading surface. Skip both optical passes, even
            // on the first expansion frame; text lives outside this background layer.
            drawOutline(shape.createOutline(size, layoutDirection, this),
                Brush.linearGradient(listOf(Color.White.copy(alpha = .65f * bubble.opacity),
                    Color(themeArgb(bubble.text)).copy(alpha = .16f * bubble.opacity), Color.White.copy(alpha = .45f * bubble.opacity))),
                style = Stroke(1.dp.toPx()))
        } else drawContent()
    }
    // Pre-31 / non-chat thumbnails keep a readable solid surface. The foreground is never captured.
    if (source == null || Build.VERSION.SDK_INT < 31) {
        Box(Modifier.matchParentSize().then(detailPresentation).clip(shape).background(color.copy(alpha = bubble.opacity)))
        return
    }
    val input = remember(source) {
        // This source contains wallpaper only. Relayout/scroll may temporarily
        // leave a bubble without a drawable source; keep its last optical frame
        // instead of clearing the glass until the next capture is available.
        HazeInput.Sources(source, retention = HazeSourceRetention.KeepLastFrame)
    }
    val effect = if (material.type == "liquid" && Build.VERSION.SDK_INT >= 33) {
        val style = remember(material, bubble.radius, bubble.opacity, color) {
            GlassStyle {
                shape(RoundedCornerShape(bubble.radius.dp))
                optics(refractionStrength = material.refraction,
                    refractionDisplacement = material.displacement.dp,
                    refractionHeightFraction = material.refractionHeight,
                    refractionFoldStrength = material.fold,
                    depth = material.depth,
                    blurRadius = material.blur.dp)
                backgroundColor(color)
                tint(color.copy(alpha = material.tintOpacity))
                specularIntensity(material.highlight)
                ambientResponse(material.rim)
                // The colored edge pass uses the author's palette, avoiding unwanted green/black RGB fringes.
                chromaticAberrationStrength(0f)
                lightPosition(BiasAlignment(material.lightX, material.lightY))
                contentNormalBlend(0f)
                alpha(bubble.opacity)
            }
        }
        Modifier.hazeGlass(input, style)
    } else {
        val style = remember(material, color, bubble.opacity) {
            HazeBlurStyle {
                blurRadius(material.blur.dp)
                noiseFactor(.025f)
                backgroundColor(color)
                colorEffects(listOf(HazeColorEffect.tint(color.copy(alpha = material.tintOpacity))))
                fallbackColorEffect(HazeColorEffect.tint(color))
                alpha(bubble.opacity)
            }
        }
        Modifier.hazeBlur(input, style)
    }
    Box(Modifier.matchParentSize().then(detailPresentation).clip(shape).then(liquidFlowModifier(material, bubble.radius)).then(effect))
    if (Build.VERSION.SDK_INT >= 33 && bubble.texture?.type == "ice" && bubble.texture.opacity > 0f) {
        Box(Modifier.matchParentSize().drawWithContent {
            if (details?.outlineOnly != true) drawContent()
        }) { ThemeIceCrystalLayer(bubble, shape, input) }
    }
    // Optional draw-only glint shares the existing foreground/lifecycle-aware theme clock.
    Box(Modifier.matchParentSize().clip(shape).drawBehind {
        if (details?.outlineOnly == true) return@drawBehind
        val phase = if (material.shimmer) {
            sin((clock?.value ?: 0L) % material.shimmerDurationMs / material.shimmerDurationMs.toDouble() * 2 * PI).toFloat()
        } else 0f
        val x = (material.lightX + 1f) / 2f * size.width + phase * size.width * .25f
        val y = (material.lightY + 1f) / 2f * size.height
        val brush = Brush.radialGradient(listOf(
            Color.White.copy(alpha = material.highlight * .65f * bubble.opacity),
            Color.White.copy(alpha = material.rim * .08f * bubble.opacity),
            Color.Transparent), center = Offset(x, y), radius = maxOf(size.minDimension, size.width * .65f, 1f))
        drawOutline(shape.createOutline(size, layoutDirection, this), brush, style = Stroke(1.5.dp.toPx()))
    })
}
