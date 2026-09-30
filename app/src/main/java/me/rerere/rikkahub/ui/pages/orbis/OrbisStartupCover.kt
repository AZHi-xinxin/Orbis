package me.rerere.rikkahub.ui.pages.orbis

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.model.*

/** UI-only lifetime, never persisted and never used to gate database or service work. */
@Stable
internal class OrbisStartupUiState(enabled: Boolean, val targetChatId: String?,
    val startedAtMillis: Long = SystemClock.elapsedRealtime()) {
    var visible by mutableStateOf(enabled)
        private set
    var chatReady by mutableStateOf(false)
        private set
    var slow by mutableStateOf(false)
        internal set
    var fading by mutableStateOf(false)
        internal set

    fun reportChatReady(id: String) {
        if (visible && id == targetChatId) chatReady = true
    }
    fun dismiss() { visible = false }
}

@Composable
internal fun rememberOrbisStartupState(
    enabled: Boolean,
    targetChatId: String?,
    currentChatId: String?,
    settingsReady: Boolean,
    migrationVisible: Boolean,
): OrbisStartupUiState {
    val state = remember { OrbisStartupUiState(enabled, targetChatId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Leaving for another app must not replay or resume an unfinished intro on return.
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.dismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Wall-clock guard is deliberately independent of animation duration/system scale.
    LaunchedEffect(state.visible) {
        if (!state.visible) return@LaunchedEffect
        fun elapsed() = (SystemClock.elapsedRealtime() - state.startedAtMillis).coerceAtLeast(0)
        delay((ORBIS_STARTUP_SLOW_MS - elapsed()).coerceAtLeast(0))
        state.slow = orbisStartupSlow(elapsed())
        delay((ORBIS_STARTUP_TIMEOUT_MS - elapsed()).coerceAtLeast(0))
        state.dismiss()
    }
    LaunchedEffect(state.visible, settingsReady, state.chatReady, currentChatId, migrationVisible) {
        if (!state.visible) return@LaunchedEffect
        when (orbisStartupExit(settingsReady, state.chatReady,
            SystemClock.elapsedRealtime() - state.startedAtMillis,
            migrationVisible || currentChatId != state.targetChatId)) {
            OrbisStartupExit.READY -> state.fading = true
            OrbisStartupExit.TIMEOUT, OrbisStartupExit.LEAVE -> state.dismiss()
            null -> Unit
        }
    }
    return state
}

@Composable
internal fun OrbisStartupCover(state: OrbisStartupUiState) {
    if (!state.visible) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val allowMotion = remember(context) {
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power != null && !power.isPowerSaveMode &&
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(false)
    }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(state.fading) {
        if (state.fading) {
            if (allowMotion) alpha.animateTo(0f, tween(200))
            state.dismiss()
        }
    }
    BackHandler { state.dismiss() }
    Box(Modifier.fillMaxSize().zIndex(20f).graphicsLayer { this.alpha = alpha.value }) {
        // Separate sibling behind the scene, not an ancestor consuming child gestures.
        // Button/scroll hits go to the scene; otherwise this shields the chat underneath.
        Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                }
        })
        OrbisStartupScene(
            animate = allowMotion && resumed,
            slowLoading = state.slow,
            onContinue = state::dismiss,
        )
    }
}
