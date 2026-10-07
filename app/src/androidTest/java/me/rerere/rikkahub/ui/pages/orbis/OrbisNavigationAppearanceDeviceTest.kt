package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production visual components with synthetic colors only; no user store, media or app services. */
@RunWith(AndroidJUnit4::class)
class OrbisNavigationAppearanceDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun headerOnlyFadesItsBackgroundAndKeepsSelectedDayNightPalette() {
        val dark = mutableStateOf(false)
        val opacity = mutableStateOf(1f)
        val wallpaper = Color(0xFF54738B)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalOrbisSeason provides OrbisSeason.SPRING) {
                OrbisVisualTheme(darkTheme = dark.value) {
                    val colors = OrbisTheme.colors
                    Box(Modifier.size(120.dp).background(wallpaper), contentAlignment = Alignment.Center) {
                        OrbisHeaderControlSurface(opacity.value, Modifier.size(100.dp).testTag("header")) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(10.dp).background(colors.ink))
                            }
                        }
                    }
                }
            }
        } }
        for (isDark in listOf(false, true)) for (alpha in listOf(0f, .4f, 1f)) {
            compose.runOnIdle { dark.value = isDark; opacity.value = alpha }
            val colors = orbisSeasonColors(OrbisSeason.SPRING, isDark)
            val pixels = compose.onNodeWithTag("header").captureToImage().toPixelMap()
            assertColorNear(colors.raisedPanel.copy(alpha = alpha).compositeOver(wallpaper),
                pixels[pixels.width / 4, pixels.height / 2])
            assertColorNear(colors.ink, pixels[pixels.width / 2, pixels.height / 2])
        }
    }

    @Test fun everySeasonDockAndHomeButtonUseTheirOwnPaletteInBothModes() {
        val season = mutableStateOf(OrbisSeason.SPRING)
        val dark = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalOrbisSeason provides season.value, LocalOrbisDeepSeekStyle provides false) {
                OrbisVisualTheme(darkTheme = dark.value) {
                    OrbisBottomDock("Synthetic", {}, {}, modifier = Modifier.width(340.dp).testTag("dock"),
                        windowInsets = WindowInsets(0, 0, 0, 0))
                }
            }
        } }
        for (isDark in listOf(false, true)) for (selected in OrbisSeason.entries.filter { it != OrbisSeason.NONE }) {
            compose.runOnIdle { season.value = selected; dark.value = isDark }
            val colors = orbisSeasonColors(selected, isDark)
            val pixels = compose.onNodeWithTag("dock").captureToImage().toPixelMap()
            assertColorNear(colors.dock, pixels[(pixels.width * .12f).toInt(), (pixels.height * .68f).toInt()])
            // Inside the home circle, above the star: this was the fixed blue gradient.
            assertColorNear(colors.homeButton, pixels[pixels.width / 2, (pixels.height * .10f).toInt()])
            assertColorNear(colors.star, pixels[pixels.width / 2, (pixels.height * .40f).toInt()])
        }
    }

    @Test fun illustratedAndLineIconTilesShareSizeAndTextAlignmentAndKeepActions() {
        val destinations = listOf(OrbisToolDestination.GAMES, OrbisToolDestination.STICKERS, OrbisToolDestination.SECRET_BASE)
        val entries = destinations.map { destination -> orbisToolEntries.first { it.destination == destination } }
        val opened = mutableListOf<OrbisToolDestination>()
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            Row(Modifier.width(360.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { entry ->
                    ToolsTile(entry, enabled = true, Modifier.weight(1f).testTag(entry.destination.name)) {
                        opened.add(entry.destination)
                    }
                }
            }
        } } }
        val bounds = destinations.map { compose.onNodeWithTag(it.name).fetchSemanticsNode().boundsInRoot }
        bounds.drop(1).forEach { assertEquals(bounds.first().height, it.height, .5f) }
        val titleTop = entries.map { compose.onNodeWithText(it.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        titleTop.drop(1).forEach { assertEquals(titleTop.first(), it, .5f) }
        val subtitleTop = entries.map { compose.onNodeWithText(it.subtitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        subtitleTop.drop(1).forEach { assertEquals(subtitleTop.first(), it, .5f) }
        destinations.forEach { compose.onNodeWithTag(it.name).performClick() }
        compose.runOnIdle { assertEquals(destinations, opened) }
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .025f)
        assertEquals(expected.green, actual.green, .025f)
        assertEquals(expected.blue, actual.blue, .025f)
    }
}
