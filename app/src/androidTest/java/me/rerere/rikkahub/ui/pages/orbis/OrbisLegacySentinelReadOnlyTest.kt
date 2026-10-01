package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lover.connect.BridgeLegacyAutomationReadOnlyCard
import com.lover.connect.BridgeLegacySentinelReadOnlyCard
import com.lover.connect.BridgeLegacyReminderDistanceReadOnlyCard
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure synthetic legacy cards: never instantiate MainScreen, open user preferences or start services. */
@RunWith(AndroidJUnit4::class)
class OrbisLegacySentinelReadOnlyTest {
    @get:Rule val compose = createShellComposeRule()

    private fun assertNoEditorsOrSwitches() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
    }

    @Test fun connectionViewContainsNoEditableFieldsSwitchesOrSaveButton() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BridgeLegacySentinelReadOnlyCard(sendingEnabled = true, endpointConfigured = true, credentialConfigured = true)
        } } }
        assertNoEditorsOrSwitches()
        compose.onNodeWithText("旧连接凭证：已配置（不显示原文）").assertExists()
        compose.onNodeWithText("旧目标地址：已配置").assertExists()
        compose.onNodeWithText("保存哨兵配置").assertDoesNotExist()
        compose.onNodeWithText("启用事件发送（保存后生效）").assertDoesNotExist()
    }

    @Test fun missingConnectionDoesNotPretendToBeConfiguredOrMigrated() {
        compose.setContent { MaterialTheme {
            BridgeLegacySentinelReadOnlyCard(sendingEnabled = false, endpointConfigured = false, credentialConfigured = false)
        } }
        compose.onNodeWithText("旧目标地址：未配置").assertExists()
        compose.onNodeWithText("旧连接凭证：未配置（不显示原文）").assertExists()
        compose.onNodeWithText("配置存在不等于已经运行或送达，也不代表迁移完成；不会自动改投目标或发送测试事件。").assertExists()
        assertNoEditorsOrSwitches()
    }

    @Test fun visualPromptAndIntervalAreReadOnlyAndOnlyExplicitPrivacyStopCallsBack() {
        var stops = 0
        val prompt = "  合成人格原文\n[不改写]  "
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BridgeLegacyAutomationReadOnlyCard(showVision = true, collectionEnabled = true,
                aiName = "合成 AI", userName = "合成用户", relationship = "合成关系", personality = prompt,
                intervalMinutes = "37", restThresholdMinutes = "80", onStopCollection = { stops++ })
        } } }
        compose.runOnIdle { assertEquals(0, stops) }
        assertNoEditorsOrSwitches()
        compose.onNodeWithText(prompt).assertExists()
        compose.onNodeWithText("37 分钟").assertExists()
        compose.onNodeWithText("保存观察配置").assertDoesNotExist()
        compose.onNodeWithText("启用内置观察模块").assertDoesNotExist()
        compose.onNodeWithTag("legacy-stop-collection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun stoppedCollectionOffersNoReenablePath() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BridgeLegacyAutomationReadOnlyCard(showVision = true, collectionEnabled = false,
                aiName = "", userName = "", relationship = "", personality = "", intervalMinutes = "30",
                restThresholdMinutes = "60", onStopCollection = { error("Already stopped") })
        } } }
        compose.onNodeWithTag("legacy-stop-collection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("旧自动采集设置：已关闭（不代表当前服务正在运行）").assertExists()
        assertNoEditorsOrSwitches()
    }

    @Test fun restThresholdCannotBeEditedButPrivacyStopRemains() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BridgeLegacyAutomationReadOnlyCard(showVision = false, collectionEnabled = true,
                aiName = "", userName = "", relationship = "", personality = "never-render-this-vision-prompt",
                intervalMinutes = "30", restThresholdMinutes = "85", onStopCollection = {})
        } } }
        assertNoEditorsOrSwitches()
        compose.onNodeWithText("85 分钟").assertExists()
        compose.onNodeWithText("never-render-this-vision-prompt").assertDoesNotExist()
        compose.onNodeWithTag("legacy-stop-collection").assertExists()
    }

    @Test fun locationReminderDistanceHasNoEditingOrSaveAction() {
        compose.setContent { MaterialTheme {
            BridgeLegacyReminderDistanceReadOnlyCard(reminderKmText = "6")
        } }
        assertNoEditorsOrSwitches()
        compose.onNodeWithText("第二次提醒距离 · 只读").assertExists()
        compose.onNodeWithText("6 公里").assertExists()
        compose.onNodeWithText("保存提醒距离").assertDoesNotExist()
    }
}
