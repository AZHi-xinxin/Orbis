package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.web.dto.toDto

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.checkpoint.AndroidGenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.isConversationNotification
import me.rerere.rikkahub.data.orbis.FreshHumanInputRecoveryStore
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStorage
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.OrbisCompactionRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSManager
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/**
 * Actual ChatService enqueue -> production Job -> cancelVoiceCallReply -> Room/journal -> queue.
 * Unlike policy-only tests, these never write partialSafelySaved or call settlement helpers.
 * Requires the isolated runner: only fixture-owned FilesManager/SettingsStore bindings; no app modules,
 * user DB/settings, camera, mic, UI, ST or external requests. Run this class in its own process.
 * Persistence gates and the actual ingress mutex only delay work; their implementations remain real.
 * A legacy hold fixture writes a real restriction, never a successful remote ACK or settlement.
 */
@RunWith(AndroidJUnit4::class)
class ChatServiceVoiceCancellationDeviceTest {
    @Test fun tabletopRecoveryWindowKeepsOriginalHistoryAndMemory() = runBlocking {
        fixture(maxRequests=2,connectCall=false) { it.assertTabletopCompanion(false,recovered=true) }
    }

    @Test fun tabletopWakeUsesOriginalHistoryMemoryIdentityAndTools() = runBlocking {
        fixture(maxRequests=2,connectCall=false) { it.assertTabletopCompanion(false) }
    }
    @Test fun tabletopToolsAlsoWorkFromAnotherWindowOfTheSameAssistant() = runBlocking {
        fixture(maxRequests=2,connectCall=false) { it.assertTabletopCompanion(true) }
    }

    @Test fun pausedWindowCanRetryUnansweredEarlierInputWithoutReplacingLaterUser() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertEarlierTextRegeneration(hasReply = false) }
    }
    @Test fun pausedWindowCanRegenerateEarlierAssistantWithoutChangingLaterHistory() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertEarlierTextRegeneration(assistantRetry = true) }
    }
    @Test fun gardenRetryAssistantKeepsOriginalBranchAndRejectsStaleOrBusyActions() = runBlocking {
        fixture(connectCall=false) { it.assertGardenRegeneration(assistantRetry=true, recovered=false) }
    }
    @Test fun gardenRepeatHumanWorksInRecoveredDisplayedLane() = runBlocking {
        fixture(connectCall=false) { it.assertGardenRegeneration(assistantRetry=false, recovered=true) }
    }
    @Test fun gardenQuickSendDoesNotRestoreRemovedPin() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("unpin") }
    }
    @Test fun gardenQuickSendKeepsPendingReceiptAcrossDrawerReopenWithoutResending() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenPendingReceipt() }
    }
    @Test fun gardenQuickSendDoesNotResumeOldQueuedInput() = runBlocking {
        fixture(maxRequests = 0, connectCall = false) { it.assertGardenOldQueue() }
    }
    @Test fun gardenQuickSendDoesNotUndoPinChangedAfterWindowLoaded() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("pinned") }
    }
    @Test fun recoveryCanRegenerateEarlierTextAndSaveWithoutChangingLaterHistory() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertEarlierTextRegeneration() }
    }
    @Test fun gardenQuickImageOnlyUsesOriginalWindowAndNormalProviderRoute() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("image") }
    }
    @Test fun gardenQuickSendKeepsReceiptAfterLocalSaveHandoff() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("save-handoff") }
    }
    @Test fun gardenQuickSendWorksInOrdinaryWindow() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("ordinary") }
    }
    @Test fun gardenQuickSendWorksWithStaleRecoveryLatch() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("local") }
    }
    @Test fun gardenQuickSendWorksWithUnconfirmedOldGatewayHold() = runBlocking {
        fixture(maxRequests = 1, connectCall = false) { it.assertGardenQuickSend("gateway") }
    }
    @Test fun balanceFailureThenRegenerateToolsCanEditOriginalHistory() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertFailedRequestThenRegenerate(false) }
    }
    @Test fun stopBeforeFirstTokenThenRegenerateToolsCanEditOriginalHistory() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertFailedRequestThenRegenerate(true) }
    }
    @Test fun failedRecoveryThenAnotherWindowDoesNotContaminateOriginalRegeneration() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertFailedRequestThenRegenerate(false, true) }
    }
    @Test fun stoppedRecoveryThenAnotherWindowDoesNotContaminateOriginalRegeneration() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertFailedRequestThenRegenerate(true, true) }
    }

    @Test fun marketPrivateSaveIsAdvertisedAndSurvivesApproval() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertMarketSaveRoundTrip() }
    }
    @Test fun marketPrivateSaveInIndependentLaneSurvivesApproval() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertMarketSaveRoundTrip(continuation = true) }
    }
    @Test fun marketDiscoveryFindsPrivateStorySaveInOriginalTurn() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) {
            it.assertMarketSaveRoundTrip(discoverFirst = true, discoveryQuery = "market 市场 番外 保存")
        }
    }
    @Test fun marketDiscoveryFindsGallerySaveInIndependentTurn() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) {
            it.assertMarketSaveRoundTrip(continuation = true, discoverFirst = true, category = "grid")
        }
    }
    @Test fun marketBoundaryAndDiscoveryBothFindTheSameSaveTool() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) {
            it.assertMarketSaveRoundTrip(discoverFirst = true, boundaryFirst = true)
        }
    }
    @Test fun requestAfterModelPausesOriginalConversation() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertModelPauseThenRequest() }
    }
    @Test fun requestAfterModelPausesIndependentContinuation() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertModelPauseThenRequest(continuation = true) }
    }
    @Test fun pausedContinuationCanResumeWithoutErasingOldGatewayHold() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertModelPauseThenRequest(continuation = true, decision = "resume", oldHold = true) }
    }
    @Test fun pausedContinuationCanKeepAllWindowsPaused() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertModelPauseThenRequest(continuation = true, decision = "pause", scope = "assistant") }
    }
    @Test fun pausedContinuationRequestDoesNotWaitForHistoryWriter() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertModelPauseThenRequest(continuation = true, holdWriter = true) }
    }
    @Test fun pausedContinuationKeepsSharedThreeRequestLimit() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertModelPauseThenRequest(continuation = true, scope = "assistant", repeatRequests = true) }
    }

    @Test fun pausedApplicationsKeepToolsAndStopAfterThree() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertPausedTurn(wake = false) }
    }
    @Test fun pausedSentinelCompletesToolsAndReplyThenExpiresWithoutQueueCard() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertPausedTurn(wake = true) }
    }

    @Test fun globalPauseAllowsOneOriginalContextRequestAcrossAllWindows() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertBoundaryRequest("resume", scope = "assistant") }
    }
    @Test fun globalRequestCanExplicitlyKeepAllWindowsPaused() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertBoundaryRequest("pause", stream = true, scope = "assistant") }
    }

    @Test fun streamedBoundaryRequestKeepsContextAndFinishesTheReply() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertBoundaryRequest("resume", stream = true) }
    }
    @Test fun boundaryRequestUsesOriginalContextAndExplicitPause() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertBoundaryRequest("pause") }
    }
    @Test fun boundaryRequestUsesOriginalContextAndExplicitResume() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertBoundaryRequest("resume") }
    }
    @Test fun stateOnlyRequestCanBeSubmittedAgain() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertBoundaryRequest("state_only") }
    }
    @Test fun rejectedPauseToolDoesNotSpendTheApplication() = runBlocking {
        fixture(maxRequests = 4, connectCall = false) { it.assertBoundaryRequest("invalid_pause", stream = true, scope = "assistant") }
    }

    @Test fun boundaryRequestWithoutDecisionKeepsActualReplyNotAnInventedRefusal() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertBoundaryRequest(null) }
    }

    @Test fun globalPlainReplyKeepsTheSharedRequestAvailable() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertBoundaryRequest(null, stream = true, scope = "assistant") }
    }

    @Test fun recallToolWorksInIndependentHumanContinuation() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertRecallRoundTrip(continuation = true) }
    }
    @Test fun conversationSettingsToolPersistsDuringReplyAndAppliesNextTurn() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertConversationSettingsRoundTrip(false) }
    }
    @Test fun conversationSettingsToolPersistsInIndependentContinuation() = runBlocking {
        fixture(maxRequests = 3, connectCall = false) { it.assertConversationSettingsRoundTrip(true) }
    }
    @Test fun recallToolSurvivesGenerationAndChangesNextProviderRequestWithoutLeakingResult() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertRecallRoundTrip() }
    }

    @Test fun ordinaryReplyPublishesOneCompletedNotificationEvent() = runBlocking {
        fixture(connectCall = false) { f ->
            f.assertReplyNotification(continuation = false)
        }
    }

    @Test fun independentReplyPublishesOneCompletedNotificationEvent() = runBlocking {
        fixture(connectCall = false) { f ->
            f.assertReplyNotification(continuation = true)
        }
    }

    @Test fun independentReplyAppearsInAndroidNotificationsWithLiveUpdatesOff() = runBlocking {
        fixture(connectCall = false) { f -> f.assertAndroidNotification(enabled = true) }
    }

    @Test fun disabledCompletionPreferenceDoesNotPostAndroidNotification() = runBlocking {
        fixture(connectCall = false) { f -> f.assertAndroidNotification(enabled = false) }
    }

    @Test fun cancelledIndependentReplyDoesNotPublishCompletedNotification() = runBlocking {
        fixture(connectCall = false) { f -> f.assertCancelledReplyHasNoNotification() }
    }

    @Test fun independentVoiceReplyDoesNotPublishChatNotification() = runBlocking {
        fixture { f ->
            f.installStaleLocalLatch()
            assertEquals(ANSWER, withTimeout(15_000) { f.utterance("synthetic voice notification boundary").await() })
            f.assertNoCompletedNotification()
        }
    }

    @Test fun explicitNewCallConnectsOverPausedBacklogWithoutReplayingIt() = runBlocking {
        fixture(connectCall = false) { f ->
            f.assertFreshCallOverOldQueue()
        }
    }

    @Test fun explicitNewCallConnectsOverPendingHistoricalToolWithoutExecutingIt() = runBlocking {
        fixture(connectCall = false) { f ->
            f.assertFreshCallOverOldQueue(pendingTool = true)
        }
    }

    @Test fun stoppedMiddleRegenerationReleasesHistoryAfterExactDurableCheck() = runBlocking {
        fixture(connectCall = false) { it.assertStoppedCheckpointCanEdit(missingTail = false) }
    }

    @Test fun stoppedRecoverableTailIsPreservedBeforeHistoryUnlock() = runBlocking {
        fixture(connectCall = false) { it.assertStoppedCheckpointCanEdit(missingTail = true) }
    }

    @Test fun stoppingPartialReasoningWithoutToolsPreservesTextAndUnlocksHistory() = runBlocking {
        fixture(connectCall = false) { it.assertStoppedReasoningStreamCanEdit() }
    }

    @Test fun lateCompletedDiskReplyKeepsStoppedVisibleTextAndUnlocksHistory() = runBlocking {
        fixture(connectCall = false) { it.assertLateCompletedDiskReplyCanEdit() }
    }

    @Test fun savedContinuationReturnsToNormalWithoutSentinelAndKeepsUsageEpoch() = runBlocking {
        fixture(maxRequests = 2, connectCall = false, initialCompactionEpoch = 7) { f ->
            f.installStaleLocalLatch()
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic fresh after pause"))))
            }
            f.awaitFreshHumanAnswer()
            f.awaitNormalHistory()
            f.assertCurrentUsageReceipt()
            f.assertBatchDeleteWorks()
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic next normal turn"))))
                f.assertNoContinuationLane()
            }
            withTimeout(10_000) {
                while (f.peer.requests.get() < 2) kotlinx.coroutines.delay(20)
                f.service.getGenerationJobStateFlow(f.conversationId).first { it == null }
            }
            f.assertNoContinuationLane()
            f.assertCurrentUsageReceipt()
            assertEquals(2, f.peer.requests.get())
        }
    }

    @Test fun aPausedQueueAloneDoesNotChangeTheNextHumanReplyPipeline() = runBlocking {
        fixture(connectCall = false) { f ->
            f.pauseQueueWithoutRecovery()
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic explicit new send"))))
                f.assertNoContinuationLane()
            }
            f.awaitFreshHumanAnswer()
            f.service.getGenerationJobStateFlow(f.conversationId).first { it == null }
            f.assertNoContinuationLane()
            assertEquals(1, f.peer.requests.get())
        }
    }

    @Test fun ordinaryStopDuringARealModelRequestIsNotReportedAsARecoveryError() = runBlocking {
        fixture(maxRequests = 2) { f ->
            f.peer.holdFirstRequest.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic mistaken request"))))
            }
            withTimeout(10_000) { f.peer.firstRequestHeld.await() }
            val oldJob = f.currentJob()
            withContext(Dispatchers.Main) { f.service.interruptGenerationForHuman(f.conversationId) }
            f.peer.releaseFirstRequest.countDown()
            withTimeout(10_000) { oldJob.join() }
            assertTrue("Ordinary interruption is not a recovery error", f.service.errors.value.isEmpty())
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic correction after model started"))))
            }
            f.awaitFreshHumanAnswer()
            assertEquals(2, f.peer.requests.get())
        }
    }

    @Test fun ordinaryStopAcceptsTheCorrectedMessageWithoutRecoveryOrAcknowledgement() = runBlocking {
        fixture { f ->
            f.pauseNextHistorySave.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic typo"))))
            }
            withTimeout(5_000) { f.beforeHistorySave.await() }
            withContext(Dispatchers.Main) {
                f.service.interruptGenerationForHuman(f.conversationId)
                assertNull(f.service.getGenerationJobStateFlow(f.conversationId).first())
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text("synthetic correction"))))
            }
            val visible = f.awaitFreshHumanAnswer()
            assertEquals(1, visible.currentMessages.count { it.toText() == "synthetic correction" })
            assertEquals(1, f.peer.requests.get())
            assertEquals(QueueRecoveryPhase.IDLE, f.service.messageQueueRecoveryState(f.conversationId).first().phase)
        }
    }

    @Test fun ordinaryStopReturnsWhileOldCleanupIsStillBlockedAndLateOutputCannotReplaceCorrection() = runBlocking {
        fixture { f -> f.assertNormalStopDoesNotWaitForCleanup() }
    }

    @Test fun stoppedToolCleanupCannotResurrectWaitingUiAfterSavedHandoff() = runBlocking {
        fixture(connectCall = false, maxRequests = 1) { f -> f.assertStoppedCleanupStaysIdle() }
    }

    @Test fun savedReplyConflictDoesNotRequireRecoveryOrASecondHumanSend() = runBlocking {
        fixture { f ->
            val before = f.installCheckpointConflict(tailCommitted = true)
            val checkpoint = AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId)
            withContext(Dispatchers.Main) {
                assertTrue("First send must start an independent turn", f.service.sendMessage(
                    f.conversationId, listOf(UIMessagePart.Text("synthetic fresh request"))))
            }
            val visible = f.awaitFreshHumanAnswer()
            assertEquals(before.id, visible.id)
            assertEquals(before.assistantId, visible.assistantId)
            assertEquals(before.messageNodes, visible.messageNodes.take(before.messageNodes.size))
            assertArrayEquals(checkpoint, AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId))
            assertTrue(f.service.getMessageQueueFlow(f.conversationId).value.messages.isEmpty())
            assertEquals(1, f.peer.requests.get())
        }
    }

    @Test fun uncommittedCheckpointConflictStillAllowsANewHumanRoundAndPreservesOldEvidence() = runBlocking {
        fixture { f ->
            val before = f.installCheckpointConflict(tailCommitted = false)
            val checkpoint = AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId)
            withContext(Dispatchers.Main) { assertTrue(f.service.sendMessage(f.conversationId,
                listOf(UIMessagePart.Text("synthetic independent wake")))) }
            assertEquals(before.id, f.awaitFreshHumanAnswer().id)
            assertEquals(1, f.peer.requests.get())
            assertArrayEquals(checkpoint, AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId))
        }
    }

    @Test fun humanSendDoesNotWaitForTheHistoryWriterMutex() = runBlocking {
        fixture { f -> f.assertHumanWakeWhileHistoryLocked() }
    }

    @Test fun continuationCommitPreservesNewMetadataAndRefusesUnseenHistoryEdits() = runBlocking {
        fixture { f ->
            val before = checkNotNull(f.repository.getConversationById(f.conversationId))
            f.repository.updateConversation(before.copy(title = "new human title"))
            val next = before.copy(messageNodes = before.messageNodes + UIMessage.user("new human input").toMessageNode())
            val committed = checkNotNull(f.repository.commitHumanContinuation(before, next))
            assertEquals("new human title", committed.title)
            val unseen = committed.copy(messageNodes = committed.messageNodes + UIMessage.user("unseen edit").toMessageNode())
            f.repository.updateConversation(unseen)
            assertNull(f.repository.commitHumanContinuation(committed,
                committed.copy(messageNodes = committed.messageNodes + UIMessage.assistant("late reply").toMessageNode())))
            assertEquals(unseen.messageNodes, checkNotNull(f.repository.getConversationById(f.conversationId)).messageNodes)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun incompleteReplyWithHistoryConflictIsArchivedWithoutOverwritingHistoryOrReplayingIt() = runBlocking {
        fixture { f ->
            val before = f.installCheckpointConflict(tailCommitted = false)
            val checkpoint = AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId)
            withContext(Dispatchers.Main) { f.service.dismissPauseAndContinueFreshInput(f.conversationId) }.join()
            // The current recovery contract permits fresh input AFTER a durable conflict copy.
            // Success releases that admission barrier; it must not merge or replay the old reply.
            assertEquals(QueueRecoveryPhase.SUCCESS, f.service.messageQueueRecoveryState(f.conversationId).first().phase)
            assertFalse(f.paused())
            assertEquals(before.messageNodes, checkNotNull(f.repository.getConversationById(f.conversationId)).messageNodes)
            assertNull(AndroidGenerationCheckpointStore.create(f.context.noBackupFilesDir).read(f.conversationId))
            val copies = me.rerere.rikkahub.data.ai.checkpoint.ContinuationConflictCopies
            assertArrayEquals(checkpoint, File(File(f.context.noBackupFilesDir, copies.GENERATION_DIRECTORY),
                copies.name(f.conversationId, checkNotNull(checkpoint))).readBytes())
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun staleLocalLatchWithExactSavedHistoryRecoversWithoutClearingOldAutomaticProtection() = runBlocking {
        fixture { f ->
            f.installStaleLocalLatch()
            f.dismissPausedNotice()
            assertFalse(f.paused())
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.persistedFreshRecoveryStatus())
            assertEquals(0, f.peer.requests.get())
            f.assertNewReplyAndNewCallWork()
        }
    }

    @Test fun proactiveAdmissionNeverCreatesUnknownSessionsAndRespectsHumanPause() = runBlocking {
        fixture { f ->
            f.assertProactiveUnknownTargetIsReadOnly()
            f.assertHumanPauseBlocksProactiveDecision()
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun freshUtteranceDoesNotWaitForOldIngressMutex() = runBlocking {
        fixture { f ->
            val original = "synthetic fresh speech bypasses old ingress"
            val ingress = f.voiceIngressMutex()
            lateinit var oldReply: Deferred<String?>
            ingress.lock()
            try {
                oldReply = f.utterance(original)
                assertEquals(ANSWER, withTimeout(10_000) { oldReply.await() })
                assertEquals(1, f.peer.requests.get())
                f.assertRetainedExactlyOnce(original, substring = true)
            } finally {
                ingress.unlock()
            }
            assertEquals(1, checkNotNull(f.service.voiceCalls.get(f.callId)).transcript.count { it.content == original })
            assertFalse(f.paused())
        }
    }

    @Test fun newSpeechCanAnswerWhilePreviousVoiceHistorySaveIsStillBlocked() = runBlocking {
        fixture { f ->
            f.pauseNextHistorySave.set(true)
            val old = f.utterance("synthetic old voice awaiting save")
            withTimeout(5_000) { f.beforeHistorySave.await() }
            val next = f.utterance("synthetic new voice correction")
            assertEquals(ANSWER, withTimeout(10_000) { next.await() })
            val visible = f.service.getDisplayedConversationFlow(f.conversationId).value
            assertEquals(1, visible.currentMessages.count { it.toText().contains("synthetic new voice correction") })
            assertEquals(1, f.peer.requests.get())
            // The new speech does not require the old producer/queue to acknowledge success.
            assertTrue(next.isCompleted)
        }
    }

    @Test fun detachedLegacyHoldAllowsNewReplyCallAndPreflightBargeInWithoutClearingOldEvidence() = runBlocking {
        fixture(maxRequests = 2) { f ->
            val originalHold = f.installLegacyGatewayHold()
            f.dismissPausedNotice()
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.persistedFreshRecoveryStatus())
            f.assertLegacyGatewayHoldUnchanged(originalHold)
            val newCallId = f.assertNewReplyAndNewCallWork()
            f.assertLegacyGatewayHoldUnchanged(originalHold)

            val interrupted = "synthetic detached new call preflight interruption"
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance(interrupted, newCallId)
            f.beforeHistorySave.await()
            assertEquals("The new call has not submitted its interrupted input", 1, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply, newCallId)
            job.join()
            assertNull(reply.await())
            assertFalse("Old detached evidence must not poison a new pre-model barge-in", f.paused())
            assertEquals(1, checkNotNull(f.service.voiceCalls.get(newCallId)).transcript.count { it.content == interrupted })
            f.assertLegacyGatewayHoldUnchanged(originalHold)

            val following = "synthetic genuinely new spoken input after detached barge-in"
            val nextReply = f.utterance(following, newCallId)
            assertEquals(ANSWER, nextReply.await())
            withContext(Dispatchers.Main) {
                f.service.getGenerationJobStateFlow(f.conversationId).first { it == null }
            }
            f.assertRetainedExactlyOnce(following, substring = true)
            assertEquals(2, f.peer.requests.get())
            assertFalse(f.paused())
            assertEquals(FreshHumanRecoveryStatus.DETACHED, f.persistedFreshRecoveryStatus())
            f.assertLegacyGatewayHoldUnchanged(originalHold)
            assertFalse(f.service.mayAcceptPeriodicVideoFrame(f.conversationId, f.callId))
            assertTrue(f.service.mayAcceptPeriodicVideoFrame(f.conversationId, newCallId))
        }
    }

    @Test fun dismissNoticeRetainsOrdinaryInputCancelledBeforeHistoryAndAllowsANewReply() = runBlocking {
        fixture { f ->
            val original = "synthetic unsaved ordinary input"
            f.pauseNextHistorySave.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text(original))))
            }
            f.beforeHistorySave.await()
            val oldJob = f.currentJob()
            f.dismissPausedNotice()
            assertTrue(oldJob.isCompleted)
            assertFalse(f.paused())
            assertEquals(0, f.peer.requests.get())
            f.assertRetainedExactlyOnce(original)
            f.assertNewReplyAndNewCallWork()
            f.assertRetainedExactlyOnce(original)
        }
    }

    @Test fun dismissNoticeRetainsSpokenInputCancelledBeforeHistoryWithoutReplayingIt() = runBlocking {
        fixture { f ->
            val original = "synthetic unsaved dictated input"
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance(original)
            f.beforeHistorySave.await()
            val oldJob = f.currentJob()
            f.dismissPausedNotice()
            assertTrue(oldJob.isCompleted)
            assertFalse(f.paused())
            assertEquals(0, f.peer.requests.get())
            assertTrue(reply.isCompleted)
            f.assertRetainedExactlyOnce(original, substring = true)
            f.assertFreshInputCanDrain()
            f.assertRetainedExactlyOnce(original, substring = true)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun dismissNoticeFinishesAnAttemptedInputSaveOnlyOnceWithoutSendingIt() = runBlocking {
        fixture { f ->
            val original = "synthetic input with interrupted history save"
            f.historyChecksBeforeGate.set(1)
            f.pauseNextHistorySave.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(f.service.sendMessage(f.conversationId, listOf(UIMessagePart.Text(original))))
            }
            f.beforeHistorySave.await()
            assertEquals(0, f.peer.requests.get())
            f.dismissPausedNotice()
            assertFalse(f.paused())
            f.assertRetainedExactlyOnce(original)
            f.assertFreshInputCanDrain()
            f.assertRetainedExactlyOnce(original)
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun bargeInWhileFirstHistorySaveIsSuspendedDoesNotPauseTheQueue() = runBlocking {
        fixture { f ->
            f.pauseNextHistorySave.set(true)
            val reply = f.utterance("synthetic first interrupted utterance")
            f.beforeHistorySave.await()
            assertEquals("The preflight boundary must precede any HTTP", 0, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply)
            job.join()
            assertFalse("A safely settled pre-model barge-in must not pause the real queue", f.paused())
            assertNull(reply.await())
            val record = checkNotNull(f.service.voiceCalls.get(f.callId))
            assertEquals(1, record.transcript.count { it.content == "synthetic first interrupted utterance" })
            f.assertFreshInputCanDrain()
            assertEquals(0, f.peer.requests.get())
        }
    }

    @Test fun bargeInAfterModelCommitButDuringFinalArchiveSnapshotDoesNotPauseTheQueue() = runBlocking {
        fixture { f ->
            f.installFinalSnapshotGate()
            val reply = f.utterance("synthetic completed model utterance")
            f.finalSnapshotEntered.await()
            // This gate admits only a real answer already committed to Room and with its
            // checkpoint cleared. Streaming archive projections cannot trigger it.
            val stored = checkNotNull(f.repository.getConversationById(f.conversationId))
            assertEquals(1, stored.currentMessages.count { it.parts.any { part ->
                part is UIMessagePart.Text && part.text == ANSWER
            } })
            assertFalse(f.journal.hasCheckpoint(f.conversationId))
            assertEquals(1, f.peer.requests.get())
            val job = f.currentJob()
            f.cancel(reply)
            f.releaseSnapshot.countDown()
            job.join()
            assertFalse("Final archive cancellation must not poison a durably completed model turn", f.paused())
            assertNull(reply.await())
            assertEquals(1, checkNotNull(f.repository.getConversationById(f.conversationId))
                .currentMessages.count { it.parts.any { part -> part is UIMessagePart.Text && part.text == ANSWER } })
            f.assertFreshInputCanDrain()
            assertEquals("No model request or old input may be replayed", 1, f.peer.requests.get())
        }
    }

    @Test fun selfCheckReadsActualOwnerSettingsThroughTheGenerationToolPipeline() = runBlocking {
        fixture(maxRequests = 2, connectCall = false) { it.assertSelfCheckRoundTrip() }
    }

    private suspend fun fixture(maxRequests: Int = 1, connectCall: Boolean = true,
        initialCompactionEpoch: Long = 0, block: suspend (Fixture) -> Unit) {
        val f = Fixture(maxRequests)
        try { withTimeout(30_000) { f.prepare(connectCall, initialCompactionEpoch); block(f); f.peer.assertHealthy() } }
        finally { withContext(NonCancellable) { f.close() } }
    }

    private class Fixture(maxRequests: Int) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName in setOf("org.orbis.agent.dev", "org.orbis.agent.bodylab"))
            check(!LcExternalRecoveryGate.isAllowed())
            check(GlobalContext.getOrNull() == null) { "App Koin must not be initialized" }
            listOf(OrbisVoiceCallRuntime::class.java, OrbisVideoCallRuntime::class.java, me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels::class.java).forEach { type ->
                check(type.getDeclaredField("instance").apply { isAccessible = true }.get(null) == null) {
                    "Run this class alone; never replace another fixture's process runtime"
                }
            }
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(cache.toPath(), "service-voice-cancellation-").toFile()
        val context = StorageOnlyApplication(instrumentation.targetContext, directory)
        val peer = OneAnswerPeer(maxRequests)
        private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS)
            .dns { name -> check(name == "127.0.0.1"); listOf(LOOPBACK) }
            .addInterceptor { chain ->
                val url = chain.request().url
                check(url.scheme == "http" && url.host == "127.0.0.1" && url.port == peer.port &&
                    url.encodedPath == "/v1/chat/completions") { "Non-fixture request forbidden" }
                chain.proceed(chain.request())
            }.build()
        private val dormantScope = AppScope().also { it.cancel() }
        private val activeScope = AppScope()
        private val model = Model(modelId = "synthetic-service-voice", displayName = "Synthetic only",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE), abilities = emptyList())
        private val assistant = Assistant(name = "Synthetic owner", chatModelId = model.id,
            systemPrompt = "Synthetic local fixture", streamOutput = false, localTools = emptyList(),
            enableMemory = false, enableWebSearch = false, enableRecentChatsReference = false)
        private val settings = SettingsStore(context, dormantScope).also { store ->
            store.settingsFlow.value = Settings(assistantId = assistant.id, assistants = listOf(assistant),
                chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(name = "Loopback only",
                    baseUrl = "http://127.0.0.1:${peer.port}/v1", apiKey = "", models = listOf(model))),
                enableSuggestion = false, mcpServers = emptyList(), searchServices = emptyList(),
                modeInjections = emptyList(), lorebooks = emptyList(), ttsProviders = emptyList(),
                networkSetting = NetworkSetting(enableAutoRetry = false))
        }
        private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        val pauseNextHistorySave = AtomicBoolean(false)
        val failNextHistorySave = AtomicBoolean(false)
        val historyChecksBeforeGate = AtomicInteger(0)
        val beforeHistorySave = CompletableDeferred<Unit>()
        private val allowHistorySave = CompletableDeferred<Unit>()
        private val actualDao = database.conversationDao()
        private val gatedDao = object : ConversationDAO by actualDao {
            override suspend fun existsById(id: String): Boolean {
                if (failNextHistorySave.compareAndSet(true, false)) error("Synthetic one-time input save failure")
                if (pauseNextHistorySave.get() && historyChecksBeforeGate.getAndDecrement() <= 0 &&
                    pauseNextHistorySave.compareAndSet(true, false)) {
                    beforeHistorySave.complete(Unit)
                    allowHistorySave.await() // Cancellable actual service pre-save suspension.
                }
                return actualDao.existsById(id)
            }
        }
        private val files = FilesManager(context, FilesRepository(database.managedFileDao()), dormantScope)
        // ChatToolFactory resolves FilesManager while registering the voice-note tool, even
        // though no tool is executed. Install exactly that isolated binding, not appModule.
        private val isolatedKoin = startKoin { modules(module {
            single<FilesManager> { files }
            single<SettingsStore> { settings }
        }) }
        private val fts = MessageFtsManager(database)
        val repository = ConversationRepository(gatedDao, database.messageNodeDao(), database.favoriteDao(), database, files, fts)
        private val memory = MemoryRepository(database.memoryDao())
        private val localMemory = me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository(database)
        private val manager = ProviderManager(client, context)
        private val workspaceManager = WorkspaceManager(File(directory, "workspace"))
        private val workspace = WorkspaceRepository(database.workspaceDao(), workspaceManager, RootfsInstaller(workspaceManager), settings)
        private val mcp = McpManager(settings, dormantScope, files)
        private val bus = AppEventBus()
        private val notificationEvents = java.util.concurrent.CopyOnWriteArrayList<AppEvent>()
        private val notificationObserver = activeScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            bus.events.collect { notificationEvents += it }
        }
        private val tools = ChatToolFactory(JsonInstant, memory, repository,
            LocalTools(context, bus, TTSManager(context), settings), mcp, SkillManager(context, settings), workspace, context,
            settingsStore = settings)
        val service = ChatService(context, activeScope, bus, settings, repository, memory,
            GenerationLoop(context, manager, JsonInstant,
                localMemory=me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRuntime(database,localMemory,settings),
                readRecallState = { owner, id ->
                repository.getConversationById(id)?.takeIf { it.assistantId == owner }?.currentMessages.orEmpty()
            }), TranslationHandler(manager),
            TemplateTransformer(PebbleEngine.Builder().loader(AssistantTemplateLoader(settings)).build(), settings),
            manager, tools, mcp, files, workspace, FolderRepository(database.folderDao(), gatedDao),
            OrbisCompactionRepository(database, fts), client)
        val journal = GenerationCheckpointJournal(AndroidGenerationCheckpointStore.create(context.noBackupFilesDir))
        val conversationId = Uuid.random()
        lateinit var callId: String
        val finalSnapshotEntered = CompletableDeferred<Unit>()
        val releaseSnapshot = CountDownLatch(1)

        suspend fun assertTabletopCompanion(otherWindow:Boolean,recovered:Boolean=false) {
            val configured=assistant.copy(systemPrompt="SYNTHETIC-COMPANION-IDENTITY",
                customHeaders=listOf(me.rerere.ai.provider.CustomHeader("x-companion-test","original-connection")),
                orbisMemoryMode=me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode.STANDARD)
            settings.settingsFlow.value=settings.settingsFlow.value.let { it.copy(assistants=listOf(configured),
                providers=it.providers.map { p -> (p as ProviderSetting.OpenAI).copy(models=listOf(model.copy(abilities=listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) }) }
            val memoryResult=localMemory.execute(assistant.id.toString(),buildJsonObject {
                put("action","store");put("body","SYNTHETIC-SHARED-MEMORY");put("state","pinned")
            },"tabletop-fixture-memory")
            assertFalse(memoryResult.toString().contains("error"))
            val base=service.getDisplayedConversationSnapshot(conversationId).copy(messageNodes=listOf(
                UIMessage.user("SYNTHETIC-OUR-EARLIER-HUMAN-TEXT").toMessageNode(),
                UIMessage.assistant("SYNTHETIC-OUR-EARLIER-ANSWER").toMessageNode()))
            withContext(Dispatchers.Main) { service.saveConversation(conversationId,base) }
            val calls=mutableListOf<String>()
            val api=object:me.rerere.rikkahub.data.orbis.tabletop.TabletopGameApi {
                override val assistantId=configured.id.toString()
                override val room="a".repeat(32)
                override fun pending(asAi:Boolean)=false
                override suspend fun roleImage(name:String):String=error("No image in this fixture")
                override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
                    assertTrue(asAi);assertEquals("script_read",path);calls+=path
                    return buildJsonObject { put("role","SYNTHETIC-BOOK-ROLE");put("text","SYNTHETIC-ROLE-CHAPTER") }
                }
            }
            val host=me.rerere.rikkahub.data.orbis.tabletop.TabletopConversationHost.get(context)
            val bound=host.bind(api,conversationId.toString(),"原聊天")
            if(recovered)installStaleLocalLatch()
            peer.customMessages.add("""{"role":"assistant","content":null,"tool_calls":[{"id":"read-own-book","type":"function","function":{"name":"script_read","arguments":"{\"kind\":\"chapter\"}"}}]}""")
            val target=if(otherWindow) Uuid.random() else conversationId
            if(otherWindow) {
                val another=base.copy(id=target,title="另一已有窗口",messageNodes=listOf(UIMessage.user("SYNTHETIC-SECOND-WINDOW-HISTORY").toMessageNode()))
                repository.insertConversation(another)
                withContext(Dispatchers.Main) {
                    service.initializeConversation(target)
                    assertTrue(service.sendMessage(target,listOf(UIMessagePart.Text("现在一起读本"))))
                }
            } else if(recovered) {
                // An explicit new human message opens the normal recovery continuation.
                // A synthetic stale recovery hold alone must not authorize automatic replay.
                withContext(Dispatchers.Main) {
                    assertTrue(service.trySendGardenQuickChat(target,assistant.id,"现在继续一起读本"))
                }
            } else {
                host.setAutomatic(assistant.id.toString(),true)
                // The game's opt-in is independent of any personal sentinel master setting.
                val native=me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels.open(context)
                native.rules.setHumanEnabled(false,System.currentTimeMillis())
                withContext(Dispatchers.Main) {
                    service.bindOrbisEvents(setOf(bound.source),me.rerere.rikkahub.data.orbis.OrbisEventBinding(assistant.id.toString(),target.toString()))
                    service.acceptOrbisEvent(me.rerere.rikkahub.data.orbis.OrbisIncomingEvent(
                        event_id=api.room+":synthetic:phase-1",source=bound.source,text="新的角色剧本已开放，请读本。"))
                }
            }
            withTimeout(15_000) { service.getDisplayedConversationFlow(target).first { c -> c.currentMessages.any { m -> m.parts.any { it is UIMessagePart.Text && it.text.trim() == ANSWER } } } }
            withTimeout(10_000) { service.getGenerationJobStateFlow(target).first { it==null } }
            assertEquals(listOf("script_read"),calls)
            assertEquals(2,peer.requests.get())
            peer.requestBodies.forEach { body ->
                assertTrue(body.contains("SYNTHETIC-COMPANION-IDENTITY"))
                assertTrue(body.contains("SYNTHETIC-SHARED-MEMORY"))
                assertTrue(body.contains(if(otherWindow)"SYNTHETIC-SECOND-WINDOW-HISTORY" else "SYNTHETIC-OUR-EARLIER-HUMAN-TEXT"))
                for(name in listOf("script_status","script_read","script_say","script_note","script_pass"))assertTrue(body.contains(name))
                assertEquals(model.modelId,Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content)
                assertFalse(body.contains("auxiliary-no-memory"))
            }
            assertTrue(peer.requestBodies.last().contains("SYNTHETIC-ROLE-CHAPTER"))
            assertTrue(peer.requestHeaders.all { rows -> rows.any { it.equals("x-companion-test: original-connection",ignoreCase=true) } })
            val stored=repository.getConversationById(target)!!
            assertEquals(target,stored.id);assertEquals(assistant.id,stored.assistantId)
            assertEquals(if(otherWindow)2 else 1,repository.getConversationsOfAssistantPage(assistant.id,0,20).items.size)
            assertEquals(configured,settings.settingsFlow.value.assistants.single())
            assertTrue(host.tools(Uuid.random().toString(),true).isEmpty())
            host.clear(assistant.id.toString())
            assertTrue(host.tools(assistant.id.toString(),true).isEmpty())
        }

        suspend fun assertMarketSaveRoundTrip(continuation: Boolean = false, discoverFirst: Boolean = false,
            category: String = "extra", discoveryQuery: String = "market", boundaryFirst: Boolean = false) {
            settings.settingsFlow.value = settings.settingsFlow.value.let { it.copy(
                providers = it.providers.map { p -> (p as ProviderSetting.OpenAI).copy(models = listOf(model.copy(
                    abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) },
                assistants = it.assistants.map { a -> if (a.id != assistant.id) a else a.copy(localTools = buildList {
                    addAll(a.localTools)
                    if (discoverFirst) add(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AgentWork)
                    if (category == "grid") add(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.LocalGallery)
                }) }) }
            isolatedKoin.koin.declare(service)
            if (boundaryFirst) service.boundaryHost.grant(assistant.id.toString(), setOf("conversation", "model", "mcp", "tool"))
            if (continuation) {
                installLegacyGatewayHold()
                withContext(Dispatchers.Main) {
                    assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("开始合成独立续聊"))))
                }
                awaitFreshHumanAnswer()
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
                assertNotNull("Fixture must exercise the independent lane", session().humanContinuation.value)
            }
            val deliveries = me.rerere.rikkahub.data.orbis.market.MarketDeliveries(context)
            val delivery = deliveries.create(buildJsonObject {
                put("id", java.util.UUID.randomUUID().toString()); put("state", "published"); put("version", 1)
                put("title", "合成市场设定"); put("prompt", "只在本机保存合成正文"); put("category", category); put("author", "测试")
            }, assistant.id.toString(), conversationId.toString())
            if (boundaryFirst) peer.customMessages.add("""{"role":"assistant","content":null,"tool_calls":[{"id":"market-boundary-one","type":"function","function":{"name":"orbis_boundary","arguments":"{\"action\":\"state\",\"kind\":\"tool\",\"query\":\"market\",\"limit\":10}"}}]}""")
            if (discoverFirst) {
                val arguments = JsonPrimitive(buildJsonObject { put("query", discoveryQuery) }.toString())
                peer.customMessages.add("""{"role":"assistant","content":null,"tool_calls":[{"id":"market-discover-one","type":"function","function":{"name":"orbis_tool_discover","arguments":$arguments}}]}""")
            }
            peer.customMessages.add("""{"role":"assistant","content":null,"tool_calls":[{"id":"market-save-one","type":"function","function":{"name":"orbis_market_save","arguments":"{\"text\":\"合成私人正文\"}"}}]}""")
            service.bindOrbisEvents(setOf(delivery.source), me.rerere.rikkahub.data.orbis.OrbisEventBinding(
                assistant.id.toString(), conversationId.toString()))
            val event = service.acceptOrbisEvent(delivery.input()).first
            withTimeout(15_000) { service.orbisEvents.inbox.state.first { state ->
                state.events.any { it.id == event.id && it.state in setOf("pending_tool", "replied", "failed", "unknown") }
            } }
            val request = Json.parseToJsonElement(peer.requestBodies[if (continuation) 1 else 0]).jsonObject
            assertTrue("Original market event must advertise its save tool", request["tools"]!!.jsonArray.any {
                it.jsonObject["function"]!!.jsonObject["name"] == JsonPrimitive("orbis_market_save")
            })
            assertEquals("pending_tool", service.orbisEvents.inbox.get(event.id)!!.state)
            if (boundaryFirst) {
                val afterBoundary = Json.parseToJsonElement(peer.requestBodies[if (continuation) 2 else 1]).jsonObject
                val result = afterBoundary["messages"]!!.jsonArray.map { it.jsonObject }.single {
                    it["tool_call_id"] == JsonPrimitive("market-boundary-one")
                }["content"]!!.jsonPrimitive.content
                val directory = Json.parseToJsonElement(result).jsonObject
                assertEquals(JsonPrimitive(true), directory["ok"])
                assertEquals(JsonPrimitive(1), directory["page"]!!.jsonObject["total"])
                assertEquals(JsonPrimitive("orbis_market_save"), directory["choices"]!!.jsonObject["tool"]!!.jsonArray.single().jsonObject["id"])
            }
            if (discoverFirst) {
                val afterDiscovery = Json.parseToJsonElement(peer.requestBodies[(if (continuation) 2 else 1) + (if (boundaryFirst) 1 else 0)]).jsonObject
                val result = afterDiscovery["messages"]!!.jsonArray.map { it.jsonObject }.single {
                    it["tool_call_id"] == JsonPrimitive("market-discover-one")
                }["content"]!!.jsonPrimitive.content
                val directory = Json.parseToJsonElement(result).jsonObject
                assertEquals(JsonPrimitive(true), directory["ok"])
                assertTrue(directory["matches"]!!.jsonPrimitive.int >= 1)
                assertTrue("Market save must be discoverable on the first page", directory["tools"]!!.jsonArray.any {
                    it.jsonObject["name"] == JsonPrimitive("orbis_market_save")
                })
            }
            withContext(Dispatchers.Main) {
                service.getGenerationJobStateFlow(conversationId).first { it == null }
                service.handleToolApproval(conversationId, "market-save-one", approved = true)
            }
            withTimeout(15_000) { service.orbisEvents.inbox.state.first { state ->
                state.events.any { it.id == event.id && it.state == "replied" }
            } }
            assertEquals(if (category == "extra") 2 else 1, deliveries.read(delivery.id).savedRevision)
            val body = if (category == "extra") me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
                .open(context, assistant.id.toString()).snapshot().stories.single().body
                else me.rerere.rikkahub.data.orbis.gallery.openGallery(context, assistant.id.toString()).body(delivery.id)
            assertEquals("合成私人正文", body)
            assertEquals(2 + (if (continuation) 1 else 0) + (if (discoverFirst) 1 else 0) + (if (boundaryFirst) 1 else 0), peer.requests.get())
        }

        suspend fun prepare(connectCall: Boolean, initialCompactionEpoch: Long) {
            // Seed an already-compacted synthetic page before the service loads it. Ordinary
            // saves correctly reject changing the epoch of an existing live conversation.
            repository.insertConversation(Conversation.ofId(conversationId, assistant.id).copy(
                title = "Synthetic fixed title", compactionEpoch = initialCompactionEpoch,
                messageNodes = if (initialCompactionEpoch > 0) listOf(
                    UIMessage.user("synthetic retained history").toMessageNode()) else emptyList()))
            withContext(Dispatchers.Main) {
                service.addConversationReference(conversationId)
                service.initializeConversation(conversationId)
                if (!connectCall) return@withContext
                val call = service.prepareVoiceCall(conversationId, video = true)
                callId = call.id
                service.connectVoiceCall(callId, System.currentTimeMillis())
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
        }

        suspend fun utterance(text: String, targetCallId: String = callId): Deferred<String?> = withContext(Dispatchers.Main) {
            service.enqueueVoiceCallUtterance(conversationId, targetCallId, text)
        }

        suspend fun cancel(reply: Deferred<String?>, targetCallId: String = callId) = withContext(Dispatchers.Main) {
            service.cancelVoiceCallReply(conversationId, targetCallId, reply)
        }

        suspend fun currentJob(): Job = withContext(Dispatchers.Main) {
            checkNotNull(service.getGenerationJobStateFlow(conversationId).first())
        }

        fun paused(): Boolean = service.getMessageQueueFlow(conversationId).value.paused

        suspend fun pauseQueueWithoutRecovery() = withContext(Dispatchers.Main) {
            assertFalse(session().generationRecoveryBlocked)
            session().messageQueue.pause()
        }

        suspend fun assertFreshCallOverOldQueue(pendingTool: Boolean = false) {
            val oldTool = UIMessagePart.Tool("old-call-tool", "synthetic_must_not_execute", "{}",
                approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)
            withContext(Dispatchers.Main) {
                if (pendingTool) {
                    val before = session().state.value
                    service.saveConversation(conversationId, before.copy(messageNodes = before.messageNodes +
                        UIMessage.assistant("").copy(parts = listOf(oldTool)).toMessageNode()))
                } else {
                    session().messageQueue.pause()
                    session().messageQueue.enqueue(listOf(UIMessagePart.Text("synthetic old backlog")))
                }
                val call = service.prepareVoiceCall(conversationId)
                callId = call.id
                withTimeout(5_000) { service.connectVoiceCall(call.id, System.currentTimeMillis(), freshHumanCall = true) }
                assertEquals(OrbisVoiceCallStatus.ACTIVE, service.voiceCalls.get(call.id)?.status)
                assertEquals("A begin marker is not a model request", 0, peer.requests.get())
            }
            val reply = utterance("synthetic new call input")
            assertEquals(ANSWER, withTimeout(15_000) { reply.await() })
            assertEquals("Only the fresh utterance may reach the model", 1, peer.requests.get())
            if (!pendingTool) {
                assertTrue(service.getMessageQueueFlow(conversationId).value.messages.any {
                    it.parts.any { p -> p is UIMessagePart.Text && p.text == "synthetic old backlog" }
                })
            }
        }

        suspend fun assertFailedRequestThenRegenerate(stop: Boolean, otherWindow: Boolean = false) {
            settings.settingsFlow.value = settings.settingsFlow.value.let { original -> original.copy(init=false,
                assistants=listOf(assistant.copy(streamOutput=true,localTools=listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SelfCheck))),
                providers=original.providers.map { (it as ProviderSetting.OpenAI).copy(models=listOf(model.copy(abilities=listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) }) }
            if (otherWindow) installStaleLocalLatch()
            if(stop) peer.holdFirstRequest.set(true) else peer.balanceFailure.set(true)
            withContext(Dispatchers.Main) { assertTrue(service.sendMessage(conversationId,listOf(UIMessagePart.Text("synthetic failed question")))) }
            withTimeout(10_000) { while(peer.requests.get()==0) kotlinx.coroutines.delay(20) }
            if(stop) {
                peer.firstRequestHeld.await()
                val old=currentJob()
                withContext(Dispatchers.Main) { service.interruptGenerationForHuman(conversationId) }
                peer.releaseFirstRequest.countDown()
                withTimeout(10_000) { old.join() }
                awaitNormalHistory()
            } else withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it==null } }
            if (otherWindow) {
                val otherId = Uuid.random()
                repository.insertConversation(Conversation.ofId(otherId, assistant.id).copy(title = "Other synthetic window"))
                withContext(Dispatchers.Main) {
                    service.initializeConversation(otherId, selectAssistant = false)
                    assertTrue(service.sendMessage(otherId, listOf(UIMessagePart.Text("OTHER-WINDOW-ONLY"))))
                }
                withTimeout(15_000) { service.getDisplayedConversationFlow(otherId).first { c ->
                    c.currentMessages.any { it.toText().trim() == ANSWER }
                } }
                withTimeout(10_000) { service.getGenerationJobStateFlow(otherId).first { it == null } }
            }
            val before=service.getDisplayedConversationSnapshot(conversationId)
            val input=before.currentMessages.last { it.role==me.rerere.ai.core.MessageRole.USER }
            peer.customMessages.add("""{"role":"assistant","content":"checking","tool_calls":[{"id":"retry-check","type":"function","function":{"name":"orbis_self_check","arguments":"{\"focus\":\"theme\"}"}}]}""")
            withContext(Dispatchers.Main) { service.regenerateAtMessage(conversationId,input) }
            // Exercise the real approval/resume route rather than waiting forever for an
            // intentionally gated tool. This is also where continuation headers must survive.
            withTimeout(15_000) { service.getDisplayedConversationFlow(conversationId).first { c ->
                c.currentMessages.flatMap { it.getTools() }.any { it.toolCallId == "retry-check" }
            } }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            withContext(Dispatchers.Main) { service.handleToolApproval(conversationId, "retry-check", approved = true) }
            withTimeout(15_000) { service.getDisplayedConversationFlow(conversationId).first { c -> c.currentMessages.any { it.toText().trim().endsWith(ANSWER) } } }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it==null } }
            awaitNormalHistory()
            val saved=repository.getConversationById(conversationId)!!
            assertEquals(saved.messageNodes,service.getDisplayedConversationSnapshot(conversationId).messageNodes)
            assertEquals(if (otherWindow) 4 else 3,peer.requests.get())
            assertTrue(saved.currentMessages.flatMap { it.getTools() }.single().isExecuted)
            peer.requestHeaders.forEachIndexed { index, headers ->
                if (!(otherWindow && index == 1)) {
                    assertTrue(headers.any { it.equals("X-ST-Thread-ID: orbis:$conversationId",true) })
                    assertFalse(peer.requestBodies[index].contains("OTHER-WINDOW-ONLY"))
                }
            }
            assertBatchDeleteWorks()
        }

        suspend fun awaitNormalHistory() = withTimeout(10_000) {
            session().humanContinuation.first { it == null }
        }

        fun assertNoContinuationLane() = assertNull(session().humanContinuation.value)

        fun assertCurrentUsageReceipt() {
            val c = service.getDisplayedConversationFlow(conversationId).value
            val config = me.rerere.rikkahub.data.ai.preparedContextConfiguration(assistant, model,
                c.customSystemPrompt, c.orbisPrompt, c.modeInjectionIds, c.lorebookIds, c.workspaceCwd)
            val receipt = me.rerere.rikkahub.data.ai.validPreparedContextUsage(c.currentMessages,
                c.compactionEpoch, c.assistantId, model.id, config)
            assertNotNull("The current request receipt must match the displayed conversation", receipt)
            assertEquals(c.compactionEpoch, receipt!!.contextEpoch)
        }

        suspend fun assertStoppedCheckpointCanEdit(missingTail: Boolean) {
            val base = checkNotNull(repository.getConversationById(conversationId)).copy(messageNodes = listOf(
                UIMessage.user("protected first").toMessageNode(),
                UIMessage.user("middle input").toMessageNode(),
                UIMessage.user("protected later").toMessageNode()))
            withContext(Dispatchers.Main) { service.saveConversation(conversationId, base) }
            withContext(Dispatchers.IO) {
                val h = journal.begin(base, prefixCount = 1, suffixCount = 1)
                if (missingTail) journal.checkpoint(h, base.assistantId, base.compactionEpoch,
                    base.messageNodes.drop(1).dropLast(1) + UIMessage.assistant("saved late reply").toMessageNode())
                // Old implementation left this recoverable record in the active slot forever.
                assertFalse(journal.clearContainedInDurableContinuation(base))
                assertFalse(journal.preserveIncompatible(base, { true }, {}))
            }
            withContext(Dispatchers.Main) { service.interruptGenerationForHuman(conversationId) }
            awaitNormalHistory()
            assertFalse(journal.hasCheckpoint(conversationId))
            assertEquals(base.messageNodes, repository.getConversationById(conversationId)!!.messageNodes)
            assertEquals(0, peer.requests.get())
            assertBatchDeleteWorks()
            assertEquals(0, peer.requests.get())
        }

        suspend fun assertStoppedReasoningStreamCanEdit() {
            settings.settingsFlow.value = settings.settingsFlow.value.copy(
                assistants = listOf(assistant.copy(streamOutput = true)))
            peer.holdReasoningStream.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic plain text request"))))
            }
            val reasoning = try { withTimeout(10_000) {
                service.getDisplayedConversationFlow(conversationId).first { c ->
                    c.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>()
                        .any { it.reasoning == PARTIAL_REASONING }
                }.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().single()
            } } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                peer.assertHealthy()
                throw AssertionError("Partial reasoning not displayed; requests=${peer.requests.get()}, " +
                    "parts=${service.getDisplayedConversationSnapshot(conversationId).currentMessages.flatMap { it.parts }}, " +
                    "errors=${service.errors.value}", failure)
            }
            assertNull(reasoning.finishedAt)
            val oldJob = currentJob()
            withContext(Dispatchers.Main) { service.interruptGenerationForHuman(conversationId) }
            peer.releaseReasoningStream.countDown()
            withTimeout(10_000) { oldJob.join() }
            try { awaitNormalHistory() } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Stopped stream history did not unlock; checkpoint=${journal.hasCheckpoint(conversationId)}, " +
                    "errors=${service.errors.value}", failure)
            }
            val saved = checkNotNull(repository.getConversationById(conversationId))
            val retained = saved.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().single()
            assertEquals(PARTIAL_REASONING, retained.reasoning)
            assertNotNull(retained.finishedAt)
            assertFalse(saved.currentMessages.flatMap { it.parts }.any { it is UIMessagePart.Tool })
            assertFalse(journal.hasCheckpoint(conversationId))
            assertEquals(saved.messageNodes, service.getDisplayedConversationSnapshot(conversationId).messageNodes)
            assertBatchDeleteWorks()
            assertEquals("Stopping and editing must not send another request", 1, peer.requests.get())
        }

        suspend fun assertLateCompletedDiskReplyCanEdit() {
            val base = checkNotNull(repository.getConversationById(conversationId)).copy(messageNodes = listOf(
                UIMessage.user("protected first").toMessageNode(), UIMessage.user("last input").toMessageNode()))
            withContext(Dispatchers.Main) { service.saveConversation(conversationId, base) }
            val h = withContext(Dispatchers.IO) { journal.begin(base) }
            val reasoning = UIMessagePart.Reasoning("partial", finishedAt = null)
            val reply = UIMessage.assistant("").copy(parts = listOf(reasoning)).toMessageNode()
            val visible = base.copy(messageNodes = base.messageNodes + reply)
            withContext(Dispatchers.Main) { service.saveConversation(conversationId, visible) }
            val completed = reply.copy(messages = listOf(reply.currentMessage.copy(
                finishedAt = reply.currentMessage.createdAt,
                usage = me.rerere.ai.core.TokenUsage(promptTokens = 20, completionTokens = 9, totalTokens = 29),
                parts = listOf(reasoning.copy(reasoning = "partial final chunk", finishedAt = reasoning.createdAt),
                    UIMessagePart.Text("final answer already durable")))))
            // Simulate a late writer committing before its UI callback: same identities, a
            // verified checkpoint, extra prose and final usage, with the visible tail older.
            withContext(Dispatchers.IO) {
                journal.checkpoint(h, base.assistantId, base.compactionEpoch, base.messageNodes.takeLast(1) + completed)
                repository.updateConversation(base.copy(messageNodes = base.messageNodes + completed))
            }
            withContext(Dispatchers.Main) { service.interruptGenerationForHuman(conversationId) }
            try { awaitNormalHistory() } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Original window remained blocked by the late completed reply", failure)
            }
            val saved = checkNotNull(repository.getConversationById(conversationId))
            assertEquals(visible.id, saved.id)
            assertEquals(visible.messageNodes.map { it.id }, saved.messageNodes.map { it.id })
            // The user stopped at the visible partial reply. A hidden late suffix stays in
            // its recovery copy; it must not replace the visible version after interruption.
            assertEquals("partial", saved.currentMessages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
            assertTrue(saved.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>().isEmpty())
            assertNull(saved.currentMessages.last().usage)
            assertEquals(saved.messageNodes, service.getDisplayedConversationSnapshot(conversationId).messageNodes)
            assertFalse(journal.hasCheckpoint(conversationId))
            val preview = service.previewManualContext(conversationId, 1, "synthetic preserved summary")
            val archiveId = service.applyManualContext(conversationId, preview)
            awaitNormalHistory()
            val compacted = checkNotNull(repository.getConversationById(conversationId))
            assertEquals(saved.compactionEpoch + 1, compacted.compactionEpoch)
            assertEquals("partial", compacted.currentMessages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
            assertTrue(compacted.currentMessages.last().parts.filterIsInstance<UIMessagePart.Text>().isEmpty())
            assertEquals(saved.currentMessages.map { it.toText() },
                checkNotNull(repository.getConversationById(archiveId)).currentMessages.map { it.toText() })
            assertBatchDeleteWorks()
            assertEquals("Local reconciliation must not send requests or replay tools", 0, peer.requests.get())
        }

        suspend fun assertBatchDeleteWorks() {
            val c = service.getDisplayedConversationFlow(conversationId).value
            val preview = service.previewMessageBatch(conversationId, setOf(c.messageNodes.first().id),
                me.rerere.rikkahub.data.model.OrbisMessageBatchOperation.DELETE)
            service.applyMessageBatch(conversationId, preview)
            assertFalse(checkNotNull(repository.getConversationById(conversationId)).messageNodes
                .any { it.id == c.messageNodes.first().id })
        }

        suspend fun installCheckpointConflict(tailCommitted: Boolean): Conversation = withContext(Dispatchers.Main) {
            val base = checkNotNull(repository.getConversationById(conversationId)).copy(messageNodes = listOf(
                UIMessage.user("synthetic prior history").toMessageNode(),
                UIMessage.user("synthetic prior question").toMessageNode()))
            service.saveConversation(conversationId, base)
            val handle = withContext(Dispatchers.IO) { journal.begin(base) }
            val reply = UIMessage.assistant("synthetic prior complete reply").toMessageNode()
            withContext(Dispatchers.IO) {
                journal.checkpoint(handle, base.assistantId, base.compactionEpoch, base.messageNodes.takeLast(1) + reply)
            }
            val edited = base.copy(messageNodes = base.messageNodes.mapIndexed { i, node ->
                if (i == 0) node.copy(messages = node.messages.map { it.copy(translation = "synthetic human edit") }) else node
            } + if (tailCommitted) listOf(reply) else emptyList())
            service.saveConversation(conversationId, edited)
            session().messageQueue.pause()
            // Reproduce only the blocking restriction; production must prove and clear it.
            session().generationRecoveryBlocked = true
            edited
        }

        suspend fun assertGardenOldQueue() {
            withContext(Dispatchers.Main) {
                session().messageQueue.pause()
                session().messageQueue.enqueue(listOf(UIMessagePart.Text("OLD-QUEUE-DO-NOT-SEND")))
                try {
                    service.trySendGardenQuickChat(conversationId, assistant.id, "new drawer input")
                    fail("Backlog must stay held")
                } catch (e: me.rerere.rikkahub.data.orbis.GardenQuickChatSendRejected) {
                    assertEquals("garden_quick_chat_queued", e.reasonCode)
                }
            }
            assertEquals(0, peer.requests.get())
            assertTrue(session().messageQueue.state.value.paused)
            assertEquals(1, session().messageQueue.state.value.messages.size)
            assertTrue(repository.getConversationById(conversationId)!!.messageNodes.isEmpty())
        }

        suspend fun assertGardenPendingReceipt() = coroutineScope {
            installStaleLocalLatch()
            val s = session()
            s.orbisPromptEditMutex.lock()
            val first = async(Dispatchers.Main) { service.trySendGardenQuickChat(conversationId, assistant.id, "one drawer input") }
            try {
                awaitFreshHumanAnswer()
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
                assertFalse("No durable receipt while the writer is blocked", first.isCompleted)
                withContext(Dispatchers.Main) {
                    try {
                        service.trySendGardenQuickChat(conversationId, assistant.id, "one drawer input")
                        fail("Reopening the drawer must not lose its pending submission")
                    } catch (e: me.rerere.rikkahub.data.orbis.GardenQuickChatSendRejected) {
                        assertEquals("garden_quick_chat_submitting", e.reasonCode)
                    }
                }
                assertEquals(1, peer.requests.get())
            } finally { s.orbisPromptEditMutex.unlock() }
            assertTrue(withTimeout(10_000) { first.await() })
            assertEquals(1, repository.getConversationById(conversationId)!!.currentMessages.count { it.toText() == "one drawer input" })
        }

        suspend fun assertEarlierTextRegeneration(hasReply: Boolean = true, assistantRetry: Boolean = false) {
            val input = UIMessage.user("edited earlier question")
            val old = UIMessage.assistant("old answer")
            val suffix = listOf(UIMessage.user("later question").toMessageNode(), UIMessage.assistant("later answer").toMessageNode())
            val original = service.getDisplayedConversationSnapshot(conversationId).copy(
                messageNodes = listOf(input.toMessageNode()) + (if (hasReply) listOf(old.toMessageNode()) else emptyList()) + suffix)
            withContext(Dispatchers.Main) { service.saveConversation(conversationId, original) }
            // A recovered/edited window may correctly retain a paused OLD queue, while
            // its history is fully writable. Reproduce the user's actual edit -> retry.
            withContext(Dispatchers.Main) {
                session().messageQueue.pause()
                service.editMessage(conversationId, input.id, listOf(UIMessagePart.Text("edited earlier question again")))
                val editedInput = service.getDisplayedConversationSnapshot(conversationId).currentMessages.first()
                session().messageQueue.enqueue(listOf(UIMessagePart.Text("OLD-QUEUE-NOT-A-RETRY")))
                service.regenerateAtMessage(conversationId, if (assistantRetry) old else editedInput)
            }
            val shown = awaitFreshHumanAnswer()
            assertEquals(suffix, shown.messageNodes.takeLast(2))
            if (hasReply) assertTrue(shown.messageNodes[1].messages.contains(old))
            withTimeout(15_000) {
                while (repository.getConversationById(conversationId)?.currentMessages?.none { it.toText() == ANSWER } != false)
                    kotlinx.coroutines.delay(30)
            }
            val durable = checkNotNull(repository.getConversationById(conversationId))
            assertEquals(suffix, durable.messageNodes.takeLast(2))
            if (hasReply) assertTrue(durable.messageNodes[1].messages.contains(old))
            assertEquals(1, peer.requests.get())
            assertTrue("Retry must not resume the old queue", session().messageQueue.state.value.paused)
            assertEquals(1, session().messageQueue.state.value.messages.size)
            assertFalse(durable.currentMessages.any { it.toText() == "OLD-QUEUE-NOT-A-RETRY" })
        }

        suspend fun assertGardenRegeneration(assistantRetry: Boolean, recovered: Boolean) {
            val human=UIMessage.user("earlier drawer question")
            val old=UIMessage.assistant("original drawer answer")
            // An actively recovering lane deliberately permits only its final text turn.
            // Ordinary writable windows also support earlier turns and retain the suffix.
            val suffix=if(recovered) emptyList() else listOf(UIMessage.user("later preserved question").toMessageNode(),UIMessage.assistant("later preserved answer").toMessageNode())
            val base=service.getDisplayedConversationSnapshot(conversationId).copy(messageNodes=listOf(human.toMessageNode(),old.toMessageNode())+suffix)
            withContext(Dispatchers.Main) { service.saveConversation(conversationId,base) }
            if(recovered) installStaleLocalLatch()
            withContext(Dispatchers.Main) {
                assertTrue(runCatching { service.regenerateGardenQuickChat(conversationId,assistant.id,Uuid.random()) }.isFailure)
                assertTrue(runCatching { service.regenerateGardenQuickChat(conversationId,Uuid.random(),human.id) }.isFailure)
                service.regenerateGardenQuickChat(conversationId,assistant.id,if(assistantRetry) old.id else human.id)
                assertTrue(runCatching { service.regenerateGardenQuickChat(conversationId,assistant.id,human.id) }.isFailure)
            }
            val shown=awaitFreshHumanAnswer()
            assertEquals(suffix,shown.messageNodes.takeLast(suffix.size))
            assertTrue(shown.messageNodes[1].messages.contains(old))
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it==null } }
            assertEquals(1,peer.requests.get())
            assertEquals(suffix,repository.getConversationById(conversationId)!!.messageNodes.takeLast(suffix.size))
        }

        suspend fun assertGardenQuickSend(mode: String) {
            if (mode == "local") installStaleLocalLatch()
            if (mode == "pinned") repository.togglePinStatus(conversationId)
            if (mode == "unpin") {
                repository.togglePinStatus(conversationId)
                withContext(Dispatchers.Main) { service.updateConversationState(conversationId) { it.copy(isPinned = true) } }
                repository.togglePinStatus(conversationId)
            }
            val hold = if (mode == "gateway") installLegacyGatewayHold() else null
            val input = if (mode == "image") "" else "synthetic drawer message"
            val imageBytes = java.io.ByteArrayOutputStream().also { output ->
                val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                bitmap.recycle()
            }.toByteArray()
            // Match gallery/camera input: persist a local attachment before submitting.
            // Inline base64 is deliberately forbidden in durable conversation rows.
            val images = if (mode == "image") files.createChatFilesByByteArrays(listOf(imageBytes))
                .map { UIMessagePart.Image(it.toString()) } else emptyList()
            if (mode == "save-handoff") failNextHistorySave.set(true)
            val stored = withTimeout(15_000) { withContext(Dispatchers.Main) {
                service.trySendGardenQuickChat(conversationId, assistant.id, input, images)
            } }
            assertTrue("Drawer must accept the same fresh human input as the main window ($mode): ${service.errors.value}", stored)
            val durable = checkNotNull(repository.getConversationById(conversationId))
            if (mode == "pinned") assertTrue("A send must not undo a newer pin choice", durable.isPinned)
            if (mode == "unpin") assertFalse("A send must not restore a removed pin", durable.isPinned)
            assertEquals(1, durable.currentMessages.count { it.toText() == input })
            val shown = awaitFreshHumanAnswer()
            assertEquals(1, shown.currentMessages.count { it.toText() == input })
            assertEquals(1, peer.requests.get())
            if (mode == "image") {
                assertEquals(images, durable.currentMessages.single { it.role == MessageRole.USER }.parts.filterIsInstance<UIMessagePart.Image>())
                assertTrue(File(java.net.URI(images.single().url)).readBytes().contentEquals(imageBytes))
                assertTrue(peer.requestBodies.single().contains("image_url"))
                val request = Json.parseToJsonElement(peer.requestBodies.single()).jsonObject
                val user = request["messages"]!!.jsonArray.map { it.jsonObject }.last { it["role"] == JsonPrimitive("user") }
                val image = user["content"]!!.jsonArray.map { it.jsonObject }.single { it["type"] == JsonPrimitive("image_url") }
                val url = image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(url.startsWith("data:image/jpeg;base64,")) // Shared provider encoder normalizes to JPEG.
                val sentBytes = java.util.Base64.getDecoder().decode(url.substringAfter(','))
                val sent = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(sentBytes, 0, sentBytes.size))
                assertEquals(2, sent.width); assertEquals(2, sent.height)
                assertTrue(android.graphics.Color.blue(sent.getPixel(0, 0)) > 240)
                sent.recycle()
            }
            hold?.let(::assertLegacyGatewayHoldUnchanged)
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            if (mode == "pinned" || mode == "unpin") assertEquals(mode == "pinned", repository.getConversationById(conversationId)!!.isPinned)
            assertTrue(service.getMessageQueueFlow(conversationId).value.messages.isEmpty())
        }

        suspend fun installStaleLocalLatch() = withContext(Dispatchers.Main) {
            check(!journal.hasCheckpoint(conversationId))
            session().generationRecoveryBlocked = true
            session().messageQueue.pause()
        }

        suspend fun awaitFreshHumanAnswer(): Conversation = withTimeout(15_000) {
            service.getDisplayedConversationFlow(conversationId).first { c ->
                c.currentMessages.any { it.toText() == ANSWER }
            }
        }

        suspend fun assertModelPauseThenRequest(continuation: Boolean = false, decision: String? = null,
            oldHold: Boolean = false, holdWriter: Boolean = false, scope: String = "conversation", repeatRequests: Boolean = false) {
            if (continuation) installStaleLocalLatch()
            val holdBytes = if (oldHold) installLegacyGatewayHold() else null
            settings.settingsFlow.value = settings.settingsFlow.value.let { original -> original.copy(
                providers = original.providers.map { (it as ProviderSetting.OpenAI).copy(models = listOf(model.copy(
                    abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) }) }
            val host = service.boundaryHost
            val owner = assistant.id.toString()
            host.grant(owner, setOf("conversation"))
            val args = buildJsonObject {
                put("action", "pause"); put("kind", "conversation")
                put("scope", scope)
                put("revision", host.state(owner).revision); put("allow_request", true)
                put("until_epoch_ms", System.currentTimeMillis() + 120_000)
            }
            peer.customMessages.add(buildJsonObject {
                put("role", "assistant"); put("content", "SYNTHETIC-PAUSE")
                putJsonArray("tool_calls") { addJsonObject {
                    put("id", "pause-original"); put("type", "function")
                    putJsonObject("function") { put("name", "orbis_boundary"); put("arguments", args.toString()) }
                } }
            }.toString())
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("SYNTHETIC-PAUSE-REQUEST"))))
            }
            withTimeout(15_000) { host.states.first { host.pause(owner, conversationId.toString()) != null } }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            val pause = checkNotNull(host.pause(owner, conversationId.toString()))
            assertEquals(3, pause.requestsRemaining)
            if (oldHold) kotlinx.coroutines.delay(2_000) // A durable old hold outlives final-save scheduling.
            if (decision != null) peer.customMessages.add(buildJsonObject {
                put("role", "assistant"); put("content", "SYNTHETIC-DECISION")
                putJsonArray("tool_calls") { addJsonObject {
                    put("id", "decide-pause"); put("type", "function")
                    putJsonObject("function") {
                        put("name", "orbis_boundary")
                        put("arguments", buildJsonObject {
                            put("action", decision); put("scope", scope); put("revision", host.state(owner).revision + 1)
                            put("allow_request", true); put("until_epoch_ms", System.currentTimeMillis() + 120_000)
                        }.toString())
                    }
                } }
            }.toString())
            if (holdWriter) session().orbisPromptEditMutex.lock()
            try {
                val result = withTimeout(10_000) { service.requestBoundaryResume(conversationId, pause.revision, "AFTER-MODEL-PAUSE") }
                assertTrue(result, result.contains(when (decision) {
                    "resume" -> "主动恢复"; "pause" -> "明确继续暂停"; else -> "尚未通过工具"
                }))
                assertEquals(2, peer.requests.get())
                if (decision == "resume") assertNull(host.pause(owner, conversationId.toString()))
                else assertEquals(if (decision == "pause") 3 else 2, host.pause(owner, conversationId.toString())!!.requestsRemaining)
                assertTrue(service.getDisplayedConversationSnapshot(conversationId).currentMessages.any { it.toText().contains("AFTER-MODEL-PAUSE") })
                if (holdWriter) assertTrue(session().orbisPromptEditMutex.isLocked)
                holdBytes?.let(::assertLegacyGatewayHoldUnchanged)
                if (repeatRequests) {
                    repeat(2) { service.requestBoundaryResume(conversationId, pause.revision, "REPEAT-$it") }
                    assertEquals(0, host.pause(owner, "another-window")!!.requestsRemaining)
                    assertTrue(runCatching { service.requestBoundaryResume(conversationId, pause.revision, "FOURTH") }.isFailure)
                    assertEquals(4, peer.requests.get())
                    assertFalse(service.getDisplayedConversationSnapshot(conversationId).currentMessages.any { it.toText().contains("FOURTH") })
                    assertTrue(runCatching { host.requireInput(owner, "another-window") }.isFailure)
                }
            } finally {
                if (holdWriter) session().orbisPromptEditMutex.unlock()
            }
        }

        suspend fun assertPausedTurn(wake: Boolean) {
            settings.settingsFlow.value = settings.settingsFlow.value.let { it.copy(
                assistants = listOf(assistant.copy(localTools=listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.TimeInfo))),
                providers = it.providers.map { p -> (p as ProviderSetting.OpenAI).copy(models=listOf(model.copy(
                    abilities=listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) }) }
            val host = service.boundaryHost
            val owner = assistant.id.toString()
            host.grant(owner, setOf("conversation"))
            session().messageQueue.enqueue(listOf(UIMessagePart.Text("OLD-BACKLOG")))
            host.update(owner) { me.rerere.rikkahub.data.orbis.boundary.changeBoundary(it,it.revision,conversationId.toString(),
                "pause","conversation","",allowRequest=true,until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis(),scope="assistant") }
            val revision = host.pause(owner,conversationId.toString())!!.revision
            withTimeout(5_000) { service.getMessageQueueFlow(conversationId).first {
                it.messages.single().recoveryHeldReason != null } }
            assertFalse("Boundary pause must not leave a generic failure card", paused())
            peer.customMessages.add("""{"role":"assistant","content":"Checking the time","tool_calls":[{"id":"time-in-pause","type":"function","function":{"name":"get_time_info","arguments":"{}"}}]}""")
            if (wake) {
                service.bindOrbisEvents(setOf("self_reminder"), me.rerere.rikkahub.data.orbis.OrbisEventBinding(owner,conversationId.toString()))
                val event = service.acceptOrbisEvent(me.rerere.rikkahub.data.orbis.OrbisIncomingEvent(
                    event_id="synthetic-boundary-wake",source="self_reminder",text="SYNTHETIC-WAKE",wake=true)).first
                withTimeout(20_000) { service.orbisEvents.inbox.state.first { state ->
                    state.events.any { it.id==event.id && it.state=="replied" } } }
                assertTrue(service.orbisEvents.inbox.get(event.id)!!.historyCommitted)
                assertFalse(service.orbisEvents.inbox.get(event.id)!!.isConversationNotification(owner,conversationId.toString()))
            } else {
                withTimeout(20_000) { service.requestBoundaryResume(conversationId,revision,"APPLICATION-0") }
                repeat(2) { withTimeout(20_000) { service.requestBoundaryResume(conversationId,revision,"APPLICATION-${it+1}") } }
                val before = repository.getConversationById(conversationId)!!
                assertTrue(runCatching { service.requestBoundaryResume(conversationId,revision,"FOURTH-MUST-NOT-SEND") }.isFailure)
                assertEquals(before,repository.getConversationById(conversationId))
                assertEquals(0,host.pause(owner,conversationId.toString())!!.requestsRemaining)
            }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it==null } }
            val saved = repository.getConversationById(conversationId)!!
            val actual = saved.currentMessages.flatMap { it.getTools() }.single { it.toolName=="get_time_info" }
            assertTrue(actual.isExecuted)
            assertTrue(actual.toString().contains("timestamp_ms"))
            assertTrue(saved.currentMessages.any { it.toText().contains(ANSWER) })
            val bodies = peer.requestBodies.toList().map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(bodies.first().getValue("tools").toString().contains("get_time_info"))
            assertEquals("Frozen catalogue across tool continuation",bodies[0]["tools"],bodies[1]["tools"])
            File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                "boundary149-wire-$wake.json").writeText(kotlinx.serialization.json.JsonArray(bodies).toString())
            assertNotNull(host.pause(owner,conversationId.toString()))
            assertTrue(runCatching { host.requireInput(owner,"another-window") }.isFailure)
            assertEquals(if(wake) 2 else 4,peer.requests.get())
            host.update(owner) { it.copy(assistantPause=it.assistantPause!!.copy(until=System.currentTimeMillis()-1)) }
            withTimeout(5_000) { host.states.first { it[owner]?.assistantPause?.paused==false } }
            assertFalse(paused())
            assertTrue(session().messageQueue.state.value.messages.single().recoveryHeldReason != null)
            assertTrue(saved.currentMessages.none { it.toText()=="OLD-BACKLOG" })
            host.requireInput(owner,conversationId.toString())
            assertFreshInputCanDrain()
            assertTrue(repository.getConversationById(conversationId)!!.currentMessages.none { it.toText()=="OLD-BACKLOG" })
        }

        suspend fun assertBoundaryRequest(decision: String?, stream: Boolean = false, scope: String = "conversation") = coroutineScope {
            val mode = me.rerere.rikkahub.data.model.PromptInjection.ModeInjection(content = "ORIGINAL-MODE")
            val lore = me.rerere.rikkahub.data.model.Lorebook(entries = listOf(
                me.rerere.rikkahub.data.model.PromptInjection.RegexInjection(
                    content = "ORIGINAL-LORE", keywords = listOf("ORIGINAL-REQUEST"))))
            val other = assistant.copy(id = Uuid.random(), systemPrompt = "OTHER-OWNER-MUST-NOT-LEAK")
            val enabled = model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
            settings.settingsFlow.value = settings.settingsFlow.value.let { it.copy(
                assistantId = other.id, assistants = listOf(assistant.copy(allowConversationSystemPrompt = true, allowConversationPromptInjection = true, streamOutput = stream), other),
                providers = it.providers.map { p -> (p as ProviderSetting.OpenAI).copy(models = listOf(enabled)) },
                modeInjections = listOf(mode), lorebooks = listOf(lore)) }
            val history = (1..14).flatMap { listOf(UIMessage.user("ORIGINAL-USER-$it"), UIMessage.assistant("ORIGINAL-AI-$it")) }
                .map { it.toMessageNode() }
            val base = repository.getConversationById(conversationId)!!.copy(messageNodes = history,
                customSystemPrompt = "ORIGINAL-CONVERSATION-SYSTEM", modeInjectionIds = setOf(mode.id), lorebookIds = setOf(lore.id))
            withContext(Dispatchers.Main) {
                service.saveConversation(conversationId, base)
                service.saveConversationInjections(me.rerere.rikkahub.data.model.ConversationInjectionEdit(
                    conversationId, assistant.id, setOf(mode.id), emptySet(), setOf(lore.id), emptySet()))
            }
            val configured = repository.getConversationById(conversationId)!!
            assertEquals("ORIGINAL-CONVERSATION-SYSTEM", configured.customSystemPrompt)
            assertEquals(setOf(mode.id), configured.modeInjectionIds)
            assertEquals(setOf(lore.id), configured.lorebookIds)
            val host = service.boundaryHost
            val owner = assistant.id.toString()
            host.grant(owner, setOf("conversation"))
            val state = host.update(owner) {
                me.rerere.rikkahub.data.orbis.boundary.changeBoundary(it, it.revision, conversationId.toString(),
                    "pause", "conversation", "", reason = "Original agreement", allowRequest = true, until = System.currentTimeMillis()+120_000, now = System.currentTimeMillis(), scope = scope)
            }
            val revision = host.pause(owner, conversationId.toString())!!.revision
            if (scope == "assistant") {
                assertTrue(runCatching { host.requireInput(owner, "future-window") }.isFailure)
                assertTrue(runCatching { host.requestContext(owner, null).check(model.id.toString()) }.isFailure)
                host.requireInput(other.id.toString(), "future-window")
                host.requestContext(other.id.toString(), null).check(model.id.toString())
            }
            session().messageQueue.pause()
            session().messageQueue.enqueue(listOf(UIMessagePart.Text("OLD-QUEUED-MUST-NOT-RUN")))
            fun control(action: String) = """{"role":"assistant","content":"$ANSWER","reasoning_content":"synthetic boundary reasoning","tool_calls":[{"id":"boundary-$action","type":"function","function":{"name":"orbis_boundary","arguments":"{\"action\":\"$action\",\"revision\":${host.state(owner).revision + 1},\"allow_request\":true,\"until_epoch_ms\":${System.currentTimeMillis()+120_000}}"}}]}"""
            when (decision) {
                "state_only" -> peer.customMessages.add(control("state"))
                "invalid_pause" -> peer.customMessages.add(control("pause").replace("allow_request", "ignored_field"))
                null -> Unit
                else -> { peer.customMessages.add(control("state")); peer.customMessages.add(control(decision)) }
            }
            peer.holdBoundaryRequest.set(true)
            val request = async { service.requestBoundaryResume(conversationId, revision, "ORIGINAL-REQUEST") }
            withTimeout(10_000) { peer.boundaryRequestHeld.await() }
            assertNotNull("The request must not unlock ordinary input", host.pause(owner, conversationId.toString()))
            assertTrue(host.pause(owner, conversationId.toString())!!.requestUsed)
            val ordinary = runCatching { service.sendMessage(conversationId, listOf(UIMessagePart.Text("BLOCKED-NORMAL-INPUT"))) }
            assertTrue(ordinary.isFailure || ordinary.getOrNull() == false)
            assertTrue(runCatching { service.requestBoundaryResume(conversationId, revision, "DUPLICATE-REQUEST") }.isFailure)
            if (scope == "assistant") {
                assertTrue(runCatching { host.claimRecovery(owner, "different-window", revision) }.isFailure)
                assertNotNull(host.pause(owner, "new-window"))
            }
            peer.releaseBoundaryRequest.countDown()
            val result = withTimeout(15_000) { request.await() }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            val saved = repository.getConversationById(conversationId)!!
            assertEquals("Existing messages must remain byte-for-byte represented", history, saved.messageNodes.take(history.size))
            assertEquals(1, saved.currentMessages.count { it.toText() == "【申请继续】\nORIGINAL-REQUEST" })
            assertTrue(saved.currentMessages.drop(history.size + 1).any { it.toText().contains(ANSWER) })
            assertTrue(saved.currentMessages.drop(history.size + 1).all { it.finishedAt != null })
            assertTrue(saved.currentMessages.drop(history.size + 1).flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Reasoning>().all { it.finishedAt != null })
            assertFalse(journal.hasCheckpoint(conversationId))
            assertEquals(if (decision == null) 1 else 2, peer.requests.get())
            peer.requestBodies.forEach { body ->
                listOf("ORIGINAL-USER-1", "ORIGINAL-AI-1", "ORIGINAL-USER-14", "ORIGINAL-AI-14",
                    "ORIGINAL-CONVERSATION-SYSTEM", "ORIGINAL-MODE", "ORIGINAL-LORE", "ORIGINAL-REQUEST")
                    .forEach { assertTrue("Original context missing: $it", body.contains(it)) }
                listOf("OTHER-OWNER-MUST-NOT-LEAK", "BLOCKED-NORMAL-INPUT", "DUPLICATE-REQUEST", "OLD-QUEUED-MUST-NOT-RUN")
                    .forEach { assertFalse("Unrelated or blocked input leaked: $it", body.contains(it)) }
                assertTrue(body.contains("orbis_boundary"))
            }
            assertTrue(session().messageQueue.state.value.messages.any { it.parts.any { p -> p is UIMessagePart.Text && p.text == "OLD-QUEUED-MUST-NOT-RUN" } })
            when (decision) {
                "resume" -> { assertNull(host.pause(owner, conversationId.toString())); assertTrue(result.contains("主动恢复")) }
                "pause" -> { assertNotNull(host.pause(owner, conversationId.toString())); assertTrue(result.contains("明确继续暂停")) }
                else -> {
                    assertNotNull(host.pause(owner, conversationId.toString()))
                    assertTrue(result.contains("尚未通过工具")); assertFalse(result.contains("选择继续暂停"))
                    assertFalse("No decision must release this request slot", host.pause(owner, conversationId.toString())!!.requestUsed)
                    peer.customMessages.add(control("state")); peer.customMessages.add(control("resume"))
                    val next = withTimeout(15_000) { service.requestBoundaryResume(conversationId, revision, "ORIGINAL-REQUEST-AGAIN") }
                    assertTrue(next.contains("主动恢复")); assertNull(host.pause(owner, conversationId.toString()))
                    assertEquals(if (decision == null) 3 else 4, peer.requests.get())
                }
            }
        }

        suspend fun assertSelfCheckRoundTrip() {
            settings.settingsFlow.value = settings.settingsFlow.value.let { original -> original.copy(
                init = false,
                assistants = listOf(assistant.copy(localTools = listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SelfCheck))),
                providers = original.providers.map { (it as ProviderSetting.OpenAI).copy(models = listOf(model.copy(
                    abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL)))) }) }
            peer.customMessages.add("""{"role":"assistant","content":"Checking local theme configuration","tool_calls":[{"id":"self-check-1","type":"function","function":{"name":"orbis_self_check","arguments":"{\"focus\":\"theme\"}"}}]}""")
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("check the theme setup"))))
            }
            // This turn has text before the tool and the final answer in the same message.
            withTimeout(15_000) { service.getDisplayedConversationFlow(conversationId).first { c ->
                c.currentMessages.any { it.toText().trim().endsWith(ANSWER) }
            } }
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            val saved = repository.getConversationById(conversationId)!!
            assertTrue(saved.currentMessages.any { it.toText().contains("Checking local theme configuration") && it.toText().trim().endsWith(ANSWER) })
            val tool = saved.currentMessages.flatMap { it.getTools() }.single { it.toolName == "orbis_self_check" }
            assertTrue(tool.isExecuted)
            val report = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
            assertTrue(report, report.contains("tool_orbis_theme"))
            assertTrue(report, report.contains("人类尚未"))
            assertTrue(report, report.contains("theme"))
            assertEquals(2, peer.requests.get())
            val bodies = peer.requestBodies.toList().map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(bodies.first().getValue("tools").toString().contains("orbis_self_check"))
            assertTrue(bodies.last().getValue("messages").toString().contains("tool_orbis_theme"))
            assertEquals("Checking must not enable the missing theme tool", listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SelfCheck),
                settings.settingsFlow.value.assistants.single().localTools)
        }

        suspend fun assertConversationSettingsRoundTrip(continuation: Boolean) {
            val option = me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.ConversationSettings
            val enabledModel = model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
            settings.settingsFlow.value = settings.settingsFlow.value.let { original -> original.copy(
                assistants = listOf(assistant.copy(localTools = listOf(option))),
                providers = original.providers.map { (it as ProviderSetting.OpenAI).copy(models = listOf(enabledModel)) }) }
            val original = me.rerere.rikkahub.data.model.OrbisConversationPrompt("OLD-PROMPT", true, "KEPT-WORLD", true)
            service.saveOrbisPrompt(conversationId, original)
            if (continuation) installStaleLocalLatch()
            peer.customMessages.add("""{"role":"assistant","content":null,"tool_calls":[{"id":"settings-one","type":"function","function":{"name":"orbis_conversation_settings","arguments":"{\"action\":\"update\",\"text\":\"NEW-PROMPT\"}"}}]}""")
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("update this conversation"))))
            }
            withTimeout(15_000) { service.getDisplayedConversationFlow(conversationId).first { c ->
                c.currentMessages.any { it.toText().trim() == ANSWER }
            } }
            withTimeout(15_000) {
                while (journal.hasCheckpoint(conversationId) || repository.getConversationById(conversationId)!!
                    .currentMessages.none { it.toText().trim() == ANSWER }) kotlinx.coroutines.delay(25)
            }
            val saved = repository.getConversationById(conversationId)!!
            assertEquals(original.copy(text = "NEW-PROMPT"), saved.orbisPrompt)
            assertEquals(saved.orbisPrompt, service.getDisplayedConversationFlow(conversationId).value.orbisPrompt)
            val tool = saved.currentMessages.flatMap { it.getTools() }.single { it.toolName == "orbis_conversation_settings" }
            assertTrue(tool.isExecuted)
            val receipt = Json.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("saved", receipt["status"]!!.jsonPrimitive.content)
            fun system(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }.toString()
            assertEquals(2, peer.requests.get())
            peer.requestBodies.forEach { assertTrue(system(it).contains("OLD-PROMPT")); assertFalse(system(it).contains("NEW-PROMPT")) }
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("next turn"))))
            }
            withTimeout(15_000) { while (peer.requests.get() < 3) kotlinx.coroutines.delay(25) }
            assertTrue(system(peer.requestBodies.last()).contains("NEW-PROMPT"))
            assertTrue(system(peer.requestBodies.last()).contains("KEPT-WORLD"))
            assertTrue(runCatching { service.updateOrbisPromptFromTool(conversationId, Uuid.random()) { it.copy(text = "wrong owner") } }.isFailure)
            assertEquals("NEW-PROMPT", repository.getConversationById(conversationId)!!.orbisPrompt.text)
        }

        suspend fun assertRecallRoundTrip(continuation: Boolean = false) {
            if (continuation) installStaleLocalLatch()
            val recallOption = me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.MessageRecall
            val enabledModel = model.copy(abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
            settings.settingsFlow.value = settings.settingsFlow.value.let { original -> original.copy(
                assistants = listOf(assistant.copy(localTools = listOf(recallOption))),
                providers = original.providers.map { (it as ProviderSetting.OpenAI).copy(models = listOf(enabledModel)) }) }
            val oldHuman = UIMessage.user("RECALL-HUMAN-SECRET")
            val oldAi = UIMessage.assistant("RECALL-AI-SECRET")
            val base = repository.getConversationById(conversationId)!!.copy(messageNodes = listOf(oldHuman.toMessageNode(), oldAi.toMessageNode()))
            withContext(Dispatchers.Main) { service.saveConversation(conversationId, base) }
            service.recallHumanMessage(conversationId, oldHuman.id)
            peer.recallNext.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("recall the previous reply"))))
            }
            withTimeout(15_000) { service.getDisplayedConversationFlow(conversationId).first { c ->
                c.currentMessages.any { it.toText().trim() == ANSWER }
            } }
            val cleared = kotlinx.coroutines.withTimeoutOrNull(15_000) { while (journal.hasCheckpoint(conversationId)) kotlinx.coroutines.delay(50); true }
            assertEquals("Recall final journal should clear", true, cleared)
            // Independent continuation publishes before its asynchronous Room observer completes.
            val saved = withTimeout(15_000) {
                while (true) {
                    val committed = repository.getConversationById(conversationId)!!
                    if (committed.currentMessages.any { it.toText().trim() == ANSWER }) return@withTimeout committed
                    kotlinx.coroutines.delay(25)
                }
                error("unreachable")
            }
            assertTrue(saved.currentMessages.single { it.id == oldAi.id }.orbisRecalled)
            assertEquals(2, peer.requests.get())
            assertTrue(peer.requestBodies.none { it.contains("RECALL-HUMAN-SECRET") })
            assertTrue(peer.requestBodies.last().contains("RECALL-AI-SECRET"))
            assertTrue(peer.requestBodies.last().contains("本消息已被你撤回"))
            val tool = saved.currentMessages.flatMap { it.getTools() }.single { it.toolName == "orbis_recall_message" }
            assertEquals(listOf(UIMessagePart.Text("{\"success\":true}")), tool.output)
            assertFalse(saved.currentMessages.single { it.id == oldAi.id }.toDto().parts.toString().contains("RECALL-AI-SECRET"))
        }

        suspend fun assertReplyNotification(continuation: Boolean) {
            if (continuation) installStaleLocalLatch()
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic notification request"))))
            }
            awaitFreshHumanAnswer()
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            // The shared event consumer is asynchronous; this is not a fabricated completion event.
            kotlinx.coroutines.delay(200)
            val ended = notificationEvents.filterIsInstance<AppEvent.ChatGenerationEnded>()
                .filter { it.contentPreview != null }
            assertEquals("A real completed reply must reach the system-notification subscriber", 1, ended.size)
            assertEquals(conversationId, ended.single().conversationId)
            assertEquals(ANSWER.take(50), ended.single().contentPreview)
            assertTrue(notificationEvents.any { it is AppEvent.ChatGenerationUpdate && it.conversationId == conversationId })
            assertEquals(1, peer.requests.get())
        }

        suspend fun assertNoCompletedNotification() {
            kotlinx.coroutines.delay(200)
            assertFalse(notificationEvents.any { it is AppEvent.ChatGenerationEnded && it.contentPreview != null })
        }

        suspend fun assertAndroidNotification(enabled: Boolean) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            assertTrue("The isolated emulator must grant notifications for this integration test", manager.areNotificationsEnabled())
            manager.createNotificationChannel(android.app.NotificationChannel(
                me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, "Synthetic completion",
                android.app.NotificationManager.IMPORTANCE_HIGH))
            manager.cancel(1)
            settings.settingsFlow.value = settings.settingsFlow.value.let { value -> value.copy(
                displaySetting = value.displaySetting.copy(enableNotificationOnMessageGeneration = enabled,
                    enableLiveUpdateNotification = false)) }
            withContext(Dispatchers.Main) { ChatNotificationManager(context, activeScope, bus, settings) }
            kotlinx.coroutines.delay(100) // Start the real subscriber before submitting the model request.
            try {
                assertReplyNotification(continuation = true)
                if (enabled) {
                    val notice = withTimeout(5_000) {
                        while (manager.activeNotifications.none { it.id == 1 }) kotlinx.coroutines.delay(20)
                        manager.activeNotifications.single { it.id == 1 }.notification
                    }
                    assertEquals(ANSWER.take(50), notice.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
                    assertEquals(me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, notice.channelId)
                } else {
                    kotlinx.coroutines.delay(200)
                    assertTrue(manager.activeNotifications.none { it.id == 1 })
                }
            } finally { manager.cancel(1) }
        }

        suspend fun assertCancelledReplyHasNoNotification() {
            installStaleLocalLatch()
            peer.holdFirstRequest.set(true)
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic cancelled request"))))
            }
            withTimeout(10_000) { peer.firstRequestHeld.await() }
            withContext(Dispatchers.Main) { service.stopGeneration(conversationId) }
            peer.releaseFirstRequest.countDown()
            withTimeout(10_000) { service.getGenerationJobStateFlow(conversationId).first { it == null } }
            assertNoCompletedNotification()
        }

        suspend fun assertHumanWakeWhileHistoryLocked() {
            val s = session()
            s.orbisPromptEditMutex.lock()
            try {
                withContext(Dispatchers.Main) {
                    s.manualContextWriteInProgress = true
                    assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("wake while writer is stuck"))))
                }
                assertEquals(conversationId, awaitFreshHumanAnswer().id)
                assertEquals(1, peer.requests.get())
                assertTrue("Test must still own the unavailable writer", s.orbisPromptEditMutex.isLocked)
            } finally {
                s.manualContextWriteInProgress = false
                s.orbisPromptEditMutex.unlock()
            }
        }

        suspend fun assertStoppedCleanupStaysIdle() {
            val s = session()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = activeScope.launch(Dispatchers.Main, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                finally { withContext(NonCancellable) { release.await() } }
            }
            withContext(Dispatchers.Main) { s.setJob(old) }
            started.await()
            try {
                withContext(Dispatchers.Main) { service.interruptGenerationForHuman(conversationId) }
                withTimeout(10_000) { s.humanContinuation.first { it == null } }
                assertTrue(old.isCancelled)
                assertFalse("Keep the original cleanup blocked through the handoff", old.isCompleted)
                assertTrue(s.hasUnfinishedJobs())
                repeat(3) {
                    assertNull(service.getGenerationJobStateFlow(conversationId).first())
                    assertFalse(service.getConversationJobs().first().containsKey(conversationId))
                    kotlinx.coroutines.delay(100)
                }
                assertEquals("Stopping and saving must not issue a model request", 0, peer.requests.get())
                withContext(Dispatchers.Main) {
                    assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic fresh message after stop"))))
                }
                awaitFreshHumanAnswer()
                assertEquals("Only the explicitly sent new input runs", 1, peer.requests.get())
            } finally { release.complete(Unit); old.join() }
        }

        suspend fun assertNormalStopDoesNotWaitForCleanup() {
            val s = session()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val baseline = s.state.value
            val old = activeScope.launch(Dispatchers.Main, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                finally { withContext(NonCancellable) {
                    release.await()
                    // Simulate the old producer returning a stale display snapshot after cancellation.
                    s.state.value = baseline
                    finished.complete(Unit)
                } }
            }
            withContext(Dispatchers.Main) { s.setJob(old); old.start() }
            started.await()
            try {
                withTimeout(1_000) { withContext(Dispatchers.Main) {
                    service.interruptGenerationForHuman(conversationId)
                } }
                assertFalse(old.isCompleted)
                withContext(Dispatchers.Main) {
                    assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic new correction"))))
                }
                awaitFreshHumanAnswer()
                release.complete(Unit)
                finished.await()
                val current = service.getDisplayedConversationFlow(conversationId).value
                assertTrue(current.currentMessages.any { it.toText() == "synthetic new correction" })
                assertTrue(current.currentMessages.any { it.toText() == ANSWER })
                assertEquals(1, peer.requests.get())
            } finally { release.complete(Unit); old.join() }
        }

        suspend fun assertProactiveUnknownTargetIsReadOnly() = withContext(Dispatchers.Main) {
            val field = ChatService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            val before = (field.get(service) as Map<*, *>).size
            assertFalse(service.mayRunProactiveDecision(Uuid.random(), assistant.id))
            assertFalse(service.mayRunProactiveDecision(conversationId, Uuid.random()))
            assertEquals(before, (field.get(service) as Map<*, *>).size)
        }

        suspend fun assertHumanPauseBlocksProactiveDecision() = withContext(Dispatchers.Main) {
            session().messageQueue.pause()
            assertTrue(session().messageQueue.state.value.paused)
            assertFalse(service.mayRunProactiveDecision(conversationId, assistant.id, session().state.value))
            assertTrue("Read-only admission must not clear the human pause", session().messageQueue.state.value.paused)
        }

        fun voiceIngressMutex(): Mutex = ChatService::class.java.getDeclaredField("voiceIngressMutex")
            .apply { isAccessible = true }.get(service) as Mutex

        private fun session(): ConversationSession {
            val field = ChatService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            return (field.get(service) as Map<*, *>)[conversationId] as ConversationSession
        }

        private fun readPauseBytes(name: String): String? {
            val file = AtomicFile(File(context.noBackupFilesDir, name))
            return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else null
        }

        private fun readOnlyPauseStore(name: String) = OrbisQueuePauseStore(
            read = { readPauseBytes(name) }, write = { error("Assertion must not modify durable restrictions") })

        suspend fun installLegacyGatewayHold(): String = withContext(Dispatchers.Main) {
            // Reproduce an unresolved hold from a previous process using the service's actual
            // AtomicFile writer. This is only a restriction, never a fake remote ACK/idle result.
            val field = ChatService::class.java.getDeclaredField("gatewayRecoveryHoldStore\$delegate")
                .apply { isAccessible = true }
            val store = (field.get(service) as Lazy<*>).value as OrbisQueuePauseStore
            store.pause(conversationId.toString(), "gateway_terminal_unconfirmed")
            session().gatewayRecoveryBlocked = true
            assertEquals(QueuePauseStatus.PAUSED, store.status(conversationId.toString()))
            checkNotNull(readPauseBytes(GATEWAY_HOLD_FILE))
        }

        fun persistedFreshRecoveryStatus(): FreshHumanRecoveryStatus = FreshHumanInputRecoveryStore(
            readOnlyPauseStore(FreshHumanInputRecoveryStore.FILE_NAME)
        ).status(conversationId.toString(), assistant.id.toString())

        fun assertLegacyGatewayHoldUnchanged(expectedBytes: String) {
            assertEquals(expectedBytes, readPauseBytes(GATEWAY_HOLD_FILE))
            assertEquals("gateway_terminal_unconfirmed", readOnlyPauseStore(GATEWAY_HOLD_FILE)
                .pauseReason(conversationId.toString()))
            assertTrue(session().gatewayRecoveryBlocked)
        }

        suspend fun dismissPausedNotice() {
            val reset = withContext(Dispatchers.Main) {
                // Reproduce a host error pause while input persistence is suspended. This does
                // not fake an ACK, checkpoint result, permit, or any part of the recovery action.
                session().messageQueue.pause()
                service.dismissPauseAndContinueFreshInput(conversationId)
            }
            reset.join()
            assertEquals(QueueRecoveryPhase.SUCCESS, service.messageQueueRecoveryState(conversationId).first().phase)
        }

        suspend fun assertRetainedExactlyOnce(text: String, substring: Boolean = false) {
            fun List<UIMessagePart>.matches() = filterIsInstance<UIMessagePart.Text>().any {
                if (substring) text in it.text else text == it.text
            }
            val stored = checkNotNull(repository.getConversationById(conversationId))
            val queued = service.getMessageQueueFlow(conversationId).value.messages.filter { it.parts.matches() }
            assertEquals(1, stored.currentMessages.count { it.parts.matches() } + queued.size)
            assertTrue(queued.all { it.recoveryHeldReason != null })
        }

        suspend fun assertAbsentFromChatAndQueue(text: String) {
            fun List<UIMessagePart>.containsText() = filterIsInstance<UIMessagePart.Text>().any { text in it.text }
            assertFalse(checkNotNull(repository.getConversationById(conversationId)).currentMessages.any { it.parts.containsText() })
            assertFalse(service.getMessageQueueFlow(conversationId).value.messages.any { it.parts.containsText() })
        }

        suspend fun assertNewReplyAndNewCallWork(): String {
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic genuinely new request"))))
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            assertEquals(1, peer.requests.get())
            assertFalse(paused())
            assertTrue(checkNotNull(repository.getConversationById(conversationId)).currentMessages.any {
                it.parts.any { part -> part is UIMessagePart.Text && part.text == ANSWER }
            })
            assertFalse(service.mayAcceptPeriodicVideoFrame(conversationId, callId))
            val newCallId = withContext(Dispatchers.Main) {
                val next = service.prepareVoiceCall(conversationId, video = true)
                service.connectVoiceCall(next.id, System.currentTimeMillis())
                service.getGenerationJobStateFlow(conversationId).first { it == null }
                assertTrue(service.mayAcceptPeriodicVideoFrame(conversationId, next.id))
                assertFalse(service.mayAcceptPeriodicVideoFrame(conversationId, callId))
                next.id
            }
            assertEquals("New call connection must not replay any previous model request", 1, peer.requests.get())
            return newCallId
        }

        fun installFinalSnapshotGate() {
            // Decorate only this fixture's repository storage, not service completion/ACK logic.
            // Preserve the real Android atomic-file writer and shared lock key. No production
            // injectable hooks are added merely to make this regression pass.
            val field = OrbisVoiceCallRepository::class.java.getDeclaredField("storage").apply { isAccessible = true }
            val actual = field.get(service.voiceCalls) as OrbisVoiceCallStorage
            val gated = object : OrbisVoiceCallStorage by actual {
                private val once = AtomicBoolean(false)
                override fun write(id: String, value: String) {
                    val record = JsonInstant.decodeFromString<OrbisVoiceCallRecord>(value)
                    if (record.sourceNodesJson?.contains(ANSWER) == true && !journal.hasCheckpoint(conversationId) &&
                        once.compareAndSet(false, true)) {
                        finalSnapshotEntered.complete(Unit)
                        check(releaseSnapshot.await(15, TimeUnit.SECONDS)) { "Synthetic final snapshot gate timed out" }
                    }
                    actual.write(id, value)
                }
            }
            field.set(service.voiceCalls, gated)
            check(field.get(service.voiceCalls) === gated)
        }

        suspend fun assertFreshInputCanDrain() {
            withContext(Dispatchers.Main) {
                assertTrue(service.sendMessage(conversationId, listOf(UIMessagePart.Text("synthetic fresh input")), answer = false))
                service.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            val stored = checkNotNull(repository.getConversationById(conversationId))
            assertEquals(1, stored.currentMessages.count { it.parts.any { p -> p is UIMessagePart.Text && p.text == "synthetic fresh input" } })
            assertFalse(paused())
        }

        suspend fun close() {
            allowHistorySave.complete(Unit)
            releaseSnapshot.countDown()
            val job = withContext(Dispatchers.Main) {
                val current = service.getGenerationJobStateFlow(conversationId).first()
                service.cleanup()
                activeScope.cancel()
                current
            }
            withTimeout(15_000) {
                job?.join()
                // Independent replies also own asynchronous persistence jobs in this scope.
                // Cancellation alone does not finish their NonCancellable finalizers.
                activeScope.coroutineContext[Job]?.join()
            }
            // Tool registration initializes idle runtime singletons. Stop only our exact
            // context-owned scopes so their maintenance jobs cannot outlive this cache tree.
            val runtimeJobs = withContext(Dispatchers.Main) {
                listOf(OrbisVideoCallRuntime::class.java, OrbisVoiceCallRuntime::class.java, me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels::class.java).mapNotNull { type ->
                    val instanceField = type.getDeclaredField("instance").apply { isAccessible = true }
                    val instance = instanceField.get(null) ?: return@mapNotNull null
                    val owner = type.getDeclaredField("context").apply { isAccessible = true }.get(instance)
                    check(owner === context) { "Refusing to change a runtime not owned by this fixture" }
                    if (instance is OrbisVoiceCallRuntime) check(!instance.callState.value.isActive)
                    val scope = type.getDeclaredField("scope").apply { isAccessible = true }.get(instance) as CoroutineScope
                    scope.cancel()
                    instanceField.set(null, null)
                    scope.coroutineContext[Job]
                }
            }
            withTimeout(15_000) { runtimeJobs.forEach { it.join() } }
            val boundaryType = me.rerere.rikkahub.data.orbis.boundary.BoundaryHost::class.java
            val boundaryField = boundaryType.getDeclaredField("instance").apply { isAccessible = true }
            boundaryField.get(null)?.let { host ->
                val ledger = boundaryType.getDeclaredField("file").apply { isAccessible = true }.get(host) as AtomicFile
                check(ledger.baseFile.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                (boundaryType.getDeclaredField("timer").apply { isAccessible = true }.get(host) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
                boundaryField.set(null, null)
            }
            me.rerere.rikkahub.data.orbis.tabletop.TabletopConversationHost::class.java.getDeclaredField("instance").apply { isAccessible=true }.set(null,null)
            check(GlobalContext.getOrNull() === isolatedKoin.koin)
            stopKoin()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            peer.close()
            database.close()
            check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("service-voice-cancellation-"))
            directory.walkTopDown().forEach { check(!Files.isSymbolicLink(it.toPath())) }
            check(directory.deleteRecursively()) // Only this newly generated fixture, never app data.
        }
    }

    private class EmptySentinelPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = error("Fixture must not configure sentinel ownership")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
    /** No call is forwarded to the real application's SharedPreferences. */
    private class CatalogMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        @Synchronized override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
        @Synchronized override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        @Synchronized override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        @Synchronized override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        @Synchronized override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        @Synchronized override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listener?.let(listeners::add) }
        @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { listeners.remove(listener) }
        private inner class Editor : SharedPreferences.Editor {
            private val writes = mutableMapOf<String, Any?>()
            private var clearAll = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { writes[requireNotNull(key)] = value }
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                synchronized(this@CatalogMemoryPreferences) {
                    if (clearAll) values.clear()
                    writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    writes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@CatalogMemoryPreferences, key) } }
                }
                return true
            }
        }
    }
    private class StorageOnlyApplication(base: Context, private val owned: File) : Application() {
        private val catalog = CatalogMemoryPreferences()
        private val tabletopPreferences=CatalogMemoryPreferences()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = owned
        override fun getFilesDir(): File = File(owned, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(owned, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(owned, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = error("Persistent database forbidden")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            if (name == "orbis_agent_catalog") return catalog
            if (name == "tabletop-assistant-chats-v1") return tabletopPreferences
            check(name == "orbis_sentinel_transport") { "Real preferences forbidden" }
            return EmptySentinelPreferences()
        }
        override fun startService(service: Intent): ComponentName? = error("Fixture does not start services")
        override fun startForegroundService(service: Intent): ComponentName? = error("Fixture does not start foreground services")
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = error("Fixture does not bind services")
        override fun startActivity(intent: Intent) { error("Fixture does not open UI") }
        override fun sendBroadcast(intent: Intent) { error("Fixture does not broadcast") }
        override fun sendBroadcast(intent: Intent, receiverPermission: String?) { error("Fixture does not broadcast") }
    }

    private class OneAnswerPeer(private val maxRequests: Int) : Closeable {
        private val socket = ServerSocket(0, 1, LOOPBACK)
        val port = socket.localPort
        val requests = AtomicInteger()
        val recallNext = AtomicBoolean(false)
        val customMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val holdBoundaryRequest = AtomicBoolean(false)
        val boundaryRequestHeld = CompletableDeferred<Unit>()
        val releaseBoundaryRequest = CountDownLatch(1)
        val requestBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestHeaders = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val balanceFailure = AtomicBoolean(false)
        val holdFirstRequest = AtomicBoolean(false)
        val firstRequestHeld = CompletableDeferred<Unit>()
        val releaseFirstRequest = CountDownLatch(1)
        val holdReasoningStream = AtomicBoolean(false)
        val releaseReasoningStream = CountDownLatch(1)
        private val stopped = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "synthetic-chat-service-peer", isDaemon = true) {
            try {
                while (!stopped.get()) socket.accept().use { connection ->
                    connection.soTimeout = 8_000
                    val input = connection.getInputStream().buffered()
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) {
                            val next = input.read()
                            check(next >= 0 && text.length < 8192)
                            if (next == 10) return text.toString().trimEnd('\r')
                            text.append(next.toChar())
                        }
                    }
                    check(line() == "POST /v1/chat/completions HTTP/1.1")
                    val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
                    requestHeaders += headers
                    val count = headers.single { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    check(count in 1..262_144)
                    val request = input.readNBytes(count)
                    check(request.size == count)
                    requestBodies += request.toString(Charsets.UTF_8)
                    check(requests.incrementAndGet() <= maxRequests) { "Unexpected replay/derived request" }
                    if(balanceFailure.compareAndSet(true,false)) {
                        val body="""{"error":{"message":"synthetic insufficient balance","code":"insufficient_balance"}}""".toByteArray()
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 402 Payment Required\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                        return@use
                    }
                    if (holdReasoningStream.compareAndSet(true, false)) {
                        check(Json.parseToJsonElement(requestBodies.last()).jsonObject["stream"] == JsonPrimitive(true))
                        // A real open SSE response: already-visible reasoning, no tools,
                        // no finish marker. Cancellation must keep the received content.
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            // Span the host's 100 ms visual/checkpoint cadence, as a real
                            // continuing reasoning response does, before holding it open.
                            listOf(PARTIAL_REASONING.dropLast(1), PARTIAL_REASONING.takeLast(1)).forEachIndexed { index, text ->
                                if (index > 0) Thread.sleep(250)
                                val delta = buildJsonObject {
                                    put("role", "assistant"); put("reasoning_content", text)
                                }
                                write(("data: " + """{"id":"partial","object":"chat.completion.chunk","choices":[{"index":0,"delta":$delta,"finish_reason":null}]}""" + "\n\n").toByteArray())
                                flush()
                            }
                        }
                        check(releaseReasoningStream.await(15, TimeUnit.SECONDS))
                        return@use
                    }
                    if (holdFirstRequest.compareAndSet(true, false)) {
                        firstRequestHeld.complete(Unit)
                        check(releaseFirstRequest.await(15, TimeUnit.SECONDS)) { "Synthetic request gate timed out" }
                        // The test cancels its first real HTTP request, not a fabricated Job.
                        // Closing without an answer must not replay that input or block correction.
                        return@use
                    }
                    if (holdBoundaryRequest.compareAndSet(true, false)) {
                        boundaryRequestHeld.complete(Unit)
                        check(releaseBoundaryRequest.await(15, TimeUnit.SECONDS))
                    }
                    val customMessage = customMessages.poll()
                    val doRecall = recallNext.compareAndSet(true, false)
                    val responseMessage = customMessage ?: if (doRecall)
                        """{"role":"assistant","content":null,"tool_calls":[{"id":"recall-one","type":"function","function":{"name":"orbis_recall_message","arguments":"{\"recent_index\":1}"}}]}"""
                        else """{"role":"assistant","content":"$ANSWER"}"""
                    val finishReason = if (doRecall || customMessage?.contains("tool_calls") == true) "tool_calls" else "stop"
                    val body = """{"id":"synthetic","object":"chat.completion","created":1,"model":"synthetic-service-voice","choices":[{"index":0,"message":$responseMessage,"finish_reason":"$finishReason"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".toByteArray()
                    val streaming = (Json.parseToJsonElement(requestBodies.last()).jsonObject["stream"] as? JsonPrimitive)?.booleanOrNull == true
                    val payload = if (streaming) {
                        val delta = Json.parseToJsonElement(responseMessage).jsonObject.let { m -> JsonObject(m.mapValues { (k, v) ->
                            if (k == "tool_calls") JsonArray(v.jsonArray.mapIndexed { index, call ->
                                JsonObject(call.jsonObject + ("index" to JsonPrimitive(index))) }) else v
                        }) }
                        ("data: " + """{"id":"synthetic","object":"chat.completion.chunk","choices":[{"index":0,"delta":$delta,"finish_reason":"$finishReason"}]}""" + "\n\ndata: [DONE]\n\n").toByteArray()
                    } else body
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if (streaming) "text/event-stream" else "application/json"}\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload); flush()
                    }
                }
            } catch (error: Throwable) {
                if (!(stopped.get() && error is SocketException)) failure.set(error)
            }
        }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic loopback failed", it) } }
        override fun close() { stopped.set(true); releaseBoundaryRequest.countDown(); releaseFirstRequest.countDown(); releaseReasoningStream.countDown(); socket.close(); worker.join(9_000); check(!worker.isAlive); assertHealthy() }
    }

    private companion object {
        const val ANSWER = "synthetic fully committed reply"
        const val PARTIAL_REASONING = "synthetic thinking thinking thinking without tools"
        const val GATEWAY_HOLD_FILE = "orbis-gateway-recovery-holds-v1.json"
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
