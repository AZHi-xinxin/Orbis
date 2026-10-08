package me.rerere.rikkahub.service

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoiceCallAudioRoutingTest {
    private val speaker = VoiceCallAudioDevice(1, VoiceCallAudioDeviceKind.SPEAKER)
    private val earpiece = VoiceCallAudioDevice(2, VoiceCallAudioDeviceKind.OTHER)
    private val wired = VoiceCallAudioDevice(3, VoiceCallAudioDeviceKind.EXTERNAL)
    private val bluetooth = VoiceCallAudioDevice(4, VoiceCallAudioDeviceKind.EXTERNAL)

    private inner class Backend(override val modernRouting: Boolean = true) : VoiceCallAudioBackend {
        var refuseMode = false
        var modeWrites = 0
        private var effectiveMode = VoiceCallAudioMode.NORMAL
        override var mode: VoiceCallAudioMode
            get() = effectiveMode
            set(value) { modeWrites++; if (!refuseMode) effectiveMode = value }
        override var currentDevice: VoiceCallAudioDevice? = earpiece
        override var availableDevices = listOf(earpiece, speaker)
        override var legacyHeadsetConnected = false
        override var legacyUnconfirmedBluetooth = false
        override var speakerphoneOn = false
        var acceptSelection = true
        var completeSelectionImmediately = true
        var throwOnClear = false
        val selections = mutableListOf<Int>()
        var clears = 0
        override fun selectDevice(id: Int): Boolean {
            selections += id
            if (acceptSelection && completeSelectionImmediately) currentDevice = availableDevices.single { it.id == id }
            return acceptSelection
        }
        override fun clearDevice() {
            clears++
            if (throwOnClear) error("synthetic route release failure")
            currentDevice = earpiece
        }
    }

    @Test fun speakerIsDefaultInsteadOfEarpieceAndCloseReleasesRequestAndMode() {
        val backend = Backend()
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertEquals(VoiceCallAudioMode.COMMUNICATION, backend.mode)
        assertEquals(listOf(speaker.id), backend.selections)
        lease.close()
        lease.close()
        assertEquals(1, backend.clears)
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
    }

    @Test fun currentExternalRouteWinsEvenWhenAnotherHeadsetIsListedFirst() {
        val backend = Backend().apply {
            availableDevices = listOf(speaker, wired, bluetooth)
            currentDevice = bluetooth
        }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertTrue(backend.selections.isEmpty())
        lease.close()
        assertEquals(0, backend.clears)
        assertEquals(bluetooth, backend.currentDevice)
    }

    @Test fun connectedHeadsetIsPreferredOverSpeaker() {
        for (headset in listOf(wired, bluetooth)) {
            val backend = Backend().apply { availableDevices = listOf(speaker, headset) }
            val lease = VoiceCallAudioRouting(backend)
            assertTrue(lease.start())
            assertEquals(listOf(headset.id), backend.selections)
            lease.close()
        }
    }

    @Test fun plugAndUnplugReevaluateTheActualAvailableDevices() {
        val backend = Backend()
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        backend.availableDevices = listOf(speaker, wired)
        assertTrue(lease.refresh())
        assertEquals(wired, backend.currentDevice)
        backend.availableDevices = listOf(earpiece, speaker)
        backend.currentDevice = earpiece
        assertTrue(lease.refresh())
        assertEquals(listOf(speaker.id, wired.id, speaker.id), backend.selections)
        lease.close()
    }

    @Test fun rejectedModeAssignmentDoesNotPretendToRouteAudio() {
        val backend = Backend().apply { refuseMode = true }
        val lease = VoiceCallAudioRouting(backend)
        assertFalse(lease.start())
        assertTrue(backend.selections.isEmpty())
        lease.close()
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
    }

    @Test fun rejectedRouteIsNotReportedAsReadyAndUnwindsOwnedMode() {
        val backend = Backend().apply { acceptSelection = false }
        val lease = VoiceCallAudioRouting(backend)
        assertFalse(lease.start())
        lease.close()
        assertEquals(0, backend.clears)
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
    }

    @Test fun acceptedAsynchronousBluetoothRequestIsReleasedEvenBeforeItBecomesCurrent() {
        val backend = Backend().apply {
            availableDevices = listOf(speaker, bluetooth)
            completeSelectionImmediately = false
        }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertEquals(earpiece, backend.currentDevice)
        assertFalse("Request acceptance is not proof of actual routing", lease.isConfirmed())
        lease.close()
        assertEquals(1, backend.clears)
    }

    @Test fun noCommunicationOutputFailsInsteadOfSilentlyKeepingEarpiece() {
        val backend = Backend().apply { availableDevices = listOf(earpiece) }
        val lease = VoiceCallAudioRouting(backend)
        assertFalse(lease.start())
        lease.close()
    }

    @Test fun otherCallAtEntryIsUntouched() {
        val backend = Backend().apply { mode = VoiceCallAudioMode.OTHER }
        val writesBefore = backend.modeWrites
        val lease = VoiceCallAudioRouting(backend)
        assertFalse(lease.start())
        lease.close()
        assertEquals(writesBefore, backend.modeWrites)
        assertTrue(backend.selections.isEmpty())
    }

    @Test fun focusLossReleasesOurRouteAndGainCanAcquireItAgain() {
        val backend = Backend()
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        lease.suspendRouting()
        assertEquals(1, backend.clears)
        assertTrue(lease.resume())
        assertEquals(listOf(speaker.id, speaker.id), backend.selections)
        lease.close()
        assertEquals(2, backend.clears)
    }

    @Test fun realPhoneCallDuringSuspensionCannotBeOverwrittenByRefreshResumeOrClose() {
        val backend = Backend()
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        lease.suspendRouting()
        backend.mode = VoiceCallAudioMode.OTHER
        val writesBefore = backend.modeWrites
        assertFalse(lease.refresh())
        assertFalse(lease.resume())
        lease.close()
        assertEquals(writesBefore, backend.modeWrites)
        assertEquals(VoiceCallAudioMode.OTHER, backend.mode)
    }

    @Test fun lateDeviceCallbackCannotRestoreAnEndedCall() {
        val backend = Backend()
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        lease.close()
        val writesBefore = backend.modeWrites
        assertFalse(lease.refresh())
        assertFalse(lease.resume())
        assertFalse(lease.start())
        assertEquals(writesBefore, backend.modeWrites)
        assertEquals(1, backend.selections.size)
    }

    @Test fun legacyDefaultSpeakerIsRestoredWithoutChangingVolume() {
        val backend = Backend(modernRouting = false)
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertTrue(backend.speakerphoneOn)
        lease.close()
        assertFalse(backend.speakerphoneOn)
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
        assertTrue(backend.selections.isEmpty())
    }

    @Test fun legacyHeadsetIsNeverOverriddenBySpeakerAndItsPreviousFlagIsRestored() {
        val backend = Backend(modernRouting = false).apply {
            legacyHeadsetConnected = true
            speakerphoneOn = true
        }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertFalse(backend.speakerphoneOn)
        lease.close()
        assertTrue(backend.speakerphoneOn)
    }

    @Test fun legacyExternalSpeakerChangeIsNotUndoneOnExit() {
        val backend = Backend(modernRouting = false).apply { speakerphoneOn = true; legacyHeadsetConnected = true }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        backend.speakerphoneOn = true // Changed by another owner, no longer our last value.
        lease.close()
        assertTrue(backend.speakerphoneOn)
    }

    @Test fun legacyHangupDoesNotChangeTheRoutingOfARealPhoneCall() {
        val backend = Backend(modernRouting = false)
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        backend.mode = VoiceCallAudioMode.OTHER
        lease.close()
        assertTrue(backend.speakerphoneOn)
        assertEquals(VoiceCallAudioMode.OTHER, backend.mode)
    }

    @Test fun releaseFailureDoesNotSkipModeCleanup() {
        val backend = Backend().apply { throwOnClear = true }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        lease.close()
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
    }

    @Test fun callVolumeKeysNeverInvokeTheChatScrollerWhileInactiveKeysStillDo() {
        val observed = mutableListOf<Boolean>()
        val scroll: (Boolean) -> Boolean = { observed += it; true }
        for (down in listOf(true, false)) for (up in listOf(true, false)) {
            assertFalse(dispatchChatVolumeKey(callActive = true, down = down, volumeUp = up, chatHandler = scroll))
        }
        assertTrue(observed.isEmpty())
        assertTrue(dispatchChatVolumeKey(false, true, true, scroll))
        assertTrue(dispatchChatVolumeKey(false, true, false, scroll))
        assertFalse(dispatchChatVolumeKey(false, false, true, scroll))
        assertFalse(dispatchChatVolumeKey(false, true, null, scroll))
        assertEquals(listOf(true, false), observed)
    }

    @Test fun legacyA2dpOnlyFailsWithoutForcingSpeakerOrPretendingScoExists() {
        val backend = Backend(modernRouting = false).apply { legacyUnconfirmedBluetooth = true }
        val lease = VoiceCallAudioRouting(backend)
        assertFalse(lease.start())
        assertFalse(lease.isConfirmed())
        assertFalse(backend.speakerphoneOn)
        lease.close()
        assertEquals(VoiceCallAudioMode.NORMAL, backend.mode)
    }

    @Test fun legacyConfirmedWiredOrScoCanBeUsedEvenIfAnA2dpDeviceIsAlsoPresent() {
        val backend = Backend(modernRouting = false).apply {
            legacyUnconfirmedBluetooth = true
            legacyHeadsetConnected = true
        }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertTrue(lease.isConfirmed())
        assertFalse(backend.speakerphoneOn)
        lease.close()
    }

    @Test fun losingAModernHeadsetRequiresHumanResumeBeforeSpeakerFallback() {
        val backend = Backend().apply { currentDevice = wired; availableDevices = listOf(speaker, wired) }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertTrue(lease.isConfirmed())
        backend.availableDevices = listOf(speaker, earpiece)
        backend.currentDevice = earpiece
        assertFalse(lease.refresh(allowSpeakerFallback = false))
        assertTrue(backend.selections.isEmpty())
        assertFalse(lease.isConfirmed())
        assertTrue(lease.resume()) // Explicit human action authorizes choosing available speaker.
        assertTrue(lease.isConfirmed())
        assertEquals(listOf(speaker.id), backend.selections)
        lease.close()
    }

    @Test fun losingALegacyHeadsetDoesNotAutomaticallyTurnOnSpeaker() {
        val backend = Backend(modernRouting = false).apply { legacyHeadsetConnected = true }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertTrue(lease.isConfirmed())
        backend.legacyHeadsetConnected = false
        assertFalse(lease.isConfirmed())
        assertFalse(lease.refresh(allowSpeakerFallback = false))
        assertFalse(backend.speakerphoneOn)
        assertTrue(lease.resume())
        assertTrue(lease.isConfirmed())
        assertTrue(backend.speakerphoneOn)
        lease.close()
    }

    @Test fun actualCommunicationCallbackConfirmsWithoutAnyDeviceListChange() = runTest {
        val backend = Backend().apply { completeSelectionImmediately = false }
        val lease = VoiceCallAudioRouting(backend)
        val signal = VoiceCallRouteConfirmation()
        assertTrue(lease.start())
        val waiting = async { signal.awaitReady(lease::isConfirmed) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        backend.currentDevice = speaker // Actual OnCommunicationDeviceChanged, not added/removed.
        signal.changed()
        assertTrue(waiting.await())
        assertEquals(listOf(earpiece, speaker), backend.availableDevices)
        lease.close()
    }

    @Test fun acceptedButNeverAppliedRouteTimesOutInsteadOfPlayingThroughOldOutput() = runTest {
        val backend = Backend().apply { completeSelectionImmediately = false }
        val lease = VoiceCallAudioRouting(backend)
        val signal = VoiceCallRouteConfirmation()
        assertTrue(lease.start())
        val waiting = async { signal.awaitReady(lease::isConfirmed) }
        runCurrent()
        signal.changed() // Unrelated callback must not be treated as confirmation.
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(waiting.await())
        lease.suspendRouting()
        assertEquals(1, backend.clears)
        lease.close()
    }

    @Test fun canceledRouteWaitCannotResumeOnALateCallbackAndLeavesNoTimerWork() = runTest {
        val backend = Backend().apply { completeSelectionImmediately = false }
        val lease = VoiceCallAudioRouting(backend)
        val signal = VoiceCallRouteConfirmation()
        assertTrue(lease.start())
        var continuedPlayback = false
        val waiting = async {
            if (signal.awaitReady(lease::isConfirmed)) continuedPlayback = true
        }
        runCurrent()
        waiting.cancelAndJoin()
        lease.close()
        backend.currentDevice = speaker
        signal.changed()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(waiting.isCancelled)
        assertFalse(continuedPlayback)
        assertFalse(lease.isConfirmed())
        assertEquals(1, backend.clears)
    }

    @Test fun headsetDisappearingDuringAcceptedPendingRequestCannotAutoFallback() {
        val backend = Backend().apply {
            availableDevices = listOf(speaker, bluetooth)
            completeSelectionImmediately = false
        }
        val lease = VoiceCallAudioRouting(backend)
        assertTrue(lease.start())
        assertFalse(lease.isConfirmed())
        backend.availableDevices = listOf(speaker, earpiece)
        assertFalse(lease.refresh(allowSpeakerFallback = false))
        assertEquals(listOf(bluetooth.id), backend.selections)
        lease.close()
    }

    @Test fun focusLossInvalidatesPendingStartupEvenIfRouteConfirmationArrivesTogether() = runTest {
        val signal = VoiceCallRouteConfirmation()
        var epoch = 1L
        val startupEpoch = epoch
        var actualReady = false
        var startedCapture = false
        val waiting = async {
            if (signal.awaitReady(isConfirmed = { actualReady }, mayContinue = { epoch == startupEpoch })) {
                startedCapture = true
            }
        }
        runCurrent()
        epoch++ // Same invalidation performed by adapter.suspendRouting() on focus loss.
        actualReady = true
        signal.changed()
        waiting.await()
        assertFalse(startedCapture)
    }

    @Test fun actualRouteReadbackExceptionBecomesTimeoutNotUncaughtCoroutineFailure() = runTest {
        val signal = VoiceCallRouteConfirmation()
        val waiting = async { signal.awaitReady(isConfirmed = { throw SecurityException("synthetic route read denied") }) }
        runCurrent()
        signal.changed()
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(waiting.await())
    }
}
