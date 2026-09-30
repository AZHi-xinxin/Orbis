package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBubbleStyle
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.context.orbisChatTextStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisPalette
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/** In-memory appearance and synthetic content only; no Koin, preferences, images or model calls. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class OrbisMessageAppearanceTest {
    @get:Rule val compose = createShellComposeRule()
    private var appearance by mutableStateOf(OrbisAppearance(bubbleOpacity = 0f))
    private var user by mutableStateOf(false)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1f),
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
            ) {
                MaterialTheme {
                    OrbisVisualTheme(darkTheme = false, content = content)
                }
            }
        }
    }

    private fun capture(): PixelMap = compose.onNodeWithTag("bubble-canvas").captureToImage().toPixelMap()
    private fun closeTo(actual: Color, expected: Color): Boolean =
        kotlin.math.abs(actual.red - expected.red) < .025f &&
            kotlin.math.abs(actual.green - expected.green) < .025f &&
            kotlin.math.abs(actual.blue - expected.blue) < .025f && actual.alpha > .97f

    private fun textColor(text: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        return results.single().layoutInput.style.color
    }

    @Test fun zeroOpacityRemovesEveryStyleDecorationButLeavesContentOpaque() {
        show {
            Box(Modifier.requiredSize(180.dp, 100.dp).background(Color.Black).testTag("bubble-canvas")) {
                OrbisMessageBubble(user, appearance, Modifier.fillMaxSize()) {
                    Box(Modifier.size(20.dp).background(Color.Red))
                }
            }
        }
        for (style in OrbisBubbleStyle.entries) for (isUser in listOf(false, true)) {
            compose.runOnIdle { appearance = appearance.copy(bubbleStyle = style); user = isUser }
            val image = capture()
            var redPixels = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image[x, y]
                if (closeTo(pixel, Color.Red)) redPixels++ else
                    assertTrue("$style/$isUser decoration remained at ($x,$y)", closeTo(pixel, Color.Black))
            }
            assertTrue("Bubble opacity must not hide its content", redPixels >= 300)
        }
    }

    @Test fun intermediateOpacityFadesDecorationWithoutFadingItsContent() {
        show {
            Box(Modifier.requiredSize(180.dp, 100.dp).background(Color.Black).testTag("bubble-canvas")) {
                OrbisMessageBubble(false, appearance, Modifier.fillMaxSize()) {
                    Box(Modifier.size(20.dp).background(Color.Red))
                }
            }
        }
        val backgroundChannels = listOf(0f, .5f, 1f).map { alpha ->
            compose.runOnIdle { appearance = appearance.copy(bubbleOpacity = alpha) }
            val image = capture()
            assertTrue("Content retains full opacity at $alpha", closeTo(image[20, 16], Color.Red))
            image[90, 50].red
        }
        assertTrue(backgroundChannels[0] < .025f)
        assertTrue(backgroundChannels[1] in 0.4f..0.6f)
        assertTrue(backgroundChannels[2] > .95f)
    }

    @Test fun actualBubbleUsesItsRoleOverrideAndKeepsTextIndependent() {
        appearance = OrbisAppearance(bubbleOpacity = .5f, userBubbleOpacity = 0f, assistantBubbleOpacity = 1f)
        show {
            Box(Modifier.requiredSize(180.dp, 100.dp).background(Color.Black).testTag("bubble-canvas")) {
                OrbisMessageBubble(user, appearance, Modifier.fillMaxSize()) {
                    Box(Modifier.size(20.dp).background(Color.Red))
                }
            }
        }
        assertTrue(capture()[90, 50].red > .95f)
        compose.runOnIdle { user = true }
        assertTrue(closeTo(capture()[90, 50], Color.Black))
        assertTrue(closeTo(capture()[20, 16], Color.Red))
        compose.runOnIdle { appearance = appearance.copy(userBubbleOpacity = 1f) }
        assertTrue(capture()[90, 50].red > .85f)
        compose.runOnIdle { user = false }
        assertTrue(capture()[90, 50].red > .95f)
        assertTrue(closeTo(capture()[20, 16], Color.Red))
    }

    @Test fun actualMarkdownAndReasoningFollowColorAndResetWithoutChangingOutsideText() {
        val custom = Color(0xFF275B89)
        appearance = appearance.copy(chatTextColor = 0xFF275B89.toInt())
        val start = Instant.fromEpochMilliseconds(0)
        val reasoning = UIMessagePart.Reasoning("Synthetic reasoning body", start,
            Instant.fromEpochMilliseconds(2000))
        show {
            Column {
                Text("Outside message")
                OrbisMessageBubble(false, appearance) {
                    MarkdownBlock("Assistant prose", style = orbisChatTextStyle())
                    ChainOfThought(steps = listOf(reasoning),
                        cardColors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                        ChatMessageReasoningStep(it, null, null)
                    }
                }
                OrbisMessageBubble(true, appearance) {
                    MarkdownBlock("User prose", style = orbisChatTextStyle())
                }
            }
        }
        val title = compose.activity.getString(R.string.deep_thinking_seconds, 2f)
        val outsideColor = textColor("Outside message")
        assertNotEquals(custom, outsideColor)
        assertEquals(custom, textColor("Assistant prose"))
        assertEquals(custom, textColor("User prose"))
        assertEquals(custom, textColor(title))
        compose.onNodeWithText(title).performClick()
        assertEquals(custom, textColor("Synthetic reasoning body"))
        assertEquals(outsideColor, textColor("Outside message"))
        compose.runOnIdle { appearance = appearance.copy(chatTextColor = null) }
        assertEquals(OrbisPalette.Light.ink, textColor("Assistant prose"))
        assertEquals(OrbisPalette.Light.mutedInk, textColor(title))
        assertEquals(outsideColor, textColor("Outside message"))
    }

    @Test fun whiteChatTextDoesNotOverrideCodeSurfaceContrastOrSyntaxPalette() {
        appearance = appearance.copy(chatTextColor = 0xFFFFFFFF.toInt())
        var codeInk = Color.Unspecified
        show {
            codeInk = MaterialTheme.colorScheme.onSurface
            OrbisMessageBubble(false, appearance) {
                MarkdownBlock("White prose\n\n```text\nsynthetic_code_literal\n```",
                    style = orbisChatTextStyle())
            }
        }
        assertEquals(Color.White, textColor("White prose"))
        assertEquals(codeInk, textColor("synthetic_code_literal"))
        assertNotEquals(Color.White, codeInk)
    }
}
