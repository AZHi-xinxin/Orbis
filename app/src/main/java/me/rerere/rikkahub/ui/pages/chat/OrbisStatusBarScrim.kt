package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Presentation only: the caller decides whether the selected background needs icon contrast. */
@Composable
internal fun OrbisStatusBarScrim(
    visible: Boolean,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.statusBars,
) {
    if (!visible) return

    // Surface propagates its full-screen minimum constraints to direct children.
    // This shell absorbs them; the inset-height child can then measure at its own
    // height and is explicitly placed at the top, never centered by size coercion.
    Box(modifier.fillMaxSize(), propagateMinConstraints = false) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(insets)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .56f), Color.Black.copy(alpha = .44f)),
                    ),
                ),
        )
    }
}
