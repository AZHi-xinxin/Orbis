package me.rerere.rikkahub.ui.components.ai

import android.media.AudioManager
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Bind keys, not a volume index. Closing a call never resets the human's chosen loudness. */
@Composable
internal fun BindVoiceCallVolumeStream(window: Window?, active: Boolean) {
    DisposableEffect(window, active) {
        val previous = window?.volumeControlStream
        if (active && window != null) window.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        onDispose {
            if (active && window != null && previous != null &&
                window.volumeControlStream == AudioManager.STREAM_VOICE_CALL) {
                window.volumeControlStream = previous
            }
        }
    }
}

/** Compose Dialog owns a separate Window: the Activity binding alone does not cover it. */
@Composable
internal fun BindVoiceCallDialogVolumeStream() {
    val view = LocalView.current
    BindVoiceCallVolumeStream((view.parent as? DialogWindowProvider)?.window, active = true)
}
