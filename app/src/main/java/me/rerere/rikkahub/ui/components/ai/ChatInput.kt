package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.OrbisComposerPanel
import me.rerere.rikkahub.data.model.OrbisComposerAction
import me.rerere.rikkahub.data.model.OrbisDictationDraft
import me.rerere.rikkahub.data.model.toggleOrbisComposerPanel
import me.rerere.rikkahub.service.OrbisVoiceCallPreflight
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import kotlin.uuid.Uuid

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    enableSearch: Boolean,
    onUpdateSearchMode: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    onResumeMessageQueue: () -> Unit = {},
    onStopGatewayWait: () -> Unit = {},
    gatewayStopNotice: String? = null,
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    onOrbisCapability: ((OrbisComposerAction) -> Unit)? = null,
    onOrbisPromptClick: (() -> Unit)? = null,
    orbisPromptActive: Boolean = false,
    onOrbisSendSticker: (suspend (String) -> Boolean)? = null,
    onOrbisSendTextEmotion: ((String) -> Boolean)? = null,
) {
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val hazeTintColor = MaterialTheme.colorScheme.surfaceContainerLow
    val inputHazeStyle = HazeBlurStyle.Material3 {
        blurRadius(12.dp)
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val orbis = BuildConfig.ORBIS_ENABLED
    val composerColor = if (orbis) OrbisTheme.colors.raisedPanel.copy(
        alpha = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).composerOpacity,
    ) else if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor
    val navController = LocalNavController.current
    var composerPanel by remember { mutableStateOf<OrbisComposerPanel?>(null) }
    var voiceNoteOpen by remember { mutableStateOf(false) }
    var composerExpanded by rememberSharedPreferenceBoolean("orbis_composer_expanded", false)
    val tts = LocalTTSState.current
    val ttsAvailable by tts.isAvailable.collectAsState()
    val ttsSpeaking by tts.isSpeaking.collectAsState()
    val containerShape = if (orbis) RoundedCornerShape(23.dp) else MaterialTheme.shapes.largeIncreased
    val modelListState = rememberModelListState(
        modelId = assistant.chatModelId ?: settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) {
            onCancelClick()
        } else if (settings.getCurrentChatModel() == null) {
            modelListState.open()
        } else {
            onSendClick()
        }
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val voiceUnavailableReason = when {
        voiceState.phase != VoicePhase.Off -> "已有通话正在进行或收尾，请先结束当前通话。"
        loading -> "当前回复尚未结束，请完成或停止本轮后再发起通话。"
        asrState.isRecording || asrState.status == ASRStatus.Connecting || asrState.status == ASRStatus.Stopping ->
            "请先结束当前录音或识别，再发起通话。"
        messageQueue.paused -> "消息队列已暂停，请先检查并恢复队列。"
        messageQueue.messages.isNotEmpty() -> "消息队列尚未处理完，请稍后再发起通话。"
        onStartVoiceMode == null -> "当前页面暂不能发起通话，请回到聊天页面。"
        else -> OrbisVoiceCallPreflight.firstFailure(settings, assistant)?.explanation
    }
    val asrCorrectionNotice by asr.correctionNotice.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    val dictationDraft = remember(state) { OrbisDictationDraft() }
    var ownsManualDictation by remember { mutableStateOf(false) }
    fun closeManualDictation() {
        dictationDraft.cancel()
        if (ownsManualDictation) {
            ownsManualDictation = false
            if (asr.state.value.status == ASRStatus.Connecting || asr.state.value.status == ASRStatus.Listening) {
                asr.stop()
            }
        }
    }
    DisposableEffect(asr, state) {
        onDispose { if (orbis) closeManualDictation() }
    }
    fun toggleAsr() {
        when (asrState.status) {
            ASRStatus.Listening -> asr.stop()
            ASRStatus.Idle, ASRStatus.Error -> {
                if (!asrPermission.allRequiredPermissionsGranted) {
                    asrPermission.requestPermissions()
                } else {
                    asrBaseText = state.textContent.text.toString()
                    val ticket = dictationDraft.begin(asrBaseText)
                    ownsManualDictation = true
                    asr.start { transcript ->
                        val update = dictationDraft.update(ticket, state.textContent.text.toString(), transcript)
                        if (update.stopCapture) closeManualDictation()
                        update.text?.let(state::setMessageText)
                    }
                }
            }
            ASRStatus.Connecting, ASRStatus.Stopping -> {}
        }
    }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = if (orbis) 12.dp else 8.dp)
                .padding(bottom = if (orbis) 2.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (orbis) AsrCorrectionReview(
                review = asrCorrectionNotice.review,
                eventId = asrCorrectionNotice.eventId,
                dismissed = asrCorrectionNotice.dismissed,
                onDismiss = { asr.dismissCorrectionNotice(asrCorrectionNotice.eventId) },
            )
            MessageQueuePanel(
                state = messageQueue,
                onRemove = onRemoveQueuedMessage,
                onBeginEdit = onBeginEditQueuedMessage,
                onFinishEdit = onFinishEditQueuedMessage,
                onResume = onResumeMessageQueue,
                onStopGatewayWait = onStopGatewayWait,
                gatewayStopNotice = gatewayStopNotice,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .then(
                        if (!orbis && settings.displaySetting.enableBlurEffect) Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = inputHazeStyle,
                        )
                        else Modifier
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                shadowElevation = if (orbis) 6.dp else 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = composerColor,
                contentColor = if (orbis) OrbisTheme.colors.ink else contentColorFor(composerColor),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    state.orbisQuote?.let { quote ->
                        me.rerere.rikkahub.ui.components.message.OrbisQuotePreview(
                            quote = quote, onCancel = { state.orbisQuote = null },
                        )
                    }
                    if (voiceState.phase != VoicePhase.Off) {
                        VoiceModeRow(
                            state = voiceState,
                            onStop = onStopVoiceMode,
                            onRetry = { onStartVoiceMode?.invoke() },
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }
                    if (loading) KeepScreenOn()

                    Row(verticalAlignment = Alignment.Top) {
                        if (orbis) IconButton(onClick = {
                            val expanded = !composerExpanded
                            composerExpanded = expanded
                            if (!expanded) { closeManualDictation(); composerPanel = null }
                        }, modifier = Modifier.size(48.dp).testTag("orbis-composer-expand")) {
                            Icon(if (composerExpanded) HugeIcons.Cancel01 else HugeIcons.Add01,
                                if (composerExpanded) "收起输入功能" else "展开输入功能", modifier = Modifier.size(19.dp))
                        }
                        TextInputRow(
                            state = state,
                            modifier = Modifier.weight(1f),
                            completionProviders = completionProviders,
                            onSendMessage = { sendMessage() },
                        )
                        if (orbis) {
                            AnimatedVisibility(visible = !asrState.isRecording,
                                enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                SendButton(loading = loading, empty = state.isEmpty(),
                                    onClick = { sendMessage() },
                                    onLongClick = { sendMessageWithoutAnswer() },
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }

                    if (!orbis || composerExpanded) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Model Picker
                            ModelSelectorButton(
                                state = modelListState,
                                onlyIcon = true,
                                compact = orbis,
                                modifier = Modifier,
                            )

                            // Search
                            val enableSearchMsg = stringResource(R.string.web_search_enabled)
                            val disableSearchMsg = stringResource(R.string.web_search_disabled)
                            val chatModel = settings.getCurrentChatModel()
                            SearchPickerButton(
                                compact = orbis,
                                enableSearch = enableSearch,
                                settings = settings,
                                onUpdateSearchMode = { mode ->
                                    onUpdateSearchMode(mode)
                                    val enabled = mode != SearchMode.OFF
                                    toaster.show(
                                        message = if (enabled) enableSearchMsg else disableSearchMsg,
                                        duration = 1.seconds,
                                        type = if (enabled) {
                                            ToastType.Success
                                        } else {
                                            ToastType.Normal
                                        }
                                    )
                                },
                                onUpdateSearchService = onUpdateSearchService,
                                model = chatModel,
                            )

                            // Reasoning
                            val model = settings.getCurrentChatModel()
                            if (model?.abilities?.contains(ModelAbility.REASONING) == true) {
                                ReasoningButton(
                                    compact = orbis,
                                    reasoningLevel = assistant.reasoningLevel,
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                    },
                                    onlyIcon = true,
                                )
                            }

                        }

                        if (!orbis) ActionIconButton(
                            onClick = onMoreClick
                        ) {
                            Icon(
                                imageVector = HugeIcons.Add01,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }

                        if (!orbis && !voiceState.isActive && (asrState.isAvailable || asrState.isRecording)) {
                            AsrButton(
                                state = asrState,
                                onClick = {
                                    when (asrState.status) {
                                        ASRStatus.Listening -> asr.stop()
                                        ASRStatus.Idle, ASRStatus.Error -> {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                asrBaseText = state.textContent.text.toString()
                                                asr.start { transcript ->
                                                    val spacer =
                                                        if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                    state.setMessageText(asrBaseText + spacer + transcript)
                                                }
                                            }
                                        }

                                        ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !orbis && !asrState.isRecording,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            SendButton(
                                loading = loading,
                                empty = state.isEmpty(),
                                onClick = { sendMessage() },
                                onLongClick = { sendMessageWithoutAnswer() },
                            )
                        }
                    }
                    if (orbis && composerExpanded) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OrbisComposerPanel.entries.forEach { panel ->
                                    OrbisComposerTab(panel.title, panel == composerPanel) {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        val next = toggleOrbisComposerPanel(composerPanel, panel)
                                        if (next != OrbisComposerPanel.VOICE) closeManualDictation()
                                        composerPanel = next
                                    }
                                }
                                if (onOrbisPromptClick != null) {
                                OrbisComposerTab("对话设置", selected = orbisPromptActive) {
                                        closeManualDictation()
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        composerPanel = null
                                        onOrbisPromptClick()
                                    }
                                }
                            }
                            OrbisComposerTab("后台设置", selected = true) {
                                closeManualDictation()
                                navController.navigate(Screen.OrbisPhone) { launchSingleTop = true }
                            }
                        }
                        when (composerPanel) {
                            OrbisComposerPanel.CAPABILITIES -> OrbisCapabilityPanel { action ->
                                if (action == OrbisComposerAction.TOOLS) composerPanel = null
                                onOrbisCapability?.invoke(action)
                                    ?: toaster.show(message = "这个入口尚未接通。", type = ToastType.Normal)
                            }
                            OrbisComposerPanel.EMOTIONS -> OrbisStickerPanel(
                                onSendImage = onOrbisSendSticker,
                                onSendText = onOrbisSendTextEmotion,
                                sendEnabled = !loading && !state.isEditing() && !voiceState.isActive &&
                                    !asrState.isRecording && messageQueue.messages.isEmpty(),
                            )
                            OrbisComposerPanel.VOICE -> OrbisVoicePanel(
                                canRecognize = asrState.isAvailable || asrState.isRecording,
                                recording = asrState.isRecording,
                                busy = asrState.status == ASRStatus.Connecting || asrState.status == ASRStatus.Stopping,
                                canStartVoice = voiceUnavailableReason == null,
                                voiceUnavailableReason = voiceUnavailableReason,
                                batchVoiceMode = settings.getSelectedASRProvider().let {
                                    it is ASRProviderSetting.MiMo || it is ASRProviderSetting.Step
                                },
                                canSpeak = ttsAvailable,
                                speaking = ttsSpeaking,
                                autoRead = settings.displaySetting.autoPlayTTSAfterGeneration,
                                onRecognize = ::toggleAsr,
                                onStartVoice = {
                                    closeManualDictation()
                                    onStartVoiceMode?.invoke()
                                },
                                onStopVoice = onStopVoiceMode,
                                voiceActive = voiceState.isActive,
                                onSpeak = { tts.speak("我在这里。这是当前音色的试听。") },
                                onStopSpeaking = tts::stop,
                                onConfigure = {
                                    closeManualDictation()
                                    navController.navigate(Screen.SettingSpeech) { launchSingleTop = true }
                                },
                                onVoiceNote = {
                                    closeManualDictation()
                                    if (!asrPermission.allRequiredPermissionsGranted) asrPermission.requestPermissions()
                                    else voiceNoteOpen = true
                                },
                                canRecordNote = !loading && !voiceState.isActive && !asrState.isRecording && !state.isEditing(),
                            )
                            null -> Unit
                        }
                    }
                }
            }

        }
    }

    if (voiceNoteOpen) OrbisVoiceNoteRecorder(asr, onDismiss = { voiceNoteOpen = false },
        onDraft = { state.messageContent = state.messageContent + it })
    ModelListSheet(
        state = modelListState,
        onSelect = onUpdateChatModel,
    )
}

@Composable
private fun SendButton(
    loading: Boolean,
    empty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStop = loading && empty
    val containerColor = when {
        showStop -> MaterialTheme.colorScheme.errorContainer
        empty -> if (BuildConfig.ORBIS_ENABLED) OrbisTheme.colors.accent else MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        showStop -> MaterialTheme.colorScheme.onErrorContainer
        empty -> if (BuildConfig.ORBIS_ENABLED) OrbisTheme.colors.onAccent.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(if (BuildConfig.ORBIS_ENABLED) 48.dp else 30.dp)
            .testTag("chat_send_button")
            .clip(if (BuildConfig.ORBIS_ENABLED) RoundedCornerShape(16.dp) else CircleShape)
            .combinedClickable(
                enabled = showStop || !empty,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Surface(
            // Keep the outer 48dp hit target while matching the prototype's
            // compact 40dp send surface. Semantics/cancel behavior stay real.
            modifier = if (BuildConfig.ORBIS_ENABLED) Modifier.size(40.dp) else Modifier.fillMaxSize(),
            shape = if (BuildConfig.ORBIS_ENABLED) RoundedCornerShape(14.dp) else CircleShape,
            color = containerColor,
            content = {},
        )
        Icon(
            imageVector = if (showStop) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
            contentDescription = stringResource(if (showStop) R.string.stop else R.string.send),
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    val assistant = settings.getCurrentAssistant()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                state.addImages(
                                    filesManager.createChatFilesByContents(
                                        listOf(uri)
                                    )
                                )
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                val document = filesManager.createChatTextFile(text)
                                state.addFiles(listOf(document))
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        TextField(
            state = state.textContent,
            inputTransformation = if (BuildConfig.ORBIS_ENABLED) OrbisStickerDraftInputTransformation else null,
            outputTransformation = if (BuildConfig.ORBIS_ENABLED) OrbisStickerDraftOutputTransformation else null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                },
            shape = MaterialTheme.shapes.largeIncreased,
            textStyle = if (BuildConfig.ORBIS_ENABLED) MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp) else MaterialTheme.typography.bodyLarge,
            contentPadding = if (BuildConfig.ORBIS_ENABLED) PaddingValues(horizontal = 10.dp, vertical = 12.dp) else TextFieldDefaults.contentPaddingWithoutLabel(),
            placeholder = {
                Text(if (BuildConfig.ORBIS_ENABLED) "输入消息，与我聊聊…" else stringResource(R.string.chat_input_placeholder),
                    style = if (BuildConfig.ORBIS_ENABLED) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            },
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isFocused) {
                        IconButton(
                            onClick = {
                                isFullScreen = !isFullScreen
                            }) {
                            Icon(HugeIcons.Fullscreen, null)
                        }
                    }
                }
            },
            leadingIcon = if (!BuildConfig.ORBIS_ENABLED && quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(state = state) {
                isFullScreen = false
            }
        }
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        inputTransformation = if (BuildConfig.ORBIS_ENABLED) OrbisStickerDraftInputTransformation else null,
                        outputTransformation = if (BuildConfig.ORBIS_ENABLED) OrbisStickerDraftOutputTransformation else null,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
