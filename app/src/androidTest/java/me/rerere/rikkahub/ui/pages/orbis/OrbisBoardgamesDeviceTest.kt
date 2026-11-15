package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OrbisBoardgamesDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun allThreeGamesNavigateToTheirOwnRooms() {
        val choices = mutableListOf<String>(); var backs = 0
        compose.setContent { MaterialTheme { OrbisBoardgamesCatalog({ choices += it }, { backs++ }) } }
        screenshot("boardgames-top.png")
        compose.onNodeWithTag("boardgame-werewolf").performTouchInput { click() }
        compose.onNodeWithTag("boardgame-drawguess").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("boardgame-heartprison").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        screenshot("boardgames-heart.png")
        assertEquals(listOf("werewolf", "drawguess", "heartprison"), choices)
        compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        assertEquals(1, backs)
    }

    @Test fun largeFontCompactWindowKeepsEveryDestinationAndBackReachable() {
        val choices = mutableListOf<String>(); var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme { Box(Modifier.size(360.dp, 480.dp)) { OrbisBoardgamesCatalog({ choices += it }, { backs++ }) } }
            }
        }
        compose.onNodeWithTag("boardgame-werewolf").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("boardgame-drawguess").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("boardgame-heartprison").performScrollTo().assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        screenshot("boardgames-compact-large-font.png")
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performTouchInput { click() }
        assertEquals(listOf("werewolf", "drawguess", "heartprison"), choices)
        assertEquals(1, backs)
    }
}
