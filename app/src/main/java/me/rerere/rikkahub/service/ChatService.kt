package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import android.util.AtomicFile
import me.rerere.rikkahub.data.ai.approval.ToolApprovalStore
import me.rerere.rikkahub.data.ai.approval.matchesHostApproval
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.toLocalDateTime
import me.rerere.rikkahub.data.model.reanchorDeletedToolRecords
import me.rerere.rikkahub.data.model.withCommittedVoiceNotePlayed
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.orbis.OrbisEventStore
import me.rerere.rikkahub.data.orbis.voice.*
import me.rerere.rikkahub.data.orbis.consultation.*
import me.rerere.rikkahub.data.model.ConsultationConversationBinding
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import me.rerere.rikkahub.data.orbis.orbisEventCompletionState
import me.rerere.rikkahub.data.ai.transformers.OrbisEventTransformer
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationTerminalEvidence
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.GenerationDurabilityException
import me.rerere.rikkahub.data.ai.OutputGenerationScope
import me.rerere.rikkahub.data.ai.frozenOutputPrefixCount
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.model.mapPreservingIdentity
import me.rerere.rikkahub.data.ai.compaction.AppliedCompaction
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTokens
import me.rerere.rikkahub.data.ai.compaction.estimateCompactionTextTokens
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import me.rerere.rikkahub.data.model.OrbisCompactionEvent
import me.rerere.rikkahub.data.model.OrbisCompactionMetadata
import me.rerere.rikkahub.data.model.requireCompactionEpoch
import me.rerere.rikkahub.data.repository.OrbisCompactionRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.tools.refreshOrbisHelpForTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.withManualTitle
import me.rerere.rikkahub.data.model.withGeneratedTitleIfUnchanged
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.requireCurrentQuote
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal const val ORBIS_COMPRESSION_UNAVAILABLE_MESSAGE =
    "可撤销上下文整理尚未接通，本次未修改原文。"

internal fun requireLegacyCompressionAvailable(isOrbisDebug: Boolean) {
    check(!isOrbisDebug) { ORBIS_COMPRESSION_UNAVAILABLE_MESSAGE }
}

/** An empty loading/error placeholder must never overwrite an existing history. */
internal fun requireWholeConversationSaveReady(exists: Boolean, initialized: Boolean) {
    check(!exists || initialized) { "对话尚未完整加载，本次未保存。请返回后重试。" }
}

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/** A keep_recent=0 compaction may archive the real trigger; do not mislabel its own reply unknown. */
internal fun compactionAwareEventCompletionMessages(
    current: Conversation,
    turnStart: Conversation,
    eventId: String,
): List<UIMessage> {
    if (current.compactionEpoch <= turnStart.compactionEpoch ||
        current.currentMessages.any { it.orbisEvent?.recordId == eventId }) return current.currentMessages
    val realTrigger = turnStart.currentMessages.firstOrNull { it.orbisEvent?.recordId == eventId }
        ?: return current.currentMessages
    // Used only for completion accounting; neither the saved page nor outgoing input is changed.
    return listOf(realTrigger) + current.currentMessages
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    orbisPrompt = source.orbisPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

internal data class VoiceToolContinuationBinding(val callId: String?, val kind: String?,
    val allowAmbientCallBinding: Boolean = false)

/** Resume the tool's own message, never whichever call happens to be active at approval time. */
internal fun voiceToolContinuationBinding(messages: List<UIMessage>, toolCallId: String): VoiceToolContinuationBinding? =
    messages.firstOrNull { message -> message.getTools().any { it.toolCallId == toolCallId && it.isPending } }
        ?.let { VoiceToolContinuationBinding(it.orbisVoiceCallId, it.orbisVoiceCallKind) }

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        OrbisEventTransformer,
        me.rerere.rikkahub.data.ai.transformers.OrbisVoiceCallTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val compactionRepository: OrbisCompactionRepository,
) {
    private val generationJournal by lazy {
        GenerationCheckpointJournal(AndroidGenerationCheckpointStore.create(context.noBackupFilesDir))
    }
    val voiceCalls = OrbisVoiceCallRepository(context)
    private val consultationStore by lazy { ConsultationRuntimeStore.open(context) }
    private val consultationIngressMutex = Mutex()
    private data class ConsultationActiveJob(val turn: ConsultationConversationTurn, val job: Job)
    private val consultationJobs = ConcurrentHashMap<Uuid, ConsultationActiveJob>()
    private val voiceArchiveJobs = ConcurrentHashMap<String, Job>()
    private val voiceIngressMutex = Mutex()
    private class VoiceReplyBinding(val conversationId: Uuid, val callId: String, val messageId: Uuid) {
        @Volatile var cancelled = false
    }
    private val voiceReplyBindings = ConcurrentHashMap<Deferred<String?>, VoiceReplyBinding>()
    private val voiceGenerationJobs = ConcurrentHashMap<Uuid, Job>()
    private val voiceRecovery = appScope.launch {
        runCatching {
            voiceCalls.recoverInterrupted().forEach { record ->
                val id = Uuid.parse(record.conversationId)
                // Read source only: no session initialization, queue, tool, model or journal acknowledgement.
                val stored = conversationRepo.getConversationById(id) ?: return@forEach
                if (stored.assistantId.toString() != record.assistantId) return@forEach
                val recovered = runCatching { withContext(Dispatchers.IO) { generationJournal.recover(stored)?.conversation } }
                    .getOrNull() ?: stored
                voiceCalls.update(record.id) { captureVoiceCallSource(it, recovered, finished = true,
                    capturedAtMs = System.currentTimeMillis(), authoritativeLiveSnapshot = false) }
            }
        }.onFailure { Log.w(TAG, "Voice call recovery failed; original history retained", it) }
    }

    suspend fun prepareVoiceCall(conversationId: Uuid): OrbisVoiceCallRecord {
        voiceRecovery.join()
        initializeConversation(conversationId, selectAssistant = false)
        val c = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = checkNotNull(settings.getAssistantById(c.assistantId)) { "当前 AI 不存在" }
        return voiceCalls.create(OrbisVoiceCallRecord(
            id = Uuid.random().toString(), conversationId = conversationId.toString(),
            assistantId = c.assistantId.toString(), startedAtMs = System.currentTimeMillis(),
            modelId = (assistant.chatModelId ?: settings.chatModelId)?.toString(),
        ))
    }

    suspend fun connectVoiceCall(callId: String, connectedAt: Long, onPersisted: suspend () -> Unit = {}) {
        val record = voiceCalls.update(callId) { it.copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = connectedAt) }
        // Incoming-call tool execution owns this chat's queue until its result is returned.
        // Acknowledge the durable connected archive BEFORE waiting for the queued begin marker.
        onPersisted()
        // Persist the start event before the first dictated utterance; no extra greeting request.
        enqueueCallMessage(Uuid.parse(record.conversationId), callId, OrbisVoiceCallProtocol.begin(callId),
            "begin", answer = false).await()
    }

    fun enqueueCallMessage(conversationId: Uuid, callId: String, text: String,
        kind: String = "turn", answer: Boolean = true, messageId: Uuid = Uuid.random(),
        shouldEnqueue: () -> Boolean = { true },
        orbisUserMessageTime: me.rerere.ai.ui.OrbisUserMessageTime? = null): Deferred<String?> {
        require(kind !in setOf("archive", "restore")) { "归档只能使用独立记录整理入口，不能进入聊天队列。" }
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            if (!shouldEnqueue()) { reply.complete(null); return reply }
            check(!session.messageQueue.state.value.paused) {
                "聊天队列已暂停，通话原文仍保留；恢复队列后可重试。"
            }
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(if (kind == "turn") voiceTurnForModel(callId, text) else text)),
                answer = answer, reply = reply, id = messageId, voiceCallId = callId, voiceCallKind = kind,
                orbisUserMessageTime = if (kind == "turn") orbisUserMessageTime else null)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    /** Accepted speech survives process death even if it has not yet left the chat queue. */
    fun enqueueVoiceCallUtterance(conversationId: Uuid, callId: String, text: String,
        originalTranscript: String? = null): Deferred<String?> {
        val binding = VoiceReplyBinding(conversationId, callId, Uuid.random())
        val acceptedAt = System.currentTimeMillis()
        val capturedTime = captureHumanMessageTime(getOrCreateSession(conversationId), acceptedAt)
        val observer = appScope.async(start = CoroutineStart.LAZY) {
            val queued = voiceIngressMutex.withLock {
                voiceCalls.update(callId) { it.copy(transcript = it.transcript +
                    OrbisVoiceTranscriptEntry(binding.messageId.toString(), "USER", text, acceptedAt, binding.messageId.toString(),
                        originalTranscript = originalTranscript?.takeIf { it != text })) }
                if (binding.cancelled) return@async null
                enqueueCallMessage(conversationId, callId, text, messageId = binding.messageId,
                    shouldEnqueue = { !binding.cancelled }, orbisUserMessageTime = capturedTime)
            }
            queued.await()
        }
        voiceReplyBindings[observer] = binding
        observer.invokeOnCompletion { voiceReplyBindings.remove(observer, binding) }
        observer.start()
        return observer
    }

    /** Accept the opening durably, then return without waiting for the model or the current tool. */
    suspend fun enqueueIncomingOpening(conversationId: Uuid, callId: String, reason: String): Deferred<String?>? {
        require(reason.length <= 2000) { "来电原因过长。" }
        val conversation = checkNotNull(conversationRepo.getConversationById(conversationId)) { "来电窗口已不存在。" }
        val binding = VoiceReplyBinding(conversationId, callId, Uuid.random())
        val claimed = voiceCalls.claimIncomingOpening(callId, conversationId.toString(),
            conversation.assistantId.toString(), binding.messageId.toString(), reason) ?: return null
        val observer = appScope.async(start = CoroutineStart.LAZY) {
            try {
                val queued = voiceIngressMutex.withLock {
                    if (binding.cancelled) {
                        voiceCalls.update(callId) { it.copy(openingStatus = OrbisVoiceOpeningStatus.CANCELLED) }
                        return@async null
                    }
                    val live = checkNotNull(voiceCalls.get(callId))
                    check(live.status == OrbisVoiceCallStatus.ACTIVE && live.openingRequestId == claimed.openingRequestId &&
                        live.assistantId == conversation.assistantId.toString() && live.conversationId == conversationId.toString()) {
                        "这通来电已结束或所属窗口变更，未发送开场。"
                    }
                    enqueueCallMessage(conversationId, callId, incomingVoiceOpeningForModel(callId, reason),
                        kind = "opening", messageId = binding.messageId, shouldEnqueue = { !binding.cancelled })
                }
                val reply = queued.await()
                voiceCalls.update(callId) { old ->
                    if (old.openingStatus in setOf(OrbisVoiceOpeningStatus.CLAIMED, OrbisVoiceOpeningStatus.GENERATING))
                        old.copy(openingStatus = if (reply == null || binding.cancelled) OrbisVoiceOpeningStatus.CANCELLED
                            else OrbisVoiceOpeningStatus.COMPLETED)
                    else old
                }
                reply
            } catch (error: Exception) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    sessions[conversationId]?.messageQueue?.let { queue ->
                        queue.state.value.messages.firstOrNull { it.id == binding.messageId &&
                            it.voiceCallId == callId && it.voiceCallKind == "opening" }?.let { queue.remove(it.id) }
                    }
                    runCatching { voiceCalls.update(callId) { old ->
                        if (old.openingStatus in setOf(OrbisVoiceOpeningStatus.CLAIMED, OrbisVoiceOpeningStatus.GENERATING))
                            old.copy(openingStatus = if (binding.cancelled) OrbisVoiceOpeningStatus.CANCELLED else OrbisVoiceOpeningStatus.UNKNOWN,
                                openingError = "开场请求未完成或结果未确认；不会自动补发。") else old
                    } }
                }
                throw error
            }
        }
        voiceReplyBindings[observer] = binding
        observer.invokeOnCompletion { voiceReplyBindings.remove(observer, binding) }
        observer.start()
        return observer
    }

    /** Never cancel another call, a tool-approval continuation, an archive or ordinary text work. */
    fun cancelVoiceCallReply(conversationId: Uuid, callId: String, reply: Deferred<String?>) {
        val binding = voiceReplyBindings[reply]?.takeIf { it.conversationId == conversationId && it.callId == callId } ?: return
        binding.cancelled = true
        val session = sessions[conversationId] ?: return
        synchronized(session) {
            val exactJob = voiceGenerationJobs[binding.messageId]
            if (exactJob != null && session.getJob() === exactJob) {
                exactJob.cancel(VoiceBargeInCancellation(binding.messageId, callId))
            } else {
                session.messageQueue.state.value.messages.firstOrNull {
                    it.id == binding.messageId && it.voiceCallId == callId && it.voiceCallKind in setOf("turn", "opening")
                }?.let { session.messageQueue.remove(it.id) }
            }
        }
    }

    suspend fun endVoiceCall(callId: String, end: OrbisVoiceCallEnd) {
        val record = voiceCalls.update(callId) { old -> old.copy(
            status = if (end.reason in setOf(OrbisVoiceCallEndReason.USER, OrbisVoiceCallEndReason.AI_END))
                OrbisVoiceCallStatus.ENDED else OrbisVoiceCallStatus.INTERRUPTED,
            endedAtMs = end.endedAtMillis.coerceAtLeast(old.connectedAtMs ?: old.startedAtMs),
            durationMs = end.durationMillis?.takeIf { old.connectedAtMs != null },
            endReason = end.reason.name, endReasonText = end.endReasonText, endError = end.error,
        ) }
        if (record.connectedAtMs == null) {
            voiceCalls.update(callId) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED,
                archiveError = "通话未接通，没有请求 AI 生成摘要。") }
            return
        }
        voiceIngressMutex.withLock { retryVoiceCallArchive(record.id) }
    }

    /** Runs in the application scope; neither leaving the page nor hanging up cancels archiving. */
    fun retryVoiceCallArchive(callId: String) = launchIndependentVoiceArchive(callId)

    /** Only the explicitly confirmed history-page action uses this independent, no-tools request. */
    fun retryVoiceCallArchiveIsolated(callId: String) = launchIndependentVoiceArchive(callId)

    private fun launchIndependentVoiceArchive(callId: String) {
        synchronized(voiceArchiveJobs) {
            if (voiceArchiveJobs[callId]?.isCompleted == false) return
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val record = checkNotNull(voiceCalls.get(callId))
                    check(record.status == OrbisVoiceCallStatus.ENDED || record.status == OrbisVoiceCallStatus.INTERRUPTED)
                    val conversationId = Uuid.parse(record.conversationId)
                    // Read only already durable history; never initialize or recover the old chat session.
                    val savedConversation = checkNotNull(conversationRepo.getConversationById(conversationId))
                    val captured = voiceCalls.update(callId) { captureVoiceCallSource(it, savedConversation,
                        finished = true, capturedAtMs = System.currentTimeMillis(), authoritativeLiveSnapshot = false) }
                    archiveVoiceCallWithoutResumingQueue(captured, savedConversation)
                } catch (e: Exception) {
                  withContext(kotlinx.coroutines.NonCancellable) {
                    val code = (e as? VoiceArchiveFailure)?.safeCode ?: if (e is CancellationException)
                        "archive_interrupted" else "archive_persistence_failed"
                    runCatching { voiceCalls.update(callId) { it.copy(
                        archiveStatus = if (it.archiveStatus == OrbisVoiceArchiveStatus.READY) it.archiveStatus
                            else if (code == "model_not_configured") OrbisVoiceArchiveStatus.PENDING else OrbisVoiceArchiveStatus.FAILED,
                        archiveFailureCode = code, archiveError = voiceArchiveFailureMessage(code)) } }
                  }
                  if (e is CancellationException) throw e
                }
            }
            voiceArchiveJobs[callId] = job
            job.start()
        }
    }

    private suspend fun archiveVoiceCallWithoutResumingQueue(record: OrbisVoiceCallRecord, savedConversation: Conversation) {
        if (record.archiveStatus != OrbisVoiceArchiveStatus.READY) {
            val digest = voiceArchiveSourceDigest(record)
            val result = runIndependentVoiceArchive(record, savedConversation, settingsStore.settingsFlow.first { !it.init },
                beforeRequest = { modelId -> voiceCalls.update(record.id) {
                    if (voiceArchiveSourceDigest(it) != digest) throw VoiceArchiveFailure("archive_source_changed")
                    it.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING, archiveError = null,
                        archiveFailureCode = null, archiveRequestCount = Math.addExact(it.archiveRequestCount, 1),
                        archiveLastModelId = modelId.toString())
                } }, request = { request ->
                    // No GenerationLoop, checkpoint recovery, tools, workspace, persona or queue.resume.
                    kotlinx.coroutines.withTimeoutOrNull(180_000L) {
                        providerManager.getProviderByType(request.provider).generateText(
                            providerSetting = request.provider, messages = request.messages, params = request.params)
                    } ?: throw VoiceArchiveFailure("archive_timeout")
                })
            voiceCalls.update(record.id) {
                if (voiceArchiveSourceDigest(it) != digest) throw VoiceArchiveFailure("archive_source_changed")
                it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
                    summary = result.archive.summary, modelTranscript = result.archive.transcript,
                    archiveError = null, archiveFailureCode = null)
            }
        }
        // The chat list folds by call ownership and reads this archive directly. Summary success
        // must not remove raw nodes, rewrite model history, resume a queue or depend on a page commit.
    }

    private suspend fun snapshotVoiceCall(callId: String, conversationId: Uuid,
        finished: Boolean = true): OrbisVoiceCallRecord {
        val conversation = getConversationFlow(conversationId).value
        return voiceCalls.update(callId) { captureVoiceCallSource(it, conversation, finished, System.currentTimeMillis()) }
    }

    private suspend fun collapseArchivedVoiceCall(record: OrbisVoiceCallRecord, session: ConversationSession) {
        check(record.conversationId == session.id.toString() &&
            record.assistantId == session.state.value.assistantId.toString()) {
            "通话所属 AI 已变更，原文与摘要留在原记录库，未修改其他 AI 的聊天。"
        }
        val nodes = JsonInstant.decodeFromString<List<MessageNode>>(checkNotNull(record.sourceNodesJson))
        val summary = UIMessage.assistant(OrbisVoiceCallProtocol.summary(record)).copy(
            orbisVoiceCallId = record.id, orbisVoiceCallKind = "summary",
        )
        withContext(kotlinx.coroutines.NonCancellable) {
            val next = conversationRepo.commitVoiceCallArchive(session.state.value, record.id, nodes, summary)
            session.state.value = next
            session.isInitialized = true
            voiceCalls.update(record.id) { it.copy(chatCommitted = true, archiveError = null) }
        }
    }

    val orbisEvents = OrbisEventStore(context)
    private val nativeSentinels by lazy { me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels.open(context) }

    suspend fun acceptOrbisTouch(eventId: String, occurredAtMs: Long) = nativeSentinels.acceptTouch(eventId, occurredAtMs)
    fun orbisTouchReceipt(eventId: String) = nativeSentinels.touchReceipt(eventId)
    fun orbisTouchStatus(): Pair<Boolean, Int> = nativeSentinels.rules.refresh().let { state ->
        state.enabled to state.rules.count { it.enabled && it.type == me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinelType.TOUCH }
    }
    private val orbisEventDispatchMutex = Mutex()
    private val orbisEventsAtStartup = orbisEvents.inbox.state.value.events.toList()
    private val queuePauseStore by lazy { openPauseStore(OrbisQueuePauseStore.FILE_NAME) }
    // Separate hard holds: an ordinary human stop / provider failure must not disable future wakes.
    private val automaticWakeHoldStore by lazy { openPauseStore("orbis-automatic-wake-holds-v1.json") }
    // PAUSED here means "legacy migration complete", never a live dispatch/pause decision.
    // Separate from active holds so a durable human ACK cannot be undone by old host markers.
    private val automaticWakeMigrationStore by lazy { openPauseStore("orbis-automatic-wake-legacy-migrations-v1.json") }
    private fun openPauseStore(name: String): OrbisQueuePauseStore {
        val file = AtomicFile(File(context.noBackupFilesDir, name))
        return OrbisQueuePauseStore(
            read = {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                    file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                else null
            },
            write = { text ->
                val output = file.startWrite()
                try {
                    output.write(text.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (failure: Throwable) {
                    file.failWrite(output)
                    throw failure
                }
            },
        )
    }
    @Volatile private var queuePauseRecoveryReady = false

    private fun automaticWakeAllowed(session: ConversationSession): Boolean =
        !session.generationRecoveryBlocked && ensureQueuePauseRecovery() &&
            queuePauseStore.status(session.id.toString()) != QueuePauseStatus.UNAVAILABLE &&
            automaticWakeHoldStore.status(session.id.toString()) == QueuePauseStatus.UNPAUSED

    private fun holdAutomaticWakes(session: ConversationSession, reason: String): Boolean =
        try {
            automaticWakeHoldStore.pause(session.id.toString(), reason)
            true
        } catch (_: Exception) {
            session.generationRecoveryBlocked = true
            false
        }

    private fun recordAutomaticReceiptSafely(session: ConversationSession, persistReceipt: () -> Unit) {
        if (!persistAutomaticReceiptOrHold(
                persistReceipt = persistReceipt,
                persistHold = { holdAutomaticWakes(session, "event_receipt_not_saved") },
                blockOnHoldFailure = { session.generationRecoveryBlocked = true },
            )) {
            addError(IllegalStateException("自动唤醒的执行回执未能可靠保存，已暂停自动处理；请先核对结果，不会自动重试。"), session.id)
        }
    }

    private fun acknowledgeAutomaticHold(session: ConversationSession) {
        // Called only by an explicit human action, never a restored event / automatic callback.
        if (session.generationRecoveryBlocked) return
        try {
            automaticWakeHoldStore.resume(session.id.toString())
        } catch (_: Exception) {
            addError(IllegalStateException("自动唤醒的保护状态未能保存，唤醒仍暂停，原记录保留。"), session.id)
        }
    }

    /** Run before constructing any queue, including an event arriving during startup. */
    private fun ensureQueuePauseRecovery(): Boolean = synchronized(queuePauseStore) {
        if (!queuePauseRecoveryReady) {
            try {
                queuePauseStore.migrateLegacy(orbisEventsAtStartup)
                orbisEventsAtStartup.filter { it.state == "generating" }
                    .map { it.conversationId }.distinct().forEach {
                        queuePauseStore.pause(it, "interrupted_generation")
                    }
                queuePauseRecoveryReady = true
            } catch (failure: Exception) {
                Log.w(TAG, "Queue pause recovery unavailable (${failure.javaClass.simpleName}); no automatic dispatch")
            }
        }
        queuePauseRecoveryReady
    }

    private fun persistQueuePause(conversationId: Uuid, paused: Boolean): Boolean = try {
        check(ensureQueuePauseRecovery()) { "queue_pause_recovery_unavailable" }
        if (paused) queuePauseStore.pause(conversationId.toString())
        else queuePauseStore.resume(conversationId.toString())
        true
    } catch (failure: Exception) {
        addError(IllegalStateException("队列暂停状态未能保存，已保留暂停；请检查手机存储后再继续发送。"), conversationId)
        false
    }

    /** Explicit binding; never follow another AI merely because it is visible in the UI. */
    suspend fun bindOrbisEvents(sources: Set<String>, binding: OrbisEventBinding) = orbisEventDispatchMutex.withLock {
        val target = conversationRepo.getConversationById(Uuid.parse(binding.conversationId))
        require(target != null && target.assistantId.toString() == binding.assistantId) { "event_target_invalid" }
        require(settingsStore.settingsFlow.first { !it.init }.assistants.any { it.id.toString() == binding.assistantId }) { "event_assistant_missing" }
        withContext(Dispatchers.IO) { orbisEvents.inbox.bind(sources, binding) }
    }

    suspend fun acceptOrbisEvent(input: OrbisIncomingEvent, expectedSentinelGeneration: Long? = null): Pair<OrbisInboxEvent, Boolean> = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
      orbisEventDispatchMutex.withLock {
        val binding = orbisEvents.inbox.binding(input.source) ?: error("event_source_not_bound")
        val target = conversationRepo.getConversationById(Uuid.parse(binding.conversationId))
        require(target != null && target.assistantId.toString() == binding.assistantId) { "event_target_invalid" }
        require(settingsStore.settingsFlow.first { !it.init }.assistants.any { it.id.toString() == binding.assistantId }) { "event_assistant_missing" }
        val accepted = withContext(Dispatchers.IO) { orbisEvents.inbox.accept(input, binding, System.currentTimeMillis(),
            expectedSentinelGeneration ?: nativeSentinels.rules.refresh().masterGeneration) }
        if (!accepted.second && accepted.first.state == "accepted") {
            try { queueOrbisEvent(accepted.first) }
            catch (error: Exception) {
                // Durable acceptance is not undone by a queue failure. Return its receipt.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    orbisEvents.inbox.mark(accepted.first.id, "unknown", "accepted_queue_unavailable")
                }
                if (error is CancellationException) throw error
            }
        }
        (orbisEvents.inbox.get(accepted.first.id) ?: accepted.first) to accepted.second
      }
    }

    /** Only safely undispatched receipts resume; uncertain model work never auto-replays after a crash. */
    suspend fun restoreOrbisEvents() = withContext(Dispatchers.Main.immediate) {
      orbisEventDispatchMutex.withLock {
        settingsStore.settingsFlow.first { !it.init }
        orbisEventsAtStartup.forEach { event ->
            if (event.state == "generating") {
                withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "unknown", "interrupted_generation_no_auto_retry") }
            } else if (event.state in setOf("accepted", "queued")) {
                runCatching { queueOrbisEvent(event) }.onFailure {
                    if (it is CancellationException) throw it
                    withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "target_invalid", "restore_target_unavailable") }
                }
            }
        }
      }
    }

    private suspend fun queueOrbisEvent(event: OrbisInboxEvent) {
        if (orbisEvents.inbox.get(event.id)?.state !in setOf("accepted", "queued")) return
        if (!nativeSentinels.mayDeliver(event)) {
            withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "suppressed", "sentinel_paused_no_replay") }
            return
        }
        val id = Uuid.parse(event.conversationId)
        val target = conversationRepo.getConversationById(id)
        if (!orbisEvents.inbox.targetStillMatches(event) || target?.assistantId.toString() != event.assistantId) {
            withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "target_invalid", "target_changed_before_queue") }
            return
        }
        initializeConversation(id, selectAssistant = false)
        val session = getOrCreateSession(id)
        val messageId = Uuid.parse(event.id)
        if (session.state.value.messageNodes.any { node -> node.messages.any { it.id == messageId } }) {
            withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "displayed", "recovered_message_no_auto_reply") }
            return
        }
        if (session.submittingMessage?.id == messageId) return
        withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "queued") }
        synchronized(session) {
            val eventParts = buildList {
                add(UIMessagePart.Text(event.text))
                event.localImage?.let { name ->
                    val image = nativeSentinels.imageFile(name)
                    check(image.isFile) { "event_image_missing" }
                    add(UIMessagePart.Image(android.net.Uri.fromFile(image).toString()))
                }
            }
            session.automaticWakeQueue.enqueue(eventParts, event.wake,
                id = messageId, eventId = event.id)
            dispatchNextQueuedMessage(id)
        }
    }
    /** A human stop applies to legacy and native events, never to a human-authored conversation. */
    suspend fun pauseOrbisAutomaticWakes(pausedGeneration: Long) = withContext(Dispatchers.Main.immediate) {
        orbisEventDispatchMutex.withLock {
            fun affected(event: OrbisInboxEvent) =
                me.rerere.rikkahub.data.orbis.sentinel.sentinelBelongsToPause(event, pausedGeneration)
            sessions.forEach { (conversationId, session) ->
                session.automaticWakeQueue.pending.filter {
                    it.orbisEventId?.let(orbisEvents.inbox::get)?.let(::affected) == true
                }.forEach { queued ->
                    session.automaticWakeQueue.remove(queued.id)
                    withContext(Dispatchers.IO) { orbisEvents.inbox.mark(checkNotNull(queued.orbisEventId), "suppressed", "human_master_paused") }
                }
                val active = session.state.value.currentMessages.lastOrNull { it.role == MessageRole.USER }?.orbisEvent
                    ?.recordId?.let { orbisEvents.inbox.get(it) }
                if (active?.state == "generating" && affected(active)) {
                    val humanWasPaused = session.messageQueue.state.value.paused
                    stopGeneration(conversationId)
                    withContext(Dispatchers.IO) { orbisEvents.inbox.mark(active.id, "suppressed", "human_master_stopped_generation") }
                    // The master controls automatic wakeups, not the human's pending messages.
                    if (!humanWasPaused) session.messageQueue.resume()
                    dispatchNextQueuedMessage(conversationId)
                }
            }
            orbisEvents.inbox.state.value.events.filter { it.state in setOf("accepted", "queued") && affected(it) }.forEach {
                withContext(Dispatchers.IO) { orbisEvents.inbox.mark(it.id, "suppressed", "human_master_paused") }
            }
        }
    }
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    // Lock order: folder mutation, then session prompt edit. A manual archive inherits its
    // source folder, so folder deletion/moves cannot interleave with archive publication.
    private val folderMutationMutex = Mutex()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            val pauseStorageReady = ensureQueuePauseRecovery()
            val pauseStatus = if (pauseStorageReady) queuePauseStore.status(id.toString()) else QueuePauseStatus.UNAVAILABLE
            if (pauseStatus == QueuePauseStatus.UNAVAILABLE ||
                automaticWakeHoldStore.status(id.toString()) == QueuePauseStatus.UNAVAILABLE) {
                addError(IllegalStateException("队列保护记录暂时不可用，自动消息已暂停，原记录未被清空。"), id)
            }
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { id, cause ->
                    val session = sessions[id]
                    if (cause != null && !cause.isSavedVoiceInterruption()) session?.messageQueue?.pause()
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
                    }
                    appScope.launch { dispatchNextQueuedMessage(id) }
                },
                messageQueue = MessageQueue(
                    initiallyPaused = pauseStatus != QueuePauseStatus.UNPAUSED,
                    onPauseChanged = { paused -> persistQueuePause(id, paused) },
                ),
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) {
            // A new send may queue while a tool-record transaction is committing. Read its
            // conversation only after that commit, never an already-deleted tool snapshot.
            getOrCreateSession(conversationId).orbisPromptEditMutex.withLock { Unit }
            block()
        }

        return appScope.launch(start = CoroutineStart.LAZY) {
            getOrCreateSession(conversationId).orbisPromptEditMutex.withLock { Unit }
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid, selectAssistant: Boolean = true) {
        val session = getOrCreateSession(conversationId)
        session.orbisPromptEditMutex.withLock {
            // Read after obtaining the lock. A delayed initializer cannot replace a newer commit.
            if (session.isInitialized) {
                if (selectAssistant) settingsStore.updateAssistant(session.state.value.assistantId)
                return@withLock
            }
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                val recovered = recoverGenerationCheckpoint(session, conversation)
                updateConversation(conversationId, recovered, restoreCommittedPrompt = true)
                session.isInitialized = true
                if (selectAssistant) settingsStore.updateAssistant(conversation.assistantId)
            } else {
                check(selectAssistant) { "event_target_missing" }
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                val newConversation = Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(assistant.presetMessages)
                updateConversation(conversationId, newConversation, restoreCommittedPrompt = true)
                session.isInitialized = true
            }
        }
    }

    /** Caller holds the session edit mutex. Restore receipts only, never launch tools or a model. */
    private suspend fun recoverGenerationCheckpoint(
        session: ConversationSession,
        persisted: Conversation,
        voiceInterruption: VoiceBargeInCancellation? = null,
        preserveUnpausedQueueOnSafeFailure: Boolean = false,
    ): Conversation {
        // A public build may retain an internal build's Room rows; loading is not permission to recover them.
        if (persisted.isConsultation && !consultationFeature.enabled) return persisted
        return try {
            // Old releases may already have cleared the recovery journal. Preserve their
            // host-marked unknown tool outcome before treating a missing journal as clean.
            if (!session.isInitialized) {
                val migrationStatus = automaticWakeMigrationStore.status(persisted.id.toString())
                check(migrationStatus != QueuePauseStatus.UNAVAILABLE) { "automatic_legacy_migration_unavailable" }
                migrateLegacyAutomaticToolHoldOnce(
                    migrationDone = migrationStatus == QueuePauseStatus.PAUSED,
                    humanPaused = session.messageQueue.state.value.paused,
                    messages = persisted.currentMessages,
                    persistHold = { holdAutomaticWakes(session, "legacy_unknown_tool_result") },
                    persistMigrationDone = { automaticWakeMigrationStore.pause(persisted.id.toString(), "legacy_checked") },
                )
            }
            if (!withContext(Dispatchers.IO) { generationJournal.hasCheckpoint(persisted.id) }) {
                if (voiceInterruption != null && canAcknowledgeVoiceInterruption(true, session.messageQueue.state.value.paused,
                        session.generationRecoveryBlocked, emptySet()))
                    voiceInterruption.partialSafelySaved = true
                return persisted
            }
            if (voiceInterruption == null && !preserveUnpausedQueueOnSafeFailure) session.messageQueue.pause()
            val recovery = withContext(Dispatchers.IO) {
                generationJournal.discardSupersededByDurableEpoch(persisted)
                generationJournal.recover(persisted)
            }
            if (recovery == null) {
                session.generationRecoveryBlocked = false
                if (voiceInterruption != null && canAcknowledgeVoiceInterruption(true, session.messageQueue.state.value.paused,
                        session.generationRecoveryBlocked, emptySet())) voiceInterruption.partialSafelySaved = true
                return persisted
            }
            if (recovery.unknownToolIds.isNotEmpty()) {
                session.messageQueue.pause()
                // Never erase the only durable unknown-tool evidence until its independent
                // automatic safety hold is verified on disk (RAM uncertainty does not survive death).
                requireAutomaticRecoveryHold {
                    holdAutomaticWakes(session, "unknown_tool_result")
                }
            }
            if (recovery.changed) {
                conversationRepo.updateConversation(recovery.conversation,
                    requireExistingOwner = persisted.assistantId)
            }
            val stored = checkNotNull(conversationRepo.getConversationById(persisted.id))
            withContext(Dispatchers.IO) { generationJournal.clearAfterDurableCommit(recovery.handle, stored) }
            session.generationRecoveryBlocked = false
            if (voiceInterruption != null && canAcknowledgeVoiceInterruption(true, session.messageQueue.state.value.paused,
                    session.generationRecoveryBlocked, recovery.unknownToolIds)) {
                voiceInterruption.partialSafelySaved = true
                return stored
            }
            if (preserveUnpausedQueueOnSafeFailure && recovery.unknownToolIds.isEmpty()) return stored
            addError(IllegalStateException(if (recovery.unknownToolIds.isEmpty())
                "已恢复上次中断时保存的回复；旧生成和队列没有自动继续。"
            else "已恢复上次回复和已保存的工具结果；部分工具外部结果未知，请先核对，未自动重试。"), persisted.id)
            stored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            session.messageQueue.pause()
            session.generationRecoveryBlocked = true
            holdAutomaticWakes(session, "recovery_conflict")
            addError(IllegalStateException("上次回复恢复记录与当前聊天暂不能安全合并，已保留两份记录并暂停生成，请勿清除数据。"), persisted.id)
            Log.w(TAG, "Generation recovery refused (${failure.javaClass.simpleName}); private data withheld")
            persisted
        }
    }

    /** Called only after the previous generation has ended, before mutating its conversation. */
    private suspend fun settlePreviousGeneration(session: ConversationSession) {
        session.orbisPromptEditMutex.withLock {
            if (withContext(Dispatchers.IO) { generationJournal.hasCheckpoint(session.id) }) {
                val persisted = checkNotNull(conversationRepo.getConversationById(session.id))
                val recovered = recoverGenerationCheckpoint(session, persisted)
                updateConversation(session.id, recovered, restoreCommittedPrompt = true)
            }
            check(!session.generationRecoveryBlocked) {
                "回复恢复记录待核对，暂不开始新请求；原聊天与恢复文件均保留。"
            }
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let { removed ->
            cleanupQueuedAttachments(removed)
            removed.orbisEventId?.let { id -> appScope.launch(Dispatchers.IO) {
                orbisEvents.inbox.mark(id, "failed", "withdrawn_by_user")
            } }
        }
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + it.automaticWakeQueue.pending + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.let { session -> synchronized(session) {
            if (session.generationRecoveryBlocked || session.manualContextWriteInProgress ||
                session.state.value.currentMessages.any { it.getTools().any { tool -> !tool.isExecuted } }) {
                session.messageQueue.pause()
                addError(IllegalStateException("回复恢复或工具结果尚待核对，队列输入已保留，未继续。"), conversationId)
                return
            }
            session.messageQueue.resume()
            if (!session.messageQueue.state.value.paused) acknowledgeAutomaticHold(session)
        }
        }
        dispatchNextQueuedMessage(conversationId)
    }

    /** Claim/head replacement and local approvals share this lock. A pending continuation can
     * never accidentally write its result into a newer delivery's durable outbox. */
    internal suspend fun <T> withConsultationDispatchLock(block: suspend () -> T): T =
        consultationIngressMutex.withLock { block() }

    internal suspend fun recoverConsultationSession(sessionId: String) =
        withConsultationDispatchLock { recoverConsultationSessionLocked(sessionId) }

    /** Evidence recovery only, under dispatch lock. Normal journal recovery preserves unknown
     * tool receipts and clears its journal only after verified Room commit, never blindly. */
    internal suspend fun recoverConsultationSessionLocked(sessionId: String) = withContext(Dispatchers.Main.immediate) {
        consultationFeature.requireEnabled()
        val config = consultationStore.config()
        if (!config.enabled || config.subject.isBlank()) return@withContext
        val id = consultationConversationId(sessionId, config.subject)
        val head = consultationStore.conversationHead(id.toString()) ?: return@withContext
        val saved = checkNotNull(consultationStore.read(head))
        if (saved.state != "RUNNING" || saved.conversationTurn == null) return@withContext
        val turn = saved.conversationTurn
        check(turn.sessionId == sessionId && turn.subject == config.subject && turn.assistantId == config.assistantId &&
            turn.conversationId == id && turn.requestId == head)
        val existing = sessions[id]
        val hasJournal = withContext(Dispatchers.IO) { generationJournal.hasCheckpoint(id) }
        if (!canRecoverAbandonedConsultation(saved, consultationRuntimeProcessId,
                existing?.getJob() != null || consultationJobs[id]?.job?.isCompleted == false, hasJournal)) return@withContext
        val persisted = checkNotNull(conversationRepo.getConversationById(id)) { "consultation_recovery_conversation_missing" }
        check(persisted.assistantId.toString() == config.assistantId &&
            persisted.consultation == ConsultationConversationBinding(sessionId, config.subject))
        initializeConversation(id, selectAssistant = false)
        val session = getOrCreateSession(id)
        session.withRefSuspend {
            check(session.getJob() == null)
            settlePreviousGeneration(session)
            check(!session.generationRecoveryBlocked)
            persistConsultationOutcome(id, succeeded = false)
            val recovered = requireConsultationCheckpoint(id)
            consultationStore.write(recovered.copy(failure = "process_interrupted_evidence_recovered_no_automatic_retry",
                failedAtMillis = System.currentTimeMillis()))
            session.messageQueue.pause()
        }
    }

    private fun registerConsultationJob(turn: ConsultationConversationTurn, job: Job) {
        val active = ConsultationActiveJob(turn, job)
        consultationJobs[turn.conversationId] = active
        job.invokeOnCompletion { consultationJobs.remove(turn.conversationId, active) }
    }

    /** Does not acquire dispatch lock: an active normal generation owns it. Only this registered
     * hidden conversation/request can be cancelled, never the assistant's ordinary main chat. */
    internal suspend fun cancelConsultationExecution(sessionId: String, requestId: String? = null,
        phase: String = "ACTIVE") = withContext(Dispatchers.Main.immediate) {
        val targets = consultationJobs.values.filter { consultationJobMatchesEnd(it.turn, sessionId, requestId, phase) }
        for (active in targets) {
            val session = sessions[active.turn.conversationId] ?: continue
            synchronized(session) {
                if (consultationJobs[session.id] === active && session.getJob() === active.job &&
                    session.state.value.assistantId.toString() == active.turn.assistantId &&
                    session.state.value.consultation == ConsultationConversationBinding(active.turn.sessionId, active.turn.subject))
                    active.job.cancel(CancellationException("consultation_session_ended_no_automatic_retry"))
            }
            active.job.join()
        }
    }

    internal suspend fun cancelConsultationExecutionsOutsideInbox(activePhases: Set<Pair<String, String>>) {
        consultationJobs.values.toList().filter { (it.turn.sessionId to it.turn.phase) !in activePhases }.forEach {
            cancelConsultationExecution(it.turn.sessionId, it.turn.requestId, it.turn.phase)
        }
    }

    /** Called under withConsultationDispatchLock. Remote deliveries never select the main
     * assistant, resume human queues or grant tools. */
    internal suspend fun runConsultationTurn(turn: ConsultationConversationTurn,
        seedMessages: List<UIMessage>, input: String): ConsultationTurnResult {
        consultationFeature.requireEnabled()
        val result = CompletableDeferred<ConsultationTurnResult>()
        var ownedJob: Job? = null
        withContext(Dispatchers.Main.immediate) {
            run {
                val checkpoint = requireConsultationCheckpoint(turn.conversationId)
                check(checkpoint.requestId == turn.requestId && checkpoint.state == "RUNNING")
                validateConsultationTurn(checkpoint)
                if (conversationRepo.getConversationById(turn.conversationId) == null) {
                    check(checkpoint.allowCreateConversation) { "consultation_conversation_missing" }
                    val assistant = settingsStore.settingsFlow.value.getAssistantById(Uuid.parse(turn.assistantId))
                        ?: error("consultation_assistant_missing")
                    conversationRepo.insertConversation(Conversation.ofId(turn.conversationId, assistant.id).copy(
                        title = "咨询室 · ${turn.sessionId.takeLast(8)}",
                        messageNodes = (assistant.presetMessages + seedMessages).map { it.toMessageNode() },
                        consultation = ConsultationConversationBinding(turn.sessionId, turn.subject)))
                }
                initializeConversation(turn.conversationId, selectAssistant = false)
                val session = getOrCreateSession(turn.conversationId)
                check(session.state.value.assistantId.toString() == turn.assistantId &&
                    session.state.value.consultation == ConsultationConversationBinding(turn.sessionId, turn.subject))
                checkpoint.retryOfRequestId?.let { oldId ->
                    val old = checkNotNull(consultationStore.read(oldId))
                    check(canManuallyRetryConsultation(old) && consultationStore.hasRetryConsent(old))
                    // Only this hidden queue resumes after recorded consent for a NEW delivery.
                    session.messageQueue.resume()
                }
                checkpoint.closedTurnRequestId?.let { oldId ->
                    check(turn.phase == "ARCHIVING")
                    val old = checkNotNull(consultationStore.read(oldId))
                    check(canArchiveStoppedConsultation(old))
                    validateConsultationTurn(checkpoint, checkRoom = true)
                    check(session.getJob() == null && !session.generationRecoveryBlocked)
                    val current = session.state.value
                    val stoppedMessages = current.currentMessages.filter { it.id.toString() in old.outputMessageIds }
                    check(stoppedMessages.all { message -> message.getTools().all { it.isExecuted || it.isPending } }) {
                        "consultation_tool_outcome_unresolved"
                    }
                    val settled = stoppedMessages.associate { it.id to denyStoppedConsultationPendingTools(it) }
                    val closed = current.copy(messageNodes = current.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { message -> settled[message.id] ?: message })
                    })
                    saveConversation(turn.conversationId, closed)
                    consultationStore.write(old.copy(state = "STOPPED", messages = old.messages.map { settled[it.id] ?: it },
                        failure = "session_ended_archive_without_active_replay"))
                    // A NEW server-issued archive wake, never a retry of the stopped turn.
                    session.messageQueue.resume()
                }
                synchronized(session) {
                    check(session.getJob() == null && session.submittingMessage == null &&
                        session.messageQueue.state.value.messages.isEmpty() && session.automaticWakeQueue.pending.isEmpty()) {
                        "consultation_conversation_busy"
                    }
                    check(!session.generationRecoveryBlocked && !session.messageQueue.state.value.paused) {
                        "consultation_recovery_requires_confirmation"
                    }
                    check(session.state.value.currentMessages.none { it.getTools().any { tool -> tool.isPending } }) {
                        "consultation_tool_approval_pending"
                    }
                    val job = launchGenerationJob(turn.conversationId) {
                        try {
                            settlePreviousGeneration(session)
                            check(!session.generationRecoveryBlocked)
                            validateConsultationTurn(requireConsultationCheckpoint(turn.conversationId))
                            val before = session.state.value
                            check(before.messageNodes.none { node -> node.messages.any { it.id == turn.inputId } }) {
                                "consultation_input_already_committed_no_regeneration"
                            }
                            val baseline = before.messageNodes.flatMap { it.messages }.map { it.id.toString() }.toSet()
                            consultationStore.write(checkpoint.copy(baselineMessageIds = baseline))
                            val incoming = UIMessage.user(input).copy(id = turn.inputId, isSynthetic = true)
                            saveConversation(turn.conversationId, before.copy(messageNodes = before.messageNodes + incoming.toMessageNode()))
                            var terminalProof: Pair<String, GenerationTerminalEvidence>? = null
                            val succeeded = handleMessageComplete(turn.conversationId, realWake = true,
                                allowAmbientCallBinding = false,
                                onConsultationTerminal = { requestId, evidence -> terminalProof = requestId to evidence })
                            result.complete(persistConsultationOutcome(turn.conversationId, succeeded, terminalProof))
                        } catch (error: Throwable) {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                runCatching { persistConsultationOutcome(turn.conversationId, false) }
                            }
                            result.completeExceptionally(error)
                            if (error is CancellationException) throw error
                        }
                    }
                    ownedJob = job
                    job.invokeOnCompletion { cause ->
                        if (!result.isCompleted) result.completeExceptionally(cause ?: IllegalStateException("consultation_generation_not_completed"))
                    }
                    registerConsultationJob(turn, job)
                    session.setJob(job)
                }
            }
        }
        return try { result.await().also { ownedJob?.join() } }
        catch (cancelled: CancellationException) {
            ownedJob?.cancel(cancelled)
            withContext(kotlinx.coroutines.NonCancellable) { ownedJob?.join() }
            throw cancelled
        }
    }

    private suspend fun requireConsultationCheckpoint(conversationId: Uuid): ConsultationCheckpoint {
        val head = checkNotNull(consultationStore.conversationHead(conversationId.toString())) { "consultation_request_missing" }
        return checkNotNull(consultationStore.read(head)).also {
            check(it.conversationTurn?.conversationId == conversationId && it.requestId == head)
        }
    }

    /** Explicit device-owner UI only. The relay/operator never supplies an approval decision. */
    internal suspend fun consultationPendingApprovals(sessionId: String): List<ConsultationPendingApproval> {
        consultationFeature.requireEnabled()
        val config = consultationStore.config()
        check(config.enabled && config.assistantId.isNotBlank() && config.subject.isNotBlank())
        val conversationId = consultationConversationId(sessionId, config.subject)
        if (consultationStore.conversationHead(conversationId.toString()) == null) return emptyList()
        val checkpoint = requireConsultationCheckpoint(conversationId)
        val turn = checkNotNull(checkpoint.conversationTurn)
        check(turn.sessionId == sessionId && turn.assistantId == config.assistantId && turn.subject == config.subject)
        if (checkpoint.state != "WAITING_APPROVAL") return emptyList()
        validateConsultationTurn(checkpoint)
        initializeConversation(conversationId, selectAssistant = false)
        val conversation = getOrCreateSession(conversationId).state.value
        check(conversation.assistantId.toString() == config.assistantId &&
            conversation.consultation == ConsultationConversationBinding(sessionId, config.subject))
        return conversation.currentMessages.filter { it.id.toString() in checkpoint.outputMessageIds }
            .flatMap { it.getTools() }.filter { it.isPending }.map {
                ConsultationPendingApproval(conversationId.toString(), checkpoint.requestId,
                    it.toolCallId, it.toolName, it.input, it.approvalInputFingerprint)
            }
    }

    internal suspend fun confirmConsultationTool(sessionId: String, expected: ConsultationPendingApproval,
        approved: Boolean, answer: String? = null) = withContext(Dispatchers.Main.immediate) {
        consultationFeature.requireEnabled()
        consultationIngressMutex.withLock {
        val pending = consultationPendingApprovals(sessionId).firstOrNull { it == expected }
            ?: error("consultation_tool_approval_changed")
        val id = Uuid.parse(pending.conversationId)
        val session = getOrCreateSession(id)
        session.withRefSuspend {
            check(session.getJob() == null) { "consultation_conversation_busy" }
            val checkpoint = requireConsultationCheckpoint(id)
            validateConsultationTurn(checkpoint, checkRoom = true)
            consultationStore.write(consultationApprovalIntent(checkpoint, consultationRuntimeProcessId))
            var approvalJob: Job? = null
            try {
                handleToolApproval(id, pending.toolCallId, approved,
                    reason = if (approved) "" else "本机用户拒绝本次工具操作。", answer = answer, remember = false)
                approvalJob = session.getJob()
                approvalJob?.let { registerConsultationJob(checkNotNull(checkpoint.conversationTurn), it) }
                approvalJob?.join()
                settleConsultationApprovalIntent(id)
            } catch (error: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    approvalJob?.cancel()
                    approvalJob?.join()
                    settleConsultationApprovalIntent(id)
                }
                throw error
            }
            check(session.state.value.currentMessages.none { message ->
                message.getTools().any { it.toolCallId == pending.toolCallId && it.isPending }
            }) { "consultation_tool_approval_not_committed" }
        }
        }
    }

    /** Approval preflight may fail or a lazy job may be cancelled before entering its body.
     * Inspect committed tools, never treat that as permission to run the model again. */
    private suspend fun settleConsultationApprovalIntent(conversationId: Uuid) {
        val latest = requireConsultationCheckpoint(conversationId)
        if (latest.state != "RUNNING") return
        val persisted = checkNotNull(conversationRepo.getConversationById(conversationId))
        val stillPending = latest.toolInFlight == null && persisted.currentMessages
            .filter { it.id.toString() in latest.outputMessageIds }.any { message -> message.getTools().any { it.isPending } }
        persistConsultationOutcome(conversationId, succeeded = stillPending)
    }

    private suspend fun validateConsultationTurn(checkpoint: ConsultationCheckpoint, checkRoom: Boolean = false) {
        consultationFeature.requireEnabled()
        val turn = checkNotNull(checkpoint.conversationTurn)
        check(checkpoint.state in setOf("RUNNING", "WAITING_APPROVAL")) { "consultation_request_not_running" }
        check(System.currentTimeMillis() < turn.expiresAtMillis) { "consultation_delivery_expired" }
        val live = consultationStore.config()
        check(live.enabled && live.revision == turn.configRevision && live.assistantId == turn.assistantId && live.subject == turn.subject) {
            "consultation_binding_changed"
        }
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(Uuid.parse(turn.assistantId)) ?: error("consultation_assistant_missing")
        val model = settings.findModelById(assistant.chatModelId, settings.chatModelId) ?: error("consultation_model_missing")
        check(consultationBindingDigest(live, assistant, model, settings) == checkpoint.bindingDigest) { "consultation_binding_changed" }
        if (checkRoom) {
            val permission = ConsultationRuntimeClient().call(live, "sessions/${turn.sessionId}/capability",
                kotlinx.serialization.json.buildJsonObject { put("capability", kotlinx.serialization.json.JsonPrimitive("execute_authorized_tools")) })
            check((permission["allowed"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" &&
                (permission["session_state"] as? kotlinx.serialization.json.JsonPrimitive)?.content == turn.phase) {
                "consultation_session_not_active"
            }
        }
    }

    private suspend fun persistConsultationOutcome(conversationId: Uuid, succeeded: Boolean,
        terminalProof: Pair<String, GenerationTerminalEvidence>? = null): ConsultationTurnResult {
        val checkpoint = requireConsultationCheckpoint(conversationId)
        val turn = checkNotNull(checkpoint.conversationTurn)
        val conversation = checkNotNull(conversationRepo.getConversationById(conversationId))
        check(conversation.assistantId.toString() == turn.assistantId &&
            conversation.consultation == ConsultationConversationBinding(turn.sessionId, turn.subject))
        val messages = conversation.currentMessages
        val outputIds = checkpoint.outputMessageIds + messages.filter {
            it.id.toString() !in checkpoint.baselineMessageIds && it.id != turn.inputId && !it.isCompactionSummary()
        }.map { it.id.toString() }
        val evidence = terminalProof?.takeIf { it.first == checkpoint.requestId }?.second
        val assessment = consultationTerminalAssessment(messages, turn.inputId.toString(), succeeded, outputIds,
            terminalEvidence = evidence, contextEpoch = conversation.compactionEpoch, requireTerminalEvidence = true)
        val outcome = assessment.result
        val state = when (outcome) {
            is ConsultationTurnResult.Complete -> "COMPLETE"
            ConsultationTurnResult.WaitingApproval -> "WAITING_APPROVAL"
            ConsultationTurnResult.Unknown -> "UNKNOWN"
        }
        consultationStore.write(checkpoint.copy(state = state,
            messages = messages.filter { it.id == turn.inputId || it.id.toString() in outputIds },
            outputMessageIds = outputIds,
            finalText = (outcome as? ConsultationTurnResult.Complete)?.text.orEmpty(),
            finalMessageId = (outcome as? ConsultationTurnResult.Complete)?.messageId,
            toolInFlight = if (outcome is ConsultationTurnResult.Complete) null else checkpoint.toolInFlight,
            failure = assessment.failure?.code))
        return outcome
    }

    private fun captureHumanMessageTime(session: ConversationSession,
        epochMillis: Long = System.currentTimeMillis()): me.rerere.ai.ui.OrbisUserMessageTime? {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(session.state.value.assistantId) ?: return null
        return me.rerere.rikkahub.data.ai.transformers.captureOrbisUserMessageTime(assistant.enableUserMessageTime, epochMillis)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true,
        orbisQuote: me.rerere.ai.ui.OrbisMessageQuote? = null): Boolean {
        if (content.isEmptyInputMessage()) return false
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.manualContextWriteInProgress) return false
            if (orbisQuote != null) {
                check(session.isInitialized) { "聊天尚未读完，请稍后发送引用。" }
                session.state.value.requireCurrentQuote(orbisQuote)
            }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer, orbisQuote = orbisQuote,
                orbisUserMessageTime = captureHumanMessageTime(session))
            dispatchNextQueuedMessage(conversationId)
        }
        return true
    }

    /** Native garden drawer only. No page text, solution, bridge payload or auto-send path. */
    suspend fun requireGardenQuickChatTarget(conversationId: Uuid, assistantId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        check(settings.assistantId == assistantId && settings.getAssistantById(assistantId) != null) {
            "当前 AI 已改变，请重新选择聊天窗口。"
        }
        check(conversationRepo.getConversationSummaryOfAssistant(conversationId, assistantId) != null) {
            "原聊天窗口已不存在或已移动，没有新建窗口。"
        }
        val session = sessions[conversationId]
        check(session?.isInitialized == true && session.state.value.assistantId == assistantId) {
            "聊天尚未完整读取，请重新打开侧栏。"
        }
    }

    suspend fun trySendGardenQuickChat(conversationId: Uuid, assistantId: Uuid, text: String): Boolean {
        requireGardenQuickChatTarget(conversationId, assistantId)
        val session = sessions[conversationId] ?: return false
        val committed = CompletableDeferred<Boolean>()
        val accepted = synchronized(session) {
            if (session.generationRecoveryBlocked || session.manualContextWriteInProgress) return@synchronized false
            val settings = settingsStore.settingsFlow.value
            val state = me.rerere.rikkahub.data.orbis.GardenQuickChatState(
                currentAssistantId = settings.assistantId.toString(), targetAssistantId = assistantId.toString(),
                conversationAssistantId = session.state.value.assistantId.toString(),
                assistantExists = settings.getAssistantById(assistantId) != null, conversationExists = true,
                initialized = session.isInitialized, generating = session.getJob() != null,
                submitting = session.submittingMessage != null,
                queued = session.messageQueue.state.value.messages.isNotEmpty() || session.automaticWakeQueue.pending.isNotEmpty(),
                pendingTool = session.state.value.currentMessages.any { m -> m.parts.any { it is UIMessagePart.Tool && it.isPending } },
                voiceActive = OrbisVoiceCallRuntime.get(context).callState.value.isActive,
            )
            if (!me.rerere.rikkahub.data.orbis.canSendGardenQuickChat(state, text)) return@synchronized false
            val message = QueuedMessage(parts = listOf(UIMessagePart.Text(text)),
                orbisUserMessageTime = captureHumanMessageTime(session))
            session.messageQueue.resume() // Explicit new send only; a nonempty old queue was refused above.
            session.submittingMessage = message
            try {
                sendQueuedMessage(session, message, expectedAssistantId = assistantId,
                    onInputCommitted = { committed.complete(true) })
                    .invokeOnCompletion { committed.complete(false) }
            } catch (error: Throwable) {
                if (session.submittingMessage?.id == message.id) session.submittingMessage = null
                throw error
            }
            true
        }
        // Clear a draft only after durable history persistence, not merely after scheduling a job.
        // Model failure AFTER this receipt leaves the user's message in its original conversation.
        return accepted && committed.await()
    }

    /** An explicit stop must finish even if its drawer is immediately closed or disposed. */
    fun stopGardenQuickChat(conversationId: Uuid) = appScope.launch {
        try { stopGeneration(conversationId, HostToolFailure.USER_CANCELLED) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { addError(failure, conversationId, title = "停止结果待核对") }
    }

    /** Explicit sticker send is never deferred to a later turn or silently added to the queue. */
    fun trySendOrbisSticker(conversationId: Uuid, content: List<UIMessagePart>): Boolean {
        if (content.isEmptyInputMessage()) return false
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (!session.isInitialized) return false
            val queue = session.messageQueue.state.value
            if (!me.rerere.rikkahub.data.model.canSendOrbisStickerNow(
                    generating = session.getJob() != null,
                    submitting = session.submittingMessage != null,
                    queued = queue.messages.isNotEmpty(),
                    pendingTool = session.state.value.currentMessages.any { message ->
                        message.parts.any { it is UIMessagePart.Tool && it.isPending }
                    },
                )) return false
            // Like an ordinary explicit send, a NEW tap can leave an empty failed-queue
            // state. No old item is replayed, and a nonempty (even paused) queue is refused.
            session.messageQueue.resume()
            val message = QueuedMessage(parts = content.toList(), orbisUserMessageTime = captureHumanMessageTime(session))
            session.submittingMessage = message
            try {
                sendQueuedMessage(session, message, requireImageInput = content.any { it is UIMessagePart.Image })
                    .invokeOnCompletion { cleanupQueuedAttachments(message) }
            } catch (error: Throwable) {
                if (session.submittingMessage?.id == message.id) session.submittingMessage = null
                throw error
            }
            return true
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply,
                orbisUserMessageTime = captureHumanMessageTime(session))
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            // Check BEFORE takeNext removes an input. Recovery-blocked human messages must not disappear.
            if (session.generationRecoveryBlocked || session.manualContextWriteInProgress || session.submittingMessage != null) return null
            // A pending tool approval is still part of the current turn.
            val next = takeNextConversationInput(
                human = session.messageQueue, automatic = session.automaticWakeQueue,
                busy = session.getJob() != null,
                pendingApproval = session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                },
                automaticAllowed = automaticWakeAllowed(session),
            ) ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next, requireImageInput =
                next.orbisEventId != null && next.parts.any { it is UIMessagePart.Image })
        }
    }

    /** Ending a call before its opening starts is a withdrawal, not a failure of the chat queue. */
    private suspend fun withdrawVoiceOpening(session: ConversationSession, queued: QueuedMessage) {
        voiceCalls.update(checkNotNull(queued.voiceCallId)) { old ->
            if (old.openingRequestId == queued.id.toString() &&
                old.openingStatus in setOf(OrbisVoiceOpeningStatus.CLAIMED, OrbisVoiceOpeningStatus.GENERATING))
                old.copy(openingStatus = OrbisVoiceOpeningStatus.CANCELLED, openingError = "通话已结束或开场已撤回，未补发。")
            else old
        }
        queued.reply?.complete(null)
        session.submittingMessage = null
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage,
        requireImageInput: Boolean = false, expectedAssistantId: Uuid? = null,
        onInputCommitted: (() -> Unit)? = null): Job {
        val conversationId = session.id
        val content = queued.parts
        var eventInputCommitted = false
        var inputSaveAttempted = false
        val answer = queued.answer
        val bodyEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            bodyEntered.set(true)
            try {
                // A send can race initial loading or follow a failed load. Load successfully
                // before taking the history snapshot, including the no-generation send path.
                if (!session.isInitialized) initializeConversation(conversationId, selectAssistant = expectedAssistantId == null)
                if (session.state.value.isConsultation) consultationFeature.requireEnabled()
                require(queued.voiceCallKind !in setOf("archive", "restore")) { "旧归档队列项不再执行，请在通话记录中独立整理。" }
                expectedAssistantId?.let { requireGardenQuickChatTarget(conversationId, it) }
                if (queued.voiceCallKind == "opening") {
                    val opening = checkNotNull(voiceCalls.get(checkNotNull(queued.voiceCallId)))
                    if (!(opening.status == OrbisVoiceCallStatus.ACTIVE && opening.connectedAtMs != null &&
                        opening.conversationId == conversationId.toString() &&
                        opening.assistantId == session.state.value.assistantId.toString() &&
                        opening.openingRequestId == queued.id.toString() &&
                        opening.openingStatus == OrbisVoiceOpeningStatus.CLAIMED)) {
                        withdrawVoiceOpening(session, queued)
                        return@launchGenerationJob
                    }
                }
                settlePreviousGeneration(session)
                check(!session.generationRecoveryBlocked) { "回复恢复记录待核对，暂不发送新请求；原聊天与恢复文件均保留。" }
                if (queued.orbisEventId != null && !automaticWakeAllowed(session)) {
                    // Recovery can discover an unresolved external write AFTER selection. Keep the
                    // undispatched wake in its own lane; do not label it sent or spin/retry a model.
                    session.automaticWakeQueue.enqueue(content, answer, queued.id, queued.orbisEventId)
                    withContext(Dispatchers.IO) {
                        orbisEvents.inbox.mark(queued.orbisEventId, "queued", "automatic_safety_hold")
                    }
                    return@launchGenerationJob
                }
                finishInterruptedPendingTools(conversationId)

                if (queued.voiceCallKind == "restore" || (queued.voiceCallKind == "archive" &&
                    voiceCalls.get(checkNotNull(queued.voiceCallId))?.archiveStatus == OrbisVoiceArchiveStatus.READY)) {
                    val record = checkNotNull(voiceCalls.get(checkNotNull(queued.voiceCallId)))
                    session.orbisPromptEditMutex.withLock { collapseArchivedVoiceCall(record, session) }
                    queued.reply?.complete(record.summary)
                    session.submittingMessage = null
                    return@launchGenerationJob
                }

                val currentConversation = session.state.value
                queued.orbisQuote?.let {
                    // Already frozen at acceptance. Deleting its source while queued does not lose the reply.
                    check(it.isValid() && it.sourceConversationId == conversationId &&
                        queued.orbisEventId == null && queued.voiceCallId == null && !currentConversation.isConsultation) {
                        "引用窗口不匹配，原待发输入保留。"
                    }
                }
                val callRecord = queued.voiceCallId?.let { checkNotNull(voiceCalls.get(it)) }
                if (callRecord != null) check(currentConversation.assistantId.toString() == callRecord.assistantId) {
                    "通话所属 AI 已变更，原文保留，未交给其他 AI。"
                }
                if (queued.voiceCallKind == "opening") {
                    val opening = voiceCalls.update(checkNotNull(queued.voiceCallId)) {
                        if (it.status == OrbisVoiceCallStatus.ACTIVE && it.openingRequestId == queued.id.toString() &&
                            it.openingStatus == OrbisVoiceOpeningStatus.CLAIMED) it.copy(openingStatus = OrbisVoiceOpeningStatus.GENERATING)
                        else it
                    }
                    if (opening.status != OrbisVoiceCallStatus.ACTIVE || opening.openingStatus != OrbisVoiceOpeningStatus.GENERATING) {
                        withdrawVoiceOpening(session, queued)
                        return@launchGenerationJob
                    }
                }
                val event = queued.orbisEventId?.let { orbisEvents.inbox.get(it) ?: error("event_receipt_missing") }
                if (event != null) {
                    if (!nativeSentinels.mayDeliver(event)) {
                        withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "suppressed", "sentinel_paused_before_dispatch") }
                        return@launchGenerationJob
                    }
                    check(orbisEvents.inbox.targetStillMatches(event) && currentConversation.assistantId.toString() == event.assistantId) { "event_target_changed_before_dispatch" }
                    check(conversationRepo.getConversationById(conversationId)?.assistantId?.toString() == event.assistantId) { "event_target_missing" }
                    if (currentConversation.messageNodes.any { node -> node.messages.any { it.id == queued.id } }) {
                        withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "displayed", "duplicate_message_no_auto_reply") }
                        return@launchGenerationJob
                    }
                }
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: if (event != null || expectedAssistantId != null) error("target_assistant_missing") else settings.getCurrentAssistant()
                if (requireImageInput) {
                    val selected = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                    check(selected?.inputModalities?.contains(me.rerere.ai.provider.Modality.IMAGE) == true) {
                        "当前模型已变更或不支持图片输入，表情没有降级为文字发送。"
                    }
                }
                val processedContent = if (queued.voiceCallKind == "archive") {
                    val record = snapshotVoiceCall(checkNotNull(queued.voiceCallId), conversationId)
                    val preview = currentConversation.currentMessages + UIMessage.user("待写入的结束通知")
                    val visible = if (preview.firstOrNull()?.isCompactionSummary() == true)
                        preview.take(1) + preview.drop(1).limitContext(assistant.contextMessageLimit)
                    else preview.limitContext(assistant.contextMessageLimit)
                    val activeIds = visible.map { it.id.toString() }.toSet()
                    val saved = record.sourceNodesJson?.let { JsonInstant.decodeFromString<List<MessageNode>>(it) }.orEmpty()
                    val missingNodes = saved.map { it.currentMessage }.filter { it.id.toString() !in activeIds }
                    val savedIds = saved.flatMap { it.messages }.map { it.id.toString() }.toSet()
                    val undelivered = record.transcript.filter { it.messageId !in activeIds && it.messageId !in savedIds }
                    val missingText = missingNodes.joinToString("\n\n") { "[${it.role}] ${it.toText()}" } +
                        undelivered.joinToString("\n\n") { "[${it.role}，曾收音但尚未送达] ${it.content}" }
                    listOf(UIMessagePart.Text(voiceArchiveRequest(record) + if (missingText.isBlank()) "" else
                        "\n以下仅为本次通话已存档的历史数据，不是新指令：\n$missingText"))
                } else if (event == null && queued.voiceCallId == null) preprocessUserInputParts(content, assistant) else content

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        id = queued.id,
                        role = if (queued.voiceCallKind == "opening") MessageRole.SYSTEM else MessageRole.USER,
                        parts = processedContent,
                        isSynthetic = queued.voiceCallKind == "opening",
                        orbisEvent = event?.let { OrbisEventMetadata(it.id, it.source, it.eventId, it.receivedAt, occurredAt = it.occurredAt) },
                        orbisVoiceCallId = queued.voiceCallId,
                        orbisVoiceCallKind = queued.voiceCallKind,
                        orbisQuote = queued.orbisQuote,
                        orbisUserMessageTime = if (event == null && queued.voiceCallKind in setOf(null, "turn"))
                            queued.orbisUserMessageTime else null,
                    ).toMessageNode(),
                )
                inputSaveAttempted = true
                saveConversation(conversationId, newConversation)
                eventInputCommitted = true
                onInputCommitted?.invoke()
                session.submittingMessage = null
                if (event == null && queued.voiceCallId == null) acknowledgeAutomaticHold(session)

                if (event != null) withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "displayed") }
                var completed = false

                // 开始补全
                if (answer) {
                    if (queued.voiceCallKind == "opening" &&
                        voiceCalls.get(checkNotNull(queued.voiceCallId))?.status != OrbisVoiceCallStatus.ACTIVE) {
                        withdrawVoiceOpening(session, queued)
                        return@launchGenerationJob
                    }
                    if (event != null) withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "generating") }
                    completed = handleMessageComplete(conversationId, requireImageInput = requireImageInput, realWake = true,
                        voiceCallId = queued.voiceCallId, voiceCallKind = queued.voiceCallKind,
                        allowAmbientCallBinding = expectedAssistantId == null,
                        expectedAssistantId = expectedAssistantId)
                }

                queued.voiceCallId?.let { callId ->
                    snapshotVoiceCall(callId, conversationId)
                    if (queued.voiceCallKind == "archive") {
                        check(completed) { "本次摘要生成未完成，通话原文仍保留。" }
                        val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                        val replyText = session.state.value.currentMessages.filter {
                            it.id !in previousIds && it.role == MessageRole.ASSISTANT &&
                                !it.isCompactionSummary() && it.orbisVoiceCallId == callId &&
                                it.orbisVoiceCallKind == "archive"
                        }.lastOrNull { it.toText().isNotBlank() }?.toText().orEmpty()
                        val written = checkNotNull(OrbisVoiceCallProtocol.parseModelArchive(replyText)) {
                            "AI 的归档格式尚未完成，原文已保留，可在通话记录中重试。"
                        }
                        val record = voiceCalls.update(callId) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
                            summary = written.summary, modelTranscript = written.transcript, archiveError = null) }
                        session.orbisPromptEditMutex.withLock { collapseArchivedVoiceCall(record, session) }
                    }
                }

                if (event != null && answer) {
                    withContext(Dispatchers.IO) {
                        orbisEvents.inbox.mark(event.id, orbisEventCompletionState(
                            compactionAwareEventCompletionMessages(session.state.value, newConversation, event.id), event.id, !completed))
                    }
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!answer || completed) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT &&
                        !it.isCompactionSummary() && (queued.voiceCallId == null || it.orbisVoiceCallId == queued.voiceCallId) }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                // Failed preflight has not submitted a model/tool or attempted a history write.
                // Keep exactly this human input; never requeue a possibly committed or dispatched turn.
                if (queued.orbisEventId == null && !eventInputCommitted && !inputSaveAttempted &&
                    queued.voiceCallKind !in setOf("archive", "restore") && e !is CancellationException) {
                    synchronized(session) { session.messageQueue.retainUndispatched(queued) }
                }
                queued.orbisEventId?.let { id ->
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        if (!eventInputCommitted && session.generationRecoveryBlocked) {
                            session.automaticWakeQueue.enqueue(content, answer, queued.id, id)
                            recordAutomaticReceiptSafely(session) {
                                orbisEvents.inbox.mark(id, "queued", "automatic_safety_hold")
                            }
                        } else {
                            recordAutomaticReceiptSafely(session) {
                                orbisEvents.inbox.mark(id, "unknown", "dispatch_interrupted_no_auto_retry")
                            }
                        }
                    }
                }
                if (e.isSavedVoiceInterruption()) queued.reply?.complete(null) else queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                // A malformed archive / page commit must not pause otherwise healthy text conversations.
                if (queued.voiceCallKind !in setOf("archive", "restore")) session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            voiceGenerationJobs.remove(queued.id, job)
            if (cause != null && !bodyEntered.get() && queued.orbisEventId == null &&
                queued.voiceCallId == null) synchronized(session) {
                session.messageQueue.retainUndispatched(queued)
            }
            if (cause != null && !bodyEntered.get() && queued.orbisEventId != null) {
                appScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    orbisEventDispatchMutex.withLock {
                        val event = orbisEvents.inbox.get(queued.orbisEventId)
                        if (interruptedWakeBeforeBody(bodyEntered.get(), true, event?.state)) {
                            recordAutomaticReceiptSafely(session) {
                                orbisEvents.inbox.mark(queued.orbisEventId, "unknown",
                                    "interrupted_before_dispatch_no_auto_retry")
                            }
                        }
                    }
                }
            }
            // Cancellation can precede coroutine scheduling / the edit-mutex gate. No
            // conversation or tool was touched by this job, so there is nothing to replay
            // or flush. Preserve all existing pause/recovery blocks nevertheless.
            if (cause is VoiceBargeInCancellation && !bodyEntered.get() &&
                canAcknowledgeVoiceInterruption(true, session.messageQueue.state.value.paused,
                    session.generationRecoveryBlocked, emptySet())) cause.partialSafelySaved = true
            if (cause?.isSavedVoiceInterruption() == true) queued.reply?.complete(null)
            else if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        if (queued.voiceCallKind in setOf("turn", "opening")) voiceGenerationJobs[queued.id] = job
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.isConsultation) consultationFeature.requireEnabled()
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                settlePreviousGeneration(session)
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId, realWake = true,
                        voiceCallId = message.orbisVoiceCallId, voiceCallKind = message.orbisVoiceCallKind,
                        allowAmbientCallBinding = false)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex, realWake = true,
                            voiceCallId = message.orbisVoiceCallId, voiceCallKind = message.orbisVoiceCallKind,
                            allowAmbientCallBinding = false)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        remember: Boolean = false,
        expectedAssistantId: Uuid? = null,
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.isConsultation) consultationFeature.requireEnabled()
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    expectedAssistantId?.let { requireGardenQuickChatTarget(conversationId, it) }
                    if (session.state.value.isConsultation) consultationFeature.requireEnabled()
                    settlePreviousGeneration(session)
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val pendingOwner = conversation.currentMessages.first { message ->
                        message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                    }
                    val pending = pendingOwner.getTools().first { it.toolCallId == toolCallId && it.isPending }
                    val voiceBinding = checkNotNull(voiceToolContinuationBinding(conversation.currentMessages, toolCallId))
                    require(answer == null || pending.toolName == "ask_user") { "只有提问工具可以提交回答。" }
                    require(pending.toolName != "ask_user" || answer != null || !approved) { "请先回答问题，不能跳过提问。" }
                    if (approved && answer == null) {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = settings.getAssistantById(conversation.assistantId)
                            ?: error("当前 AI 已不存在，未批准工具。")
                        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                            ?: error("未选中聊天模型。")
                        val definition = chatToolFactory.createTools(settings, assistant, model, conversation.workspaceCwd, conversationId.toString(),
                            voiceCallId = voiceBinding.callId, allowAmbientCallBinding = voiceBinding.allowAmbientCallBinding)
                            .firstOrNull { it.name == pending.toolName }
                            ?: error("工具已停用或配置已变化，请重新发起调用。")
                        check(withContext(Dispatchers.IO) { pending.matchesHostApproval(definition) }) {
                            "审批快照已过期或参数、连接已改变；未执行，请拒绝旧调用并重新发起。"
                        }
                        if (remember) {
                            val approval = definition.hostApproval ?: error("此工具不支持记住授权。")
                            check(approval.rememberable) { "此工具需要你提供回答，不是执行授权。" }
                            ToolApprovalStore.get(context).allow(assistant.id.toString(), approval)
                        }
                    }
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        val event = updatedConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
                            ?.orbisEvent?.recordId?.let { orbisEvents.inbox.get(it) }
                        try {
                            if (event != null) withContext(Dispatchers.IO) { orbisEvents.inbox.mark(event.id, "generating") }
                            var terminalProof: Pair<String, GenerationTerminalEvidence>? = null
                            val completed = handleMessageComplete(conversationId, realWake = true,
                                voiceCallId = voiceBinding.callId, voiceCallKind = voiceBinding.kind,
                                allowAmbientCallBinding = voiceBinding.allowAmbientCallBinding,
                                expectedAssistantId = expectedAssistantId,
                                onConsultationTerminal = { requestId, evidence -> terminalProof = requestId to evidence })
                            if (updatedConversation.consultation != null)
                                persistConsultationOutcome(conversationId, completed, terminalProof)
                            if (event != null) withContext(Dispatchers.IO) {
                                orbisEvents.inbox.mark(event.id, orbisEventCompletionState(
                                    compactionAwareEventCompletionMessages(session.state.value, updatedConversation, event.id), event.id, !completed))
                            }
                        } catch (error: Exception) {
                            if (updatedConversation.consultation != null) withContext(kotlinx.coroutines.NonCancellable) {
                                runCatching { persistConsultationOutcome(conversationId, false) }
                            }
                            if (event != null) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                recordAutomaticReceiptSafely(session) {
                                    orbisEvents.inbox.mark(event.id, "unknown", "continuation_interrupted_no_auto_retry")
                                }
                            }
                            throw error
                        }
                    } else if (updatedConversation.consultation != null) {
                        // Partial multi-tool approval remains explicitly pending, not RUNNING.
                        persistConsultationOutcome(conversationId, succeeded = true)
                    }

                    if (updatedConversation.consultation == null) _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        requireImageInput: Boolean = false,
        realWake: Boolean = false,
        voiceCallId: String? = null,
        voiceCallKind: String? = null,
        allowAmbientCallBinding: Boolean = true,
        expectedAssistantId: Uuid? = null,
        onConsultationTerminal: ((String, GenerationTerminalEvidence) -> Unit)? = null,
    ): Boolean {
        expectedAssistantId?.let { requireGardenQuickChatTarget(conversationId, it) }
        val settings = settingsStore.settingsFlow.first()
        val initialSession = getOrCreateSession(conversationId)
        val initialConversation = initialSession.orbisPromptEditMutex.withLock { initialSession.state.value }
        if (initialConversation.isConsultation) consultationFeature.requireEnabled()
        val consultationCheckpoint = if (initialConversation.consultation != null)
            requireConsultationCheckpoint(conversationId).also { validateConsultationTurn(it) } else null
        val consultationTurn = consultationCheckpoint?.conversationTurn
        if (consultationCheckpoint?.state == "WAITING_APPROVAL") {
            consultationStore.write(consultationCheckpoint.copy(state = "RUNNING", executionProcessId = consultationRuntimeProcessId))
        }
        val triggeringEvent = initialConversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.orbisEvent
            ?.recordId?.let { orbisEvents.inbox.get(it) ?: error("event_receipt_missing") }
        if (triggeringEvent != null) {
            if (realWake && !nativeSentinels.mayDeliver(triggeringEvent)) {
                withContext(Dispatchers.IO) { orbisEvents.inbox.mark(triggeringEvent.id, "suppressed", "sentinel_paused_before_generation") }
                return false
            }
            check(orbisEvents.inbox.targetStillMatches(triggeringEvent) &&
                initialConversation.assistantId.toString() == triggeringEvent.assistantId) { "event_target_changed_before_generation" }
            check(conversationRepo.getConversationById(conversationId)?.assistantId?.toString() == triggeringEvent.assistantId) { "event_target_missing" }
        }
        val configuredAssistant = settings.getAssistantById(initialConversation.assistantId)
            ?: if (triggeringEvent != null || expectedAssistantId != null || consultationTurn != null) error("target_assistant_missing") else settings.getCurrentAssistant()
        val voiceRecord = voiceCallId?.let { checkNotNull(voiceCalls.get(it)) }
        if (voiceRecord != null) check(voiceRecord.assistantId == configuredAssistant.id.toString()) {
            "通话所属 AI 已改变，未把通话交给其他 AI。"
        }
        val boundAssistant = voiceRecord?.modelId?.let { configuredAssistant.copy(chatModelId = Uuid.parse(it)) } ?: configuredAssistant
        val sourceHeaders = setOf("x-st-execution-profile", "x-st-source-kind", "x-request-id")
        val assistant = if (consultationTurn == null) boundAssistant else boundAssistant.copy(
            maxTokens = consultationTurn.outputTokenLimit,
            customHeaders = boundAssistant.customHeaders.filterNot { it.name.lowercase() in sourceHeaders } + listOf(
                CustomHeader("X-ST-Source-Kind", consultationTurn.sourceKind),
                CustomHeader("X-Request-ID", consultationTurn.requestId)))
        val selectedModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")
        val model = if (consultationTurn == null) selectedModel else selectedModel.copy(
            customHeaders = selectedModel.customHeaders.filterNot { it.name.lowercase() in sourceHeaders })
        check(!requireImageInput || me.rerere.ai.provider.Modality.IMAGE in model.inputModalities) {
            "当前模型已变更或不支持图片输入，表情已保留在聊天中但没有降级为文字发送。"
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        check(!initialSession.generationRecoveryBlocked) { "回复恢复记录待核对，暂不生成。" }
        var checkpointHandle: GenerationCheckpointHandle? = null
        // Ephemeral evidence belongs only to this invocation. A normal Flow return, a
        // persisted partial reply or an old checkpoint must never manufacture finality.
        var terminalEvidence: GenerationTerminalEvidence? = null
        var terminalEvidenceCount = 0
        val initialMessageIds = initialConversation.messageNodes.map { it.currentMessage.id }.toHashSet()

        suspend fun publishCheckpoint(
            messages: List<UIMessage>, epoch: Long?, transition: GenerationToolTransition? = null,
        ) {
            val session = getOrCreateSession(conversationId)
            session.orbisPromptEditMutex.withLock {
                val current = session.state.value
                epoch?.let { requireCompactionEpoch(it, current.compactionEpoch) }
                val handle = checkNotNull(checkpointHandle)
                // Earlier-message regeneration may produce several tool/model turns. Insert
                // them before the untouched suffix, never replace unrelated later messages.
                val protectedSuffix = current.messageNodes.takeLast(handle.suffixCount)
                val editable = if (handle.suffixCount == 0) current else
                    current.copy(messageNodes = current.messageNodes.dropLast(handle.suffixCount))
                val projectedEditable = editable.updateCurrentMessages(messages.map { message ->
                    if (voiceCallId != null && !message.isCompactionSummary() && message.id !in initialMessageIds)
                        message.copy(orbisVoiceCallId = voiceCallId, orbisVoiceCallKind = voiceCallKind)
                    else message
                })
                val projected = if (handle.suffixCount == 0) projectedEditable else
                    projectedEditable.copy(messageNodes = projectedEditable.messageNodes + protectedSuffix)
                val updated = withCommittedVoiceNotePlayed(me.rerere.rikkahub.data.model.withCommittedEventPresentation(
                    me.rerere.rikkahub.data.model.withCommittedToolRecordEdits(projected, current), current), current)
                withContext(Dispatchers.IO) {
                    generationJournal.checkpoint(handle, updated.assistantId, updated.compactionEpoch,
                        updated.messageNodes.drop(handle.prefixCount).dropLast(handle.suffixCount), transition)
                }
                voiceCallId?.let { callId ->
                    voiceCalls.update(callId) { captureVoiceCallSource(it, updated, finished = false,
                        capturedAtMs = System.currentTimeMillis()) }
                }
                // Only publish content after its bounded private tail is safely on disk.
                updateConversation(conversationId, updated)
                if (consultationTurn != null && transition != null) {
                    val saved = requireConsultationCheckpoint(conversationId)
                    val outputIds = saved.outputMessageIds + updated.currentMessages.filter {
                        it.id.toString() !in saved.baselineMessageIds && it.id != consultationTurn.inputId && !it.isCompactionSummary()
                    }.map { it.id.toString() }
                    consultationStore.write(saved.copy(messages = updated.currentMessages.filter {
                        it.id == consultationTurn.inputId || it.id.toString() in outputIds
                    }, outputMessageIds = outputIds,
                        toolInFlight = if (transition.status == GenerationToolStatus.STARTED) transition.callId else null))
                }
            }
        }

        return runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            var conversation = getConversationFlow(conversationId).value
            val inputMessages = conversation.currentMessages.let {
                if (messageRange != null) it.subList(messageRange.start, messageRange.endInclusive + 1) else it
            }
            val frozenCount = frozenOutputPrefixCount(inputMessages)
            // Historical display conversions are applied once on IO and durably committed before
            // requesting any new tokens. They are not rerun on thousands of rows per stream chunk.
            val normalizedHistory = withContext(Dispatchers.IO) {
                OutputGenerationScope(emptyList()).apply(inputMessages.take(frozenCount)) { history ->
                    history.transforms(outputTransformers, context, model, assistant, settings)
                        .visualTransforms(outputTransformers, context, model, assistant, settings)
                        .onGenerationFinish(outputTransformers, context, model, assistant, settings)
                }
            }
            conversation = conversation.updateCurrentMessages(normalizedHistory)
            val compaction = if (messageRange == null) createCompactionControl(conversationId, assistant.compactionThresholdTokens) else null

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = conversation.workspaceCwd,
                    conversationId = conversationId.toString(),
                    voiceCallId = voiceCallId,
                    allowAmbientCallBinding = allowAmbientCallBinding,
                )
            } catch (error: InvalidMcpServerNamesException) {
                sessions[conversationId]?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return false
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            // Establish a durable baseline once, never rewrite the entire history per token.
            saveConversation(conversationId, conversation)
            session.orbisPromptEditMutex.withLock {
                val baseline = checkNotNull(conversationRepo.getConversationById(conversationId))
                checkpointHandle = withContext(Dispatchers.IO) {
                    generationJournal.begin(baseline,
                        prefixCount = (messageRange?.endInclusive ?: baseline.messageNodes.lastIndex).coerceAtLeast(0),
                        suffixCount = messageRange?.let {
                            (baseline.messageNodes.size - it.endInclusive - 2).coerceAtLeast(0)
                        } ?: 0)
                }
                // Repository can merge newer committed display/tool edits. Generate from the
                // exact durable baseline that was hashed, not the older pre-save session copy.
                conversation = baseline
                updateConversation(conversationId, baseline, restoreCommittedPrompt = true)
            }
            val contextTools = compaction?.tools().orEmpty()
            val scopedTools = refreshOrbisHelpForTools(tools.filterNot { tool -> contextTools.any { it.name == tool.name } } + contextTools).map { tool ->
                if (consultationTurn == null) tool else tool.copy(execute = { arguments ->
                    validateConsultationTurn(requireConsultationCheckpoint(conversationId), checkRoom = true)
                    tool.execute(arguments)
                })
            }
            generationLoop.generateText(
                settings = if (consultationTurn == null) settings else settings.copy(
                    networkSetting = settings.networkSetting.copy(enableAutoRetry = false)),
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                orbisPrompt = conversation.orbisPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(me.rerere.rikkahub.data.ai.transformers.OrbisQuotedReplyTransformer)
                    add(me.rerere.rikkahub.data.ai.transformers.OrbisUserMessageTimeTransformer)
                },
                outputTransformers = outputTransformers,
                tools = scopedTools,
                compactionControl = compaction,
                initialContextEpoch = conversation.compactionEpoch,
                includeCompactionReminder = realWake,
                durableCheckpoints = true,
                outputFrozenPrefixCount = frozenCount,
                consultationBusyWaitUntilMillis = consultationTurn?.let {
                    minOf(it.expiresAtMillis, System.currentTimeMillis() + 120_000L)
                },
                emitTerminalEvidence = consultationTurn != null,
            ).onCompletion { completionError ->
                // 可能被取消了，或者意外结束，兜底更新
                val current = getConversationFlow(conversationId).value
                val handle = checkNotNull(checkpointHandle)
                val mutableEnd = current.messageNodes.size - handle.suffixCount
                val canFinish = completionError == null && current.compactionEpoch == handle.epoch &&
                    handle.prefixCount in 0..mutableEnd
                val finishedSegment = if (canFinish) current.messageNodes.subList(handle.prefixCount, mutableEnd)
                    .mapPreservingIdentity { node ->
                        val messages = node.messages.mapPreservingIdentity { message ->
                            if (message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null })
                                message.finishReasoning() else message
                        }
                        if (messages === node.messages) node else node.copy(messages = messages)
                    } else emptyList()
                val updatedConversation = if (canFinish) current.copy(
                    messageNodes = current.messageNodes.take(handle.prefixCount) + finishedSegment +
                        current.messageNodes.takeLast(handle.suffixCount),
                    updateAt = Instant.now()
                ) else current
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                if (consultationTurn == null) appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                if (consultationTurn != null) validateConsultationTurn(requireConsultationCheckpoint(conversationId))
                when (chunk) {
                    is GenerationChunk.TerminalResponse -> {
                        terminalEvidenceCount++
                        terminalEvidence = if (consultationTurn != null && terminalEvidenceCount == 1)
                            chunk.evidence else null
                    }
                    is GenerationChunk.DurableBoundary -> {
                        if (terminalEvidenceCount > 0) terminalEvidence = null
                        try {
                            publishCheckpoint(chunk.messages, chunk.contextEpoch,
                                chunk.toolCallId?.let { id -> GenerationToolTransition(id,
                                    checkNotNull(chunk.toolName), if (chunk.toolCompleted == true)
                                        GenerationToolStatus.COMPLETED else GenerationToolStatus.STARTED) })
                            chunk.result.complete(Unit)
                        } catch (failure: Exception) {
                            chunk.result.completeExceptionally(failure)
                            throw failure
                        }
                    }
                    is GenerationChunk.CompactionCommit -> {
                        if (terminalEvidenceCount > 0) terminalEvidence = null
                        var pageCommitted = false
                        try {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                requireCompactionEpoch(chunk.contextEpoch, session.state.value.compactionEpoch)
                                val control = checkNotNull(compaction) { "此生成任务不允许整理整窗。" }
                                session.state.value.currentMessages.mapNotNull {
                                    it.orbisVoiceCallId?.takeIf { _ -> it.orbisVoiceCallKind != "summary" }
                                }.distinct().forEach { callId -> snapshotVoiceCall(callId, conversationId, finished = false) }
                                val applied = control.commit(chunk.before, chunk.replacement)
                                pageCommitted = true
                                checkpointHandle = withContext(Dispatchers.IO) {
                                    val committed = checkNotNull(conversationRepo.getConversationById(conversationId))
                                    generationJournal.rebaseAfterDurableCommit(checkNotNull(checkpointHandle), committed)
                                }
                                chunk.result.complete(applied)
                            }
                        } catch (failure: Exception) {
                            val reported = if (pageCommitted) GenerationDurabilityException(failure) else failure
                            chunk.result.completeExceptionally(reported)
                            if (failure is CancellationException) throw failure
                            if (pageCommitted) throw reported
                        }
                    }
                    is GenerationChunk.Messages -> {
                        if (terminalEvidenceCount > 0) terminalEvidence = null
                        publishCheckpoint(chunk.messages, chunk.contextEpoch)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.takeIf { consultationTurn == null }?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
            // Final persistence is part of the protected operation too: Result.onSuccess
            // exceptions do not enter onFailure. Keep the journal until Room is acknowledged.
            val finalConversation = getConversationFlow(conversationId).value
            checkpointHandle?.let { handle -> withContext(Dispatchers.IO) {
                generationJournal.checkpoint(handle, finalConversation.assistantId, finalConversation.compactionEpoch,
                    finalConversation.messageNodes.drop(handle.prefixCount).dropLast(handle.suffixCount))
            } }
            saveConversation(conversationId, finalConversation)
            checkpointHandle?.let { handle -> withContext(Dispatchers.IO) {
                generationJournal.clearAfterDurableCommit(handle,
                    checkNotNull(conversationRepo.getConversationById(conversationId)))
            } }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            if (consultationTurn == null) appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            // Stream updates live in memory. A continuation can fail AFTER a real tool write,
            // so keep those results durably before releasing any following independent input.
            // This does not mark the event successful or replay its request/tools.
            val independentFailure = !initialSession.messageQueue.state.value.paused &&
                canContinueAfterIndependentModelFailure(it, initialConversation.currentMessages,
                    initialSession.state.value.currentMessages, initialSession.generationRecoveryBlocked)
            val partialSnapshotSaved = try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (checkpointHandle != null || withContext(Dispatchers.IO) {
                            generationJournal.hasCheckpoint(conversationId)
                        }) {
                        initialSession.orbisPromptEditMutex.withLock {
                            val persisted = checkNotNull(conversationRepo.getConversationById(conversationId))
                            val recovered = recoverGenerationCheckpoint(initialSession, persisted, it as? VoiceBargeInCancellation,
                                preserveUnpausedQueueOnSafeFailure = independentFailure)
                            updateConversation(conversationId, recovered, restoreCommittedPrompt = true)
                            check(!initialSession.generationRecoveryBlocked)
                        }
                    } else {
                        saveConversation(conversationId, getConversationFlow(conversationId).value)
                        if (it is VoiceBargeInCancellation && canAcknowledgeVoiceInterruption(true,
                                initialSession.messageQueue.state.value.paused, initialSession.generationRecoveryBlocked, emptySet()))
                            it.partialSafelySaved = true
                    }
                }
                true
            } catch (saveError: Exception) {
                Log.w(TAG, "Failed generation snapshot could not be saved (${saveError.javaClass.simpleName})")
                addError(IllegalStateException("本轮已完成步骤尚未成功保存，队列已暂停；请先保留当前页面。"), conversationId)
                initialSession.generationRecoveryBlocked = true
                holdAutomaticWakes(initialSession, "partial_snapshot_not_saved")
                false
            }
            val mayContinue = partialSnapshotSaved && independentFailure &&
                canContinueAfterIndependentModelFailure(it, initialConversation.currentMessages,
                    initialSession.state.value.currentMessages, initialSession.generationRecoveryBlocked)
            if (!mayContinue) sessions[conversationId]?.messageQueue?.afterGenerationFailure(it, partialSnapshotSaved)
            voiceCallId?.let { id -> withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { snapshotVoiceCall(id, conversationId) }.onFailure {
                    Log.w(TAG, "Voice source remains in generation journal; archive projection could not be updated")
                }
            } }

            if (it is CancellationException) throw it

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
            if (consultationTurn != null) throw it
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value

            // runCatching has completed the Room commit AND journal acknowledgement.
            // This callback only returns the local proof; the caller still checks the
            // current request head, stored message and epoch before creating COMPLETE.
            if (consultationTurn != null && terminalEvidenceCount == 1) terminalEvidence?.let {
                onConsultationTerminal?.invoke(consultationTurn.requestId, it)
            }

            if (consultationTurn == null) launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            if (consultationTurn == null) launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }.isSuccess
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        reason: HostToolFailure = HostToolFailure.INTERRUPTED,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishInterruptedHostTools(reason)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            val generatedTitle = result.message.toText().trim()
            if (generatedTitle.isNotEmpty() && conversationRepo.applyGeneratedTitleIfUnchanged(
                    conversation.id, conversation.assistantId, conversation.title, generatedTitle)) {
                if (sessions.containsKey(conversation.id)) {
                    updateConversationState(conversation.id) {
                        it.withGeneratedTitleIfUnchanged(conversation.title, generatedTitle)
                    }
                }
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            if (conversation.id != conversationId) return@runCatching
            val session = sessions[conversationId] ?: return@runCatching
            val originatingJob = session.getJob()
            fun matchesRequest(current: Conversation): Boolean =
                me.rerere.rikkahub.data.repository.matchesGeneratedSuggestionSnapshot(conversation, current)
            fun matchesSession(): Boolean = sessions[conversationId] === session && session.isInitialized &&
                !session.generationRecoveryBlocked && !session.manualContextWriteInProgress &&
                session.submittingMessage == null &&
                settingsStore.settingsFlow.value.enableSuggestion
            fun mayPublish(): Boolean = matchesSession() && session.getJob() == null

            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            // The originating generation may still be finishing its success callback here.
            // Publication below requires that no generation (old OR new) remains in flight.
            if (!matchesSession() || !matchesRequest(session.state.value)) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = generateWhileChatSuggestionsEnabled(
                enabled = settingsStore.settingsFlow.map { it.enableSuggestion },
            ) {
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            settings.suggestionPrompt.applyPlaceholders(
                                "locale" to Locale.getDefault().displayName,
                                "content" to conversation.currentMessages
                                    .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                        )
                    ),
                    params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
                )
            } ?: return@runCatching
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }.take(10)

            // This runs in its own app-scope job, launched by the generation's success callback.
            // An immediately returning provider may beat that generation's completion handler.
            // Wait outside the edit lock; never treat an in-flight successor as the old job.
            originatingJob?.join()
            session.withRefSuspend {
                session.orbisPromptEditMutex.withLock {
                    if (!mayPublish() || !matchesRequest(session.state.value)) return@withLock
                    if (!conversationRepo.applyGeneratedSuggestionsIfUnchanged(conversation, suggestions)) return@withLock
                    // A RAM-only edit or queued generation can arrive during Room IO. Publish
                    // only this field against the still-current snapshot, never a whole window.
                    synchronized(session) {
                        session.state.update { current ->
                            if (mayPublish() && matchesRequest(current)) current.copy(chatSuggestions = suggestions)
                            else current
                        }
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            // Suggestions are optional. Failure must not pause a queue or expose private text.
        }
    }

    // ---- Human-confirmed whole-node batch edits; no generation or queue dispatch ----

    private suspend fun requireMessageBatchReadyLocked(session: ConversationSession) {
        requireWritableHistoryLocked(session)
        val call = OrbisVoiceCallRuntime.get(context).callState.value
        check(call.conversationId != session.id || (!call.isActive && !call.ending)) {
            "请结束当前通话并等记录保存完成，再批量整理消息。"
        }
        // Independent archivers preserve their own immutable source. Do not edit while one is
        // taking that source snapshot; no archive job is cancelled or retried by this action.
        check(voiceArchiveJobs.values.none { !it.isCompleted }) {
            "通话记录正在归档，请等归档结束后再批量整理消息。"
        }
    }

    suspend fun previewMessageBatch(
        conversationId: Uuid,
        nodeIds: Set<Uuid>,
        operation: me.rerere.rikkahub.data.model.OrbisMessageBatchOperation,
    ): me.rerere.rikkahub.data.model.OrbisMessageBatchPreview = withContext(Dispatchers.Main.immediate) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId, selectAssistant = false)
            session.orbisPromptEditMutex.withLock {
                requireMessageBatchReadyLocked(session)
                val snapshot = session.state.value
                val selected = nodeIds.toSet()
                withContext(Dispatchers.Default) {
                    me.rerere.rikkahub.data.model.prepareOrbisMessageBatch(snapshot, selected, operation)
                }
            }
        }
    }

    suspend fun applyMessageBatch(
        conversationId: Uuid,
        preview: me.rerere.rikkahub.data.model.OrbisMessageBatchPreview,
    ): me.rerere.rikkahub.data.model.OrbisMessageBatchResult = withContext(Dispatchers.Main.immediate) {
        folderMutationMutex.withLock {
            require(preview.conversationId == conversationId) { "预览不属于当前窗口，请重新选择。" }
            val session = getOrCreateSession(conversationId)
            session.withRefSuspend {
                initializeConversation(conversationId, selectAssistant = false)
                session.orbisPromptEditMutex.withLock {
                    requireMessageBatchReadyLocked(session)
                    val current = synchronized(session) {
                        check(session.getJob() == null && session.submittingMessage == null &&
                            !session.manualContextWriteInProgress) { "对话忙碌，请稍后重新选择并确认。" }
                        requireCompactionEpoch(preview.compactionEpoch, session.state.value.compactionEpoch)
                        session.manualContextWriteInProgress = true
                        session.state.value
                    }
                    try {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            val committed = withContext(Dispatchers.IO) {
                                compactionRepository.commitMessageBatch(current, preview)
                            }
                            // Room and FTS are durable before publishing. No generic save path:
                            // it could resume a paused queue, or make stale history visible first.
                            session.state.value = committed.conversation
                            session.isInitialized = true
                            committed.result
                        }
                    } finally {
                        synchronized(session) { session.manualContextWriteInProgress = false }
                    }
                }
            }
        }
    }

    // ---- Explicit human context rescue; no provider call or queue dispatch ----

    suspend fun previewManualContext(
        conversationId: Uuid,
        archiveThroughCount: Int,
        summary: String,
    ): me.rerere.rikkahub.data.model.OrbisManualContextPreview = withContext(Dispatchers.Main.immediate) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId, selectAssistant = false)
            session.orbisPromptEditMutex.withLock {
                synchronized(session) {
                    check(session.getJob() == null && session.submittingMessage == null) {
                        "请等当前回复结束或停止生成，再预览手动整理。"
                    }
                }
                val snapshot = session.state.value
                withContext(Dispatchers.Default) {
                    me.rerere.rikkahub.data.model.prepareOrbisManualContext(snapshot, archiveThroughCount, summary)
                }
            }
        }
    }

    suspend fun applyManualContext(
        conversationId: Uuid,
        preview: me.rerere.rikkahub.data.model.OrbisManualContextPreview,
    ): Uuid = withContext(Dispatchers.Main.immediate) { folderMutationMutex.withLock {
        require(preview.conversationId == conversationId) { "预览不属于当前窗口，请重新预览。" }
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId, selectAssistant = false)
            session.orbisPromptEditMutex.withLock {
                val current = synchronized(session) {
                    check(session.getJob() == null && session.submittingMessage == null) {
                        "正在生成或提交消息，本次未整理；请等结束后重新预览。"
                    }
                    requireCompactionEpoch(preview.compactionEpoch, session.state.value.compactionEpoch)
                    session.manualContextWriteInProgress = true
                    session.state.value
                }
                // One transaction preserves a separate original archive AND shrinks this page.
                // No saveConversation: that generic path would dispatch the paused old queue.
                try { withContext(kotlinx.coroutines.NonCancellable) {
                    val result = withContext(Dispatchers.IO) {
                        compactionRepository.commitManual(current, preview.replacementNodes, preview.metadata,
                            preview.expectedFingerprint)
                    }
                    session.state.value = result.commit.conversation
                    session.isInitialized = true
                    result.archiveId
                } } finally { synchronized(session) { session.manualContextWriteInProgress = false } }
            }
        }
    } }

    // ---- AI-authored, reversible active-context compaction ----

    private fun createCompactionControl(conversationId: Uuid, thresholdTokens: Int) = ConversationCompactionControl(
        thresholdTokens = thresholdTokens,
        readMessages = { getConversationFlow(conversationId).value.currentMessages },
        readHistory = { JsonInstant.encodeToString(getCompactionHistory(conversationId)) },
        commit = { before, replacement ->
            val session = getOrCreateSession(conversationId)
            session.orbisPromptEditMutex.withLock {
                val current = session.state.value
                fun UIMessage.withoutCallOwnership() = copy(orbisVoiceCallId = null, orbisVoiceCallKind = null)
                check(current.currentMessages.map { it.withoutCallOwnership() } == before.map { it.withoutCallOwnership() }) {
                    "活动消息已变化，本次未整理；请基于最新上下文重试。"
                }
                val baseline = conversationRepo.getConversationById(conversationId)
                    ?: error("原对话已不存在，本次未整理。")
                requireCompactionEpoch(current.compactionEpoch, baseline.compactionEpoch)
                // Original selected messages and alternatives keep their node IDs. Only the new
                // summary creates a node; complete tool outputs are committed in the same page.
                val hostById = current.currentMessages.associateBy { it.id }
                val replacementMessages = replacement.messages.map { message ->
                    val host = hostById[message.id]
                    if (host != null && !message.isCompactionSummary()) message.copy(
                        orbisVoiceCallId = host.orbisVoiceCallId, orbisVoiceCallKind = host.orbisVoiceCallKind) else message
                }
                val finalById = replacementMessages.associateBy { it.id }
                val originalWithReceipt = current.copy(messageNodes = current.messageNodes.map { node ->
                    val finalMessage = finalById[node.currentMessage.id]
                    if (finalMessage == null) node else node.copy(messages = node.messages.map {
                        if (it.id == finalMessage.id) it.copy(parts = finalMessage.parts) else it
                    })
                })
                val replacementNodes = replacementMessages.map { message ->
                    originalWithReceipt.getMessageNodeByMessageId(message.id)?.let { node ->
                        node.copy(messages = node.messages.map { if (it.id == message.id) message else it })
                    } ?: message.toMessageNode()
                }
                val committed = compactionRepository.commit(
                    expected = originalWithReceipt,
                    replacementNodes = replacementNodes,
                    metadata = OrbisCompactionMetadata(
                        summaryMessageId = replacement.summary.id,
                        summaryText = replacement.summary.toText(),
                        keepRecent = replacement.keepRecent,
                        beforeTokens = replacement.beforeTokens,
                        afterTokens = replacement.afterTokens,
                        beforeBasis = "previous_provider_usage_when_valid_plus_estimate",
                        afterBasis = "estimated_text_and_tools_not_exact_provider_tokens",
                        eventId = replacement.eventId,
                        createdAtEpochMillis = replacement.createdAtEpochMillis,
                    ),
                    persistedBaseline = baseline,
                )
                // Never send archived files through ordinary deletion cleanup.
                session.state.value = committed.conversation
                session.isInitialized = true
                AppliedCompaction(committed.conversation.currentMessages, committed.conversation.compactionEpoch)
            }
        },
    )

    /** Current AI's metadata across its windows. Archive bodies are not exposed by this API. */
    suspend fun getCompactionHistory(conversationId: Uuid): List<OrbisCompactionEvent> {
        initializeConversation(conversationId, selectAssistant = false)
        val current = getConversationFlow(conversationId).value
        return compactionRepository.listHistory(current.assistantId)
    }

    suspend fun getLatestCompactionRollback(conversationId: Uuid): Pair<OrbisCompactionEvent?, Long?> {
        initializeConversation(conversationId, selectAssistant = false)
        val session = getOrCreateSession(conversationId)
        return session.orbisPromptEditMutex.withLock {
            val current = session.state.value
            val latest = compactionRepository.latestRollback(conversationId, current.assistantId)
            val projected = if (!session.isGenerating) compactionRepository.projectedRollbackNodes(current) else null
            val overhead = if (projected != null) compactionFixedOverhead(current) else 0
            latest to projected?.let { estimateCompactionTokens(it.map(MessageNode::currentMessage)) + overhead }
        }
    }

    private suspend fun compactionFixedOverhead(conversation: Conversation): Long {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId)
        val model = settings.findModelById(assistant?.chatModelId ?: settings.chatModelId)
        val toolText = try {
            if (assistant == null || model == null) "" else
                chatToolFactory.createTools(settings, assistant, model, conversation.workspaceCwd, conversation.id.toString())
                    .joinToString("\n") { tool ->
                        val schema = runCatching { tool.parameters().toString() }.getOrDefault("")
                        tool.name + tool.description + schema
                    }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            "" // Local rollback is never gated on a remote tool's name, connection or schema health.
        }
        val prompt = assistant?.systemPrompt.orEmpty() + conversation.customSystemPrompt.orEmpty() +
            JsonInstant.encodeToString(conversation.orbisPrompt)
        return estimateCompactionTextTokens(prompt + toolText)
    }

    suspend fun rollbackLatestCompaction(conversationId: Uuid): Result<Unit> = runCatching {
        initializeConversation(conversationId, selectAssistant = false)
        val session = getOrCreateSession(conversationId)
        session.orbisPromptEditMutex.withLock {
            check(!session.isGenerating && session.getJob() == null) { "请等当前回复结束或停止生成，再撤销整理。" }
            val current = session.state.value
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(current.assistantId) ?: error("当前AI已不存在。")
            val overhead = compactionFixedOverhead(current)
            withContext(kotlinx.coroutines.NonCancellable) {
                val result = compactionRepository.rollbackLatest(current, assistant.compactionThresholdTokens,
                    estimateTokens = { nodes -> estimateCompactionTokens(nodes.map(MessageNode::currentMessage)) + overhead })
                session.state.value = result.conversation
                session.isInitialized = true
            }
        }
    }

    suspend fun deleteCompactionHistory(conversationId: Uuid, eventId: Uuid): Result<Unit> = runCatching {
        initializeConversation(conversationId, selectAssistant = false)
        val session = getOrCreateSession(conversationId)
        session.orbisPromptEditMutex.withLock {
            check(compactionRepository.deleteHistory(session.state.value.assistantId, eventId)) { "记录已删除或不属于当前AI。" }
        }
    }

    // ---- Legacy compress-model path (not used by compact) ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        // Orbis must not reach the legacy destructive path before reversible archives exist.
        // Keep this guard before settings, model requests, session updates or attachment cleanup.
        requireLegacyCompressionAvailable(BuildConfig.ORBIS_ENABLED)
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        restoreCommittedPrompt: Boolean = false,
    ) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
        if (session.manualContextWriteInProgress) {
            addError(IllegalStateException("正在保存原文存档和整理上下文，本次编辑未应用，请完成后重试。"), conversationId)
            return
        }
        if (!restoreCommittedPrompt && session.isInitialized) {
            requireCompactionEpoch(conversation.compactionEpoch, session.state.value.compactionEpoch)
        }
        val beforeUpdate = session.state.value
        session.state.update { current ->
            if (restoreCommittedPrompt) conversation else withCommittedVoiceNotePlayed(me.rerere.rikkahub.data.model.withCommittedEventPresentation(
                withCommittedOrbisPrompt(me.rerere.rikkahub.data.model.withCommittedToolRecordEdits(conversation, current), current), current,
            ), current)
        }
        checkFilesDelete(session.state.value, beforeUpdate)
        }
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /** Read the live session rather than a UI/paging snapshot; preserve all other current fields. */
    suspend fun saveOrbisPrompt(conversationId: Uuid, prompt: OrbisConversationPrompt) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId)
            session.orbisPromptEditMutex.withLock {
                check(!session.isGenerating) { "请等本次回复结束，或停止生成后再保存提示词" }
                persistOrbisPromptEdit(session.state, prompt, conversationRepo::saveOrbisPrompt)
            }
        }
    }

    /** A card changes only durable presentation flags, never the conversation/message snapshot. */
    suspend fun saveOrbisEventPresentation(
        conversationId: Uuid,
        edit: me.rerere.rikkahub.data.model.OrbisEventPresentationEdit,
        expectedAssistantId: Uuid? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId, selectAssistant = false)
            session.orbisPromptEditMutex.withLock {
                // Presentation is local history, not delivery. Forks/imported chats retain the
                // original provenance but may have no inbox receipt or a different target ID.
                // The live owner and existing DB row must still match, including exact stored
                // node/message identity, provenance, role and payload. No wake/replay is possible.
                requireWritableHistoryLocked(session)
                val owner = session.state.value.assistantId
                check(expectedAssistantId == null || owner == expectedAssistantId) { "窗口归属已变化，请重新选择" }
                persistOrbisEventPresentation(session.state, owner, edit) {
                    conversationRepo.saveOrbisEventPresentation(conversationId, owner, edit)
                }
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 与手动归档及删除文件夹串行；已有 session 同时持有提示词编辑锁，
     * 数据库更新与内存发布不可被取消或整对象保存打断。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) = folderMutationMutex.withLock {
        require(folderId == null || folderRepository.getFolderById(folderId) != null) { "目标文件夹已不存在，请重新选择。" }
        val session = sessions[conversationId]
        if (session == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
        } else session.withRefSuspend {
            session.orbisPromptEditMutex.withLock {
                withContext(kotlinx.coroutines.NonCancellable) {
                    conversationRepo.updateConversationFolderId(conversationId, folderId)
                    updateConversationState(conversationId) { it.copy(folderId = folderId) }
                }
            }
        }
    }

    /** Same live-session synchronization as folder moves; never saves a paged history snapshot. */
    suspend fun renameConversation(conversationId: Uuid, value: String): Boolean {
        val title = me.rerere.rikkahub.data.model.normalizeConversationTitle(value)
        val session = sessions[conversationId]
        if (session == null) return conversationRepo.renameConversation(conversationId, title)
        return session.withRefSuspend {
            session.orbisPromptEditMutex.withLock {
                withContext(kotlinx.coroutines.NonCancellable) {
                    val updated = conversationRepo.renameConversation(conversationId, title)
                    if (updated) updateConversationState(conversationId) { it.withManualTitle(title) }
                    updated
                }
            }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 与移动及手动归档串行；锁定已有关联会话后清库并发布到内存。
     * 等待期间新加载的会话也会清除归属，且不能同时提交手动归档。
     */
    suspend fun deleteFolder(folderId: Uuid) = folderMutationMutex.withLock {
        val affected = sessions.values.filter { it.state.value.folderId == folderId }.sortedBy { it.id.toString() }
        val locked = mutableListOf<ConversationSession>()
        try {
            for (session in affected) {
                session.orbisPromptEditMutex.lock()
                locked.add(session)
            }
            withContext(kotlinx.coroutines.NonCancellable) {
                folderRepository.deleteFolder(folderId)
                // The SQL clear includes a manual archive created while we waited for its source.
                sessions.values.filter { it.state.value.folderId == folderId }
                    .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
            }
        } finally { locked.asReversed().forEach { it.orbisPromptEditMutex.unlock() } }
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        if (newConversation.messageNodes === oldConversation.messageNodes) return
        // Streaming updates normally share every old node except the tail. Inspect only changed
        // nodes for potential removals; scan global references only when a local file could vanish.
        val newNodesById by lazy { newConversation.messageNodes.associateBy { it.id } }
        val changedOld = oldConversation.messageNodes.filterIndexed { index, old ->
            val aligned = newConversation.messageNodes.getOrNull(index)
            val replacement = if (aligned?.id == old.id) aligned else newNodesById[old.id]
            replacement !== old
        }
        val oldFiles = oldConversation.copy(messageNodes = changedOld).files
        if (oldFiles.isEmpty()) return
        val session = sessions[newConversation.id]
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                session?.automaticWakeQueue?.pending.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                val unreferenced = deletedFiles.filterNot { conversationRepo.hasExternalFileReference(it.toString(), newConversation.id) }
                if (unreferenced.isNotEmpty()) filesManager.deleteChatFiles(unreferenced)
            }
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.orbisPromptEditMutex.withLock {
                saveConversationLocked(session, conversation)
            }
        }

        // Re-check only after the history transaction and session publication have completed.
        dispatchNextQueuedMessage(conversationId)
    }

    /** Caller holds this session's edit mutex. Does not dispatch or reacquire the mutex. */
    private suspend fun saveConversationLocked(session: ConversationSession, conversation: Conversation) {
        val conversationId = session.id
        require(conversation.id == conversationId)
        if (session.isInitialized) requireCompactionEpoch(conversation.compactionEpoch, session.state.value.compactionEpoch)
        val exists = conversationRepo.existsConversationById(conversation.id)
        requireWholeConversationSaveReady(exists, session.isInitialized)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }
        // New forks/import-style sessions seed their inherited prompt. All subsequent
        // whole-state saves preserve the newest committed prompt, never a stale snapshot.
        val committed = if (session.isInitialized) session.state.value else conversation
        val updatedConversation = withCommittedVoiceNotePlayed(me.rerere.rikkahub.data.model.withCommittedEventPresentation(
            withCommittedOrbisPrompt(me.rerere.rikkahub.data.model.withCommittedToolRecordEdits(conversation, committed), committed), committed,
        ), committed)
        val eventOwner = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }?.orbisEvent?.recordId
            ?.let { orbisEvents.inbox.get(it) }?.takeIf { it.conversationId == conversationId.toString() }
            ?.let { Uuid.parse(it.assistantId) }
        // Preserve the existing message-update timing; only the prompt's commit ordering
        // is changed by this feature. Initial forks/DB loads may seed their committed field.
        if (eventOwner != null) {
            // Atomic existing-owner check: queued events must never resurrect deleted chats.
            conversationRepo.updateConversation(updatedConversation, requireExistingOwner = eventOwner)
            updateConversation(conversationId, updatedConversation, restoreCommittedPrompt = !session.isInitialized)
        } else if (!exists) {
            updateConversation(conversationId, updatedConversation, restoreCommittedPrompt = !session.isInitialized)
            conversationRepo.insertConversation(updatedConversation)
        } else {
            updateConversation(conversationId, updatedConversation, restoreCommittedPrompt = !session.isInitialized)
            conversationRepo.updateConversation(updatedConversation)
        }
        session.isInitialized = true
    }

    // ---- 翻译消息 ----

    /** History changes cannot invalidate the protected prefix of an in-flight or retained journal. */
    private suspend fun <T> withWritableHistory(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            initializeConversation(conversationId, selectAssistant = false)
            session.orbisPromptEditMutex.withLock {
                requireWritableHistoryLocked(session)
                block(session)
            }
        }
    }

    /** Caller retains the edit mutex through the following write; new jobs wait for that mutex. */
    private suspend fun requireWritableHistoryLocked(session: ConversationSession) {
        fun busy() = synchronized(session) {
            session.getJob() != null || session.submittingMessage != null || session.manualContextWriteInProgress
        }
        requireHistoryMutationReady(busy(), session.generationRecoveryBlocked) {
            withContext(Dispatchers.IO) { generationJournal.hasCheckpoint(session.id) }
        }
        // A generation may be registered while checking disk. It must win before any RAM edit.
        requireHistoryMutationReady(busy(), session.generationRecoveryBlocked) { false }
    }

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            var publishedTranslation = message.translation
            var translationStarted = false
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message, publishedTranslation, loadingText)
                publishedTranslation = loadingText
                translationStarted = true

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ).collect { translatedText ->
                    // The suspending collector can guard each update; the provider callback cannot.
                    updateTranslationField(conversationId, message, publishedTranslation, translatedText)
                    publishedTranslation = translatedText
                }

                updateTranslationField(conversationId, message, publishedTranslation, publishedTranslation, persist = true)
                dispatchNextQueuedMessage(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Never clear an unrelated translation or mutate history after generation started.
                if (translationStarted) runCatching {
                    updateTranslationField(conversationId, message, publishedTranslation, message.translation, persist = true)
                }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private suspend fun updateTranslationField(
        conversationId: Uuid,
        expected: UIMessage,
        expectedTranslation: String?,
        translationText: String?,
        persist: Boolean = false,
    ) = withWritableHistory(conversationId) { session ->
        val current = session.state.value
        val updated = current.withTranslationIfUnchanged(expected, expectedTranslation, translationText)
        if (persist) saveConversationLocked(session, updated) else updateConversation(conversationId, updated)
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val edited = withWritableHistory(conversationId) { session ->
            val settings = settingsStore.settingsFlow.first()
            val ownerId = session.state.value.assistantId
            val assistant = settings.getAssistantById(ownerId)
                ?: settings.getCurrentAssistant()
            val processedParts = preprocessUserInputParts(parts, assistant)
            requireWritableHistoryLocked(session)
            val currentConversation = session.state.value
            check(currentConversation.assistantId == ownerId) { "当前 AI 已变化，请重新编辑消息。" }
            var edited = false

            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node
                }
                edited = true

                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                        orbisQuote = node.messages.first { it.id == messageId }.orbisQuote,
                        orbisUserMessageTime = node.messages.first { it.id == messageId }.orbisUserMessageTime,
                    ),
                    selectIndex = node.messages.size
                )
            }
            if (edited) saveConversationLocked(session, currentConversation.copy(messageNodes = updatedNodes))
            edited
        }
        if (edited) dispatchNextQueuedMessage(conversationId)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            },
                            deletedToolRecords = message.deletedToolRecords.map { record ->
                                record.copy(tool = record.tool.copyWithForkedFileUrl() as UIMessagePart.Tool)
                            },
                        ).reanchorDeletedToolRecords()
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val changed = withWritableHistory(conversationId) { session ->
            val currentConversation = session.state.value
            val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")

            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@withWritableHistory false

            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
            }
            saveConversationLocked(session, currentConversation.copy(messageNodes = updatedNodes))
            true
        }
        if (changed) dispatchNextQueuedMessage(conversationId)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val changed = withWritableHistory(conversationId) { session ->
            val currentConversation = session.state.value
            val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)
            if (updatedConversation == null) {
                if (failIfMissing) throw NotFoundException("Message not found")
                return@withWritableHistory false
            }
            saveConversationLocked(session, updatedConversation)
            true
        }
        if (changed) dispatchNextQueuedMessage(conversationId)
    }

    suspend fun deleteToolRecord(conversationId: Uuid, messageId: Uuid, toolCallId: String) =
        editToolRecord(conversationId, me.rerere.rikkahub.data.model.OrbisToolRecordEdit(messageId, toolCallId, restore = false))

    /** Narrow presentation edit. Do not invalidate a protected generation journal to mark playback. */
    suspend fun saveVoiceNotePlayed(conversationId: Uuid,
        edit: me.rerere.rikkahub.data.model.OrbisVoiceNotePlayedEdit, expectedAssistantId: Uuid? = null) {
        sessions[conversationId]?.getJob()?.join()
        withWritableHistory(conversationId) { session ->
            val current = session.state.value
            expectedAssistantId?.let { check(current.assistantId == it) { "语音条所属 AI 已改变，未修改。" } }
            val node = current.messageNodes.singleOrNull { it.id == edit.nodeId } ?: return@withWritableHistory
            val target = node.messages.singleOrNull { it.id == edit.messageId } ?: return@withWritableHistory
            val changed = edit.applyTo(target)
            if (changed === target) return@withWritableHistory
            val updated = current.copy(messageNodes = current.messageNodes.map { item ->
                if (item.id == node.id) item.copy(messages = item.messages.map { if (it.id == target.id) changed else it }) else item
            })
            withContext(kotlinx.coroutines.NonCancellable) {
                conversationRepo.updateConversation(updated, requireExistingOwner = current.assistantId)
                updateConversation(conversationId, updated)
            }
        }
    }

    suspend fun restoreToolRecord(conversationId: Uuid, messageId: Uuid, toolCallId: String) =
        editToolRecord(conversationId, me.rerere.rikkahub.data.model.OrbisToolRecordEdit(messageId, toolCallId, restore = true))

    private suspend fun editToolRecord(
        conversationId: Uuid,
        edit: me.rerere.rikkahub.data.model.OrbisToolRecordEdit,
    ) {
        withWritableHistory(conversationId) { session ->
            val now = kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            persistOrbisToolRecordEdit(session.state, edit, now) { expected ->
                conversationRepo.saveToolRecordEdit(expected, edit, now)
            }
        }
        // This never replays the restored tool. Only unrelated already-queued messages may proceed.
        dispatchNextQueuedMessage(conversationId)
        Unit
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Tool -> copy(output = output.map { it.copyWithForkedFileUrl() })
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        // Capture what the tap saw; a delayed coroutine must not clear a newer translation.
        val expected = getConversationFlow(conversationId).value.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }.singleOrNull { it.id == messageId } ?: return
        appScope.launch {
            try {
                updateTranslationField(conversationId, expected, expected.translation, null, persist = true)
                dispatchNextQueuedMessage(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                addError(error, conversationId, title = context.getString(R.string.error_title_operation))
            }
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(
        conversationId: Uuid,
        reason: HostToolFailure = HostToolFailure.INTERRUPTED,
    ) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        jobs.forEach { it.join() }
        // Waiting for approval can outlive the generation job. Explicit stop must
        // still finish pending tool UI even when there is no job left to cancel.
        finishInterruptedPendingTools(conversationId, reason)
    }
}
