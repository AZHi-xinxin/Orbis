package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.soup.SoupAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic draft/preflight only: no repository, real games, configured model, or network requests. */
@RunWith(AndroidJUnit4::class)
class OrbisSoupDraftDialogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun requireIsolatedApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @Test fun rejectedQuestionRemainsVisibleWithInputAndCanBeCorrected() {
        val draft = mutableStateOf("他为什么下车")
        val open = mutableStateOf(true)
        var checks = 0
        var prepared: String? = null
        compose.setContent {
            MaterialTheme {
                if (open.value) OrbisSoupDraftDialog(
                    action = SoupAction.ASK, value = draft.value, enabled = true,
                    onValueChange = { draft.value = it },
                    onPrepare = { value ->
                        checks++
                        prepareSoupDraft { if (checks == 1) error("soup_yes_no_required") else value }
                    },
                    onPrepared = { prepared = it; open.value = false },
                    onConfigureHost = { error("A question error must not change host settings") },
                    onDismiss = { open.value = false },
                )
            }
        }
        compose.onNodeWithText("核对这次发送…").performClick()
        compose.onNodeWithText("向主持提问").assertIsDisplayed()
        compose.onNodeWithText("请改成能回答是或否的问题；这次没有调用模型，也没有扣次数。").assertIsDisplayed()
        compose.onNodeWithText("他为什么下车").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, prepared); assertEquals(1, checks) }
        compose.onNodeWithText("他为什么下车").performTextReplacement("他是主动下车的吗？")
        compose.onNodeWithText("核对这次发送…").performClick()
        compose.onNodeWithText("向主持提问").assertDoesNotExist()
        compose.runOnIdle { assertEquals("他是主动下车的吗？", prepared); assertEquals(2, checks) }
    }

    @Test fun rejectedSubmissionKeepsFullTextAndOffersHostSettingsWithoutResending() {
        val text = "这是合成测试推理，不是私人对局内容。"
        var checks = 0
        var settingsOpened = false
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisSoupDraftDialog<String>(
                    action = SoupAction.SUBMIT, value = text, enabled = true,
                    onValueChange = {},
                    onPrepare = { checks++; prepareSoupDraft { error("soup_dm_confirmation_required") } },
                    onPrepared = { error("Rejected preflight must never advance to paid confirmation") },
                    onConfigureHost = { settingsOpened = true },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("核对这次发送…").performClick()
        compose.onNodeWithText("提交完整推理").assertIsDisplayed()
        compose.onNodeWithText("请先确认此地址是你选择的独立主持接口，不是注入私人记忆的聊天网关。").assertIsDisplayed()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("配置独立主持（保留输入）").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settingsOpened); assertEquals(1, checks); assertEquals(false, dismissed) }
    }

    @Test fun blankDraftDoesNotRunPreflight() {
        compose.setContent {
            MaterialTheme {
                OrbisSoupDraftDialog<String>(
                    action = SoupAction.ASK, value = "  ", enabled = true,
                    onValueChange = {}, onPrepare = { error("Blank draft must not run preflight") },
                    onPrepared = {}, onConfigureHost = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("核对这次发送…").assertIsNotEnabled()
    }
}
