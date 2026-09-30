package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Synthetic shell only: never launches RouteActivity, services, user chats or a model. */
@RunWith(AndroidJUnit4::class)
class OrbisStartupSceneTest {
    // Reports a bounded failure; interruption does not replace the process watchdog.
    @get:Rule(order = 0) val timeout = Timeout.seconds(45)
    @get:Rule(order = 1) val compose = createShellComposeRule()

    @Test fun staticSceneHasBrandButNoPrematureSlowWarning() {
        compose.setContent { MaterialTheme { OrbisStartupScene(animate = false) } }
        compose.onNodeWithText("ORBiS").assertIsDisplayed()
        compose.onNodeWithText("正在点亮星图").assertIsDisplayed()
        compose.onNodeWithText("进入界面").assertDoesNotExist()
    }

    @Test fun slowExitButtonOnlyInvokesTheGivenCallback() {
        var calls = 0
        compose.setContent { MaterialTheme {
            OrbisStartupScene(animate = false, slowLoading = true, onContinue = { calls++ })
        } }
        compose.onNodeWithText("进入界面").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun reducedMotionSceneDoesNotChangeWithTheClock() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            OrbisStartupScene(Modifier.testTag("static-startup"), animate = false)
        } }
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onNodeWithTag("static-startup").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(900)
        val after = compose.onNodeWithTag("static-startup").captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before[x, y] != after[x, y]) changed++
        }
        assertEquals(0, changed)
    }

    @Test fun slowExitRemainsReachableOnShortScreens() {
        var exited = false
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 260.dp)) {
                OrbisStartupScene(animate = false, slowLoading = true, onContinue = { exited = true })
            }
        } }
        compose.onNodeWithText("进入界面").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(exited) }
    }

    @Test fun finishedSceneUnmountsWithoutOpeningAnything() {
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible.value) OrbisStartupScene(animate = false)
        } }
        compose.onNodeWithText("ORBiS").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("ORBiS").assertDoesNotExist()
    }

    @Test fun coverBlocksUnderlyingTouchesButExitButtonStillWorks() {
        compose.mainClock.autoAdvance = false
        var underlyingClicks = 0
        val state = OrbisStartupUiState(true, "synthetic", startedAtMillis = 0L).apply { slow = true }
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { underlyingClicks++ })
                OrbisStartupCover(state)
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.runOnIdle { assertEquals(0, underlyingClicks); assertTrue(state.visible) }
        compose.onNodeWithText("进入界面").performTouchInput { click() }
        compose.runOnIdle { assertFalse(state.visible); assertEquals(0, underlyingClicks) }
        // The frozen clock must deliver the dismiss recomposition before the next hit test.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("ORBiS").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        compose.runOnIdle { assertEquals(1, underlyingClicks) }
    }

    @Test fun shortScreenCoverRetainsItsScrollableExit() {
        val state = OrbisStartupUiState(true, "synthetic", startedAtMillis = 0L).apply { slow = true }
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 260.dp)) { OrbisStartupCover(state) }
        } }
        compose.onNodeWithText("进入界面").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertFalse(state.visible) }
    }
}
