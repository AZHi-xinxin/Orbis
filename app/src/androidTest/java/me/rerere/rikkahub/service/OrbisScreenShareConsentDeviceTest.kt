package me.rerere.rikkahub.service

import android.content.ComponentName
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.activity.OrbisScreenShareActivity
import me.rerere.rikkahub.ui.pages.orbis.OrbisScreenShareInvitation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class OrbisScreenShareConsentDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun invitationRequiresHumanAcceptanceAndDeclineCannotCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = OrbisScreenShareRuntime.get(context)
        val owner = Uuid.random().toString()
        val conversation = Uuid.random().toString()
        compose.setContent { MaterialTheme { OrbisScreenShareInvitation() } }
        compose.runOnIdle { runtime.invite(owner, conversation, "一起看合成测试画面") }
        compose.onNodeWithText("一起看屏幕？").assertExists()
        compose.onNodeWithText("接受").assertExists()
        compose.runOnIdle {
            assertNull(runtime.state.value.sessionId)
            assertFalse(runtime.permits(owner, conversation, "invented-session"))
            assertNull(runtime.acceptInvitation("unrelated-request"))
        }
        compose.onNodeWithText("暂时不用").performClick()
        compose.runOnIdle {
            assertNull(runtime.invitation.value)
            assertNull(runtime.state.value.sessionId)
            runtime.stop()
            assertNull(runtime.state.value.sessionId)
        }
    }

    @Test fun systemProjectionEntryIsPrivateAndIntentDoesNotCarryProjectionOrMicrophoneAuthority() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = OrbisScreenShareActivity.intent(context, "synthetic-owner", "synthetic-conversation")
        assertFalse(intent.hasExtra("projection"))
        assertFalse(intent.getBooleanExtra("microphone", false))
        val activity = context.packageManager.getActivityInfo(ComponentName(context, OrbisScreenShareActivity::class.java), 0)
        assertFalse(activity.exported)
        val service = context.packageManager.getServiceInfo(ComponentName(context, OrbisScreenShareService::class.java), 0)
        assertFalse(service.exported)
        assertTrue(service.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
    }
}
