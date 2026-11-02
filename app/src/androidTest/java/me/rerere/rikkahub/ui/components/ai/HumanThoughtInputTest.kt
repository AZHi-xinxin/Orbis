package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.humanThoughtPart
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.uuid.Uuid

class HumanThoughtInputTest {
    @get:Rule val compose = createShellComposeRule()
    private val input = ChatInputState()
    private val opacity = mutableStateOf(.4f)
    private val wallpaper = mutableStateOf(Color(0xFF164867))
    private val panel = Color(0xFF10233D)

    private fun show(fontScale: Float = 1f) {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.width(320.dp).background(wallpaper.value).padding(12.dp)) {
                    ChatComposerSurface(
                        modifier = Modifier.fillMaxWidth().testTag("thought-composer"),
                        shape = RoundedCornerShape(23.dp),
                        color = panel.copy(alpha = opacity.value), contentColor = Color.White,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .3f)), orbis = true,
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            HumanThoughtInput(input)
                            TextField(state = input.textContent,
                                modifier = Modifier.fillMaxWidth().testTag("body-input"),
                                placeholder = { Text("输入消息，与我聊聊…") },
                                colors = chatInputFieldColors(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        } }
    }

    private fun capture(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        java.io.File(dir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun foldingRetainsDraftAndSendingResetsToCompactWithoutChangingPayload() {
        show()
        compose.onNodeWithTag("human-thought-editor").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("human-thought-input").assertDoesNotExist()
        compose.onNodeWithTag("body-input").assertIsDisplayed()
        capture("thought-input-empty-collapsed.png")
        compose.onNodeWithTag("body-input").performTextInput("一起听一首歌吧")
        compose.onNodeWithTag("human-thought-input-toggle").performClick()
        compose.onNodeWithTag("human-thought-input").performTextInput("想把今天的心绪写在这里")
        capture("thought-input-expanded.png")
        compose.onNodeWithTag("human-thought-input-toggle").performClick()
        compose.onNodeWithTag("human-thought-input").assertDoesNotExist()
        compose.onNodeWithText("已填写").assertIsDisplayed()
        capture("thought-input-filled-collapsed.png")
        val expected = listOf(humanThoughtPart("想把今天的心绪写在这里"), UIMessagePart.Text("一起听一首歌吧"))
        compose.runOnIdle { assertEquals(expected, input.getContents(true)) }
        compose.onNodeWithTag("human-thought-input-toggle").performClick()
        compose.onNodeWithTag("human-thought-input").assertTextContains("想把今天的心绪写在这里")
        compose.runOnIdle {
            assertEquals(expected, input.getContents(true))
            input.clearInput()
            assertFalse(input.humanThoughtExpanded)
            assertTrue(input.humanThoughtContent.text.isEmpty())
        }
        compose.onNodeWithTag("human-thought-input").assertDoesNotExist()
        compose.onNodeWithText("选填").assertIsDisplayed()
    }

    @Test fun expandedEditorSharesComposerOpacityWithoutAnOpaqueInnerFill() {
        input.humanThoughtExpanded = true
        input.humanThoughtContent.setTextAndPlaceCursorAtEnd("想法")
        show()
        for (background in listOf(Color(0xFF164867), Color(0xFFECD8EC))) {
            for (alpha in listOf(0f, .15f, .5f, 1f)) {
                compose.runOnIdle { opacity.value = alpha; wallpaper.value = background }
                val pixels = compose.onNodeWithTag("human-thought-input").captureToImage().toPixelMap()
                val expected = panel.copy(alpha = alpha).compositeOver(background)
                for (x in listOf(.5f, .7f, .85f)) for (y in listOf(.25f, .5f, .8f)) {
                    val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                    assertTrue("alpha=$alpha x=$x y=$y expected=$expected actual=$actual",
                        abs(actual.red - expected.red) < .025f && abs(actual.green - expected.green) < .025f &&
                            abs(actual.blue - expected.blue) < .025f)
                }
            }
        }
    }

    @Test fun longDraftStaysCompactOnNarrowScreenWithLargeFontAndEditingPreservesParts() {
        show(fontScale = 1.5f)
        compose.onNodeWithTag("human-thought-input-toggle").performClick()
        compose.onNodeWithTag("human-thought-input").performTextInput("很多想法\n".repeat(20))
        val bounds = compose.onNodeWithTag("human-thought-input").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 100.dp)
        compose.onNodeWithTag("body-input").assertIsDisplayed()
        capture("thought-input-large-font.png")
        compose.runOnIdle {
            input.clearInput(preserveHumanThought = true)
            assertFalse(input.humanThoughtExpanded)
            assertEquals("很多想法\n".repeat(20), input.humanThoughtContent.text.toString())
            input.setMessageText("正文")
            assertEquals(listOf(UIMessagePart.Text("正文")), input.getContents(false))
            input.humanThoughtExpanded = true
            input.editingMessage = Uuid.random()
            input.setContents(listOf(humanThoughtPart("旧消息的补充"), UIMessagePart.Text("旧正文")))
            assertFalse(input.humanThoughtExpanded)
            assertEquals(listOf(humanThoughtPart("旧消息的补充"), UIMessagePart.Text("旧正文")), input.getContents(false))
        }
        compose.onNodeWithTag("human-thought-input").assertDoesNotExist()
        compose.onNodeWithTag("human-thought-input-toggle").performClick()
        compose.onNodeWithTag("human-thought-input").assertTextContains("旧消息的补充")
    }
}
