package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbisStopPlaybackControlTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun explicitControlIsDisabledWhileIdleAndTargetsOnlyItsVisiblePlayback() {
        var token: String? by mutableStateOf(null)
        val stopped = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            OrbisStopPlaybackControl(token, Color.Black) { stopped += it; token = null }
        } }
        compose.onNodeWithTag("orbis-call-stop-playback").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { token = "first" }
        compose.onNodeWithContentDescription("停止本条播报，保留文字，不挂断通话").assertIsEnabled().performClick()
        compose.onNodeWithTag("orbis-call-stop-playback").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("first"), stopped); token = "next" }
        compose.onNodeWithTag("orbis-call-stop-playback").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("first", "next"), stopped) }
    }
}
