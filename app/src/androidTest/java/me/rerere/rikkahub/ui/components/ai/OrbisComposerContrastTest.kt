package me.rerere.rikkahub.ui.components.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.orbis.theme.MaterialThemePreset
import me.rerere.rikkahub.data.orbis.theme.materialThemePreset
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.OrbisConversationPromptSheet
import me.rerere.rikkahub.ui.pages.orbis.ChatTheme
import me.rerere.rikkahub.ui.pages.orbis.LocalChatTheme
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrbisComposerContrastTest {
    @get:Rule val compose = createShellComposeRule()

    private fun checkPanel(tag: String, title: String, dark: Boolean, name: String) {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        compose.waitForIdle()
        val image = compose.onNodeWithTag(tag).captureToImage()
        val pixels = image.toPixelMap()
        val background = pixels[5, pixels.height / 2].luminance()
        assertTrue("$name background=$background", if (dark) background < .15f else background > .7f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val ink = layouts.single().layoutInput.style.color.luminance()
        assertTrue("$name text contrast", (maxOf(ink, background) + .05f) / (minOf(ink, background) + .05f) >= 4.5f)
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "night-panels-review").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun expandedControlsUseReadableColorsWithPaleGlassChatInBothModes() {
        var dark by mutableStateOf(true)
        var panel by mutableIntStateOf(0)
        val theme = ChatTheme(materialThemePreset(MaterialThemePreset.LIQUID_STILL, "0".repeat(64) + ".png"), null)
        val assistant = Assistant()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDarkMode provides dark, LocalChatTheme provides theme,
                LocalSettings provides Settings(assistantId = assistant.id, assistants = listOf(assistant))) {
                OrbisComposerControls {
                    when (panel) {
                        0 -> OrbisCapabilityPanel {}
                        1 -> OrbisVoicePanel(canRecognize = true, recording = false, busy = false,
                            canStartVoice = true, canSpeak = true, speaking = false, autoRead = false,
                            onRecognize = {}, onStartVoice = {}, onStopVoice = {}, voiceActive = false,
                            onSpeak = {}, onStopSpeaking = {}, onConfigure = {})
                        else -> OrbisStickerPanel()
                    }
                }
            }
        } }
        for (night in listOf(true, false)) {
            compose.runOnIdle { dark = night }
            for ((index, title) in listOf("拍照", "语音与视频", "图片表情").withIndex()) {
                compose.runOnIdle { panel = index }
                compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                checkPanel("orbis-composer-controls", title, night, "controls-$index-${if (night) "night" else "day"}")
            }
        }
    }

    @Test fun conversationPromptSheetIgnoresPaleGlassAndVeryLowComposerOpacity() {
        var dark by mutableStateOf(true)
        val theme = ChatTheme(materialThemePreset(MaterialThemePreset.LIQUID_STILL, "0".repeat(64) + ".png"), null)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDarkMode provides dark, LocalChatTheme provides theme) {
                OrbisConversationPromptSheet("synthetic", OrbisConversationPrompt("Synthetic preference", true),
                    false, .1f, onSave = {}, onDismiss = {})
            }
        } }
        checkPanel("orbis-conversation-prompt-sheet", "对话设置", true, "prompt-night")
        compose.runOnIdle { dark = false }
        checkPanel("orbis-conversation-prompt-sheet", "对话设置", false, "prompt-day")
    }
}
