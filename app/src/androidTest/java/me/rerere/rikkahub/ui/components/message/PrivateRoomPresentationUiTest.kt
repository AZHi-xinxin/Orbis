package me.rerere.rikkahub.ui.components.message

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.privateroom.PRIVATE_ROOM_CONTENT_HIDDEN
import me.rerere.rikkahub.R
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.pages.orbis.OrbisCallSourceMessages
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.Clock

/** Synthetic UI only; no room, provider, conversation repository or service is opened. */
@RunWith(AndroidJUnit4::class)
class PrivateRoomPresentationUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val secret = "SYNTHETIC_PRIVATE_TEXT_NOT_VISIBLE"
    private val publicReply = "已完成操作，请打开设置查看恢复指引。"
    private var spoken: String? = null
    private val tts = object : me.rerere.rikkahub.ui.hooks.CustomTtsState {
        override val isAvailable = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val isSpeaking = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override val currentChunk = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val totalChunks = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val playbackState = kotlinx.coroutines.flow.MutableStateFlow(me.rerere.tts.model.PlaybackState())
        override fun speak(text: String, flushCalled: Boolean) { spoken = text }
        override fun stop() {}
        override fun pause() {}
        override fun resume() {}
        override fun skipNext() {}
        override fun fastForward(ms: Long) {}
        override fun setSpeed(speed: Float) {}
        override fun cleanup() {}
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source() = UIMessage.assistant(publicReply).copy(parts = listOf(
        UIMessagePart.Text(publicReply),
        UIMessagePart.Tool("call", "orbis_private_room_write", "{\"body\":\"$secret", listOf(UIMessagePart.Text(secret))),
        UIMessagePart.Reasoning(secret),
    ))
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(init = true, providers = emptyList(), displaySetting =
                    DisplaySetting(enableMessageGenerationHapticEffect = false,
                        showThinkingContent = true, autoCloseThinking = true)),
                LocalNavController provides Navigator(mutableListOf()),
                me.rerere.rikkahub.ui.context.LocalTTSState provides tts,
            ) { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = { content() })
            } } }
        }
    }
    private fun assertPrivateRecordsAbsent() {
        compose.onNodeWithText(secret, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("输入与结果").assertDoesNotExist()
    }

    @Test fun mainChatShowsAndCopiesReplyButCannotEditOrOpenPrivateRecords() {
        val message = source()
        val originalText = message.toText()
        var actions = 0
        show {
            ChatMessage(message.toMessageNode(), onFork = { actions++ }, onRegenerate = { actions++ },
                onEdit = { actions++ }, onShare = { actions++ }, onDelete = { actions++ },
                onUpdate = { actions++ }, onToolApproval = { _, _, _, _ -> actions++ })
        }
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
        compose.onNodeWithText(context.getString(R.string.edit)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(publicReply, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.runOnIdle { assertEquals(0, actions); assertEquals(originalText, message.toText()) }
        compose.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.tts)).performClick()
        compose.runOnIdle { assertEquals(publicReply, spoken) }
        compose.onNodeWithContentDescription(context.getString(R.string.regenerate)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.edit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.share)).performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun longMixedToolChainKeepsStandardIconToolbarAndOriginalBranches() {
        val original = source().copy(parts = List(24) { index ->
            UIMessagePart.Tool("tool-$index", "ordinary_tool", "{}", listOf(UIMessagePart.Text("result-$index")))
        } + source().parts)
        var regeneration = 0
        show { ChatMessage(original.toMessageNode(), onFork = {}, onRegenerate = { regeneration++ },
            onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {}) }
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).performScrollTo().assertExists()
        compose.onNodeWithText(context.getString(R.string.copy)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.share)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.regenerate)).performClick()
        compose.runOnIdle { assertEquals(1, regeneration); assertEquals(25, original.getTools().size) }
        assertPrivateRecordsAbsent()
    }

    @Test fun previousBranchRemainsBrowsableWithUnavailableHistoryWriter() {
        val versions = listOf(UIMessage.assistant("first saved reply"), UIMessage.assistant("second saved reply"),
            UIMessage.assistant("third saved reply"))
        val node = versions.first().toMessageNode().copy(messages = versions, selectIndex = 2)
        val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = kotlin.uuid.Uuid.random(),
            messageNodes = listOf(UIMessage.user("synthetic question").toMessageNode(), node))
        val owner = kotlinx.coroutines.SupervisorJob()
        val lane = me.rerere.rikkahub.service.HumanContinuationLane(conversation,
            kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.Main.immediate),
            persist = { false }, onFailure = { throw it }, checkpoint = { false })
        try {
            show {
                val selected = lane.state.collectAsState().value.messageNodes.last()
                ChatMessage(selected, onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {},
                    onUpdate = { lane.selectBranch(it.id, it.selectIndex) })
            }
            compose.onNodeWithText("3/3").assertExists()
            compose.onNodeWithContentDescription("上一分支").performScrollTo().performClick()
            compose.onNodeWithText("second saved reply", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("2/3").assertExists()
            compose.onNodeWithContentDescription("上一分支").performClick()
            compose.onNodeWithText("first saved reply", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("下一分支").performClick()
            compose.onNodeWithText("2/3").assertExists()
            compose.runOnIdle { assertEquals(versions, lane.state.value.messageNodes.last().messages) }
        } finally { owner.cancel() }
    }

    @Test fun pendingStreamShowsProseBeforeToolNameButNotReasoningOrPartialArguments() {
        val message = source().copy(privateRoomPendingPresentation = true, parts = listOf(
            UIMessagePart.Text(publicReply), UIMessagePart.Tool("stream", "orbis_pr", secret),
            UIMessagePart.Reasoning(secret)))
        show {
            ChatMessage(message.toMessageNode(),
                loading = true, onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {})
        }
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
        compose.onNodeWithText(PRIVATE_ROOM_CONTENT_HIDDEN).assertDoesNotExist()
    }

    @Test fun standaloneToolPreviewDoesNotDisplayParsedArgumentsOutputsOrHeaderActions() {
        val tool = source().getTools().single()
        var headers = 0
        show {
            DefaultToolPreview(ToolUIContext(tool, JsonPrimitive(secret), JsonPrimitive(secret), true),
                headerActions = { headers++ })
        }
        compose.onNodeWithText(PRIVATE_ROOM_CONTENT_HIDDEN).assertExists()
        assertPrivateRecordsAbsent()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, headers) }
    }

    @Test fun rawCallViewerShowsPublicReplyButCannotExpandThePrivateRecord() {
        show { OrbisCallSourceMessages(listOf(source().toMessageNode())) }
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun rawThinkTagContentDoesNotLeakThroughPublicReplyRendering() {
        val message = source().copy(parts = listOf(source().getTools().single(),
            UIMessagePart.Text("<think>$secret</think>$publicReply")))
        show { ChatMessage(message.toMessageNode(), loading = true,
            onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {}) }
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
    }

    private fun reasoning(text: String, seconds: Int): UIMessagePart.Reasoning {
        val start = Instant.parse("2026-10-04T01:00:00Z")
        return UIMessagePart.Reasoning(text, start, start + seconds.seconds)
    }

    @Test fun ordinaryReasoningStillShowsElapsedTimeAndCanExpandWithoutAnyPrivateRoomCall() {
        val normal = reasoning("ordinary visible reasoning", 3)
        show { MessagePartsBlock(assistant = null, role = MessageRole.ASSISTANT, model = null,
            parts = listOf(normal, UIMessagePart.Text(publicReply)), annotations = emptyList(), loading = false) }
        compose.onNodeWithText(context.getString(R.string.deep_thinking_seconds, 3f), useUnmergedTree = true)
            .assertExists().performClick()
        compose.onNodeWithText(normal.reasoning, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("正在回复…").assertDoesNotExist()
    }

    @Test fun liveOrdinaryReasoningTimerActuallyGrowsWhileProseAndExpansionRemainAvailable() {
        val normal = UIMessagePart.Reasoning("ordinary live reasoning without a heading",
            createdAt = Clock.System.now() - 1.seconds, finishedAt = null)
        val format = context.getString(R.string.deep_thinking_seconds)
        val titlePattern = Regex(Regex.escape(format.substringBefore("%1\$.1f")) +
            "([0-9]+[.,][0-9]+)" + Regex.escape(format.substringAfter("%1\$.1f")))
        val timerTitle = SemanticsMatcher("localized live reasoning seconds") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { titlePattern.matches(it.text) } == true
        }
        fun displayedSeconds(): Double? = compose.onAllNodes(timerTitle, useUnmergedTree = true)
            .fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Text)
            ?.firstNotNullOfOrNull { titlePattern.matchEntire(it.text)?.groupValues?.get(1) }
            ?.replace(',', '.')?.toDoubleOrNull()
        show { MessagePartsBlock(assistant = null, role = MessageRole.ASSISTANT, model = null,
            // Only the live tail can keep reasoning time. Prose after reasoning closes that step.
            parts = listOf(UIMessagePart.Text(publicReply), normal), annotations = emptyList(), loading = true) }
        compose.onNode(timerTitle, useUnmergedTree = true).assertExists()
        val first = requireNotNull(displayedSeconds()) { "Live reasoning elapsed title is missing" }
        // Wall time drives the production timer; observe it rather than advancing a fake timer.
        compose.waitUntil(3_000) { (displayedSeconds() ?: first) > first }
        compose.onNode(timerTitle, useUnmergedTree = true).performClick()
        compose.onNodeWithText(normal.reasoning, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("正在回复", substring = true).assertDoesNotExist()
    }

    @Test fun privateOperationPreservesEarlierReasoningTimingAndHidesOnlyTheLaterReasoning() {
        val before = reasoning("ordinary reasoning before room", 3)
        val message = source().copy(privateRoomContentHidden = true, parts = listOf(before,
            source().getTools().single(), reasoning(secret, 7), UIMessagePart.Text(publicReply)))
        show { ChatMessage(message.toMessageNode(), onFork = {}, onRegenerate = {}, onEdit = {},
            onShare = {}, onDelete = {}, onUpdate = {}) }
        compose.onNodeWithText(context.getString(R.string.deep_thinking_seconds, 3f), useUnmergedTree = true)
            .assertExists().performClick()
        compose.onNodeWithText(before.reasoning, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.deep_thinking_seconds, 7f), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
    }

    @Test fun pendingPrivateNameDoesNotCoverReasoningThatPrecedesIt() {
        val before = reasoning("ordinary reasoning before streamed tool", 3)
        val message = source().copy(privateRoomPendingPresentation = true, parts = listOf(before,
            UIMessagePart.Tool("stream", "orbis_pr", secret), reasoning(secret, 7), UIMessagePart.Text(publicReply)))
        show { ChatMessage(message.toMessageNode(), loading = true, onFork = {}, onRegenerate = {}, onEdit = {},
            onShare = {}, onDelete = {}, onUpdate = {}) }
        compose.onNodeWithText(context.getString(R.string.deep_thinking_seconds, 3f), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.deep_thinking_seconds, 7f), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(publicReply, useUnmergedTree = true).assertExists()
        assertPrivateRecordsAbsent()
    }

    @Test fun ordinaryToolsOnBothSidesKeepTheirRowsAndOriginalApprovalCallback() {
        val before = UIMessagePart.Tool("ordinary-before-id", "ordinary_before", "{}",
            approvalState = ToolApprovalState.Pending)
        val after = UIMessagePart.Tool("ordinary-after-id", "ordinary_after", "{}",
            output = listOf(UIMessagePart.Text("{\"ok\":true}")))
        val message = source().copy(parts = listOf(before, UIMessagePart.Text("between tools"),
            source().getTools().single(), after, UIMessagePart.Text(publicReply)))
        var approvedId: String? = null
        show { ChatMessage(message.toMessageNode(), onFork = {}, onRegenerate = {}, onEdit = {},
            onShare = {}, onDelete = {}, onUpdate = {},
            onToolApproval = { id, approved, _, _ -> if (approved) approvedId = id }) }
        compose.onNodeWithText(context.getString(R.string.chat_message_tool_call_generic, before.toolName),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.chat_message_tool_call_generic, after.toolName),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithText("此次允许").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(before.toolCallId, approvedId); assertEquals(3, message.getTools().size) }
        compose.onNodeWithText(secret, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
