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
import androidx.compose.ui.platform.LocalDensity
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
    val density = LocalDensity.current
    val rise = with(density) { top.toPx() }
    val radius = with(density) { custom.radius.dp.toPx() }
    val shape = if(custom.shape == "round") RoundedCornerShape(custom.radius.dp) else GenericShape { size, _ ->
        val r = radius.coerceAtMost(size.width / 4).coerceAtMost((size.height - rise) / 2)
        if (custom.shape == "ticket") {
            val notch = minOf(8.dp.value * density.density, size.width / 12)
            moveTo(r, 0f); lineTo(size.width-r, 0f); quadraticTo(size.width,0f,size.width,r)
            lineTo(size.width,size.height/2-notch); quadraticTo(size.width-notch*2,size.height/2,size.width,size.height/2+notch)
            lineTo(size.width,size.height-r); quadraticTo(size.width,size.height,size.width-r,size.height)
            lineTo(r,size.height); quadraticTo(0f,size.height,0f,size.height-r)
            lineTo(0f,size.height/2+notch); quadraticTo(notch*2,size.height/2,0f,size.height/2-notch)
            lineTo(0f,r); quadraticTo(0f,0f,r,0f); close()
        } else addRoundRect(RoundRect(0f, rise, size.width, size.height, CornerRadius(r)))
        when(custom.shape) {
            "cat" -> listOf(.15f, .85f).forEach { x ->
                moveTo(size.width*x - rise*.7f, rise + 4f); lineTo(size.width*x, 0f)
                lineTo(size.width*x + rise*.7f, rise + 4f); close()
            }
            "rabbit" -> listOf(.2f, .8f).forEach { x ->
                addOval(Rect(size.width*x - rise*.24f, 0f, size.width*x + rise*.24f, rise*1.6f))
            }
            "speech" -> { val x = if(user) size.width - 18f else 18f
                moveTo(x, size.height - 18f); lineTo(if(user) size.width else 0f, size.height)
                lineTo(x + if(user) -8f else 8f, size.height); close() }
            "ticket" -> { /* Squared ticket silhouette; patterned edge is drawn within its bounds. */ }
        }
    }
    val foreground = Color(themeArgb(custom.text))
    BoxWithConstraints(modifier, contentAlignment = if(user) Alignment.TopEnd else Alignment.TopStart) {
        Box(Modifier.widthIn(max = maxWidth * custom.maxWidthRatio)) {
            Box(Modifier.clip(shape).background(Color(themeArgb(custom.bg))).drawBehind {
                val tint = foreground.copy(alpha = .075f)
                val step = 22.dp.toPx()
                if(custom.pattern != "none") {
                    var y = rise + step / 2
                    while(y < size.height) {
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
                        top = custom.padding.dp + top, bottom = custom.padding.dp + if(custom.decoration != "none" || custom.decorationAsset != null) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
                }
            }
            val asset = custom.decorationAsset?.let { theme?.assets?.get(it) }
            if(asset != null) AsyncImage(model = asset, contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).size(28.dp), onError = { theme?.onImageError?.invoke() })
            else if(custom.decoration != "none") Text(when(custom.decoration) { "paw" -> "🐾"; "heart" -> "♥"; else -> "✦" },
                color = foreground.copy(alpha = .65f), fontSize = 17.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 3.dp))
        }
    }
}
