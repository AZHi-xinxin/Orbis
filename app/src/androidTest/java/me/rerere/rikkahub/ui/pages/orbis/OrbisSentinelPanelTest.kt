package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.OrbisInboxState
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelAction
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelExecution
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelExecutionStatus
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelNotificationLevel
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelRule
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelState
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelType
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure display seam: synthetic in-memory values; no controller/Koin/private stores or services. */
@RunWith(AndroidJUnit4::class)
class OrbisSentinelPanelTest {
    @get:Rule val compose = createShellComposeRule()
    private val rule = OrbisSentinelRule(id = "synthetic-rule", assistantId = "synthetic-ai",
        conversationId = "synthetic-chat", type = OrbisSentinelType.CHAT_IDLE, prompt = "  原文 [保持] 不改\n第二行  ",
        name = "合成空闲哨兵", enabled = true, thresholdMs = 120_000L, createdAtMs = 1_000L)
    private val record = OrbisSentinelExecution(eventId = "synthetic-event", ruleId = rule.id,
        assistantId = rule.assistantId, conversationId = rule.conversationId, action = OrbisSentinelAction.WAKE,
        notificationLevel = OrbisSentinelNotificationLevel.LIGHT, createdAtMs = 2_000L,
        status = OrbisSentinelExecutionStatus.ACCEPTED)

    private fun show(state: OrbisSentinelState = OrbisSentinelState(enabled = true, rules = listOf(rule)),
        inbox: OrbisInboxState = OrbisInboxState(), saving: Boolean = false, runtimeRunning: Boolean = false,
        runtimeError: String? = null, legacyOwned: Boolean = false,
        recoveryConversationIds: Set<String> = emptySet(), recoveryNotices: Map<String, String> = emptyMap(),
        recoveringConversationId: String? = null,
        onRecover: (String) -> Unit = { error("Opening must not recover") },
        onChange: (Boolean) -> Unit = { error("Opening must not mutate") }) {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelPanel(state = state, assistantNames = mapOf(rule.assistantId to "合成 AI"), inbox = inbox,
                runtimeRunning = runtimeRunning, runtimeError = runtimeError, saving = saving, legacyOwned = legacyOwned,
                recoveryConversationIds = recoveryConversationIds, recoveryNotices = recoveryNotices,
                recoveringConversationId = recoveringConversationId, onRecoverFutureAutomaticWakes = onRecover,
                onHumanMasterChange = onChange)
        } } }
    }

    @Test fun openingOnlyOffersOneHumanMasterAndNoRuleMutationControls() {
        show()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        compose.onNodeWithTag("sentinel-human-master").assertIsOn()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        listOf("新增规则", "编辑规则", "删除规则", "修改原文", "选择固定会话", "更换固定会话").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun expandingRulePreservesOriginalTextAndShowsBindingConditionAndErrorReadOnly() {
        show(state = OrbisSentinelState(enabled = true, rules = listOf(rule.copy(lastError = "synthetic_permission_missing"))))
        compose.onNodeWithText(rule.prompt, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("sentinel-category-other").performScrollTo().performClick()
        compose.onNodeWithTag("sentinel-rule-${rule.id}").performScrollTo().performClick()
        compose.onNodeWithText(rule.prompt, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("合成 AI · ${rule.assistantId}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("固定会话未收到新用户消息达到 2 分钟", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("synthetic_permission_missing", useUnmergedTree = true).assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        compose.onNodeWithTag("sentinel-rule-${rule.id}").performScrollTo().performClick()
        compose.onNodeWithText(rule.prompt, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun onlyExplicitMasterTapChangesTheMasterWithoutChangingAnyRule() {
        val before = OrbisSentinelState(enabled = true, rules = listOf(rule))
        var state by mutableStateOf(before)
        val writes = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelPanel(state = state, onHumanMasterChange = { writes += it; state = state.copy(enabled = it) })
        } } }
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), writes) }
        compose.onNodeWithTag("sentinel-human-master").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf(false), writes)
            assertEquals(before.rules, state.rules)
        }
        compose.onNodeWithTag("sentinel-category-other").performScrollTo().performClick()
        compose.onNodeWithText("总开关已暂停 · AI 已启用此规则", useUnmergedTree = true).assertExists()
    }

    @Test fun whileSavingTheOnlySwitchCannotBeDoubleSubmitted() {
        show(saving = true)
        compose.onNodeWithTag("sentinel-human-master").assertIsNotEnabled()
        compose.onNodeWithText("正在保存总开关…").assertExists()
    }

    @Test fun enabledMasterNeverPretendsStoppedRuntimeIsRunningOrLegacyMigrated() {
        show(runtimeError = "synthetic_background_blocked")
        compose.onNodeWithTag("sentinel-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("本地哨兵调度：当前未运行").assertExists()
        compose.onNodeWithText("本机状态提示：synthetic_background_blocked").assertExists()
        compose.onNodeWithText("本机哨兵直接投递到固定对话。旧外部发送端是否停用尚未核实，这不代表本机哨兵未连接；请查看下方送达记录。").assertExists()
        compose.onNodeWithText("旧链路归属已核对。").assertDoesNotExist()
    }

    @Test fun openingRecoveryDoesNotSubmitOrAddRuleControlsAndExplainsNoReplay() {
        show(recoveryConversationIds = setOf(rule.conversationId))
        compose.onNodeWithTag("sentinel-recover-future-${rule.conversationId}")
            .performScrollTo().assertIsEnabled()
        compose.onNodeWithText("这里保留旧轮的保护记录，供你核对；新通知已独立投递，不需要先点恢复。旧积压提醒不补发，未知工具不重做，旧通话不重连。")
            .assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        compose.onNodeWithText("恢复后续哨兵").assertExists()
        compose.onNodeWithText("重发积压提醒").assertDoesNotExist()
    }

    @Test fun oneRecoveryTapTargetsExactConversationAndDisablesAllConcurrentControls() {
        val other = "synthetic-other-chat"
        val requested = mutableListOf<String>()
        var recovering by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OrbisSentinelPanel(state = OrbisSentinelState(enabled = true, rules = listOf(rule)),
                recoveryConversationIds = linkedSetOf(rule.conversationId, other),
                recoveringConversationId = recovering,
                onRecoverFutureAutomaticWakes = { requested += it; recovering = it },
                onHumanMasterChange = { error("Recovery must not change the master") })
        } } }
        compose.runOnIdle { assertEquals(emptyList<String>(), requested) }
        compose.onNodeWithTag("sentinel-recover-future-$other").performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("sentinel-recover-future-${rule.conversationId}").assertIsNotEnabled()
        compose.onNodeWithTag("sentinel-human-master").assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("正在检查并保存…").assertExists()
        compose.runOnIdle { assertEquals(listOf(other), requested) }
    }

    @Test fun savingMasterAlsoPreventsRecoverySubmission() {
        show(saving = true, recoveryConversationIds = setOf(rule.conversationId))
        compose.onNodeWithTag("sentinel-recover-future-${rule.conversationId}").assertIsNotEnabled()
        compose.onNodeWithTag("sentinel-human-master").assertIsNotEnabled()
    }

    @Test fun completedRecoveryKeepsResultWithoutOfferingReplayOrClaimingAiReplied() {
        val notice = "合成结果：已允许之后的新事件，旧积压未补发。"
        show(recoveryNotices = mapOf(rule.conversationId to notice))
        compose.onNodeWithTag("sentinel-recovery-result-${rule.conversationId}").performScrollTo().assertExists()
        compose.onNodeWithText(notice).assertExists()
        compose.onNodeWithTag("sentinel-recover-future-${rule.conversationId}").assertDoesNotExist()
        compose.onNodeWithText("已回复").assertDoesNotExist()
        compose.onNodeWithTag("sentinel-human-master").assertIsEnabled().assertIsOn()
    }

    @Test fun acceptedExecutionIsNotPresentedAsAnAiReply() {
        show(state = OrbisSentinelState(enabled = true, rules = listOf(rule), executions = listOf(record)))
        compose.onNodeWithTag("sentinel-executions").performScrollTo().performClick()
        compose.onNodeWithTag("sentinel-execution-${record.eventId}").performScrollTo().performClick()
        compose.onNodeWithText(record.eventId, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("收件箱已接收", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("已回复", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun oldBindingsAreReadOnlyAndUnknownReceiptShowsItsActualErrorAndText() {
        val receipt = OrbisInboxEvent(id = "synthetic-receipt", eventId = "synthetic-external", source = "rikka_sentinel",
            text = "合成外部原文", wake = true, assistantId = rule.assistantId, conversationId = rule.conversationId,
            receivedAt = 3_000L, state = "unknown", error = "synthetic_receipt_unknown")
        show(inbox = OrbisInboxState(bindings = mapOf("rikka_sentinel" to OrbisEventBinding(rule.assistantId, rule.conversationId)),
            events = listOf(receipt)))
        compose.onNodeWithTag("sentinel-bindings").performScrollTo().performClick()
        compose.onNodeWithTag("sentinel-source-rikka_sentinel").performScrollTo().performClick()
        compose.onNodeWithText("允许接收，仍受总开关控制", useUnmergedTree = true).assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
        compose.onNodeWithTag("sentinel-receipts").performScrollTo().performClick()
        compose.onNodeWithTag("sentinel-receipt-${receipt.id}").performScrollTo().performClick()
        compose.onNodeWithText("synthetic_receipt_unknown", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(receipt.text, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("无法确认是否完成；请先查看固定会话，避免重复唤醒。").assertExists()
    }

    @Test fun emptyRuleListDoesNotCreateRulesAndRunningStatusIsOnlyExplicit() {
        show(state = OrbisSentinelState(enabled = true), runtimeRunning = true, legacyOwned = true)
        compose.onNodeWithTag("sentinel-diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("本地哨兵调度：运行中").assertExists()
        compose.onNodeWithText("旧链路归属已核对。").assertExists()
        compose.onNodeWithText("打开此页不创建规则", substring = true).assertExists()
    }

    @Test fun sevenDesignCategoriesAndAllArchivesStartCollapsedWithNoExtraSwitches() {
        show()
        assertEquals(7, orbisSentinelCategories.size)
        val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠")
        orbisSentinelCategories.forEach { category ->
            compose.onNodeWithTag("sentinel-category-${category.id}").assert(collapsed)
        }
        listOf("sentinel-category-other", "sentinel-diagnostics", "sentinel-executions", "sentinel-bindings", "sentinel-receipts").forEach {
            compose.onNodeWithTag(it).assert(collapsed)
        }
        compose.onNodeWithTag("sentinel-rule-${rule.id}").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
    }

    @Test fun categoryCanExpandReadOnlyAndCollapseAgain() {
        val ritual = rule.copy(type = OrbisSentinelType.RITUAL, dailyAtLocal = "08:00", name = "合成早安")
        show(state = OrbisSentinelState(enabled = true, rules = listOf(ritual)))
        compose.onNodeWithTag("sentinel-rule-${rule.id}").assertDoesNotExist()
        compose.onNodeWithTag("sentinel-category-ritual").performScrollTo().performClick()
        compose.onNodeWithTag("sentinel-rule-${rule.id}").assertExists().performClick()
        compose.onNodeWithText(rule.prompt, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("每天 08:00 定点一次", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sentinel-category-ritual").performScrollTo().performClick()
        compose.onNodeWithText(rule.prompt, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(1)
    }
}
