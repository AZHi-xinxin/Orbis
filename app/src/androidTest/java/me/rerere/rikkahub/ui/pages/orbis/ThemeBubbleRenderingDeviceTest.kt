package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.message.OrbisMessageBubble
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeBubbleRenderingDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun backgroundOpacityBlendsWithoutFadingContent() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.background(Color(0xFF204060))) {
                    listOf(0f, .55f, 1f).forEachIndexed { i, opacity ->
                        val bubble = ThemeBubble(bg = "#FFFFFF", text = "#112233", radius = 0, opacity = opacity, maxWidthRatio = 1f)
                        CompositionLocalProvider(LocalChatTheme provides ChatTheme(ThemePackage(name = "opacity", bubbles = ThemeBubbles(assistant = bubble)), null)) {
                            OrbisMessageBubble(false, OrbisAppearance(), Modifier.width(220.dp).testTag("opacity-$i")) {
                                Box(Modifier.fillMaxWidth().height(40.dp)) {
                                    Box(Modifier.size(8.dp).background(LocalContentColor.current).testTag("ink-$i"))
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf(0f, .55f, 1f).forEachIndexed { i, opacity ->
            val pixels = compose.onNodeWithTag("opacity-$i").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            listOf(color.red to 32f, color.green to 64f, color.blue to 96f).forEach { (actual, base) ->
                assertEquals(base / 255f * (1 - opacity) + opacity, actual, .015f)
            }
            val ink = compose.onNodeWithTag("ink-$i", useUnmergedTree = true).captureToImage().toPixelMap()
            val colorInk = ink[ink.width / 2, ink.height / 2]
            assertEquals(17 / 255f, colorInk.red, .01f)
            assertEquals(34 / 255f, colorInk.green, .01f)
            assertEquals(51 / 255f, colorInk.blue, .01f)
        }
    }

    @Test fun bothTailsStayInsideTheMessageAndMirrorWithRoleAndLayoutDirection() {
        val cases = listOf("cat", "curl").flatMap { tail ->
            listOf(false, true).flatMap { user -> listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).map { Triple(tail, user, it) } }
        }
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())) {
                    cases.forEachIndexed { i, (tail, user, direction) ->
                        val bubble = ThemeBubble(bg = "#FFFFFF", opacity = .55f, shape = "cat", tail = tail, maxWidthRatio = 1f)
                        CompositionLocalProvider(LocalLayoutDirection provides direction,
                            LocalChatTheme provides ChatTheme(ThemePackage(name = tail, bubbles = ThemeBubbles(bubble, bubble)), null)) {
                            OrbisMessageBubble(user, OrbisAppearance(), Modifier.width(220.dp).testTag("tail-$i")) {
                                Spacer(Modifier.fillMaxWidth().height(24.dp))
                            }
                        }
                    }
                }
            }
        }
        cases.forEachIndexed { i, (_, user, direction) ->
            val node = compose.onNodeWithTag("tail-$i").performScrollTo()
            val pixels = node.captureToImage().toPixelMap()
            val scale = pixels.width / 220f
            // Overlapping ear/body subpaths used to cut a transparent seam here.
            for (ear in listOf(.15f, .85f)) {
                val join = pixels[(pixels.width * ear).toInt(), (13.5f * scale).toInt()]
                assertEquals("continuous ear join", .55f, join.red, .02f)
            }
            var painted = 0
            val onRight = user == (direction == LayoutDirection.Ltr)
            for (y in (pixels.height - 16 * scale).toInt() until pixels.height) for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.red > .05f) {
                    painted++
                    assertTrue("tail at expected side", if (onRight) x > pixels.width / 2 else x < pixels.width / 2)
                    assertTrue("no second translucent layer", c.red < .58f)
                }
            }
            assertTrue("tail visible outside body", painted > 20)
            // Curves must finish before the measured lower edge rather than being clipped there.
            assertTrue((0 until pixels.width).all { pixels[it, pixels.height - 1].red < .05f })
        }
    }

    @Test fun captureThemeGalleryUsingTheRealChatRenderer() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFFE2EAF1)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("猫耳、尾巴与透光气泡")
                    listOf("none", "cat", "curl").forEach { tail ->
                        val a = ThemeBubble(shape = "cat", tail = tail, opacity = .55f, pattern = "stars", decoration = "paw")
                        val u = ThemeBubble(bg = "#E8DDEB", shape = "round", tail = tail, opacity = .8f)
                        CompositionLocalProvider(LocalChatTheme provides ChatTheme(ThemePackage(name = tail, bubbles = ThemeBubbles(a, u)), null)) {
                            OrbisMessageBubble(false, OrbisAppearance(), Modifier.fillMaxWidth()) { Text("$tail · 今天的风是透明的。") }
                            OrbisMessageBubble(true, OrbisAppearance(), Modifier.fillMaxWidth()) { Text("文字依然清楚，尾巴也在。") }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "theme-bubble-gallery.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
