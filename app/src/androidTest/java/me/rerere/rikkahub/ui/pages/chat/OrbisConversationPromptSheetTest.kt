package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import me.rerere.rikkahub.testutil.createShellComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import me.rerere.rikkahub.data.model.OrbisGenerationParameterConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Synthetic in-memory prompt state only. No Koin, SettingsStore, Room, model or network.
 * Build with -PorbisIsolatedTests=true and explicitly select IsolatedGenerationLoopRunner.
 * This does not exercise the real activity or prove Room persistence (migration tests do that).
 */
@RunWith(AndroidJUnit4::class)
class OrbisConversationPromptSheetTest {
    private val compose = createShellComposeRule()
    private val isolatedApplication = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner) {
                "Synthetic tests require the explicit IsolatedGenerationLoopRunner."
            }
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(isolatedApplication).around(compose)

    @Test
    fun savingAndReopeningKeepsSavedTextAndEnabledState() {
        val saved = mutableStateOf(OrbisConversationPrompt(text = "原有合成提示", enabled = false))
        val visible = mutableStateOf(true)
        var saves = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    OrbisConversationPromptSheet(
                        conversationId = "synthetic-save",
                        prompt = saved.value,
                        generating = false,
                        composerOpacity = 0.75f,
                        onSave = { saved.value = it; saves++ },
                        onDismiss = { visible.value = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").performScrollTo().performTextReplacement("合成提示：请先给结论。")
        compose.onNodeWithContentDescription("在本会话启用提示词").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-dismiss").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(visible.value)
            assertEquals(1, saves)
            assertEquals(OrbisConversationPrompt("合成提示：请先给结论。", true), saved.value)
            visible.value = true
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertTextContains("合成提示：请先给结论。")
        compose.onNodeWithContentDescription("在本会话启用提示词").assertIsOn()
    }

    @Test
    fun cancellingDiscardsUnsavedEditsAndReopeningUsesSavedPrompt() {
        val original = OrbisConversationPrompt("已经保存的合成提示", false)
        val visible = mutableStateOf(true)
        var saves = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    OrbisConversationPromptSheet(
                        "synthetic-cancel", original, false, 1f,
                        onSave = { saves++ },
                        onDismiss = { visible.value = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").performScrollTo().performTextReplacement("未保存的合成编辑")
        compose.onNodeWithContentDescription("在本会话启用提示词").performScrollTo().performClick()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(visible.value)
            assertEquals(0, saves)
            visible.value = true
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertTextContains(original.text)
        compose.onNodeWithContentDescription("在本会话启用提示词").assertIsOff()
    }

    @Test
    fun generatingDisablesEditingAndSavingButKeepsCancelAvailable() {
        var saves = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet(
                    "synthetic-generating", OrbisConversationPrompt("合成提示", true), true, 0.5f,
                    onSave = { saves++ }, onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertIsNotEnabled()
        compose.onNodeWithContentDescription("在本会话启用提示词").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-worldbook-input").assertIsNotEnabled()
        compose.onNodeWithContentDescription("在本会话启用世界书").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.onNodeWithText("取消")
            .assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(0, saves) }
    }

    @Test
    fun pendingSaveDisablesRepeatedSavesAndDismissalUntilItCompletes() {
        val savingGate = CompletableDeferred<Unit>()
        var saves = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet(
                    "synthetic-saving", OrbisConversationPrompt("合成提示", true), false, 1f,
                    onSave = { saves++; savingGate.await() },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithTag("orbis-conversation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertIsNotEnabled()
        compose.onNodeWithContentDescription("在本会话启用提示词").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-worldbook-input").assertIsNotEnabled()
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertFalse(dismissed)
            savingGate.complete(Unit)
        }
        compose.onNodeWithText("本会话内容已保存。AI 参数需使用下方按钮单独保存。").assertExists()
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithTag("orbis-conversation-dismiss").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun failedSaveRetainsTheDraftAndAllowsRetry() {
        var dismissed = false
        var saves = 0
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet(
                    "synthetic-failure", OrbisConversationPrompt(), false, 1f,
                    onSave = { saves++; error("Synthetic save failure") },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").performScrollTo().performTextReplacement("失败后仍保留的合成提示")
        compose.onNodeWithTag("orbis-conversation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertTextContains("失败后仍保留的合成提示").assertIsEnabled()
        compose.onNodeWithTag("orbis-conversation-save").assertIsEnabled()
        compose.onNodeWithText("暂未保存成功，请在回复完成后重试。填写的内容还在这里。")
            .assertExists()
        compose.runOnIdle { assertEquals(1, saves); assertFalse(dismissed) }
    }

    @Test fun worldbookSavesAndReopensIndependentlyFromPrompt() {
        val saved = mutableStateOf(OrbisConversationPrompt("合成提示", true))
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) OrbisConversationPromptSheet("synthetic-worldbook", saved.value,
                    false, 1f, onSave = { saved.value = it }, onDismiss = { visible.value = false })
            }
        }
        compose.onNodeWithTag("orbis-conversation-worldbook-input").performScrollTo()
            .performTextReplacement("合成背景：这是一座图书馆。")
        compose.onNodeWithContentDescription("在本会话启用世界书").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-conversation-dismiss").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(OrbisConversationPrompt("合成提示", true, "合成背景：这是一座图书馆。", true), saved.value)
            visible.value = true
        }
        compose.onNodeWithTag("orbis-conversation-worldbook-input").assertTextContains("合成背景：这是一座图书馆。")
        compose.onNodeWithContentDescription("在本会话启用世界书").assertIsOn()
        compose.onNodeWithContentDescription("在本会话启用提示词").assertIsOn()
    }

    @Test fun cancellingParameterDraftDoesNotSaveEitherScope() {
        val assistant = Assistant(temperature = 0.5f, contextMessageLimit = 20)
        var promptSaves = 0
        var parameterSaves = 0
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) OrbisConversationPromptSheet("synthetic-parameter-cancel", OrbisConversationPrompt(),
                    false, 1f, onSave = { promptSaves++ }, onDismiss = { visible.value = false },
                    assistant = assistant, onSaveGenerationParameters = { parameterSaves++; it.after })
            }
        }
        compose.onNodeWithText("当前 AI，影响使用该 AI 的会话").assertExists()
        compose.onNodeWithTag("orbis-generation-temperature").performScrollTo().performTextReplacement("1.5")
        compose.onNodeWithTag("orbis-generation-context").performScrollTo().performTextReplacement("40")
        compose.onNodeWithTag("orbis-conversation-dismiss").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, promptSaves)
            assertEquals(0, parameterSaves)
            assertFalse(visible.value)
            visible.value = true
        }
        compose.onNodeWithTag("orbis-generation-temperature").assertTextContains("0.5")
        compose.onNodeWithTag("orbis-generation-context").assertTextContains("20")
    }

    @Test fun parameterFailureAndRetryNeverResavesSuccessfulConversationContent() {
        val assistant = Assistant(maxTokens = 500)
        var promptSaves = 0
        var parameterSaves = 0
        var savedParameters = OrbisGenerationParameters.from(assistant)
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet("synthetic-parameter-retry", OrbisConversationPrompt(), false, 1f,
                    onSave = { promptSaves++ }, onDismiss = {}, assistant = assistant,
                    onSaveGenerationParameters = {
                        parameterSaves++
                        if (parameterSaves == 1) error("synthetic persistence failure")
                        savedParameters = it.after
                        it.after
                    })
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").performScrollTo().performTextReplacement("保存一次的合成提示")
        compose.onNodeWithTag("orbis-conversation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-generation-max-tokens").performScrollTo().performTextReplacement("2048")
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.onNodeWithText("AI 参数保存未确认，请重试；草稿仍保留。本会话内容不会随此操作保存。").assertExists()
        compose.onNodeWithTag("orbis-generation-max-tokens").assertTextContains("2048")
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, promptSaves)
            assertEquals(2, parameterSaves)
            assertEquals(2048, savedParameters.maxTokens)
        }
    }

    @Test fun parameterSaveDoesNotPersistUnsavedPromptAndBlocksRepeatWhilePending() {
        val gate = CompletableDeferred<Unit>()
        val assistant = Assistant()
        var promptSaves = 0
        var parameterSaves = 0
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet("synthetic-parameter-pending", OrbisConversationPrompt(), false, 1f,
                    onSave = { promptSaves++ }, onDismiss = {}, assistant = assistant,
                    onSaveGenerationParameters = { parameterSaves++; gate.await(); it.after })
            }
        }
        compose.onNodeWithTag("orbis-conversation-prompt-input").performScrollTo().performTextReplacement("未保存提示")
        compose.onNodeWithContentDescription("流式输出").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-dismiss").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-generation-max-tokens").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, parameterSaves); assertEquals(0, promptSaves); gate.complete(Unit) }
        compose.onNodeWithText("当前 AI 参数已保存。本会话内容需使用上方按钮单独保存。").assertExists()
        compose.onNodeWithTag("orbis-conversation-save").assertIsEnabled()
        compose.onNodeWithTag("orbis-conversation-prompt-input").assertTextContains("未保存提示")
    }

    @Test fun parameterConflictKeepsDraftAndDoesNotSaveConversation() {
        val assistant = Assistant(temperature = 0.5f)
        var promptSaves = 0
        var parameterSaves = 0
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet("synthetic-parameter-conflict", OrbisConversationPrompt(), false, 1f,
                    onSave = { promptSaves++ }, onDismiss = {}, assistant = assistant,
                    onSaveGenerationParameters = { parameterSaves++; throw OrbisGenerationParameterConflict("温度") })
            }
        }
        compose.onNodeWithTag("orbis-generation-temperature").performScrollTo().performTextReplacement("1.5")
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.onNodeWithText("「温度」已在别处修改。请关闭并重新打开对话设置，确认最新值后再保存。").assertExists()
        compose.onNodeWithTag("orbis-generation-temperature").assertTextContains("1.5")
        compose.runOnIdle { assertEquals(0, promptSaves); assertEquals(1, parameterSaves) }
    }

    @Test fun generatingDisablesBothScopesAndInvalidParameterCannotSave() {
        val generating = mutableStateOf(false)
        val assistant = Assistant()
        var saves = 0
        compose.setContent {
            MaterialTheme {
                OrbisConversationPromptSheet("synthetic-parameter-invalid", OrbisConversationPrompt(), generating.value, 1f,
                    onSave = {}, onDismiss = {}, assistant = assistant,
                    onSaveGenerationParameters = { saves++; it.after })
            }
        }
        compose.onNodeWithTag("orbis-generation-context").performScrollTo().performTextReplacement("19")
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-generation-context").performScrollTo().performTextReplacement("20")
        compose.onNodeWithTag("orbis-generation-save").assertIsEnabled()
        compose.runOnIdle { generating.value = true }
        compose.onNodeWithTag("orbis-generation-context").assertIsNotEnabled()
        compose.onNodeWithContentDescription("流式输出").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-conversation-dismiss").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
