package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.orbis.theme.ThemeSticker
import me.rerere.rikkahub.data.orbis.theme.parseThemePath
import me.rerere.rikkahub.ui.pages.orbis.ChatTheme

@Composable internal fun BoxScope.ThemeBubbleSticker(sticker: ThemeSticker, theme: ChatTheme?, clock: State<Long>?) {
    val alignment = when (sticker.anchor) {
        "topStart" -> Alignment.TopStart
        "topCenter" -> Alignment.TopCenter
        "bottomStart" -> Alignment.BottomStart
        "bottomCenter" -> Alignment.BottomCenter
        "bottomEnd" -> Alignment.BottomEnd
        else -> Alignment.TopEnd
    }
    // Rotation stays inside its reserved square; decoration owns no click/focus/semantics target.
    Box(Modifier.align(alignment).size(sticker.size.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        val modifier = Modifier.fillMaxSize().graphicsLayer {
            rotationZ = sticker.rotation
            scaleX = .75f; scaleY = .75f
            alpha = sticker.opacity
        }
        val asset = sticker.asset?.let { theme?.assets?.get(it) }
        if (asset != null) AsyncImage(model = asset, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = modifier, onError = { theme?.onImageError?.invoke() })
        sticker.drawing?.let { drawing ->
            val paths = remember(drawing) { drawing.layers.map { parseThemePath(it.path).asThemePath() } }
            Canvas(modifier) { drawThemeLayers(drawing, paths, clock?.value ?: 0, 1f) }
        }
    }
}
