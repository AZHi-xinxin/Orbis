package me.rerere.asr

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect

/** Native Android AEC only. Availability does not guarantee useful cancellation on every device. */
internal fun nativeEchoCancellation(audioSessionId: Int, requested: Boolean,
    onState: (EchoCancellationState) -> Unit): EchoCancellationSession = EchoCancellationSession(
    object : EchoCancellationFactory {
        override fun isAvailable() = AcousticEchoCanceler.isAvailable()
        override fun create(audioSessionId: Int): EchoCancellationEffect? = AcousticEchoCanceler.create(audioSessionId)?.let { native ->
            object : EchoCancellationEffect {
                override fun enable() = native.setEnabled(true) == AudioEffect.SUCCESS && native.enabled
                override fun isEnabled() = native.enabled
                override fun hasControl() = native.hasControl()
                override fun listen(onChange: (() -> Unit)?) {
                    native.setEnableStatusListener(if (onChange == null) null else AudioEffect.OnEnableStatusChangeListener { _, _ -> onChange() })
                    native.setControlStatusListener(if (onChange == null) null else AudioEffect.OnControlStatusChangeListener { _, _ -> onChange() })
                }
                override fun release() = native.release()
            }
        }
    }, onState,
).apply { start(requested, audioSessionId) }
