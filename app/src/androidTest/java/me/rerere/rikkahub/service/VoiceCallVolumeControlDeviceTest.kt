package me.rerere.rikkahub.service

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.Window
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.ai.BindVoiceCallDialogVolumeStream
import me.rerere.rikkahub.ui.components.ai.BindVoiceCallVolumeStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Only synthetic Activity/Dialog windows; no call, audio focus, speaker, volume-index or TTS changes. */
@RunWith(AndroidJUnit4::class)
class VoiceCallVolumeControlDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun mergedManifestContainsNormalAudioSettingsPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(permissions.orEmpty().contains(Manifest.permission.MODIFY_AUDIO_SETTINGS))
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS))
    }

    @Test fun collapsedCallBindsActivityKeysAndEndingRestoresItsPreviousStream() {
        val active = mutableStateOf(false)
        compose.runOnUiThread { compose.activity.window.volumeControlStream = AudioManager.STREAM_MUSIC }
        compose.setContent { BindVoiceCallVolumeStream(compose.activity.window, active.value); Text("Synthetic call window") }
        compose.runOnIdle { assertEquals(AudioManager.STREAM_MUSIC, compose.activity.window.volumeControlStream); active.value = true }
        compose.runOnIdle { assertEquals(AudioManager.STREAM_VOICE_CALL, compose.activity.window.volumeControlStream); active.value = false }
        compose.runOnIdle { assertEquals(AudioManager.STREAM_MUSIC, compose.activity.window.volumeControlStream) }
    }

    @Test fun fullScreenCallUsesItsOwnDialogWindowThenReturnsToTheStillActiveActivityBinding() {
        val active = mutableStateOf(true)
        val fullScreen = mutableStateOf(true)
        val dialog = AtomicReference<Window?>()
        val previousDialogStream = AtomicReference<Int?>()
        compose.runOnUiThread { compose.activity.window.volumeControlStream = AudioManager.STREAM_MUSIC }
        compose.setContent {
            BindVoiceCallVolumeStream(compose.activity.window, active.value)
            Text("Synthetic minimized call surface")
            if (active.value && fullScreen.value) Dialog(onDismissRequest = { fullScreen.value = false }) {
                val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                val previous = remember(window) { window?.volumeControlStream }
                BindVoiceCallDialogVolumeStream()
                SideEffect { dialog.set(window); previousDialogStream.set(previous) }
                Text("Synthetic voice/video full-screen window")
            }
        }
        compose.runOnIdle {
            assertNotNull(dialog.get())
            assertNotSame(compose.activity.window, dialog.get())
            assertEquals(AudioManager.STREAM_VOICE_CALL, dialog.get()!!.volumeControlStream)
            assertEquals(AudioManager.STREAM_VOICE_CALL, compose.activity.window.volumeControlStream)
            fullScreen.value = false
        }
        compose.runOnIdle {
            assertEquals(AudioManager.STREAM_VOICE_CALL, compose.activity.window.volumeControlStream)
            assertEquals(previousDialogStream.get(), dialog.get()!!.volumeControlStream)
            active.value = false
        }
        compose.runOnIdle { assertEquals(AudioManager.STREAM_MUSIC, compose.activity.window.volumeControlStream) }
    }

    @Test fun disposingBindingDoesNotOverwriteANewerExplicitWindowStream() {
        val active = mutableStateOf(true)
        compose.runOnUiThread { compose.activity.window.volumeControlStream = AudioManager.STREAM_MUSIC }
        compose.setContent { BindVoiceCallVolumeStream(compose.activity.window, active.value); Text("Synthetic call window") }
        compose.runOnIdle {
            assertEquals(AudioManager.STREAM_VOICE_CALL, compose.activity.window.volumeControlStream)
            compose.activity.window.volumeControlStream = AudioManager.STREAM_ALARM
            active.value = false
        }
        compose.runOnIdle { assertEquals(AudioManager.STREAM_ALARM, compose.activity.window.volumeControlStream) }
    }
}
