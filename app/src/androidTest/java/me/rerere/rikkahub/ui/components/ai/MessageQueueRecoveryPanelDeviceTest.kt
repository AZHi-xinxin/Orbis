package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.service.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Synthetic callbacks only. Does not open real conversations, tools, network or audio. */
@RunWith(AndroidJUnit4::class)
class MessageQueueRecoveryPanelDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolated = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    @Test fun emptyPausedQueueHasCompactEntryAndRequiresExplicitResumeConfirmation() {
        var recoveryCalls = 0
        var stops = 0
        val recovery = mutableStateOf(QueueRecoveryState())
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(paused = true), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = {
                recoveryCalls++
                recovery.value = QueueRecoveryState(QueueRecoveryPhase.RUNNING, "正在检查连接…")
            }, onStopGatewayWait = { stops++ }, recovery = recovery.value,
        ) } }
        compose.onNodeWithText("消息已暂停 · 0 条待发").assertExists()
        compose.onNodeWithTag("chat_queue_recover").assertDoesNotExist()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, recoveryCalls); assertEquals(0, stops) }
        compose.onNodeWithTag("chat_queue_confirm_resume").performClick()
        compose.onNodeWithTag("chat_queue_recover").assertIsNotEnabled()
        compose.onNodeWithText("正在检查连接…").assertExists()
        compose.runOnIdle { assertEquals(1, recoveryCalls); assertEquals(0, stops) }
        compose.onNodeWithText("停止旧轮并核对？").assertDoesNotExist()
    }

    @Test fun pendingRecoveryKeepsInputAndItsExplicitManagementControls() {
        val text = "synthetic unsent input"
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text(text)))
        var removals = 0
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(listOf(item), paused = true), onRemove = { removals++ },
            onBeginEdit = { null }, onFinishEdit = { _, _ -> }, onResume = {},
            recovery = QueueRecoveryState(QueueRecoveryPhase.PENDING, "连接仍在收尾，请稍后再试。", 1),
        ) } }
        compose.onNodeWithText(text, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText(text, substring = true).assertExists()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, removals) }
    }

    @Test fun heldCallControlUsesPlainLabelAndDoesNotExposeProtocolByDefault() {
        val marker = "ORBIS_VOICE_CALL_V1 synthetic-control"
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text(marker)), voiceCallId = "synthetic-call",
            voiceCallKind = "begin", recoveryHeldReason = "stale_call")
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(listOf(item)), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = {},
            recovery = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "已恢复，旧通话记录保留待核对。", 1, 1, true),
        ) } }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("通话开始记录", substring = true).assertExists()
        compose.onNodeWithText(marker, substring = true).assertDoesNotExist()
        compose.onNodeWithText("编辑为新消息").assertDoesNotExist()
        compose.onNodeWithText("原文").performClick()
        compose.onNodeWithTag("chat_queue_raw_message").assertTextEquals(marker)
    }

    @Test fun historicalSuccessNeverShowsACardOrReturnsAfterLeavingChat() {
        var calls = 0
        val result = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "已恢复，可以继续聊天。")
        val notices = mutableStateOf(mapOf("chat" to result))
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible.value) MessageQueuePanel(
            state = MessageQueueState(), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = { calls++ },
            recovery = notices.value["chat"] ?: QueueRecoveryState(),
            onDismissRecoveryResult = { expected ->
                notices.value = consumeQueueRecoveryNotice(notices.value, "chat", expected)
            },
        ) } }
        compose.onNodeWithText("新消息输入已恢复").assertDoesNotExist()
        compose.onNodeWithTag("chat_queue_recover").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun historicalSuccessDoesNotCreateCardsInEitherConversation() {
        val first = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "第一个会话已恢复")
        val second = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "第二个会话已恢复")
        val notices = mutableStateOf(mapOf("first" to first, "second" to second))
        val conversation = mutableStateOf("first")
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = { fail("dismissal must not resume work") },
            recovery = notices.value[conversation.value] ?: QueueRecoveryState(),
            onDismissRecoveryResult = { expected ->
                notices.value = consumeQueueRecoveryNotice(notices.value, conversation.value, expected)
            },
        ) } }
        compose.onNodeWithText("第一个会话已恢复").assertDoesNotExist()
        compose.runOnIdle { conversation.value = "second" }
        compose.onNodeWithText("第二个会话已恢复").assertDoesNotExist()
        compose.runOnIdle { conversation.value = "first" }
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.runOnIdle { assertSame(second, notices.value["second"]) }
    }

    @Test fun historicalSuccessDoesNotHideALaterRealPauseOrExplicitManagementProgress() {
        val result = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "旧恢复结果")
        val notices = mutableStateOf(mapOf("chat" to result))
        val queue = mutableStateOf(MessageQueueState())
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = queue.value, onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = {},
            recovery = notices.value["chat"] ?: QueueRecoveryState(),
            onDismissRecoveryResult = { expected ->
                notices.value = consumeQueueRecoveryNotice(notices.value, "chat", expected)
            },
        ) } }
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.runOnIdle {
            queue.value = MessageQueueState(paused = true)
            notices.value = mapOf("chat" to QueueRecoveryState(QueueRecoveryPhase.PENDING, "新等待尚未核对"))
        }
        compose.onNodeWithText("旧恢复结果").assertDoesNotExist()
        compose.onNodeWithText("新等待尚未核对").assertDoesNotExist()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("新等待尚未核对").assertExists()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled()
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.runOnIdle {
            notices.value = mapOf("chat" to QueueRecoveryState(QueueRecoveryPhase.RUNNING, "正在核对新等待"))
        }
        compose.onNodeWithText("正在核对新等待").assertExists()
        compose.onNodeWithTag("chat_queue_recover").assertIsNotEnabled()
    }

    @Test fun closingManagementConsumesOnlyPresentationAndKeepsHeldMessagesWithoutDispatching() {
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text("synthetic retained input")),
            recoveryHeldReason = "previous_input_before_fresh_recovery")
        val queue = MessageQueueState(listOf(item))
        val result = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "已恢复，旧内容仍保留", 1, 0, true)
        val notices = mutableStateOf(mapOf("chat" to result))
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = queue, onRemove = { fail("dismissal must not remove input") }, onBeginEdit = { null },
            onFinishEdit = { _, _ -> fail("dismissal must not edit input") },
            onResume = { fail("dismissal must not dispatch input") },
            recovery = notices.value["chat"] ?: QueueRecoveryState(),
            onDismissRecoveryResult = { expected ->
                notices.value = consumeQueueRecoveryNotice(notices.value, "chat", expected)
            },
        ) } }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("已恢复，旧内容仍保留").assertExists()
        compose.onNodeWithTag("chat_queue_close_management").performClick()
        compose.onNodeWithText("新消息输入已恢复").assertDoesNotExist()
        compose.onNodeWithText("保留待核对 · 1").assertExists()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("synthetic retained input", substring = true).assertExists()
        compose.runOnIdle {
            assertSame(item, queue.messages.single())
            assertTrue(notices.value.isEmpty())
        }
    }

    @Test fun laterPauseDoesNotShowStaleSuccessMessage() {
        val queue = mutableStateOf(MessageQueueState())
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = queue.value, onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = {},
            recovery = QueueRecoveryState(QueueRecoveryPhase.SUCCESS, "上一次成功记录"),
        ) } }
        compose.onNodeWithText("上一次成功记录").assertDoesNotExist()
        compose.runOnIdle { queue.value = MessageQueueState(paused = true) }
        compose.onNodeWithText("上一次成功记录").assertDoesNotExist()
        compose.onNodeWithText("消息已暂停 · 0 条待发").assertExists()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("上一次成功记录").assertDoesNotExist()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled()
    }

    @Test fun historicalFailureWithoutRealQueuePauseDoesNotBlockHealthyChat() {
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = { fail("historical notice must not recover a healthy chat") },
            recovery = QueueRecoveryState(QueueRecoveryPhase.PENDING, "历史连接核对结果"),
        ) } }
        compose.onNodeWithTag("chat_message_queue").assertDoesNotExist()
        compose.onNodeWithText("当前消息队列卡顿").assertDoesNotExist()
        compose.onNodeWithText("历史连接核对结果").assertDoesNotExist()
    }

    @Test fun heldOrdinaryInputRequiresExplicitConfirmationAsANewMessage() {
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text("synthetic old input")),
            recoveryHeldReason = "previous_input_before_fresh_recovery")
        val submitted = mutableListOf<List<UIMessagePart>>()
        var recoveryCalls = 0
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(listOf(item)), onRemove = {}, onBeginEdit = { item },
            onFinishEdit = { _, parts -> if (parts != null) submitted.add(parts) },
            onResume = { recoveryCalls++ },
            recovery = QueueRecoveryState(QueueRecoveryPhase.SUCCESS,
                "新消息输入已恢复；旧待发信息保留，未重发。"),
        ) } }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("编辑为新消息").performClick()
        compose.onNodeWithTag("chat_queue_fresh_input_notice").assertExists()
        compose.runOnIdle { assertTrue(submitted.isEmpty()); assertEquals(0, recoveryCalls) }
        compose.onNodeWithTag("chat_queue_edit_text").performTextReplacement("synthetic new input")
        compose.onNodeWithText("作为新消息发送").performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf(UIMessagePart.Text("synthetic new input"))), submitted)
            assertEquals(0, recoveryCalls)
        }
    }

    @Test fun cancellingHeldOrdinaryEditNeverSendsOrResumesAnything() {
        val item = QueuedMessage(parts = listOf(UIMessagePart.Text("synthetic preserved input")),
            recoveryHeldReason = "previous_input_before_fresh_recovery")
        var submitted = 0
        var cancellations = 0
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(listOf(item)), onRemove = {}, onBeginEdit = { item },
            onFinishEdit = { _, parts -> if (parts == null) cancellations++ else submitted++ },
            onResume = { fail("editing must not resume the old queue") },
        ) } }
        compose.onNodeWithText("保留待核对 · 1").assertExists()
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("编辑为新消息").performClick()
        compose.onNodeWithText(compose.activity.getString(me.rerere.rikkahub.R.string.cancel)).performClick()
        compose.onNodeWithTag("chat_queue_edit_text").assertDoesNotExist()
        compose.onNodeWithText("synthetic preserved input", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(1, cancellations) }
    }

    @Test fun closingManagementCannotReleaseRealPauseOrStopAnything() {
        val queue = MessageQueueState(paused = true)
        val result = QueueRecoveryState(QueueRecoveryPhase.PENDING, "旧工具仍待核对")
        val notices = mutableStateOf(mapOf("chat" to result))
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = queue, onRemove = { fail("must preserve input") }, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = { fail("close must not resume") },
            onStopGatewayWait = { fail("close must not stop") },
            recovery = notices.value["chat"] ?: QueueRecoveryState(),
            onDismissRecoveryResult = { notices.value = consumeQueueRecoveryNotice(notices.value, "chat", it) },
        ) } }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithText("旧工具仍待核对").assertExists()
        compose.onNodeWithTag("chat_queue_close_management").performClick()
        compose.onNodeWithText("旧工具仍待核对").assertDoesNotExist()
        compose.onNodeWithText("消息已暂停 · 0 条待发").assertExists()
        compose.runOnIdle { assertTrue(queue.paused) }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled()
        compose.onNodeWithTag("chat_queue_stop_gateway").assertIsEnabled()
    }

    @Test fun stoppingOldWorkIsConfirmedSeparatelyAndNeverAlsoResumesQueue() {
        var stops = 0
        compose.setContent { MaterialTheme { MessageQueuePanel(
            state = MessageQueueState(paused = true), onRemove = {}, onBeginEdit = { null },
            onFinishEdit = { _, _ -> }, onResume = { fail("stop must not also resume") },
            onStopGatewayWait = { stops++ },
        ) } }
        compose.onNodeWithTag("chat_queue_manage").performClick()
        compose.onNodeWithTag("chat_queue_stop_gateway").performClick()
        compose.runOnIdle { assertEquals(0, stops) }
        compose.onNodeWithText("停止并核对").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("消息已暂停 · 0 条待发").assertExists()
        compose.onNodeWithTag("chat_queue_recover").assertIsEnabled()
    }
}
