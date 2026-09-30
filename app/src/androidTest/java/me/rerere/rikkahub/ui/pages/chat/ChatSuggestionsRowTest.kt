package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI only; the shell rule requires IsolatedGenerationLoopRunner and a plain Application. */
@RunWith(AndroidJUnit4::class)
class ChatSuggestionsRowTest {
    @get:Rule val compose = createShellComposeRule()

    @Test
    fun closeOnlyDismissesThisGroupWithoutCallingSelectionOrChangingDraftOrPreference() {
        val draft = mutableStateOf("未发送的合成草稿")
        val suggestionEnabled = mutableStateOf(true)
        var selections = 0
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", "message-a", listOf("合成建议"), {
                    selections++
                    draft.value = it
                })
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.onNodeWithText("合成建议").assertDoesNotExist()
        compose.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals("未发送的合成草稿", draft.value)
            assertTrue(suggestionEnabled.value)
        }
    }

    @Test
    fun selectingSuggestionRetainsTheOriginalFillDraftCallbackWithoutSending() {
        val draft = mutableStateOf("原合成草稿")
        var selections = 0
        var sends = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    ChatSuggestionsRow("chat-a", "message-a", listOf("合成建议"), {
                        selections++
                        draft.value = it
                    })
                    Text(draft.value)
                    Button(onClick = { sends++ }) { Text("合成发送按钮") }
                }
            }
        }
        compose.onNodeWithText("合成建议").performClick()
        compose.runOnIdle {
            assertEquals("合成建议", draft.value)
            assertEquals(1, selections)
            assertEquals(0, sends)
        }
        compose.onNodeWithContentDescription(CLOSE).assertIsDisplayed()
    }

    @Test
    fun sameSuggestionsReappearForANewMessage() {
        val messageId = mutableStateOf("message-a")
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", messageId.value, listOf("相同合成建议"), {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { messageId.value = "message-b" }
        compose.onNodeWithText("相同合成建议").assertIsDisplayed()
    }

    @Test
    fun dismissalDoesNotLeakToAnotherConversation() {
        val conversationId = mutableStateOf("chat-a")
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow(conversationId.value, "same-message", listOf("相同合成建议"), {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { conversationId.value = "chat-b" }
        compose.onNodeWithText("相同合成建议").assertIsDisplayed()
    }

    @Test
    fun changedSuggestionsForTheSameMessageAreANewGroup() {
        val suggestions = mutableStateOf(listOf("第一组合成建议"))
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", "message-a", suggestions.value, {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { suggestions.value = listOf("更新后的合成建议") }
        compose.onNodeWithText("更新后的合成建议").assertIsDisplayed()
    }

    @Test
    fun clearedThenRegeneratedIdenticalSuggestionsCanReappear() {
        val suggestions = mutableStateOf(listOf("重复生成的合成建议"))
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", "message-a", suggestions.value, {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { suggestions.value = emptyList() }
        compose.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
        compose.runOnIdle { suggestions.value = listOf("重复生成的合成建议") }
        compose.onNodeWithText("重复生成的合成建议").assertIsDisplayed()
    }

    @Test
    fun temporaryCaptureVisibilityDoesNotUndoDismissal() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", "message-a", listOf("合成建议"), {}, visible = visible.value)
            }
        }
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("合成建议").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("合成建议").assertIsDisplayed()
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("合成建议").assertDoesNotExist()
    }

    @Test
    fun restoredDismissalStaysHiddenButANewMessageCanShowSuggestions() {
        val restoration = StateRestorationTester(compose)
        val messageId = mutableStateOf("message-a")
        restoration.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", messageId.value, listOf("合成建议"), {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("合成建议").assertDoesNotExist()
        compose.runOnIdle { messageId.value = "message-b" }
        compose.onNodeWithText("合成建议").assertIsDisplayed()
    }

    @Test
    fun previewRoundTripKeepsDismissalButNewMessageDuringPreviewRestoresANewGroup() {
        val preview = mutableStateOf(false)
        val messageId = mutableStateOf("message-a")
        var selections = 0
        compose.setContent {
            MaterialTheme {
                ChatListModeContent(preview.value) { showingPreview ->
                    if (showingPreview) Text("合成列表预览") else {
                        ChatSuggestionsRow("chat-a", messageId.value, listOf("合成建议"), { selections++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.runOnIdle { preview.value = true }
        compose.onNodeWithText("合成列表预览").assertIsDisplayed()
        compose.runOnIdle { preview.value = false }
        compose.onNodeWithText("合成列表预览").assertDoesNotExist()
        compose.onNodeWithText("合成建议").assertDoesNotExist()

        compose.runOnIdle { preview.value = true }
        compose.onNodeWithText("合成列表预览").assertIsDisplayed()
        compose.runOnIdle { messageId.value = "message-b"; preview.value = false }
        compose.onNodeWithText("合成建议").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selections) }
    }

    @Test
    fun persistentSwitchHidesExistingAndNewBatchesUntilReenabled() {
        val enabled = mutableStateOf(true)
        val messageId = mutableStateOf("message-a")
        val suggestions = mutableStateOf(listOf("已保存的合成建议"))
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", messageId.value, suggestions.value, {}, visible = enabled.value)
            }
        }
        compose.onNodeWithText("已保存的合成建议").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("已保存的合成建议").assertDoesNotExist()
        compose.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
        compose.runOnIdle {
            messageId.value = "message-b"
            suggestions.value = listOf("迟到的合成建议")
        }
        compose.onNodeWithText("迟到的合成建议").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("迟到的合成建议").assertIsDisplayed()
    }

    @Test
    fun closeRemainsAccessibleBesideLongSuggestionsAndHas48DpTarget() {
        compose.setContent {
            MaterialTheme {
                ChatSuggestionsRow("chat-a", "message-a", listOf("合成长建议".repeat(40)), {})
            }
        }
        compose.onNodeWithContentDescription(CLOSE).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
    }

    private companion object {
        const val CLOSE = "收起本组建议回复"
    }
}
