package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

internal enum class VoiceCallAudioMode { NORMAL, COMMUNICATION, OTHER }
internal enum class VoiceCallAudioDeviceKind { EXTERNAL, SPEAKER, OTHER }
internal data class VoiceCallAudioDevice(val id: Int, val kind: VoiceCallAudioDeviceKind)

/** No volume-index/gain API belongs here. Only the human's volume keys change volume. */
internal interface VoiceCallAudioBackend {
    val modernRouting: Boolean
    var mode: VoiceCallAudioMode
    val currentDevice: VoiceCallAudioDevice?
    val availableDevices: List<VoiceCallAudioDevice>
    fun selectDevice(id: Int): Boolean
    fun clearDevice()
    val legacyHeadsetConnected: Boolean
    /** A connected Bluetooth media device without an active communication route. */
    val legacyUnconfirmedBluetooth: Boolean
    var speakerphoneOn: Boolean
}

internal fun preferredVoiceCallDevice(
    available: List<VoiceCallAudioDevice>, current: VoiceCallAudioDevice?,
): VoiceCallAudioDevice? = available.firstOrNull {
    it.id == current?.id && it.kind == VoiceCallAudioDeviceKind.EXTERNAL
} ?: available.firstOrNull { it.kind == VoiceCallAudioDeviceKind.EXTERNAL }
    ?: available.firstOrNull { it.kind == VoiceCallAudioDeviceKind.SPEAKER }

/**
 * A main-thread call-scoped lease. getCommunicationDevice is the effective system route,
 * NOT this app's previous selection: do not restore it with a new, persistent app request.
 * Clearing our request hands routing back to Android (and any other route owner).
 */
internal class VoiceCallAudioRouting(private val backend: VoiceCallAudioBackend) {
    private var started = false
    private var closed = false
    private var ownsMode = false
    private var ownsDevice = false
    private var previousSpeaker: Boolean? = null
    private var lastSpeaker: Boolean? = null
    private var requestedDevice: VoiceCallAudioDevice? = null
    private var lastConfirmedExternal = false
    private var requestedLegacySpeaker: Boolean? = null

    fun start(): Boolean {
        if (started || closed || backend.mode != VoiceCallAudioMode.NORMAL) return false
        started = true
        previousSpeaker = if (backend.modernRouting) null else backend.speakerphoneOn
        return resume()
    }

    fun resume(): Boolean {
        if (!started || closed) return false
        return runCatching {
            if (backend.mode == VoiceCallAudioMode.NORMAL) {
                ownsMode = true
                backend.mode = VoiceCallAudioMode.COMMUNICATION
            }
            // setMode can be silently refused by Android; assignment is not confirmation.
            backend.mode == VoiceCallAudioMode.COMMUNICATION && refresh()
        }.getOrDefault(false)
    }

    fun refresh(allowSpeakerFallback: Boolean = true): Boolean {
        if (!started || closed || backend.mode != VoiceCallAudioMode.COMMUNICATION) return false
        return runCatching {
            if (backend.modernRouting) {
                val desired = preferredVoiceCallDevice(backend.availableDevices, backend.currentDevice)
                    ?: return false
                if (!allowSpeakerFallback && desired.kind == VoiceCallAudioDeviceKind.SPEAKER &&
                    (lastConfirmedExternal || requestedDevice?.kind == VoiceCallAudioDeviceKind.EXTERNAL)) return false
                requestedDevice = desired
                if (backend.currentDevice?.id == desired.id) return true
                // Acceptance is not proof of a completed Bluetooth handshake. Duplex capture
                // continues to use the runtime's separate, actual-route check.
                backend.selectDevice(desired.id).also { accepted -> if (accepted) ownsDevice = true }
            } else {
                // API 26–30: do not force speaker over a wired or Bluetooth route. Do not
                // invent an SCO connection from device presence or mutate global SCO state.
                val headset = backend.legacyHeadsetConnected
                // A2DP presence is not SCO. Do not silently substitute earpiece or speaker.
                if (!headset && backend.legacyUnconfirmedBluetooth) return false
                val speaker = !headset
                if (speaker && !allowSpeakerFallback && lastConfirmedExternal) return false
                requestedLegacySpeaker = speaker
                if (backend.speakerphoneOn != speaker) {
                    lastSpeaker = speaker
                    backend.speakerphoneOn = speaker
                }
                backend.speakerphoneOn == speaker
            }
        }.getOrDefault(false)
    }

    /** Only actual communication-device readback, never setCommunicationDevice's acceptance. */
    fun isConfirmed(): Boolean = runCatching {
        if (!started || closed || backend.mode != VoiceCallAudioMode.COMMUNICATION) return false
        if (backend.modernRouting) {
            val requested = requestedDevice ?: return false
            val actual = backend.currentDevice
            val ready = actual?.id == requested.id && backend.availableDevices.any { it.id == requested.id }
            if (ready) lastConfirmedExternal = requested.kind == VoiceCallAudioDeviceKind.EXTERNAL
            ready
        } else {
            val speaker = requestedLegacySpeaker ?: return false
            val ready = backend.speakerphoneOn == speaker && if (speaker) {
                !backend.legacyHeadsetConnected && !backend.legacyUnconfirmedBluetooth
            } else backend.legacyHeadsetConnected
            if (ready) lastConfirmedExternal = !speaker
            ready
        }
    }.getOrDefault(false)

    fun isPreferredAndConfirmed(): Boolean = isConfirmed() && (!backend.modernRouting ||
        preferredVoiceCallDevice(backend.availableDevices, backend.currentDevice)?.id == requestedDevice?.id)

    fun suspendRouting() {
        if (!started || closed) return
        releaseDevice()
    }

    private fun releaseDevice() {
        if (ownsDevice) {
            // clearCommunicationDevice cancels this process's selection, not another app's.
            runCatching { backend.clearDevice() }.onSuccess { ownsDevice = false }
        }
        val last = lastSpeaker
        val previous = previousSpeaker
        if (last != null && previous != null) {
            runCatching {
                if (backend.mode == VoiceCallAudioMode.COMMUNICATION && backend.speakerphoneOn == last) {
                    backend.speakerphoneOn = previous
                }
            }
            lastSpeaker = null
        }
    }

    fun close() {
        if (closed) return
        releaseDevice()
        if (ownsMode) runCatching {
            // A real phone call / another mode owner must never be overwritten on hang-up.
            if (backend.mode == VoiceCallAudioMode.COMMUNICATION) backend.mode = VoiceCallAudioMode.NORMAL
        }
        closed = true
    }
}

/** Wake on both real route changes and device-list changes; timeout/cancellation never means ready. */
internal class VoiceCallRouteConfirmation(private val timeoutMillis: Long = 3_000L) {
    private val changes = MutableStateFlow(0L)
    fun changed() { changes.value++ }
    suspend fun awaitReady(isConfirmed: () -> Boolean, mayContinue: () -> Boolean = { true }): Boolean = withTimeoutOrNull(timeoutMillis) {
        changes.first { !checked(mayContinue) || checked(isConfirmed) }
        checked(mayContinue) && checked(isConfirmed)
    } ?: false

    private fun checked(predicate: () -> Boolean): Boolean = try {
        predicate()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** A call must not be swallowed by the optional chat-volume-key scrolling feature. */
internal fun dispatchChatVolumeKey(
    callActive: Boolean, down: Boolean, volumeUp: Boolean?, chatHandler: (Boolean) -> Boolean,
): Boolean = !callActive && down && volumeUp != null && chatHandler(volumeUp)
