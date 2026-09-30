package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.OrbisCompactionEvent
import me.rerere.rikkahub.data.model.deriveOrbisContextBudget
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.ai.OrbisCapabilityPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic metadata and callbacks only: no Room, real chat, services or model requests. */
@RunWith(AndroidJUnit4::class)
class OrbisCompactionUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val eventId = Uuid.parse("00000000-0000-0000-0000-000000000071")
    private val event = OrbisCompactionEvent(
        id = eventId,
        conversationId = Uuid.parse("00000000-0000-0000-0000-000000000072"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000073"),
        windowTitle = "合成验收窗口", createdAtEpochMillis = 1790157000000,
        beforeTokens = 350000, afterTokens = 7000,
        beforeBasis = "合成估算", afterBasis = "合成估算",
        summaryHash = "ab".repeat(32),
        summaryMessageId = Uuid.parse("00000000-0000-0000-0000-000000000074"),
        keptRecent = 2, rollbackAvailable = true,
    )

    private fun budget(threshold: Int = 350000) = deriveOrbisContextBudget(
        currentMessages = emptyList(), currentModelId = null, referenceLimit = 1000000,
        isGenerating = false, reminderThresholdTokens = threshold,
        currentUsageTokens = 120000, currentUsageSource = "合成估算",
    )

    @Test fun ringUsesHumanThresholdAndOpeningDoesNotSubmitCompression() {
        var opened = 0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            OrbisContextBudgetButton(budget(600000), "合成模型", onOpen = { opened++ })
        } } }
        compose.onNodeWithTag("orbis-context-budget-button")
            .assert(hasContentDescription("上下文，提醒阈值 600K，参考用量 20%"))
            .performClick()
        compose.onNodeWithTag("orbis-context-threshold-input").assertExists()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun exactThresholdEntrySavesOnceWithoutExtraConfirmation() {
        val saved = mutableListOf<Int>()
        compose.setContent { MaterialTheme {
            var threshold by remember { mutableStateOf(0) }
            OrbisContextBudgetSheet(budget(threshold), "合成模型", onSaveThreshold = {
                saved += it
                threshold = it
            }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-context-threshold-input").performScrollTo()
            .performTextReplacement("350000")
        compose.onNodeWithTag("orbis-context-threshold-input").performImeAction()
        compose.runOnIdle { assertEquals(listOf(350000), saved) }
        compose.onNodeWithText("从 315K（阈值 90%）开始", substring = true).assertExists()
    }

    @Test fun historyHasNoRollbackClickAndDeleteUsesOnlyTheChosenEvent() {
        val deleted = mutableListOf<Uuid>()
        var rollbackCalls = 0
        compose.setContent { MaterialTheme {
            OrbisContextBudgetSheet(budget(), "合成模型",
                compactionState = OrbisCompactionUiState(history = listOf(event)),
                onDeleteEvent = { deleted += it }, onRollbackLatest = { rollbackCalls++ }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-compaction-event-$eventId").performScrollTo()
            .assert(hasClickAction().not())
        compose.onNodeWithTag("orbis-compaction-rollback-latest").assertDoesNotExist()
        compose.onNodeWithTag("orbis-compaction-delete-$eventId").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(eventId), deleted)
            assertEquals(0, rollbackCalls)
        }
    }

    @Test fun failedThresholdPersistenceLeavesASpecificRetryWithoutNormalConfirmations() {
        val attempted = mutableListOf<Int>()
        compose.setContent { MaterialTheme {
            var state by remember { mutableStateOf(OrbisCompactionUiState()) }
            OrbisContextBudgetSheet(budget(0), "合成模型", compactionState = state,
                onSaveThreshold = { attempted += it; state = state.copy(error = "合成保存失败") }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-context-threshold-input").performScrollTo()
            .performTextReplacement("350000")
        compose.onNodeWithTag("orbis-context-threshold-input").performImeAction()
        compose.onNodeWithTag("orbis-context-threshold-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(350000, 350000), attempted) }
    }

    @Test fun latestRollbackIsOneDirectActionRatherThanHistoryNavigation() {
        var rollbackCalls = 0
        compose.setContent { MaterialTheme {
            OrbisContextBudgetSheet(budget(), "合成模型",
                compactionState = OrbisCompactionUiState(history = listOf(event), latestRollback = event,
                    projectedRollbackTokens = 400000),
                onRollbackLatest = { rollbackCalls++ }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-compaction-rollback-latest").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, rollbackCalls) }
    }

    @Test fun rollbackAboveLimitExplainsHowToRaiseThreshold() {
        compose.setContent { MaterialTheme {
            OrbisContextBudgetSheet(budget(), "合成模型",
                compactionState = OrbisCompactionUiState(latestRollback = event, projectedRollbackTokens = 450000),
                onRollbackLatest = { error("Blocked rollback must not dispatch") }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-compaction-rollback-latest").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("如仍要恢复，请先在上方调高阈值。", substring = true).assertExists()
    }

    @Test fun capabilityPanelDoesNotDuplicateTheTopBarContextEntry() {
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            OrbisCapabilityPanel { error("Opening a panel does not dispatch") }
        } } }
        compose.onNodeWithText("整理上下文").assertDoesNotExist()
        compose.onNodeWithText("拍照").assertExists()
        compose.onNodeWithText("MCP / 工具").assertExists()
    }
}
