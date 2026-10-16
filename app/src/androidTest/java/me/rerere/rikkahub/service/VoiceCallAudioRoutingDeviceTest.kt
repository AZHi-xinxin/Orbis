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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned emulator only: real Android routing callbacks, no recorder/player/network or user data. */
@RunWith(AndroidJUnit4::class)
class VoiceCallAudioRoutingDeviceTest {
    @Test fun speakerRecoversFromNormalModeUsingRealCallbacksWithoutAnyHeadphones() = runBlocking {
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
        val adapter = AndroidVoiceCallAudioRouting(audio, scope,
            mayUpdate = { active }, onWaiting = { waiting++ }, onReady = { ready++ },
            onUnavailable = { unavailable++ })
        try {
            withContext(Dispatchers.Main) {
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                assertTrue(audio.activeRecordingConfigurations.isEmpty())
                assertTrue(withTimeout(5_000) { adapter.start() })
                assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
                repeat(4) {
                    val before = ready
                    audio.mode = AudioManager.MODE_NORMAL // Synthetic system-mode loss, not an ASR error.
                    withTimeout(5_000) { while (ready == before) delay(20) }
                    assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
                    assertEquals(0, unavailable)
                }
                assertTrue(waiting >= 4)
                active = false
                adapter.close()
                val afterClose = ready
                delay(300) // Late mode/device callbacks cannot revive the ended call.
                assertEquals(afterClose, ready)
                assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                assertTrue(audio.activeRecordingConfigurations.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main) { active = false; adapter.close(); scope.cancel() }
        }
    }
}
