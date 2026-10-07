package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbisCallQueueRecoveryControlsDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun manualContinueIsBusySafeAndChatReviewStaysAvailable() {
        var state by mutableStateOf(VoiceSessionState(replyBlocked = true))
        var checks = 0
        var reviews = 0
        compose.setContent { MaterialTheme {
            OrbisCallQueueRecoveryControls(state, Color.Black,
                onRecover = { checks++; state = state.copy(replyResumeChecking = true) },
                onReview = { reviews++ })
        } }
        compose.onNodeWithText("回复已暂停，通话仍保留").assertIsDisplayed()
        compose.onNodeWithText("一键恢复").assertDoesNotExist()
        compose.onNodeWithText("继续回复").performClick()
        compose.onNodeWithTag("orbis-call-resume-replies").assertIsNotEnabled()
        compose.onNodeWithText("正在检查能否继续回复").assertIsDisplayed()
        compose.onNodeWithText("检查中…").assertIsDisplayed()
        compose.onNodeWithText("回聊天处理").performClick()
        compose.runOnIdle {
            assertEquals(1, checks)
            assertEquals(1, reviews)
            state = state.copy(replyBlocked = false, replyResumeChecking = false)
        }
        compose.onNodeWithTag("orbis-call-resume-replies").assertDoesNotExist()
    }

    @Test fun unresolvedReplyKeepsBothManualActionsWithoutPromisingQueueDispatch() {
        compose.setContent { MaterialTheme {
            OrbisCallQueueRecoveryControls(VoiceSessionState(replyBlocked = true,
                replyNotice = "旧工具结果尚待确认，原文仍保留。"), Color.Black, {}, {})
        } }
        compose.onNodeWithText("旧工具结果尚待确认，原文仍保留。").assertIsDisplayed()
        compose.onNodeWithText("回复已暂停，通话仍保留").assertIsDisplayed()
        compose.onNodeWithTag("orbis-call-resume-replies").assertIsEnabled()
        compose.onNodeWithTag("orbis-call-review-replies").assertIsEnabled()
        compose.onNodeWithText("先在聊天中处理未完成的回复，再检查是否可以继续说话；不会重发旧消息。").assertIsDisplayed()
        compose.onNodeWithText("当前消息队列卡顿").assertDoesNotExist()
    }
}
