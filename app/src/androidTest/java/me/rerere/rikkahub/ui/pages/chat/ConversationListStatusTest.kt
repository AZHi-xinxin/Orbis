package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.R
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic display only: no conversation repository, database, network or import operation. */
@RunWith(AndroidJUnit4::class)
class ConversationListStatusTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun loadingHasNoEmptyHistoryClaimOrRetrySideEffect() {
        var retries = 0
        compose.setContent { MaterialTheme { ConversationListStatusCard(ConversationListNotice.LOADING) { retries++ } } }
        compose.onNodeWithText("正在读取会话列表……").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_page_no_conversations)).assertDoesNotExist()
        compose.onNodeWithText("重试加载").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, retries) }
    }

    @Test fun failedLoadOnlyRetriesWhenHumanClicks() {
        var retries = 0
        compose.setContent { MaterialTheme { ConversationListStatusCard(ConversationListNotice.REFRESH_FAILED) { retries++ } } }
        compose.onNodeWithText("会话列表暂时加载失败，不代表聊天已删除。请重试；仍失败时先保留数据，不要卸载或清除数据。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText("重试加载").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun confirmedEmptyUsesExistingEmptyMessage() {
        compose.setContent { MaterialTheme { ConversationListStatusCard(ConversationListNotice.EMPTY) {} } }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_page_no_conversations)).assertIsDisplayed()
        compose.onNodeWithText("重试加载").assertDoesNotExist()
    }
}
