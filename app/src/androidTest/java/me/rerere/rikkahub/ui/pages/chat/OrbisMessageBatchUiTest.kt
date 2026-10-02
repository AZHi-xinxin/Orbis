package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.HazeState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Immutable synthetic chats and callbacks; no Room, Koin, provider, preferences or business files. */
@RunWith(AndroidJUnit4::class)
class OrbisMessageBatchUiTest {
    @get:Rule val compose = createShellComposeRule()
    private fun source() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = listOf("apple one", "banana two", "apple three").map { UIMessage.user(it).toMessageNode() })

    @Composable
    private fun Preview(
        source: Conversation,
        disabled: String? = null,
        onJump: (Int) -> Unit = {},
        onPreview: suspend (Set<Uuid>, OrbisMessageBatchOperation) -> OrbisMessageBatchPreview = { ids, op -> prepareOrbisMessageBatch(source, ids, op) },
        onApply: suspend (OrbisMessageBatchPreview) -> OrbisMessageBatchResult = { error("Unexpected mutation") },
    ) {
        MaterialTheme {
            ChatListPreview(PaddingValues(0.dp), source, Settings(), remember { HazeState() }, onJump,
                disabled, onPreview, onApply)
        }
    }

    @Test fun normalClickJumpsButLongPressEntersWholeNodeSelection() {
        val c = source(); var jumped: Int? = null
        compose.setContent { Preview(c, onJump = { jumped = it }) }
        compose.onNodeWithTag("orbis-preview-row-${c.messageNodes[1].id}").performClick()
        compose.runOnIdle { assertEquals(1, jumped) }
        compose.onNodeWithTag("orbis-preview-row-${c.messageNodes[0].id}").performTouchInput { longClick() }
        compose.onNodeWithTag("orbis-preview-check-${c.messageNodes[0].id}").assertIsOn()
        compose.onNodeWithTag("orbis-preview-check-${c.messageNodes[1].id}").assertIsOff()
        compose.onNodeWithTag("orbis-preview-cancel").performClick()
        compose.onNodeWithTag("orbis-preview-check-${c.messageNodes[0].id}").assertDoesNotExist()
    }

    @Test fun selectAllUsesCurrentFilterAndChangingFilterClearsHiddenSelection() {
        val c = source(); var ids: Set<Uuid>? = null
        compose.setContent { Preview(c, onPreview = { chosen, op -> ids = chosen; prepareOrbisMessageBatch(c, chosen, op) }) }
        compose.onNodeWithTag("orbis-preview-select").performClick()
        compose.onNodeWithTag("orbis-preview-select-all").performClick()
        compose.onNodeWithTag("orbis-preview-search").performTextReplacement("apple")
        compose.onNodeWithTag("orbis-preview-delete").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-preview-select-all").performClick()
        compose.onNodeWithTag("orbis-preview-delete").performClick()
        compose.onNodeWithTag("orbis-preview-confirm").assertExists()
        compose.runOnIdle { assertEquals(setOf(c.messageNodes[0].id, c.messageNodes[2].id), ids) }
        compose.onNodeWithTag("orbis-preview-cancel-confirm").performClick()
    }

    @Test fun deleteRequiresSeparateConfirmationAndCancelNeverMutates() {
        val c = source(); var applies = 0
        compose.setContent { Preview(c, onApply = { p -> applies++; OrbisMessageBatchResult(p.operation, p.affectedCount) }) }
        compose.onNodeWithTag("orbis-preview-select").performClick()
        compose.onNodeWithTag("orbis-preview-select-all").performClick()
        compose.onNodeWithTag("orbis-preview-delete").performClick()
        compose.onNodeWithText("本次会清空当前窗口", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithTag("orbis-preview-cancel-confirm").performClick()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithTag("orbis-preview-delete").performClick()
        compose.onNodeWithTag("orbis-preview-confirm").performClick()
        compose.onNodeWithTag("orbis-preview-feedback").assertTextContains("已从当前窗口删除 3 条消息。")
        compose.runOnIdle { assertEquals(1, applies); assertEquals(3, c.messageNodes.size) }
    }

    @Test fun archiveExplainsContextRemovalAndIndependentOriginalWindow() {
        val c = source(); var applied: OrbisMessageBatchOperation? = null
        compose.setContent { Preview(c, onApply = { p -> applied = p.operation; OrbisMessageBatchResult(p.operation, p.affectedCount, Uuid.random()) }) }
        compose.onNodeWithTag("orbis-preview-row-${c.messageNodes[0].id}").performTouchInput { longClick() }
        compose.onNodeWithTag("orbis-preview-archive").performClick()
        compose.onNodeWithText("完整原窗口另存", substring = true).assertExists()
        compose.onNodeWithText("不联网、不生成摘要", substring = true).assertExists()
        compose.runOnIdle { assertNull(applied) }
        compose.onNodeWithTag("orbis-preview-confirm").performClick()
        compose.runOnIdle { assertEquals(OrbisMessageBatchOperation.ARCHIVE, applied) }
        compose.onNodeWithTag("orbis-preview-feedback").assertTextContains("原文存档", substring = true)
    }

    @Test fun changedConversationDisablesAnAlreadyOpenConfirmation() {
        val state = mutableStateOf(source())
        compose.setContent { Preview(state.value) }
        compose.onNodeWithTag("orbis-preview-select").performClick()
        compose.onNodeWithTag("orbis-preview-select-all").performClick()
        compose.onNodeWithTag("orbis-preview-delete").performClick()
        compose.onNodeWithTag("orbis-preview-confirm").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(updateAt = state.value.updateAt.plusSeconds(1)) }
        compose.onNodeWithTag("orbis-preview-confirm").assertIsNotEnabled()
        compose.onNodeWithText("记录已变化", substring = true).assertExists()
        compose.onNodeWithTag("orbis-preview-cancel-confirm").performClick()
    }

    @Test fun busyReasonBlocksNewSelectionAndAssistantChangeClearsOldSelection() {
        val state = mutableStateOf(source()); val disabled = mutableStateOf<String?>("生成尚未完成")
        compose.setContent { Preview(state.value, disabled.value) }
        compose.onNodeWithTag("orbis-preview-select").assertIsNotEnabled()
        compose.onNodeWithText("生成尚未完成").assertExists()
        compose.runOnIdle { disabled.value = null }
        compose.onNodeWithTag("orbis-preview-select").performClick()
        compose.onNodeWithTag("orbis-preview-select-all").performClick()
        compose.onNodeWithTag("orbis-preview-delete").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(assistantId = Uuid.random()) }
        compose.onNodeWithTag("orbis-preview-delete").assertDoesNotExist()
        compose.onNodeWithTag("orbis-preview-select").assertIsEnabled()
    }
}
