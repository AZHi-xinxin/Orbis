package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.orbis.OrbisStickers
import me.rerere.rikkahub.data.model.orbisStickerImageParts
import me.rerere.rikkahub.data.model.OrbisStickerSendNotice
import me.rerere.rikkahub.data.model.isOrbisStickerTapDebounced
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.quoteMessage
import me.rerere.rikkahub.data.model.OrbisGenerationParameterEdit
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.orbis.OrbisCompactionUiState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部
    internal var initialLoadSettled by mutableStateOf(false)
        private set
    internal var initialLoadFailed by mutableStateOf(false)
        private set

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()
    private val stickerSendMutex = Mutex()
    private val favoriteEditMutex = Mutex()
    private var lastStickerAcceptedAt: Long? = null

    internal var compactionUiState by mutableStateOf(OrbisCompactionUiState())
        private set

    private suspend fun readCompactionState() {
        val owner = conversation.value.assistantId
        val (history, rollback) = withContext(Dispatchers.IO) {
            chatService.getCompactionHistory(_conversationId) to chatService.getLatestCompactionRollback(_conversationId)
        }
        val (latest, projected) = rollback
        if (conversation.value.assistantId != owner) return
        compactionUiState = compactionUiState.copy(history = history, latestRollback = latest,
            projectedRollbackTokens = projected, loading = false, error = null)
    }

    fun refreshCompactionState() {
        if (compactionUiState.loading || compactionUiState.busy) return
        compactionUiState = compactionUiState.copy(loading = true, error = null)
        viewModelScope.launch {
            try { readCompactionState() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                compactionUiState = compactionUiState.copy(loading = false,
                    error = error.message ?: "暂时无法读取整理记录。")
            }
        }
    }

    private fun runCompactionUiAction(action: suspend () -> Unit) {
        if (compactionUiState.busy) return
        compactionUiState = compactionUiState.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action(); readCompactionState() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                compactionUiState = compactionUiState.copy(error = error.message ?: "操作未完成，原对话保留。")
            } finally { compactionUiState = compactionUiState.copy(busy = false, loading = false) }
        }
    }

    private var thresholdSaveRevision = 0L
    fun saveCompactionThreshold(tokens: Int) {
        // Slider changes must not be dropped merely because the previous narrow save is in flight.
        val revision = ++thresholdSaveRevision
        val owner = conversation.value.assistantId
        viewModelScope.launch {
            try {
                settingsStore.updateCompactionThreshold(owner, tokens)
                if (revision == thresholdSaveRevision) compactionUiState = compactionUiState.copy(error = null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (revision == thresholdSaveRevision) compactionUiState = compactionUiState.copy(
                    error = error.message ?: "阈值未保存，请重试。")
            }
        }
    }

    fun deleteCompactionEvent(eventId: Uuid) = runCompactionUiAction {
        chatService.deleteCompactionHistory(_conversationId, eventId).getOrThrow()
    }

    fun rollbackLatestCompaction() = runCompactionUiAction {
        chatService.rollbackLatestCompaction(_conversationId).getOrThrow()
    }

    // Large previews stay in the VM, never Android saved-state/Bundle or a provider request.
    internal var manualContextPreview by mutableStateOf<me.rerere.rikkahub.data.model.OrbisManualContextPreview?>(null)
        private set
    internal var manualContextBusy by mutableStateOf(false)
        private set
    internal var manualContextError by mutableStateOf<String?>(null)
        private set
    internal var manualContextArchiveId by mutableStateOf<Uuid?>(null)
        private set

    fun resetManualContext() {
        if (manualContextBusy) return
        invalidateManualContextPreview()
        manualContextArchiveId = null
    }

    /** The immutable VM window ID, not whichever conversation the UI later navigates to. */
    suspend fun previewMessageBatch(
        nodeIds: Set<Uuid>,
        operation: me.rerere.rikkahub.data.model.OrbisMessageBatchOperation,
    ): me.rerere.rikkahub.data.model.OrbisMessageBatchPreview =
        chatService.previewMessageBatch(_conversationId, nodeIds, operation)

    suspend fun applyMessageBatch(
        preview: me.rerere.rikkahub.data.model.OrbisMessageBatchPreview,
    ): me.rerere.rikkahub.data.model.OrbisMessageBatchResult {
        require(preview.conversationId == _conversationId) { "批量预览不属于当前窗口，请重新选择。" }
        return chatService.applyMessageBatch(_conversationId, preview)
    }

    fun invalidateManualContextPreview() {
        if (manualContextBusy) return
        manualContextPreview = null
        manualContextError = null
    }

    fun previewManualContext(archiveThroughCount: Int, summary: String) {
        if (manualContextBusy) return
        manualContextBusy = true
        manualContextPreview = null
        manualContextError = null
        manualContextArchiveId = null
        viewModelScope.launch {
            try {
                manualContextPreview = chatService.previewManualContext(_conversationId, archiveThroughCount, summary)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                manualContextError = failure.message ?: "预览未完成，原对话未改变。"
            } finally { manualContextBusy = false }
        }
    }

    fun applyManualContext() {
        val preview = manualContextPreview ?: return
        if (manualContextBusy) return
        manualContextBusy = true
        manualContextError = null
        viewModelScope.launch {
            try {
                manualContextArchiveId = chatService.applyManualContext(_conversationId, preview)
                manualContextPreview = null
                // Refresh is ancillary: a failed metadata read must not turn a successful
                // durable commit into an apparent failure and invite a second compaction.
                runCatching { readCompactionState() }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                manualContextPreview = null
                manualContextError = failure.message ?: "整理未完成，请重新预览；原文保留。"
            } finally { manualContextBusy = false }
        }
    }

    val voiceRuntime = me.rerere.rikkahub.service.OrbisVoiceCallRuntime.get(context)
    val voiceSession get() = voiceRuntime.voiceSession
    val voiceCalls get() = chatService.voiceCalls

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the session uninitialized: never replace an unread history with empty.
                initialLoadFailed = true
                chatService.addError(
                    IllegalStateException("对话加载失败，原记录未改动。请返回后重试。"),
                    conversationId = _conversationId,
                    title = "无法完整打开对话",
                )
            }
            // Visual readiness only; cancelled loads remain unsettled, errors reveal the existing error UI.
            initialLoadSettled = true
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)
    val gatewayStopNotice = chatService.gatewayStopNotice(_conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val messageQueueRecovery = chatService.messageQueueRecoveryState(_conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            me.rerere.rikkahub.service.QueueRecoveryState())

    fun dismissQueueRecoveryResult(expected: me.rerere.rikkahub.service.QueueRecoveryState) =
        chatService.dismissQueueRecoveryResult(_conversationId, expected)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    fun dismissPauseAndContinueFreshInput() = chatService.dismissPauseAndContinueFreshInput(_conversationId)

    fun saveVoiceNotePlayed(edit: me.rerere.rikkahub.data.model.OrbisVoiceNotePlayedEdit) {
        viewModelScope.launch {
            try { chatService.saveVoiceNotePlayed(_conversationId, edit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                chatService.addError(IllegalStateException("语音已播放，但已听标记未确认保存。"),
                    _conversationId, title = "语音条状态")
            }
        }
    }

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true,
        orbisQuote: me.rerere.ai.ui.OrbisMessageQuote? = null): Boolean {
        if (content.isEmptyInputMessage()) return false
        return try {
            chatService.sendMessage(_conversationId, content, answer, orbisQuote)
        } catch (error: Exception) {
            chatService.addError(error, _conversationId, title = "引用未发送，草稿保留")
            false
        }
    }

    fun prepareQuotedReply(node: MessageNode): me.rerere.ai.ui.OrbisMessageQuote {
        check(!inputState.isEditing()) { "请先完成或取消当前编辑，再引用回复。" }
        return conversation.value.quoteMessage(node.id, node.currentMessage.id)
    }

    /** Returns false when busy. The existing draft (including edits and attachments) is untouched. */
    suspend fun sendOrbisSticker(id: String): Boolean {
        if (isOrbisStickerTapDebounced(android.os.SystemClock.elapsedRealtime(), lastStickerAcceptedAt)) return false
        if (!stickerSendMutex.tryLock()) return false
        var attachment: android.net.Uri? = null
        var accepted = false
        try {
            if (inputState.isEditing() || conversationJob.value != null || voiceSession.state.value.isActive)
                return false
            val model = stickerChatModel()
                ?: throw OrbisStickerSendNotice("请先选择聊天模型，再点表情发送。")
            if (Modality.IMAGE !in model.inputModalities) throw OrbisStickerSendNotice(
                "当前模型未启用图片输入。请选择支持图片的模型；没有发送文字替代，也没有改动草稿。")
            val image = withContext(Dispatchers.IO) { OrbisStickers.open(context).imageForSend(id) }
            // Never send the library object directly: deleting a chat attachment must not delete
            // the shared sticker or invalidate another conversation's history.
            val mime = "image/${image.sticker.format}"
            withContext(NonCancellable) {
                val file = filesManager.saveManagedFromBytes(FileFolders.UPLOAD, image.bytes,
                    "${image.sticker.id}.${image.sticker.format}", mime)
                attachment = filesManager.getFile(file).toUri()
            }
            currentCoroutineContext().ensureActive()
            // Recheck after IO; a voice turn, edit or model switch may have happened meanwhile.
            if (inputState.isEditing() || voiceSession.state.value.isActive ||
                stickerChatModel() != model) return false
            accepted = chatService.trySendOrbisSticker(_conversationId,
                orbisStickerImageParts(attachment.toString()))
            if (accepted) lastStickerAcceptedAt = android.os.SystemClock.elapsedRealtime()
            return accepted
        } finally {
            try {
                if (!accepted) attachment?.let { filesManager.deleteChatFiles(listOf(it)) }
            } finally {
                stickerSendMutex.unlock()
            }
        }
    }

    fun sendOrbisTextEmotion(text: String): Boolean {
        if (inputState.isEditing() || voiceSession.state.value.isActive ||
            stickerChatModel() == null || stickerSendMutex.isLocked ||
            isOrbisStickerTapDebounced(android.os.SystemClock.elapsedRealtime(), lastStickerAcceptedAt)) return false
        return chatService.trySendOrbisSticker(_conversationId, listOf(UIMessagePart.Text(text))).also {
            if (it) lastStickerAcceptedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun stickerChatModel(): Model? = settings.value.let { current ->
        val assistant = current.getAssistantById(conversation.value.assistantId) ?: current.getCurrentAssistant()
        current.findModelById(assistant.chatModelId ?: current.chatModelId)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            try {
                chatService.editMessage(_conversationId, messageId, parts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = context.getString(R.string.error_title_operation))
            }
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return viewModelScope.launch {
            chatService.compressConversation(
                _conversationId,
                conversation.value,
                additionalPrompt,
                targetTokens,
                keepRecentMessages
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            try {
                chatService.deleteMessage(_conversationId, message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = context.getString(R.string.error_title_operation))
            }
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            try {
                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = context.getString(R.string.error_title_operation))
            }
        }
    }

    /** UI owns the snackbar/toast; errors stay visible rather than becoming silent success. */
    suspend fun deleteToolRecord(messageId: Uuid, toolCallId: String) {
        chatService.deleteToolRecord(_conversationId, messageId, toolCallId)
    }

    suspend fun restoreToolRecord(messageId: Uuid, toolCallId: String) {
        chatService.restoreToolRecord(_conversationId, messageId, toolCallId)
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        remember: Boolean = false,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason, remember = remember)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            try {
                chatService.stopGeneration(_conversationId, HostToolFailure.USER_CANCELLED, stopGatewayWait = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                chatService.addError(IllegalStateException("停止或核对未完成，队列保持暂停；未自动重发消息或工具。"), _conversationId)
            }
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    suspend fun saveOrbisPrompt(prompt: me.rerere.rikkahub.data.model.OrbisConversationPrompt) {
        chatService.saveOrbisPrompt(_conversationId, prompt)
    }

    suspend fun saveOrbisEventPresentation(
        conversationId: Uuid,
        edit: me.rerere.rikkahub.data.model.OrbisEventPresentationEdit,
    ) {
        chatService.saveOrbisEventPresentation(conversationId, edit)
    }

    suspend fun saveOrbisGenerationParameters(edit: OrbisGenerationParameterEdit): OrbisGenerationParameters {
        check(conversation.value.assistantId == edit.assistantId) { "当前 AI 已变化，请重新打开对话设置。" }
        check(chatService.getGenerationJobStateFlow(_conversationId).first() == null) {
            "请等本次回复结束，或停止生成后再保存参数。"
        }
        return persistOrbisGenerationParameters(edit, settingsStore::update)
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            favoriteEditMutex.withLock {
            try {
            val current = conversation.value
            check(!current.isConsultation && conversationJob.value == null) { "请等待当前回复结束再收藏。" }
            val selected = current.messageNodes.singleOrNull { it.id == node.id }
                ?: error("原消息已不存在。")
            check(selected.currentMessage.id == node.currentMessage.id) { "选中回答已改变，请重新收藏。" }
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = current.title,
                        nodeId = node.id,
                        node = selected
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                chatService.addError(error, _conversationId, title = "收藏未改变")
            }
            }
        }
    }

}
