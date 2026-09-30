package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure in-memory UI. No SettingsStore, user preferences, permission requests or network. */
@RunWith(AndroidJUnit4::class)
class OrbisChatPreferencesGridTest {
    @get:Rule val compose = createShellComposeRule()
    private var display by mutableStateOf(DisplaySetting())
    private var startNew by mutableStateOf(true)
    private var suggestionsEnabled by mutableStateOf(true)
    private var writes = 0

    private fun show(enabled: Boolean = true, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                OrbisVisualTheme(darkTheme = false) {
                    Box(Modifier.requiredWidth(390.dp).requiredHeight(440.dp)) {
                        OrbisChatPreferencesGrid(display, startNew, enabled,
                            onStartNew = { startNew = it; writes++ },
                            onToggle = { key, checked -> display = display.withOrbisPreference(key, checked); writes++ },
                            onLongTextThreshold = { display = display.copy(pasteLongTextThreshold = it); writes++ },
                            onVolumeRatio = { display = display.copy(volumeKeyScrollRatio = it); writes++ },
                            suggestionsEnabled = suggestionsEnabled,
                            onSuggestionsChange = { suggestionsEnabled = it; writes++ })
                    }
                }
            }
        }
    }

    @Test fun cardsUseTwoColumnsWithTheStarBelowText() {
        show()
        val first = compose.onNodeWithTag("chat-preference-start-new").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("chat-preference-suggestions").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.left)
        val label = compose.onNodeWithText("AI 建议回复", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val star = compose.onNodeWithTag("chat-preference-star-AI 建议回复", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The star belongs below the text", star.top >= label.bottom)
        compose.onNodeWithTag("chat-preference-suggestions").assertIsOn()
        assertEquals(0, writes)
    }

    @Test fun tileClickChangesOneValueOnceAndDisabledTileDoesNothing() {
        show()
        compose.onNodeWithTag("orbis-chat-preferences-grid").performScrollToNode(hasTestTag("chat-preference-USAGE"))
        compose.onNodeWithTag("chat-preference-USAGE").performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertFalse(display.showTokenUsage)
            assertFalse(display.sendOnEnter)
            assertTrue(startNew)
        }
    }

    @Test fun disabledGridCannotToggle() {
        show(enabled = false)
        compose.onNodeWithTag("chat-preference-suggestions").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, writes); assertTrue(suggestionsEnabled) }
    }

    @Test fun suggestionsTileChangesOnlyThePersistentPreferenceAndIsReversible() {
        val originalDisplay = display
        show()
        compose.onNodeWithTag("chat-preference-suggestions").performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(suggestionsEnabled)
            assertEquals(1, writes)
            assertEquals(originalDisplay, display)
            assertTrue(startNew)
        }
        compose.onNodeWithTag("chat-preference-suggestions").performClick().assertIsOn()
        compose.runOnIdle { assertTrue(suggestionsEnabled); assertEquals(2, writes) }
    }

    @Test fun shortViewportRespondsToSwipeAndCanReachTheEnd() {
        show()
        val grid = compose.onNodeWithTag("orbis-chat-preferences-grid")
        val before = compose.onNodeWithTag("chat-preference-start-new").fetchSemanticsNode().boundsInRoot.top
        grid.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val cards = compose.onAllNodesWithTag("chat-preference-start-new").fetchSemanticsNodes()
        assertTrue("User swipe must move the grid", cards.isEmpty() || cards.first().boundsInRoot.top < before)
        grid.performScrollToNode(hasTestTag("chat-preferences-end"))
        compose.onNodeWithTag("chat-preferences-end").assertIsDisplayed()
        assertEquals(0, writes)
    }

    @Test fun largeFontIsSingleColumnAndConditionalSlidersRemainReachable() {
        display = display.copy(pasteLongTextAsFile = true, enableVolumeKeyScroll = true)
        show(fontScale = 1.6f)
        val first = compose.onNodeWithTag("chat-preference-start-new").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("orbis-chat-preferences-grid")
        grid.performScrollToNode(hasTestTag("chat-preference-USAGE"))
        val second = compose.onNodeWithTag("chat-preference-USAGE").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 1f)
        grid.performScrollToNode(hasText("长文转附件门槛"))
        compose.onNodeWithText("长文转附件门槛").assertIsDisplayed()
        grid.performScrollToNode(hasText("音量键滚动距离"))
        compose.onNodeWithText("音量键滚动距离").assertIsDisplayed()
        grid.performScrollToNode(hasTestTag("chat-preferences-end"))
        compose.onNodeWithTag("chat-preferences-end").assertIsDisplayed()
        assertEquals(0, writes)
    }
}
