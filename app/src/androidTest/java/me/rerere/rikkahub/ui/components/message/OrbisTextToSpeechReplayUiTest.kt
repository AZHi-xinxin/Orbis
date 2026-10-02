package me.rerere.rikkahub.ui.components.message

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.onSubscription
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.koinInject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production renderer with a composition-local synthetic event bus only.
 * No real App/RouteActivity, TTS engine, tool executor, database, preferences, or network.
 */
@RunWith(AndroidJUnit4::class)
class OrbisTextToSpeechReplayUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val bus = AppEventBus()
    private val events = CopyOnWriteArrayList<AppEvent>()
    private val collectorSubscribed = AtomicBoolean(false)
    private val statusBarInsetPx = AtomicInteger(0)
    private val touchActions = CopyOnWriteArrayList<Int>()
    private var segmented by mutableStateOf(true)
    private var generating by mutableStateOf(false)
    private var parts by mutableStateOf<List<UIMessagePart>>(emptyList())
    private var deletes = 0

    private fun speech(input: String = """{"text":"$FULL_TEXT"}""") = UIMessagePart.Tool(
        toolCallId = "synthetic-tts",
        toolName = "text_to_speech",
        input = input,
        output = listOf(UIMessagePart.Text("""{"success":true}""")),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(tool: UIMessagePart.Tool = speech(), initialSegmented: Boolean = true, loading: Boolean = false) {
        parts = listOf(tool)
        segmented = initialSegmented
        generating = loading
        val settings = Settings(init = true, providers = emptyList(), displaySetting = DisplaySetting(
            enableMessageGenerationHapticEffect = false,
        ))
        val isolatedModule = module { single { bus } }
        compose.setContent {
            // Koin 4.2's deprecated KoinApplication reuses the process-global
            // container across activity disposal. Each test must own its bus.
            val isolatedApplication = remember { koinApplication { modules(isolatedModule) } }
            DisposableEffect(isolatedApplication) {
                onDispose { isolatedApplication.close() }
            }
            KoinIsolatedContext(isolatedApplication) {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalNavController provides Navigator(mutableListOf()),
                ) {
                    val rendererBus: AppEventBus = koinInject()
                    SideEffect {
                        assertSame("Production renderer and collector must share this test's isolated bus", bus, rendererBus)
                    }
                    LaunchedEffect(bus) {
                        bus.events.onSubscription { collectorSubscribed.set(true) }
                            .collect { events.add(it) }
                    }
                    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
                    SideEffect { statusBarInsetPx.set(statusBarTop) }
                    MaterialTheme {
                        // API 36+ enforces edge-to-edge even for this bare test activity.
                        // Keep physical pointer input away from the system status bar.
                        Column(Modifier.fillMaxSize().safeDrawingPadding()
                            .motionEventSpy { touchActions.add(it.actionMasked) }
                            .verticalScroll(rememberScrollState())) {
                            MessagePartsBlock(
                                assistant = null, role = MessageRole.ASSISTANT, model = null,
                                parts = parts, annotations = emptyList(), loading = generating,
                                segmentedReply = segmented, messageKey = "synthetic-tts-replay",
                                onDeleteToolRecord = { deletes++ },
                                onToolApproval = { _, _, _, _ -> error("No approval should be invoked") },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { collectorSubscribed.get() }
    }

    private fun windowState(): String = compose.runOnIdle {
        val activity = compose.activity
        val decor = activity.window.decorView
        val power = activity.getSystemService(PowerManager::class.java)
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        "focus=${decor.hasWindowFocus()}, shown=${decor.isShown}, attached=${decor.isAttachedToWindow}, " +
            "interactive=${power.isInteractive}, keyguard=${keyguard.isKeyguardLocked}, " +
            "deviceLocked=${keyguard.isDeviceLocked}, displayState=${decor.display?.state}"
    }

    private fun awaitPhysicalWindow() {
        try {
            compose.waitUntil(5_000) { compose.runOnIdle { compose.activity.window.decorView.hasWindowFocus() } }
        } catch (failure: Throwable) {
            throw AssertionError("Synthetic activity never received window focus: ${windowState()}", failure)
        }
        Log.i("OrbisSyntheticTtsInput", "Before physical input: ${windowState()}")
    }

    @Test fun segmentedCollapsedSpeechShowsReplayWithoutExpandingSummaryOrJson() {
        show()
        compose.onAllNodesWithTag(REPLAY).assertCountEquals(1)
        compose.onNodeWithTag(REPLAY).assertIsDisplayed()
        compose.onNodeWithTag(SUMMARY).assertDoesNotExist()
        compose.onNodeWithTag("orbis-delete-tool-record").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_message_tool_call_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test fun wholeBubbleModeHasOneReplayAndKeepsItsTextSummary() {
        show(initialSegmented = false)
        compose.onAllNodesWithTag(REPLAY).assertCountEquals(1)
        compose.onNodeWithTag(SUMMARY).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test fun onePhysicalReplayTapEmitsFullTextOnceWithoutDeletingOrOpeningJson() {
        show()
        awaitPhysicalWindow()
        val replayBounds = compose.onNodeWithTag(REPLAY).fetchSemanticsNode().boundsInWindow
        assertTrue("Replay must be below the system status bar: bounds=$replayBounds, inset=${statusBarInsetPx.get()}",
            replayBounds.top >= statusBarInsetPx.get())
        assertTrue("Synthetic event collector must be subscribed before physical input", collectorSubscribed.get())
        compose.onNodeWithTag(REPLAY).performTouchInput { click() }
        try {
            compose.waitUntil(5_000) { events.size == 1 }
        } catch (failure: Throwable) {
            val summaryCount = compose.onAllNodesWithTag(SUMMARY).fetchSemanticsNodes().size
            val jsonCount = compose.onAllNodesWithText(compose.activity.getString(R.string.chat_message_tool_call_title))
                .fetchSemanticsNodes().size
            throw AssertionError("Physical replay tap did not emit exactly once: events=${events.size}, deletes=$deletes, " +
                "subscribed=${collectorSubscribed.get()}, summaryCount=$summaryCount, jsonCount=$jsonCount, " +
                "bounds=$replayBounds, statusBarInset=${statusBarInsetPx.get()}, touchActions=$touchActions, " +
                "window=[${windowState()}]", failure)
        }
        compose.onNodeWithTag(SUMMARY).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_message_tool_call_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(AppEvent.Speak(FULL_TEXT)), events.toList())
            assertEquals(0, deletes)
        }
        compose.onNodeWithTag("orbis-delete-tool-record").performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(1, events.size) }
    }

    @Test fun layoutSwitchingAndModelContinuationDoNotReplayAutomatically() {
        show(loading = true) // Production passes loading && !tool.isExecuted to the completed tool.
        compose.onNodeWithTag(REPLAY).assertIsDisplayed()
        compose.onNodeWithTag(SUMMARY).assertDoesNotExist()
        compose.runOnIdle { segmented = false }
        compose.onNodeWithTag(SUMMARY).assertIsDisplayed()
        compose.runOnIdle { segmented = true; generating = false }
        compose.onNodeWithTag(SUMMARY).assertDoesNotExist()
        compose.onAllNodesWithTag(REPLAY).assertCountEquals(1)
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test fun otherToolWithTextKeepsExistingCollapsedStateAndHasNoReplay() {
        show(speech().copy(toolName = "synthetic_other_tool"))
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.onNodeWithTag(SUMMARY).assertDoesNotExist()
        compose.onNodeWithTag("orbis-delete-tool-record").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test fun missingOrBlankTextCannotOfferReplay() {
        show(speech("{}"))
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.runOnIdle { parts = listOf(speech("""{"text":" \n\t "}""")) }
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, events.size) }
    }

    @Test fun pendingDeniedAndHostFailedCallsNeverOfferReplay() {
        show(speech().copy(output = emptyList(), approvalState = ToolApprovalState.Pending))
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.onNodeWithText("此次允许").assertIsDisplayed()
        compose.runOnIdle { parts = listOf(speech().copy(approvalState = ToolApprovalState.Pending)) }
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.runOnIdle { parts = listOf(speech().copy(approvalState = ToolApprovalState.Denied("synthetic denial"))) }
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.runOnIdle { parts = listOf(speech().copy(output = emptyList()).withHostToolFailure(HostToolFailure.INTERRUPTED)) }
        compose.onNodeWithTag(REPLAY).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, events.size); assertEquals(0, deletes) }
    }

    private companion object {
        const val REPLAY = "orbis-tts-replay"
        const val SUMMARY = "orbis-tts-summary"
        const val FULL_TEXT = "Synthetic playback body longer than the 24 character title preview; preserve every word."
    }
}
