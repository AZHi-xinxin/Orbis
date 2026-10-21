package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.DrawerState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.orbis.companiontools.orbisForegroundChat

/** Identity is a one-shot UI request, not saved state or a persistent observation. */
internal class OrbisChatReturnRequest(val conversationId: String)
internal val LocalOrbisChatReturnRequest = staticCompositionLocalOf<OrbisChatReturnRequest?> { null }

@Composable
internal fun HandleForegroundChatReturn(conversationId: String, drawerState: DrawerState, closeOverlays: () -> Unit) {
    val request = LocalOrbisChatReturnRequest.current
    val currentCloseOverlays by rememberUpdatedState(closeOverlays)
    LaunchedEffect(request, conversationId, drawerState) {
        if (request?.conversationId == conversationId) {
            currentCloseOverlays()
            drawerState.close()
        }
    }
}

/** UI acknowledgement only, independent of the optional AI observation feature. */
@Composable
internal fun ReportForegroundChat(conversationId: String, enabled: Boolean) {
    val token = remember { Any() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    // NavDisplay can retain the composition behind Home, the drawer, or a route
    // transition. A resumed Activity alone does not mean this chat is visible.
    val focused = enabled && LocalOrbisChatAnimationVisible.current &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) && LocalWindowInfo.current.isWindowFocused
    DisposableEffect(token, conversationId, focused) {
        orbisForegroundChat.observe(token, conversationId, focused)
        onDispose { orbisForegroundChat.observe(token, conversationId, false) }
    }
}
