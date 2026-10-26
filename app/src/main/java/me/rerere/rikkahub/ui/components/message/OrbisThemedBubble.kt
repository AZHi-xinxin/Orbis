package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.orbis.theme.ThemeBubble
import me.rerere.rikkahub.data.orbis.theme.themeArgb
import me.rerere.rikkahub.ui.pages.orbis.LocalChatTheme
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor

@Composable internal fun OrbisThemedBubble(user: Boolean, custom: ThemeBubble, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalChatTheme.current
    val top = when(custom.shape) { "cat" -> 12.dp; "rabbit" -> 20.dp; else -> 0.dp }
    // Reserve the tail inside this message's measured bounds, away from text and
    // adjacent messages. It is part of the same fill, never a second alpha layer.
    val tailHeight = if (custom.tail == "none") 0.dp else 18.dp
    val density = LocalDensity.current
    val rise = with(density) { top.toPx() }
    val tailDrop = with(density) { tailHeight.toPx() }
    val radius = with(density) { custom.radius.dp.toPx() }
    val shape = if(custom.shape == "round" && custom.tail == "none") RoundedCornerShape(custom.radius.dp) else GenericShape { size, direction ->
        val bottom = size.height - tailDrop
        val r = radius.coerceAtMost(size.width / 4).coerceAtMost((bottom - rise) / 2)
        var body = Path().apply {
            if (custom.shape == "ticket") {
                val notch = minOf(8.dp.value * density.density, size.width / 12)
                moveTo(r, 0f); lineTo(size.width-r, 0f); quadraticTo(size.width,0f,size.width,r)
                lineTo(size.width,bottom/2-notch); quadraticTo(size.width-notch*2,bottom/2,size.width,bottom/2+notch)
                lineTo(size.width,bottom-r); quadraticTo(size.width,bottom,size.width-r,bottom)
                lineTo(r,bottom); quadraticTo(0f,bottom,0f,bottom-r)
                lineTo(0f,bottom/2+notch); quadraticTo(notch*2,bottom/2,0f,bottom/2-notch)
                lineTo(0f,r); quadraticTo(0f,0f,r,0f); close()
            } else addRoundRect(RoundRect(0f, rise, size.width, bottom, CornerRadius(r)))
        }
        // Union silhouettes rather than overlapping opposite-winding subpaths:
        // otherwise a transparent slit appears where ears meet the bubble.
        fun join(part: Path) { body = Path.combine(PathOperation.Union, body, part) }
        when(custom.shape) {
            "cat" -> listOf(.15f, .85f).forEach { x ->
                join(Path().apply {
                    moveTo(size.width*x - rise*.7f, rise + 4f); lineTo(size.width*x, 0f)
                    lineTo(size.width*x + rise*.7f, rise + 4f); close()
                })
            }
            "rabbit" -> listOf(.2f, .8f).forEach { x ->
                join(Path().apply {
                    addOval(Rect(size.width*x - rise*.24f, 0f, size.width*x + rise*.24f, rise*1.6f))
                })
            }
            "speech" -> join(Path().apply { val x = if(user) size.width - 18f else 18f
                moveTo(x, bottom - 18f); lineTo(if(user) size.width else 0f, bottom)
                lineTo(x + if(user) -8f else 8f, bottom); close() })
        }
        if (custom.tail == "none") addPath(body) else {
            val unit = minOf(density.density, size.width / 72f)
            val root = maxOf(r + 3 * unit, 24 * unit).coerceAtMost(size.width / 2)
            val onRight = user == (direction == LayoutDirection.Ltr)
            fun x(distance: Float) = if (onRight) size.width - distance else distance
            val tail = Path().apply {
                moveTo(x(root + 9 * unit), bottom - 4 * unit)
                if (custom.tail == "cat") {
                    cubicTo(x(root + 8 * unit), bottom + 18 * unit, x(5 * unit), bottom + 18 * unit, x(4 * unit), bottom + 4 * unit)
                    cubicTo(x(4 * unit), bottom - 2 * unit, x(9 * unit), bottom - 2 * unit, x(10 * unit), bottom + 4 * unit)
                    cubicTo(x(11 * unit), bottom + 12 * unit, x(root - 2 * unit), bottom + 10 * unit, x(root), bottom - 4 * unit)
                } else {
                    cubicTo(x(root + 14 * unit), bottom + 17 * unit, x(2 * unit), bottom + 24 * unit, x(3 * unit), bottom + 7 * unit)
                    cubicTo(x(4 * unit), bottom - 3 * unit, x(22 * unit), bottom - 3 * unit, x(22 * unit), bottom + 7 * unit)
                    cubicTo(x(22 * unit), bottom + 14 * unit, x(12 * unit), bottom + 15 * unit, x(11 * unit), bottom + 9 * unit)
                    cubicTo(x(10 * unit), bottom + 5 * unit, x(15 * unit), bottom + 3 * unit, x(16 * unit), bottom + 7 * unit)
                    cubicTo(x(17 * unit), bottom + 3 * unit, x(10 * unit), bottom + 2 * unit, x(9 * unit), bottom + 7 * unit)
                    cubicTo(x(8 * unit), bottom + 15 * unit, x(root + 3 * unit), bottom + 12 * unit, x(root), bottom - 4 * unit)
                }
                close()
            }
            addPath(Path.combine(PathOperation.Union, body, tail))
        }
    }
    val foreground = Color(themeArgb(custom.text))
    BoxWithConstraints(modifier, contentAlignment = if(user) Alignment.TopEnd else Alignment.TopStart) {
        Box(Modifier.widthIn(max = maxWidth * custom.maxWidthRatio)) {
            Box(Modifier.clip(shape).background(Color(themeArgb(custom.bg)).copy(alpha = custom.opacity)).drawBehind {
                val tint = foreground.copy(alpha = .075f * custom.opacity)
                val step = 22.dp.toPx()
                if(custom.pattern != "none") {
                    var y = rise + step / 2
                    while(y < size.height - tailDrop) {
                        var x = step / 2
                        while(x < size.width) {
                            when(custom.pattern) {
                                "dots" -> drawCircle(tint, 1.2.dp.toPx(), Offset(x,y))
                                "stars" -> { val r = 3.dp.toPx(); drawPath(Path().apply {
                                    moveTo(x,y-r);lineTo(x+r*.3f,y-r*.3f);lineTo(x+r,y);lineTo(x+r*.3f,y+r*.3f)
                                    lineTo(x,y+r);lineTo(x-r*.3f,y+r*.3f);lineTo(x-r,y);lineTo(x-r*.3f,y-r*.3f);close()
                                }, tint) }
                                "gingham" -> drawRect(tint, Offset(x-step/4,y-step/4), androidx.compose.ui.geometry.Size(step/2,step/2))
                            }
                            x += step
                        }
                        y += step
                    }
                }
            }) {
                CompositionLocalProvider(LocalContentColor provides foreground, LocalOrbisChatTextColor provides foreground) {
                    Column(Modifier.padding(start = custom.padding.dp, end = custom.padding.dp,
                        top = custom.padding.dp + top, bottom = custom.padding.dp + tailHeight + if(custom.decoration != "none" || custom.decorationAsset != null) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
                }
            }
            val asset = custom.decorationAsset?.let { theme?.assets?.get(it) }
            if(asset != null) AsyncImage(model = asset, contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = tailHeight).size(28.dp), onError = { theme?.onImageError?.invoke() })
            else if(custom.decoration != "none") Text(when(custom.decoration) { "paw" -> "🐾"; "heart" -> "♥"; else -> "✦" },
                color = foreground.copy(alpha = .65f), fontSize = 17.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 3.dp + tailHeight))
        }
    }
}
