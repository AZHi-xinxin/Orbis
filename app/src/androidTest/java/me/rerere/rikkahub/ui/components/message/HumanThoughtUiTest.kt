package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class HumanThoughtUiTest {
    @get:Rule val compose = createShellComposeRule()

    private fun capture(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        java.io.File(dir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun show(thought: String) {
        val message = UIMessage.user("正文保持可见").copy(parts = listOf(humanThoughtPart(thought), UIMessagePart.Text("正文保持可见")))
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(init = true, providers = emptyList(), displaySetting =
                    DisplaySetting(enableMessageGenerationHapticEffect = false)),
                LocalNavController provides Navigator(mutableListOf()),
            ) { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ProvideTextStyle(rememberChatMessageTextStyle(user = true)) {
                        MessagePartsBlock(assistant = null, role = message.role, model = null,
                            parts = message.parts, annotations = emptyList(), loading = false,
                            messageKey = message.id.toString())
                    }
                }
            } } }
        }
    }

    @Test fun collapsedHumanThoughtExpandsWithoutTimingAndBodyStaysVisible() {
        show("这是补充的思考内容")
        compose.onNodeWithText("正文保持可见").assertIsDisplayed()
        compose.onNodeWithText("思考链").assertIsDisplayed()
        compose.onNodeWithText("这是补充的思考内容").assertDoesNotExist()
        capture("human-thought-collapsed.png")
        compose.onNodeWithTag("human-thought-toggle").performClick()
        compose.onNodeWithText("这是补充的思考内容").assertIsDisplayed()
        capture("human-thought-expanded.png")
        compose.onNodeWithTag("human-thought-toggle").performClick()
        compose.onNodeWithText("这是补充的思考内容").assertDoesNotExist()
        compose.onNodeWithText("正文保持可见").assertIsDisplayed()
    }

    @Test fun emptyThoughtHasTheSameCollapsedCard() {
        show("")
        compose.onNodeWithText("思考链").assertIsDisplayed()
        compose.onNodeWithText("（为空）").assertDoesNotExist()
        compose.onNodeWithTag("human-thought-toggle").performClick()
        compose.onNodeWithText("（为空）").assertIsDisplayed()
    }

    @Test fun composerKeepsBothFieldsAcrossEditingAndNeverSendsHiddenDraft() {
        compose.runOnIdle {
            val input = ChatInputState()
            input.setMessageText("main")
            val enabled = input.getContents(humanThoughtEnabled = true)
            assertTrue(enabled.first().isHumanThought())
            assertEquals("", (enabled.first() as UIMessagePart.Text).text)
            input.humanThoughtContent.setTextAndPlaceCursorAtEnd("draft")
            assertEquals(listOf(UIMessagePart.Text("main")), input.getContents(false))
            input.clearInput(preserveHumanThought = true)
            assertEquals("draft", input.humanThoughtContent.text.toString())
            input.setMessageText("next")
            val sent = input.getContents(true)
            input.clearInput()
            input.editingMessage = Uuid.random()
            input.setContents(sent)
            assertEquals("draft", input.humanThoughtContent.text.toString())
            assertEquals("next", input.textContent.text.toString())
            input.humanThoughtContent.setTextAndPlaceCursorAtEnd("")
            input.setMessageText("edited")
            val edited = input.getContents(false)
            assertTrue(edited.first().isHumanThought())
            assertEquals("", (edited.first() as UIMessagePart.Text).text)
            assertEquals(UIMessagePart.Text("edited"), edited.last())
        }
    }
}
