package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBubbleStyle
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Presentation only. The caller retains the real parts, links, tools and streaming state. */
@Composable
internal fun OrbisMessageBubble(
    user: Boolean,
    appearance: OrbisAppearance,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val options = appearance.normalized()
    val dark = LocalDarkMode.current
    val colors = OrbisTheme.colors
    val deepSeek = LocalOrbisDeepSeekStyle.current
    val style = options.bubbleStyle
    val alpha = options.bubbleOpacityForRole(user)
    val shape = when (style) {
        OrbisBubbleStyle.SILK -> RoundedCornerShape(
            topStart = 22.dp, topEnd = 22.dp,
            bottomEnd = if (user) 8.dp else 22.dp,
            bottomStart = if (user) 22.dp else 8.dp,
        )
        OrbisBubbleStyle.GLASS -> RoundedCornerShape(24.dp)
        OrbisBubbleStyle.STARS -> RoundedCornerShape(
            topStart = 21.dp, topEnd = 21.dp,
            bottomEnd = 21.dp, bottomStart = 7.dp,
        )
        OrbisBubbleStyle.BOOK -> RoundedCornerShape(
            topStart = if (user) 20.dp else 10.dp,
            bottomStart = if (user) 20.dp else 10.dp,
            topEnd = if (user) 10.dp else 20.dp,
            bottomEnd = if (user) 10.dp else 20.dp,
        )
    }
    val backgroundColors = when {
        deepSeek -> List(2) { if (user) colors.tintedPanel else colors.panel }
        dark && user -> listOf(Color(0xFF554153), Color(0xFF3D435F))
        dark -> listOf(Color(0xFF2D2E43), Color(0xFF252738))
        user && style == OrbisBubbleStyle.GLASS -> listOf(Color(0xFFE0CEE8), Color(0xFFE0CEE8))
        user -> listOf(Color(0xFFF8DEC9), Color(0xFFEFD7DD))
        style == OrbisBubbleStyle.BOOK -> listOf(Color(0xFFFFFCF5), Color(0xFFFAF4E8))
        else -> listOf(Color.White, Color(0xFFFFFDFC))
    }.map { it.copy(alpha = alpha) }
    val edge = if (deepSeek) colors.border else when (style) {
        OrbisBubbleStyle.STARS -> if (dark) Color(0xFF897453) else Color(0xFFE3CFAD)
        OrbisBubbleStyle.GLASS -> if (dark) Color(0xFF666278) else Color.White
        else -> colors.border
    }
    // No fixed HTML dimensions and no horizontal scrolling on the entire message: the
    // existing Markdown/code/WebView renderers retain their own sizing and gestures.
    Box(
        modifier = modifier
            .then(if (alpha > 0f && !deepSeek) Modifier.shadow(2.dp * alpha, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = alpha),
                spotColor = Color.Black.copy(alpha = alpha)) else Modifier)
            .clip(shape)
            .then(if (alpha > 0f) Modifier
                .background(Brush.linearGradient(backgroundColors))
                .border(0.75.dp, edge.copy(alpha = 0.75f * alpha), shape) else Modifier)
            .drawBehind {
                val decorationColor = edge.copy(alpha = edge.alpha * alpha)
                if (alpha > 0f && style == OrbisBubbleStyle.BOOK) {
                    val atStart = !user
                    val onLeft = atStart == (layoutDirection == LayoutDirection.Ltr)
                    val x = if (onLeft) 1.5.dp.toPx() else size.width - 1.5.dp.toPx()
                    drawLine(
                        color = decorationColor, start = Offset(x, 9.dp.toPx()),
                        end = Offset(x, size.height - 9.dp.toPx()), strokeWidth = 3.dp.toPx(),
                    )
                }
                if (alpha > 0f && style == OrbisBubbleStyle.STARS && !user) {
                    val x = size.width - 10.dp.toPx()
                    val y = size.height - 9.dp.toPx()
                    val r = 4.dp.toPx()
                    val star = Path().apply {
                        moveTo(x, y - r); lineTo(x + r * .3f, y - r * .3f)
                        lineTo(x + r, y); lineTo(x + r * .3f, y + r * .3f)
                        lineTo(x, y + r); lineTo(x - r * .3f, y + r * .3f)
                        lineTo(x - r, y); lineTo(x - r * .3f, y - r * .3f); close()
                    }
                    drawPath(star, decorationColor)
                }
            },
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.ink,
            LocalOrbisChatTextColor provides options.chatTextColor?.let { Color(it) },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}
