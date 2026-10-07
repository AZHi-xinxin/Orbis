package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * Android may synchronously search for a replacement focus target while removing
 * a focused AndroidView. During Compose applyChanges that search can re-enter a
 * lazy list's subcomposition. Release focus while handling navigation, not from
 * onRelease/onDispose or a LaunchedEffect after visibility has already changed.
 * This is deliberately scoped to home/chat transitions, not a global focus flag.
 */
@Composable
internal fun BindOrbisHomeNavigationFocus(navigation: OrbisHomeNavigationState) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    DisposableEffect(navigation, focusManager, keyboard, hostView) {
        val releaseFocus: () -> Unit = {
            focusManager.clearFocus(force = true)
            // Embedded Views also have Android focus independent of a Compose
            // focus target. Clear a remaining child before the visibility write.
            hostView.findFocus()?.takeUnless { it === hostView }?.clearFocus()
            keyboard?.hide()
        }
        navigation.beforeVisibilityChange = releaseFocus
        onDispose {
            // Disposing a host must not itself perform a focus search.
            if (navigation.beforeVisibilityChange === releaseFocus) {
                navigation.beforeVisibilityChange = null
            }
        }
    }
}
