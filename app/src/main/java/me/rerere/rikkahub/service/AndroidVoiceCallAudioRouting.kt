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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Platform adapter; callbacks are owner-checked by the runtime and never outlive the call. */
internal class AndroidVoiceCallAudioRouting(
    private val audio: AudioManager,
    private val scope: CoroutineScope,
    private val mayUpdate: () -> Boolean,
    private val microphoneMuted: () -> Boolean,
    private val onWaiting: () -> Unit,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val routing = VoiceCallAudioRouting(AndroidVoiceCallAudioBackend(audio))
    private var registered = false
    private var closed = false
    private var waiting = false
    private var preservingMutedOutput = false
    private var refreshPending = false
    private var requestEpoch = 0L
    private var routeJob: Job? = null
    private var removeCommunicationListener: (() -> Unit)? = null
    private var removeModeListener: (() -> Unit)? = null
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
            // Android may relinquish communication mode between active capture/playback.
            // Device presence alone does not report that change (e.g. built-in speaker).
            val modeListener = AudioManager.OnModeChangedListener { update() }
            audio.addOnModeChangedListener({ command -> handler.post(command); Unit }, modeListener)
            removeModeListener = { audio.removeOnModeChangedListener(modeListener) }
        }
        val ready = requestAndConfirm { routing.start() }
        if (refreshPending && mayUpdate()) update()
        return ready
    }

    private fun update() {
        if (closed) return
        confirmation.changed()
        if (waiting || routeJob?.isActive == true) {
            refreshPending = true
            // An actual device change during the short idle-mode confirmation is not
            // covered by the exception: retain the existing pause behavior immediately.
            if (preservingMutedOutput && !routing.mayPreserveMutedSpeakerOutput(microphoneMuted())) {
                preservingMutedOutput = false
                onWaiting()
            }
            return
        }
        refreshPending = false
        if (!mayUpdate()) return
        if (runCatching { routing.isPreferredAndConfirmed() }.getOrDefault(false)) return
        // Pause before requesting another route. In particular, unplugging headphones
        // must not automatically continue private speech through the speaker.
        // Stopping TTS here also cancels synthesis. While the mic is intentionally off,
        // Android can release idle communication mode every six seconds, indefinitely
        // cancelling a slow synthesis before it gets a chance to produce any audio.
        preservingMutedOutput = routing.isMutedSpeakerIdleRelease(microphoneMuted())
        if (!preservingMutedOutput) onWaiting()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Still our live, focused call: reacquire NORMAL -> COMMUNICATION as well as
            // the route. refresh alone rejected NORMAL forever after idle mode loss.
            try {
                val ready = requestAndConfirm { routing.resume(allowSpeakerFallback = false) }
                preservingMutedOutput = false
                if (!closed && mayUpdate()) {
                    if (ready) onReady() else onUnavailable()
                }
            } finally {
                if (routeJob === currentCoroutineContext().job) {
                    preservingMutedOutput = false
                    routeJob = null
                    // A cancelled old owner must not clear or restart a replacement job.
                    if (refreshPending && !closed && mayUpdate()) update()
                }
            }
        }
        // Main.immediate may finish synchronously. Publish ownership before starting.
        routeJob = job
        job.start()
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
        preservingMutedOutput = false
        refreshPending = false
        requestEpoch++
        confirmation.changed()
        routeJob?.cancel()
        routeJob = null
        routing.suspendRouting()
    }

    fun close() {
        if (closed) return
        closed = true
        preservingMutedOutput = false
        requestEpoch++
        confirmation.changed()
        routeJob?.cancel()
        routeJob = null
        if (registered) runCatching { audio.unregisterAudioDeviceCallback(callback) }
        registered = false
        removeCommunicationListener?.let { runCatching(it) }
        removeCommunicationListener = null
        removeModeListener?.let { runCatching(it) }
        removeModeListener = null
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
