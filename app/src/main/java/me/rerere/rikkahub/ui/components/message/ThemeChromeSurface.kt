package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.orbis.theme.ThemeBubble
import me.rerere.rikkahub.data.orbis.theme.surfaceFor
import me.rerere.rikkahub.ui.pages.orbis.LocalChatTheme

/** Shared material under the existing editor/navigation controls. Keeps their layout and actions. */
@Composable internal fun ThemeChromeSurface(
    role: String, modifier: Modifier, shape: Shape, radius: Int,
    color: Color, contentColor: Color, border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp, enabled: Boolean = true, content: @Composable () -> Unit,
) {
    val theme=LocalChatTheme.current
    val surface=if(enabled) theme?.body?.chrome?.surfaceFor(role) else null
    if(surface==null) {
        Surface(modifier=modifier,shape=shape,color=color,contentColor=contentColor,border=border,
            tonalElevation=0.dp,shadowElevation=shadowElevation,content=content)
        return
    }
    val hex="#%06X".format(color.toArgb() and 0xFFFFFF)
    val bubble=ThemeBubble(bg=hex,radius=radius,opacity=surface.opacity,material=surface.material,texture=surface.texture)
    val texture=themeTextureModifier(surface.texture,theme,hex,surface.opacity,
        optical = surface.material != null && LocalBubbleBackdrop.current != null && android.os.Build.VERSION.SDK_INT >= 33)
    Box(modifier.clip(shape), propagateMinConstraints = true) {
        if(surface.material!=null) ThemeBubbleMaterialBackground(bubble,shape)
        else Box(Modifier.matchParentSize().background(color.copy(alpha=color.alpha*surface.opacity)))
        Box(Modifier.then(texture).then(if(border!=null) Modifier.border(border,shape) else Modifier), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content=content)
        }
    }
}
