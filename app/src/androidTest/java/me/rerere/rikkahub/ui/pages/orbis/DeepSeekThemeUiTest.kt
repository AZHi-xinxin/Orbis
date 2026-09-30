package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.ai.OrbisComposerTab
import me.rerere.rikkahub.ui.pages.chat.OrbisHeartbeatIndicator
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Stateless synthetic presentation; no real settings, models, routes or user data. */
@RunWith(AndroidJUnit4::class)
class DeepSeekThemeUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun nestedScopesKeepDeepSeekPaletteAcrossLightDarkAndOriginalSwitches() {
        val deepSeek = mutableStateOf(true)
        val dark = mutableStateOf(false)
        var observed: OrbisColors? = null
        var surface = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOrbisDeepSeekStyle provides deepSeek.value,
                    LocalDarkMode provides dark.value) {
                    OrbisVisualTheme {
                        OrbisVisualTheme {
                            val colors = OrbisTheme.colors
                            val currentSurface = MaterialTheme.colorScheme.surfaceBright
                            SideEffect { observed = colors; surface = currentSurface }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertSame(OrbisPalette.DeepSeekLight, observed)
            assertEquals(OrbisPalette.DeepSeekLight.raisedPanel, surface)
            dark.value = true
        }
        compose.runOnIdle {
            assertSame(OrbisPalette.DeepSeekDark, observed)
            assertEquals(OrbisPalette.DeepSeekDark.raisedPanel, surface)
            deepSeek.value = false
        }
        compose.runOnIdle { assertSame(OrbisPalette.Dark, observed) }
    }

    @Test fun deepSeekDockPreservesNavigationHitAreasPositionsAndCallbacks() {
        val deepSeek = mutableStateOf(false)
        var homeClicks = 0
        var navigationClicks = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOrbisDeepSeekStyle provides deepSeek.value) {
                    OrbisVisualTheme(darkTheme = false) {
                        Box(Modifier.width(320.dp)) {
                            OrbisBottomDock("当前聊天", onHome = { homeClicks++ },
                                onOpenNavigation = { navigationClicks++ }, windowInsets = WindowInsets(0, 0, 0, 0))
                        }
                    }
                }
            }
        }
        val home = compose.onNodeWithContentDescription("返回 Orbis 主页")
        val navigation = compose.onNodeWithContentDescription("打开北斗导航")
        val homeBounds = home.fetchSemanticsNode().boundsInRoot
        val navigationBounds = navigation.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { deepSeek.value = true }
        home.assertIsDisplayed()
        navigation.assertIsDisplayed()
        assertEquals(homeBounds, home.fetchSemanticsNode().boundsInRoot)
        assertEquals(navigationBounds, navigation.fetchSemanticsNode().boundsInRoot)
        home.performClick()
        navigation.performClick()
        compose.runOnIdle { assertEquals(1, homeClicks); assertEquals(1, navigationClicks) }
    }

    @Test fun selectedComposerTabUsesBlueAndRetainsItsClickAction() {
        val selected = mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOrbisDeepSeekStyle provides true) {
                    OrbisVisualTheme(darkTheme = false) {
                        Box(Modifier.testTag("ds-tab")) {
                            OrbisComposerTab("思考", selected.value) { clicks++ }
                        }
                    }
                }
            }
        }
        fun capture() = compose.onNodeWithTag("ds-tab").captureToImage().toPixelMap()
        assertTrue(countColor(capture(), OrbisPalette.DeepSeekLight.accent) > 20)
        compose.onNodeWithText("思考").performClick()
        compose.runOnIdle { assertEquals(1, clicks); selected.value = false }
        assertEquals(0, countColor(capture(), OrbisPalette.DeepSeekLight.accent))
    }

    @Test fun deepSeekHeartbeatIsBlueWhileOriginalPeachAndGeometryRemainAvailable() {
        val deepSeek = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOrbisDeepSeekStyle provides deepSeek.value) {
                    OrbisVisualTheme(darkTheme = false) {
                        OrbisHeartbeatIndicator(visible = true, animate = false)
                    }
                }
            }
        }
        fun capture() = compose.onNodeWithTag("orbis-heartbeat-canvas", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        val blueFrame = capture()
        assertTrue(countColor(blueFrame, OrbisPalette.DeepSeekLight.accent) > 8)
        assertEquals(0, countColor(blueFrame, Color(0xFFE8995C)))
        compose.runOnIdle { deepSeek.value = false }
        val originalFrame = capture()
        assertEquals(blueFrame.width, originalFrame.width)
        assertEquals(blueFrame.height, originalFrame.height)
        assertTrue(countColor(originalFrame, Color(0xFFE8995C)) > 8)
    }

    private fun countColor(pixels: PixelMap, target: Color): Int {
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (abs(pixel.red - target.red) < .005f && abs(pixel.green - target.green) < .005f &&
                abs(pixel.blue - target.blue) < .005f) count++
        }
        return count
    }
}
