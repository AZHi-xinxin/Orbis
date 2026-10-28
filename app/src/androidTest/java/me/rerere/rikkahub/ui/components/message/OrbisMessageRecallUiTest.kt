package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Rule
import org.junit.Test

class OrbisMessageRecallUiTest {
    @get:Rule val compose = createShellComposeRule()

    private fun show(message: UIMessage) {
        compose.setContent {
            MaterialTheme {
                ChatMessage(node = message.copy(orbisRecalled = true, translation = "private translation").toMessageNode(),
                    onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
            }
        }
        compose.onNodeWithText("private original").assertDoesNotExist()
        compose.onNodeWithText("private translation").assertDoesNotExist()
        compose.onNodeWithText("撤回").assertDoesNotExist()
    }

    @Test fun assistantRecallShowsOnlyDistinctNotice() {
        show(UIMessage.assistant("private original"))
        compose.onNodeWithText("此条消息已被对方撤回").assertIsDisplayed()
    }

    @Test fun humanRecallShowsOnlyDistinctNotice() {
        show(UIMessage.user("private original"))
        compose.onNodeWithText("该条消息已撤回").assertIsDisplayed()
    }
}
