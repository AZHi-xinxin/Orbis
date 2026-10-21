package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.foregroundChatIntent
import me.rerere.rikkahub.data.orbis.companiontools.orbisForegroundChat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Only synthetic chat IDs in an isolated ComponentActivity; never starts the real app route. */
@RunWith(AndroidJUnit4::class)
class ReportForegroundChatDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolated = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    @Test fun homeDrawerAndOldNavigationEntryCannotAcknowledgeCurrentChat() {
        val visible = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val target = mutableStateOf("synthetic-foreground-one")
        compose.setContent {
            CompositionLocalProvider(LocalOrbisChatAnimationVisible provides visible.value) {
                ReportForegroundChat(target.value, enabled.value)
            }
        }
        compose.waitUntil(10_000) { orbisForegroundChat.isVisible("synthetic-foreground-one") }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(orbisForegroundChat.isVisible("synthetic-foreground-one"))
        compose.runOnIdle { target.value = "synthetic-foreground-two" }
        compose.waitForIdle()
        assertFalse(orbisForegroundChat.isVisible("synthetic-foreground-two"))
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(10_000) { orbisForegroundChat.isVisible("synthetic-foreground-two") }
        assertFalse(orbisForegroundChat.isVisible("synthetic-foreground-one"))
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        assertFalse(orbisForegroundChat.isVisible("synthetic-foreground-two"))
    }

    @Test fun intentTargetsOnlyHostBoundChatAndReusesExistingActivity() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = foregroundChatIntent(target, "synthetic-bound-id")
        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        assertEquals("synthetic-bound-id", intent.getStringExtra("conversationId"))
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
    }

    @Test fun sameChatReturnClosesItsOpenDrawerWithoutRecreatingTheConversation() {
        val request = mutableStateOf<OrbisChatReturnRequest?>(null)
        var observedDrawer: DrawerState? = null
        var overlayClosures = 0
        compose.setContent {
            MaterialTheme {
                val drawer = rememberDrawerState(DrawerValue.Open)
                SideEffect { observedDrawer = drawer }
                CompositionLocalProvider(LocalOrbisChatReturnRequest provides request.value) {
                    HandleForegroundChatReturn("synthetic-target", drawer) { overlayClosures++ }
                    ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { Text("Synthetic drawer") } },
                        drawerState = drawer) { Text("Synthetic chat") }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(observedDrawer!!.isOpen)
        compose.runOnIdle { request.value = OrbisChatReturnRequest("another-target") }
        compose.waitForIdle()
        assertTrue(observedDrawer!!.isOpen)
        assertEquals(0, overlayClosures)
        compose.runOnIdle { request.value = OrbisChatReturnRequest("synthetic-target") }
        compose.waitUntil(10_000) { observedDrawer!!.isClosed }
        assertEquals(1, overlayClosures)
        compose.waitForIdle()
        assertEquals(1, overlayClosures)
    }
}
