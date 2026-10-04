package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceTranscriptEntry
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.chat.OrbisCallFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbisCallTimelineUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val record = OrbisVoiceCallRecord("synthetic-call", "synthetic-chat", "synthetic-ai", 1000,
        connectedAtMs = 1000, endedAtMs = 566000, durationMs = 565000,
        status = OrbisVoiceCallStatus.INTERRUPTED, archiveStatus = OrbisVoiceArchiveStatus.FAILED,
        transcript = listOf(OrbisVoiceTranscriptEntry("entry-1", "user", "合成通话原文", 2000)))

    @Test fun interruptedRecordIsFoldedByDefaultAndRetryNeedsExplicitAction() {
        var retries = 0
        compose.setContent { MaterialTheme {
            OrbisCallTimelineCardContent(record, onRetry = { retries++ })
        } }
        compose.onNodeWithText("◌  通话时长 9分25秒（异常终止）").assertExists()
        compose.onNodeWithText("未归档 · 原文保留 · 可重试").assertExists()
        compose.onNodeWithText("我：合成通话原文").assertDoesNotExist()
        compose.onNodeWithTag("orbis-call-timeline-toggle").performClick()
        compose.onNodeWithText("我：合成通话原文").assertExists()
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText("重新归档").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun activeCallHasOneQuietCardAndDoesNotOfferArchive() {
        compose.setContent { MaterialTheme {
            OrbisCallTimelineCardContent(record.copy(status = OrbisVoiceCallStatus.ACTIVE,
                archiveStatus = OrbisVoiceArchiveStatus.PENDING, endedAtMs = null, durationMs = null))
        } }
        compose.onNodeWithText("◌  正在通话中").assertExists()
        compose.onNodeWithText("我：合成通话原文").assertDoesNotExist()
        compose.onNodeWithText("重新归档").assertDoesNotExist()
        compose.onNodeWithText("CALL_MODE_V1", substring = true).assertDoesNotExist()
    }

    @Test fun missingArchiveShowsSourceFallbackAndKeepsSourceActionsWithoutModelCalls() {
        var sourceActions = 0
        var retries = 0
        compose.setContent { MaterialTheme {
            OrbisCallTimelineCardContent(null, readFailed = true, onRetry = { retries++ },
                sourceFallback = {
                    Text("合成导入通话逐句原文")
                    TextButton(onClick = { sourceActions++ }) { Text("合成原消息操作") }
                })
        } }
        compose.onNodeWithTag("orbis-call-timeline-source-fallback").assertExists()
        compose.onNodeWithText("合成导入通话逐句原文").assertExists()
        compose.onNodeWithText("重新归档").assertDoesNotExist()
        compose.onNodeWithText("合成原消息操作").performClick()
        compose.runOnIdle {
            assertEquals(1, sourceActions)
            assertEquals(0, retries)
        }
    }

    @Test fun archiveLoadingAndReadableArchiveDoNotRenderSourceFallback() {
        compose.setContent { MaterialTheme {
            OrbisCallTimelineCardContent(record, sourceFallback = { Text("不应显示的重复原文") })
            OrbisCallTimelineCardContent(null, readFailed = false,
                sourceFallback = { Text("不应显示的读取中原文") })
        } }
        compose.onNodeWithText("不应显示的重复原文").assertDoesNotExist()
        compose.onNodeWithText("不应显示的读取中原文").assertDoesNotExist()
        compose.onNodeWithText("◌  正在读取通话记录…").assertExists()
    }

    @Test fun fileEntryStaysVisibleWhenCallBodyIsCollapsedAndDoesNotOpenAutomatically() {
        var opens = 0
        val file = OrbisCallFile.Workspace("/workspace/需求单.md", 100, 2000,
            "synthetic-message", "synthetic-tool", "synthetic-workspace")
        compose.setContent { MaterialTheme { Column {
            OrbisCallTimelineCardContent(record)
            OrbisCallFileAttachmentsContent(listOf(file)) { opens++ }
        } } }
        compose.onNodeWithText("我：合成通话原文").assertDoesNotExist()
        compose.onNodeWithText("文件 · 需求单.md").assertExists()
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("orbis-call-timeline-toggle").performClick()
        compose.onNodeWithTag("orbis-call-timeline-toggle").performClick()
        compose.onNodeWithText("文件 · 需求单.md").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun rawToolHistoryRequiresExplicitHumanAction() {
        var opens = 0
        compose.setContent { MaterialTheme {
            OrbisCallTimelineCardContent(record, onSourceDetails = { opens++ })
        } }
        compose.onNodeWithTag("orbis-call-source-details").assertExists()
        compose.runOnIdle { assertEquals(0, opens) }
        compose.onNodeWithTag("orbis-call-source-details").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
