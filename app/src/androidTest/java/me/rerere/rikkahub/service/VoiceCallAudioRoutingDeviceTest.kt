package me.rerere.rikkahub.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned emulator only: real Android routing callbacks, no recorder/player/network or user data. */
@RunWith(AndroidJUnit4::class)
class VoiceCallAudioRoutingDeviceTest {
    @Test fun speakerRecoversFromNormalModeUsingRealCallbacksWithoutAnyHeadphones() = verifyRecovery(muted = false)

    @Test fun mutedSpeakerIdleRecoveryDoesNotCancelPendingSynthesis() = verifyRecovery(muted = true)

    private fun verifyRecovery(muted: Boolean) = runBlocking {
        check(Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)) {
            "Synthetic audio routing test must not run on a person's phone"
        }
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var active = true
        var waiting = 0
        var ready = 0
        var unavailable = 0
        val modes = mutableListOf<String>()
        var observedMode: Int? = null
        val modeListener = AudioManager.OnModeChangedListener { mode ->
            observedMode = mode
            modes += "$mode/read=${audio.mode}/ready=$ready"
        }
        audio.addOnModeChangedListener({ command -> android.os.Handler(android.os.Looper.getMainLooper()).post(command); Unit }, modeListener)
        // Represents slow synthesis; no network, paid model call, recording or actual speech.
        val synthesis = scope.launch { awaitCancellation() }
        val adapter = AndroidVoiceCallAudioRouting(audio, scope,
            mayUpdate = { active }, microphoneMuted = { muted },
            onWaiting = { waiting++; synthesis.cancel() }, onReady = { ready++ },
            onUnavailable = { unavailable++ })
        try {
            withContext(Dispatchers.Main) {
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                assertTrue(audio.activeRecordingConfigurations.isEmpty())
                assertTrue(withTimeout(5_000) { adapter.start() })
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
                repeat(4) { iteration ->
                    // setMode/readback and mode-change callbacks are asynchronous. Do not
                    // issue the next synthetic loss before Android announced the previous
                    // recovery; otherwise the service coalesces our artificial mode flips.
                    withTimeout(5_000) {
                        while (observedMode != AudioManager.MODE_IN_COMMUNICATION) delay(20)
                    }
                    val before = ready
                    observedMode = null
                    audio.mode = AudioManager.MODE_NORMAL // Synthetic system-mode loss, not an ASR error.
                    val recovered = withTimeoutOrNull(5_000) { while (ready == before) delay(20); true } == true
                    assertTrue("muted=$muted iteration=$iteration mode=${audio.mode} ready=$ready waiting=$waiting unavailable=$unavailable events=$modes", recovered)
                    assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
                    assertEquals(0, unavailable)
                }
                if (muted) {
                    assertEquals(0, waiting)
                    assertTrue(synthesis.isActive)
                } else {
                    assertTrue(waiting >= 4)
                    assertTrue(synthesis.isCancelled)
                }
                active = false
                adapter.close()
                val afterClose = ready
                delay(300) // Late mode/device callbacks cannot revive the ended call.
                assertEquals(afterClose, ready)
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                assertTrue(audio.activeRecordingConfigurations.isEmpty())
            }
        } finally {
            audio.removeOnModeChangedListener(modeListener)
            withContext(Dispatchers.Main) { active = false; adapter.close(); scope.cancel() }
        }
    }
}
