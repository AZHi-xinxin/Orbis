package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic pixels only. The shell rule requires IsolatedGenerationLoopRunner;
 * no real Application, settings, background image, permission or service is used.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class OrbisStatusBarScrimTest {
    @get:Rule val compose = createShellComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // Use the same Surface -> direct child relationship as ChatPage,
                        // with deterministic screen dimensions and synthetic status insets.
                        Surface(
                            modifier = Modifier.requiredSize(WIDTH.dp, HEIGHT.dp).testTag(SURFACE),
                            color = Color.White,
                            content = content,
                        )
                    }
                }
            }
        }
    }

    private fun pixels(): PixelMap =
        compose.onNodeWithTag(SURFACE).captureToImage().toPixelMap().also {
            assertEquals(WIDTH, it.width)
            assertEquals(HEIGHT, it.height)
        }

    private fun assertWhite(color: Color, message: String) {
        assertEquals(message, 1f, color.red, .02f)
        assertEquals(message, 1f, color.green, .02f)
        assertEquals(message, 1f, color.blue, .02f)
        assertEquals(message, 1f, color.alpha, .02f)
    }

    private fun assertDark(color: Color, message: String) {
        assertTrue(message, color.red in 0.40f..0.61f)
        assertEquals(message, color.red, color.green, .02f)
        assertEquals(message, color.red, color.blue, .02f)
        assertEquals(message, 1f, color.alpha, .02f)
    }

    private fun assertRowsUnchanged(image: PixelMap, firstRow: Int = 1) {
        for (y in firstRow until HEIGHT - 1) {
            for (x in intArrayOf(1, WIDTH / 2, WIDTH - 2)) {
                assertWhite(image[x, y], "Unexpected scrim outside status area at ($x, $y)")
            }
        }
    }

    @Test
    fun visibleScrimDarkensOnlyTheTopInsetInsideSurface() {
        show { OrbisStatusBarScrim(visible = true, insets = WindowInsets(top = INSET)) }
        val image = pixels()
        for (x in intArrayOf(1, WIDTH / 2, WIDTH - 2)) {
            assertDark(image[x, 3], "The top of the status area must be shaded")
            assertDark(image[x, INSET - 4], "The bottom of the status area must be shaded")
        }
        assertTrue("The original scrim gradient stays darker at the top",
            image[WIDTH / 2, 3].red < image[WIDTH / 2, INSET - 4].red)
        assertRowsUnchanged(image, firstRow = INSET + 1)
    }

    @Test
    fun hidingTheScrimRemovesAllOfItsPixels() {
        var visible by mutableStateOf(true)
        show { OrbisStatusBarScrim(visible = visible, insets = WindowInsets(top = INSET)) }
        assertDark(pixels()[WIDTH / 2, 3], "Fixture starts with a visible top scrim")
        compose.runOnIdle { visible = false }
        assertRowsUnchanged(pixels())
    }

    @Test
    fun zeroStatusInsetDoesNotDarkenTheScreen() {
        show { OrbisStatusBarScrim(visible = true, insets = WindowInsets(top = 0)) }
        assertRowsUnchanged(pixels())
    }

    @Test
    fun legacyDirectInsetChildReproducesCenteredStripeUnderSurfaceConstraints() {
        // Passing control fixture: record the actual old placement failure rather
        // than relying only on a screenshot or a hypothetical measurement model.
        show {
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets(top = INSET))
                    .background(Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .56f), Color.Black.copy(alpha = .44f)),
                    )),
            )
        }
        val image = pixels()
        assertWhite(image[WIDTH / 2, 3], "Legacy scrim misses the status area")
        assertDark(image[WIDTH / 2, HEIGHT / 2], "Legacy scrim is centered by parent size coercion")
        assertWhite(image[WIDTH / 2, HEIGHT - 3], "Legacy control has no bottom scrim")
    }

    private companion object {
        const val SURFACE = "synthetic-scrim-surface"
        const val WIDTH = 240
        const val HEIGHT = 400
        const val INSET = 36
    }
}
