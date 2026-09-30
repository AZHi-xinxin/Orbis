package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.OrbisActionMarker
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.setting.components.OrbisChatFlowSettingsEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** UI state fixtures only: no settings store, private chats, permissions, tools or network. */
@RunWith(AndroidJUnit4::class)
class OrbisChatFlowSettingsEditorTest {
    private val compose = createShellComposeRule()
    private val state = mutableStateOf(OrbisChatFlowSettings())
    private val isolation = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolation).around(compose)

    private fun show(initial: OrbisChatFlowSettings = OrbisChatFlowSettings()) {
        state.value = initial
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OrbisChatFlowSettingsEditor(value = state.value, onChange = { state.value = it })
                }
            }
        }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test fun defaultHasParagraphsButNoActionDistinctionOrFolding() {
        show()
        compose.onNodeWithTag("chat-flow-enabled").assertIsOn()
        compose.onNodeWithTag("chat-flow-no-actions").assertIsOn()
        compose.onNodeWithTag("chat-flow-collapse-actions").assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { assertEquals(OrbisChatFlowSettings(), state.value) }
    }

    @Test fun explicitMarkerSelectsDimmedVisibleActionsThenIndependentSwitchFolds() {
        show()
        click("chat-flow-marker-FULLWIDTH_ROUND")
        compose.onNodeWithTag("chat-flow-no-actions").assertIsOff()
        compose.onNodeWithTag("chat-flow-collapse-actions").assertIsEnabled().assertIsOff()
        compose.onNodeWithTag("chat-flow-status").assertTextContains("默认淡显", substring = true)
        click("chat-flow-collapse-actions")
        compose.runOnIdle { assertTrue(state.value.foldActions) }
        click("chat-flow-collapse-actions")
        compose.runOnIdle {
            assertFalse(state.value.foldActions)
            assertEquals(setOf(OrbisActionMarker.FULLWIDTH_ROUND), state.value.effectiveMarkers)
        }
    }

    @Test fun noDistinctionOverridesButDoesNotEraseMultiSelectedAndFoldedPreferences() {
        val selected = OrbisChatFlowSettings(distinguishActions = true,
            markers = setOf(OrbisActionMarker.SQUARE, OrbisActionMarker.DOUBLE_CORNER), collapseActions = true)
        show(selected)
        click("chat-flow-no-actions")
        compose.onNodeWithTag("chat-flow-collapse-actions").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(selected.markers, state.value.markers)
            assertTrue(state.value.collapseActions)
            assertTrue(state.value.effectiveMarkers.isEmpty())
        }
        click("chat-flow-no-actions")
        compose.runOnIdle { assertEquals(selected, state.value) }
    }

    @Test fun noMarkerIsSilentlySelectedWhenTurningRecognitionOn() {
        show()
        click("chat-flow-no-actions")
        compose.onNodeWithTag("chat-flow-status").assertTextContains("请选择至少一种格式", substring = true)
        compose.onNodeWithTag("chat-flow-collapse-actions").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(state.value.markers.isEmpty()) }
    }

    @Test fun layoutOffAndOnRestoresAllPreferences() {
        val selected = OrbisChatFlowSettings(distinguishActions = true,
            markers = setOf(OrbisActionMarker.TORTOISE), collapseActions = true)
        show(selected)
        click("chat-flow-enabled")
        compose.onNodeWithTag("chat-flow-no-actions").assertIsNotEnabled()
        compose.onNodeWithTag("chat-flow-marker-TORTOISE").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(selected.copy(enabled = false), state.value)
            assertFalse(state.value.foldActions)
        }
        click("chat-flow-enabled")
        compose.runOnIdle { assertEquals(selected, state.value) }
    }
}
