package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallProtocol
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceTranscriptEntry
import me.rerere.rikkahub.testutil.IsolatedVoiceCallArchive
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic cards and nonce-cache archives only; no ChatService, models, audio or real chat. */
@RunWith(AndroidJUnit4::class)
class OrbisVoiceCallUiTest {
    @get:Rule val compose = createShellComposeRule()
    // A dynamic Clock.now default can compare equal within the creation tick and
    // be omitted by Json.Default, then appear on the next serialization. Snapshot
    // every field explicitly; do not hide time fields or weaken mutation checks.
    private val snapshotJson = Json { encodeDefaults = true }

    private fun snapshot(message: UIMessage): String {
        val encoded = snapshotJson.encodeToString(message)
        assertTrue(snapshotJson.parseToJsonElement(encoded).jsonObject.containsKey("createdAt"))
        assertEquals(message, snapshotJson.decodeFromString<UIMessage>(encoded))
        return encoded
    }

    private var archive: IsolatedVoiceCallArchive? = null
    private val summary = "合成摘要：约好周末去公园，出门前确认天气。"
    private val record = OrbisVoiceCallRecord("synthetic-call", "synthetic-window", "synthetic-assistant", 1000,
        connectedAtMs = 2000, endedAtMs = 255000, durationMs = 253000,
        status = OrbisVoiceCallStatus.ENDED, archiveStatus = OrbisVoiceArchiveStatus.READY,
        summary = summary, modelTranscript = "用户：周末去公园吧。\nAI：好，出门前确认天气。",
        transcript = listOf(OrbisVoiceTranscriptEntry("user-turn", "user", "周末去公园吧。", 3000),
            OrbisVoiceTranscriptEntry("assistant-turn", "assistant", "好，出门前确认天气。", 4000)),
        sourceMessageIds = listOf("source-node-1", "source-node-2"), sourceNodesJson = "[{\"synthetic\":true}]")

    @After fun removeOnlyOwnedArchive() {
        compose.waitForIdle()
        archive?.close()
    }

    @Test fun summaryCardStartsFoldedTogglesAndNeverRewritesModelMessage() {
        val message = UIMessage.assistant(OrbisVoiceCallProtocol.summary(record))
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "summary")
        val original = snapshot(message)
        compose.setContent { MaterialTheme { OrbisVoiceCallMessageCard(message) } }
        val header = compose.onNode(hasText("通话时长 4:13", substring = true) and hasClickAction())
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        compose.onNodeWithText(summary).assertDoesNotExist()
        header.performClick()
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(original, snapshot(message))
            assertEquals(summary, OrbisVoiceCallProtocol.parse(message.toText())!!.summary)
        }
        header.performClick()
        compose.onNodeWithText(summary).assertDoesNotExist()
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        compose.runOnIdle { assertEquals(original, snapshot(message)) }
    }

    @Test fun beginAndEndCardsShowHumanLabelsWithoutProtocolJsonOrInventedDuration() {
        val begin = UIMessage.user(OrbisVoiceCallProtocol.begin(record.id))
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "begin")
        val end = UIMessage.user(OrbisVoiceCallProtocol.end(record.copy(durationMs = null)))
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "archive")
        val pending = end.copy(orbisVoiceCallKind = "ended_notice")
        var message by mutableStateOf(begin)
        compose.setContent { MaterialTheme { OrbisVoiceCallMessageCard(message) } }
        compose.onNodeWithText("已进入语音通话", substring = true).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(OrbisVoiceCallProtocol.PREFIX, substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(begin, message); message = end }
        compose.onNodeWithText("通话结束 · 记录归档", substring = true).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("正在整理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0:00", substring = true).assertDoesNotExist()
        compose.onNodeWithText("CALL_MODE_V1", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(end, message); message = pending }
        compose.onNodeWithText("通话结束 · 记录待整理", substring = true).assertIsDisplayed()
    }

    @Test fun summaryWithoutConfirmedDurationShowsEndOnlyAndStillExpands() {
        val message = UIMessage.assistant(OrbisVoiceCallProtocol.summary(record.copy(durationMs = null)))
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "summary")
        compose.setContent { MaterialTheme { OrbisVoiceCallMessageCard(message) } }
        compose.onNodeWithText("通话时长", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0:00", substring = true).assertDoesNotExist()
        compose.onNode(hasText("通话结束", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText(summary).assertIsDisplayed()
    }

    @Test fun assistantArchiveJsonDoesNotCreateASecondEndCardOrClaimOngoingProgress() {
        val request = UIMessage.user(OrbisVoiceCallProtocol.end(record))
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "archive")
        val answer = UIMessage.assistant("{\"summary\":\"合成摘要\",\"transcript\":\"合成全文\"}")
            .copy(orbisVoiceCallId = record.id, orbisVoiceCallKind = "archive")
        val original = snapshot(answer)
        compose.setContent { MaterialTheme { Column {
            OrbisVoiceCallMessageCard(request)
            OrbisVoiceCallMessageCard(answer)
        } } }
        compose.onAllNodesWithTag("orbis-call-record").assertCountEquals(1)
        compose.onNodeWithText("通话结束 · 记录归档", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在整理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("transcript", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, snapshot(answer)) }
    }

    @Test fun minimizedButtonStaysAboveLaterScaffoldWithoutBlockingOtherScreenButtons() {
        var reopened = 0
        var backgroundClicks = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                OrbisVoiceCallReopenButton { reopened++ }
                Scaffold { contentPadding ->
                    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.BottomCenter) {
                        Button(onClick = { backgroundClicks++ }) { Text("合成背景按钮") }
                    }
                }
            }
        } }
        compose.onNodeWithTag("orbis-call-reopen").assertIsDisplayed().performClick()
        compose.onNodeWithText("合成背景按钮").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, reopened); assertEquals(1, backgroundClicks) }
    }

    @Test fun historyScopesAssistantAndReadyRetryOnlyDispatchesRestoreWithoutChangingArchive() {
        val fixture = IsolatedVoiceCallArchive().also { archive = it }
        val repository = fixture.repository()
        val ready = record.copy(error = "synthetic-page-commit-failure")
        runBlocking {
            repository.create(ready)
            repository.create(record.copy(id = "foreign-call", assistantId = "different-assistant",
                summary = "其它 AI 的合成摘要不可见"))
        }
        val before = fixture.bytes()
        val retried = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            OrbisVoiceCallHistorySheet(repository, assistantId = record.assistantId,
                onRetry = { retried += it }, onDismiss = {})
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("其它 AI 的合成摘要不可见").assertDoesNotExist()
        compose.onNodeWithText(summary).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("恢复聊天摘要").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("恢复聊天摘要").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(record.id), retried) }
        compose.onNodeWithText("实际逐条记录").performScrollTo().performClick()
        compose.onNodeWithText("周末去公园吧。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, fixture.bytes()) }
        assertEquals(ready, runBlocking { fixture.repository().get(ready.id) })
    }

    @Test fun aCallThatNeverConnectedCannotAskAiToRewriteItsArchive() {
        val fixture = IsolatedVoiceCallArchive().also { archive = it }
        val repository = fixture.repository()
        val neverConnected = record.copy(id = "never-connected", connectedAtMs = null, durationMs = null,
            archiveStatus = OrbisVoiceArchiveStatus.FAILED, summary = "合成记录：未接通。",
            modelTranscript = null, transcript = emptyList(), sourceMessageIds = emptyList(), sourceNodesJson = null)
        runBlocking { repository.create(neverConnected) }
        compose.setContent { MaterialTheme {
            OrbisVoiceCallHistorySheet(repository, assistantId = record.assistantId,
                onRetry = { error("A never-connected call must not request model work") }, onDismiss = {})
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(neverConnected.summary!!).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(neverConnected.summary!!).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("记录 ID：never-connected").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("请本次 AI 重新整理").assertDoesNotExist()
        compose.onNodeWithText("恢复聊天摘要").assertDoesNotExist()
    }

    @Test fun returnScreenWaitsForDurableCommitAndObservedFoldedCard() {
        val fixture = IsolatedVoiceCallArchive().also { archive = it }
        val repository = fixture.repository()
        runBlocking { repository.create(record.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING,
            summary = null, modelTranscript = null)) }
        var summaryVisible by mutableStateOf(false)
        var dismissed = 0
        compose.setContent { MaterialTheme {
            OrbisVoiceCallReturnOverlay(repository, record.id, runtimeEnding = false, runtimeError = null,
                summaryVisible = summaryVisible, onDismiss = { dismissed++ })
        } }
        compose.onNodeWithTag("orbis-call-return").assertIsDisplayed()
        compose.onNodeWithText("正在回到聊天").assertIsDisplayed()
        compose.onNodeWithText("麦克风已关闭").assertIsDisplayed()
        compose.onNodeWithText(summary).assertDoesNotExist()
        compose.onNodeWithText("transcript", substring = true).assertDoesNotExist()
        runBlocking { repository.update(record.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = record.summary, modelTranscript = record.modelTranscript) } }
        compose.waitForIdle()
        compose.onNodeWithTag("orbis-call-return").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
        runBlocking { repository.update(record.id) { it.copy(chatCommitted = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("orbis-call-return").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed); summaryVisible = true }
        compose.waitUntil(5000) { dismissed == 1 }
        compose.onNodeWithTag("orbis-call-return").assertDoesNotExist()
        assertEquals(summary, runBlocking { repository.get(record.id) }!!.summary)
    }

    @Test fun pendingReturnHasAnExitWithoutCancellingOrRewritingArchive() {
        val fixture = IsolatedVoiceCallArchive().also { archive = it }
        val repository = fixture.repository()
        runBlocking { repository.create(record.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING,
            summary = null, modelTranscript = null)) }
        val before = fixture.bytes()
        var show by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (show) OrbisVoiceCallReturnOverlay(repository, record.id, false, null, false, onDismiss = { show = false })
        } }
        compose.onNodeWithText("先返回聊天").performClick()
        compose.onNodeWithTag("orbis-call-return").assertDoesNotExist()
        assertEquals(before, fixture.bytes())
        assertEquals(OrbisVoiceArchiveStatus.GENERATING, runBlocking { repository.get(record.id) }!!.archiveStatus)
    }

    @Test fun failedReturnExplainsRecoveryAndDoesNotRetryTheModelOrExposeItsError() {
        val fixture = IsolatedVoiceCallArchive().also { archive = it }
        val repository = fixture.repository()
        runBlocking { repository.create(record.copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED,
            summary = null, modelTranscript = null, error = "synthetic-private-provider-error")) }
        val before = fixture.bytes()
        var show by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (show) OrbisVoiceCallReturnOverlay(repository, record.id, false, null, false, onDismiss = { show = false })
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("通话已结束").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记录尚未整理完成，可到「通话记录」查看详情和已保存的内容。").assertIsDisplayed()
        compose.onNodeWithText("synthetic-private-provider-error").assertDoesNotExist()
        compose.onNodeWithText("返回聊天").performClick()
        compose.onNodeWithTag("orbis-call-return").assertDoesNotExist()
        assertEquals(before, fixture.bytes())
    }
}
