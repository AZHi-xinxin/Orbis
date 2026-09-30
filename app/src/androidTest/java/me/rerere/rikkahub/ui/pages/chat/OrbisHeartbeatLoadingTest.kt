package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Canvas and clock only. No real app, settings, network, or generation. */
@RunWith(AndroidJUnit4::class)
class OrbisHeartbeatLoadingTest {
    @get:Rule val compose = createShellComposeRule()

    private fun capture() = compose.onNodeWithTag("orbis-heartbeat-canvas", useUnmergedTree = true)
        .captureToImage().toPixelMap()
    private fun changed(a: PixelMap, b: PixelMap): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) count++
        return count
    }

    @Test fun advancingAnimationClockChangesTheEcgFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { OrbisHeartbeatIndicator(true, true) } }
        compose.mainClock.advanceTimeByFrame()
        val first = capture()
        compose.mainClock.advanceTimeBy(900)
        assertTrue("The sweep must visibly move", changed(first, capture()) > 8)
    }

    @Test fun reducedMotionIsStaticButKeepsReadableWaitingStatus() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            OrbisHeartbeatIndicator(true, shouldAnimateOrbisHeartbeat(true, true, true, true))
        } }
        compose.mainClock.advanceTimeByFrame()
        val first = capture()
        compose.mainClock.advanceTimeBy(900)
        assertEquals(0, changed(first, capture()))
        compose.onNodeWithContentDescription("等待回复").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun endingWaitingRemovesTheIndicatorAndTouchHasNoAction() {
        val waiting = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (waiting.value) OrbisHeartbeatIndicator(true, false)
        } }
        compose.onNodeWithContentDescription("等待回复").assertHasNoClickAction().performTouchInput { click() }
        compose.onNodeWithContentDescription("等待回复").assertIsDisplayed()
        compose.runOnIdle { waiting.value = false }
        compose.onNodeWithContentDescription("等待回复").assertDoesNotExist()
    }

    @Test fun hiddenIndicatorUnmountsAndNarrowParentIsRespected() {
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            Box(Modifier.width(120.dp)) { OrbisHeartbeatIndicator(visible.value, false) }
        } }
        compose.onNodeWithContentDescription("等待回复").assertIsDisplayed().assertWidthIsEqualTo(120.dp)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithContentDescription("等待回复").assertDoesNotExist()
    }
}
