package me.rerere.rikkahub.ui.pages.orbis

import android.view.ViewGroup
import android.webkit.WebView

/**
 * Use before loading either homepage into its bounded Compose container. Some WebView providers
 * resolve percentage/vh heights as zero with the factory's default WRAP_CONTENT layout params,
 * even when Compose measures the native view with an exact full-screen height. MATCH_PARENT
 * preserves the page's own viewport CSS without changing scaling, content or network settings.
 */
fun WebView.configureOrbisHomeLayout() {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
