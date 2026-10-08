package me.rerere.rikkahub.service

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Platform adapter; callbacks are owner-checked by the runtime and never outlive the call. */
internal class AndroidVoiceCallAudioRouting(
    private val audio: AudioManager,
    private val scope: CoroutineScope,
    private val mayUpdate: () -> Boolean,
    private val onWaiting: () -> Unit,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val routing = VoiceCallAudioRouting(AndroidVoiceCallAudioBackend(audio))
    private var registered = false
    private var closed = false
    private var waiting = false
    private var requestEpoch = 0L
    private var routeJob: Job? = null
    private var removeCommunicationListener: (() -> Unit)? = null
    private val confirmation = VoiceCallRouteConfirmation()
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
    }

    suspend fun start(): Boolean {
        audio.registerAudioDeviceCallback(callback, handler)
        registered = true
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { update() }
            audio.addOnCommunicationDeviceChangedListener({ command -> handler.post(command); Unit }, listener)
            removeCommunicationListener = { audio.removeOnCommunicationDeviceChangedListener(listener) }
        }
        return requestAndConfirm { routing.start() }
    }

    private fun update() {
        if (closed) return
        confirmation.changed()
        if (waiting || routeJob?.isActive == true || !mayUpdate()) return
        if (runCatching { routing.isPreferredAndConfirmed() }.getOrDefault(false)) return
        // Pause before requesting another route. In particular, unplugging headphones
        // must not automatically continue private speech through the speaker.
        onWaiting()
        routeJob = scope.launch(start = CoroutineStart.LAZY) {
            val ready = requestAndConfirm { routing.refresh(allowSpeakerFallback = false) }
            if (!closed && mayUpdate()) {
                if (ready) onReady() else onUnavailable()
            }
        }.also { it.start() }
    }

    private suspend fun requestAndConfirm(request: () -> Boolean): Boolean {
        if (closed || waiting) return false
        waiting = true
        val epoch = requestEpoch
        return try {
            request() && confirmation.awaitReady(
                isConfirmed = { routing.isConfirmed() },
                mayContinue = { !closed && epoch == requestEpoch },
            )
        } finally { waiting = false }
    }

    suspend fun resume(): Boolean = requestAndConfirm { routing.resume() }
    fun suspendRouting() {
        requestEpoch++
        confirmation.changed()
        routeJob?.cancel()
        routeJob = null
        routing.suspendRouting()
    }

    fun close() {
        if (closed) return
        closed = true
        requestEpoch++
        confirmation.changed()
        routeJob?.cancel()
        routeJob = null
        if (registered) runCatching { audio.unregisterAudioDeviceCallback(callback) }
        registered = false
        removeCommunicationListener?.let { runCatching(it) }
        removeCommunicationListener = null
        routing.close()
    }
}

@Suppress("DEPRECATION")
private class AndroidVoiceCallAudioBackend(private val audio: AudioManager) : VoiceCallAudioBackend {
    override val modernRouting get() = Build.VERSION.SDK_INT >= 31
    override var mode: VoiceCallAudioMode
        get() = when (audio.mode) {
            AudioManager.MODE_NORMAL -> VoiceCallAudioMode.NORMAL
            AudioManager.MODE_IN_COMMUNICATION -> VoiceCallAudioMode.COMMUNICATION
            else -> VoiceCallAudioMode.OTHER
        }
        set(value) {
            audio.mode = when (value) {
                VoiceCallAudioMode.NORMAL -> AudioManager.MODE_NORMAL
                VoiceCallAudioMode.COMMUNICATION -> AudioManager.MODE_IN_COMMUNICATION
                VoiceCallAudioMode.OTHER -> error("Not an owned audio mode")
            }
        }
    override val currentDevice get() = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.toCallDevice() else null
    override val availableDevices get() = if (Build.VERSION.SDK_INT >= 31) {
        audio.availableCommunicationDevices.filter { it.isSink }.map { it.toCallDevice() }
    } else emptyList()
    override fun selectDevice(id: Int): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        audio.availableCommunicationDevices.firstOrNull { it.id == id && it.isSink }
            ?.let(audio::setCommunicationDevice) ?: false
    } else false
    override fun clearDevice() { if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() }
    override val legacyHeadsetConnected get() = audio.isWiredHeadsetOn || audio.isBluetoothScoOn ||
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        }
    override val legacyUnconfirmedBluetooth get() = !audio.isBluetoothScoOn &&
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
        }
    override var speakerphoneOn: Boolean
        get() = audio.isSpeakerphoneOn
        set(value) { audio.isSpeakerphoneOn = value }
}

private fun AudioDeviceInfo.toCallDevice() = VoiceCallAudioDevice(id, when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> VoiceCallAudioDeviceKind.EXTERNAL
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> VoiceCallAudioDeviceKind.SPEAKER
    else -> VoiceCallAudioDeviceKind.OTHER
})
