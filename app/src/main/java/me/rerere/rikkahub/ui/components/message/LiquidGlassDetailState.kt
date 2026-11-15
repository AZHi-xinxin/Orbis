package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Bubble-local presentation only; never persisted into messages or theme definitions. */
@Stable
internal class LiquidGlassDetailState {
    private val details = mutableStateMapOf<Any, State<Boolean>>()
    var settling by mutableStateOf(false)
        private set
    var geometry by mutableStateOf(IntSize.Zero to Offset.Zero)
        private set
    val expanded: Boolean get() = details.values.any { it.value }
    val outlineOnly: Boolean get() = expanded || settling

    fun register(key: Any, visible: State<Boolean>) { details[key] = visible }
    fun unregister(key: Any) { details.remove(key) }
    fun hold() { settling = true }
    fun restore() { settling = false }
    fun laidOut(size: IntSize, position: Offset) { geometry = size to position }
}

internal val LocalLiquidGlassDetails = staticCompositionLocalOf<LiquidGlassDetailState?> { null }

/** Read the child's committed visibility directly in the parent's draw, including the first
 * expanded frame. Retain the hold through removal and through every closing layout frame. */
@Composable
internal fun ReportLiquidGlassDetail(visible: Boolean) {
    val owner = LocalLiquidGlassDetails.current ?: return
    val key = remember { Any() }
    val current = rememberUpdatedState(visible)
    DisposableEffect(owner, key) {
        owner.register(key, current)
        onDispose { owner.unregister(key) }
    }
    SideEffect { if (visible) owner.hold() }
}

@Composable
internal fun rememberLiquidGlassDetails(enabled: Boolean): LiquidGlassDetailState? {
    val state = remember(enabled) { if (enabled) LiquidGlassDetailState() else null }
    LaunchedEffect(state) {
        if (state != null) snapshotFlow { state.expanded to state.geometry }.collectLatest { (expanded, _) ->
            if (expanded) state.hold()
            else if (state.settling) {
                // Restart on every size/position change; never restore midway through a
                // spring animation or after a rapid reopen. This is a quiet-layout window.
                delay(350)
                state.restore()
            }
        }
    }
    return state
}
