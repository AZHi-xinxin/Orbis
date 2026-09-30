package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Public builds hide the navigation tile even when an old connection is saved. */
internal fun showConsultationInNavigation(featureEnabled: Boolean, connectionAvailable: Boolean): Boolean =
    featureEnabled && connectionAvailable

/**
 * An optional service page, not the app's start screen. The Activity owns this
 * transient state: a fresh launch/restored Activity always starts in native chat.
 * Cloud connection consent and website storage remain separate and unchanged.
 */
internal class OrbisHomeNavigationState {
    var visible by mutableStateOf(false)
        private set
    var homeRevision by mutableIntStateOf(0)
        private set

    fun openHome() {
        homeRevision++
        visible = true
    }

    fun returnToChat() {
        visible = false
    }
}
