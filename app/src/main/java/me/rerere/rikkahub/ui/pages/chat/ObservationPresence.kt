package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.observation.ObservationMeter
import me.rerere.rikkahub.data.orbis.observation.ObservationInterval

@Composable
internal fun ObserveChatPresence(enabled: Boolean, window: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    val visible = enabled && LocalOrbisChatAnimationVisible.current &&
        LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(visible, window, lifecycle) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val meter = ObservationMeter(context)
            val interval = ObservationInterval(SystemClock.elapsedRealtime())
            suspend fun flush() {
                // Consume before the cancellable dispatcher return; finally must not count it again.
                val elapsed = interval.take(SystemClock.elapsedRealtime())
                withContext(NonCancellable + Dispatchers.IO) { meter.visible(window, elapsed) }
            }
            try { while (true) { delay(10_000); flush() } }
            finally { flush() }
        }
    }
}
