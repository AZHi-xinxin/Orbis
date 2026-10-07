package me.rerere.rikkahub.ui.theme

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat

/** Decoration only: never hide system icons, consume insets, or change cutout layout. */
@Suppress("DEPRECATION")
internal fun applyTransparentStatusBarAppearance(window: Window, view: View, darkIcons: Boolean) {
    // Android 15+ is edge-to-edge by default; older versions still use this color.
    window.statusBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
}
