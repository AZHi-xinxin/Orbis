package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local-tools setting only: never opens real accounts, services, or preferences. */
@RunWith(AndroidJUnit4::class)
class AssistantMessageTimeUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun localToolsToggleWorksWithoutEnablingMemoryOrTimeQuery() {
        val original = Assistant(localTools = listOf(LocalToolOption.JavascriptEngine))
        var assistant by mutableStateOf(original)
        compose.setContent {
            MaterialTheme { AssistantMessageTimeCard(assistant) { assistant = it } }
        }
        compose.onNodeWithText("每条消息的发送时间").assertIsDisplayed()
        val toggle = compose.onNodeWithTag("assistant-user-message-time-toggle")
        toggle.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(original.copy(enableUserMessageTime = true), assistant) }
        toggle.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(original, assistant) }
    }

    @Test fun savedEnabledValueIsShownAndOnlySelectedAssistantChanges() {
        val saved = Assistant(enableUserMessageTime = true)
        val other = Assistant(name = "other synthetic assistant")
        var assistants by mutableStateOf(listOf(
            JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(saved)), other,
        ))
        compose.setContent {
            MaterialTheme {
                AssistantMessageTimeCard(assistants.first()) { updated ->
                    assistants = assistants.map { if (it.id == updated.id) updated else it }
                }
            }
        }
        compose.onNodeWithTag("assistant-user-message-time-toggle")
            .assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(listOf(saved.copy(enableUserMessageTime = false), other), assistants) }
    }
}
