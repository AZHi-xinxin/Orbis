package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.orbis.privateroom.hasPrivateRoomToolContent

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.model.orbisStartupContentReady
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.pages.orbis.OrbisChatDock
import me.rerere.rikkahub.ui.pages.orbis.OrbisManualEntry
import me.rerere.rikkahub.ui.pages.orbis.OrbisContextBudgetButton
import me.rerere.rikkahub.data.model.deriveOrbisContextBudget
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import me.rerere.rikkahub.data.ai.compaction.CompactionContextEstimate
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.pages.orbis.OrbisCompactionUiState
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.ui.pages.orbis.OrbisHeaderIconButton
import me.rerere.rikkahub.ui.pages.orbis.OrbisChatBackdrop
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.data.model.orbisChatUsesLightHeader
import me.rerere.rikkahub.data.model.orbisChatDrawerVisible
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.InjectionQuickConfigSheet
import me.rerere.rikkahub.ui.components.ai.CompressContextDialog
import me.rerere.rikkahub.data.model.OrbisComposerAction
import me.rerere.rikkahub.data.model.dispatchOrbisCapability
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null,
    onDrawerVisibilityChange: (Boolean) -> Unit = {},
    onStartupReady: (() -> Unit)? = null,
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    if (conversation.isConsultation) {
        Text(if (me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled)
            "这是咨询室会话，请从咨询室查看公开段或使用已授权的开发者查验入口。"
        else "正在开发，暂未开放")
        return
    }
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    var manualContextOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(conversation.messageNodes, conversation.compactionEpoch) {
        vm.invalidateManualContextPreview()
    }
    if (manualContextOpen && BuildConfig.ORBIS_ENABLED) {
        me.rerere.rikkahub.ui.pages.orbis.OrbisManualContextSheet(
            conversation = conversation,
            busy = vm.manualContextBusy || loadingJob != null,
            preview = vm.manualContextPreview,
            error = vm.manualContextError,
            archiveId = vm.manualContextArchiveId,
            onPreview = vm::previewManualContext,
            onApply = vm::applyManualContext,
            onInvalidate = vm::invalidateManualContextPreview,
            onDismiss = { manualContextOpen = false; vm.resetManualContext() },
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    var drawerWidthPx by remember { mutableIntStateOf(0) }
    val reportDrawerVisibility by rememberUpdatedState(onDrawerVisibilityChange)
    val drawerVisible = orbisChatDrawerVisible(isBigScreen, drawerState.isOpen,
        drawerState.targetValue == DrawerValue.Open, drawerState.currentOffset, drawerWidthPx)
    SideEffect { reportDrawerVisibility(drawerVisible) }
    DisposableEffect(id) {
        val clearThisRoute = onDrawerVisibilityChange
        onDispose { clearThisRoute(false) }
    }

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)
    me.rerere.rikkahub.ui.pages.orbis.OrbisVoiceCallOverlay(vm.voiceRuntime,
        setting.getAssistantById(conversation.assistantId), vm.voiceCalls,
        currentConversationId = conversation.id,
        summaryCallIds = conversation.messageNodes.mapNotNull { node ->
            node.currentMessage.takeIf { it.orbisVoiceCallKind != null }?.orbisVoiceCallId
        }.toSet())

    val inputState = vm.inputState
    DisposableEffect(vm) {
        onDispose { inputState.orbisQuote = null }
    }

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    val bottomFollowState = remember(chatListState, conversation.id) { ChatBottomFollowState() }
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    bottomFollowState.stopForNavigation()
                    chatListState.scrollToItem(orbisTimelineIndex(conversation.messageNodes, index, BuildConfig.ORBIS_ENABLED))
                }
            } else {
                bottomFollowState.followBottom()
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    onManualContext = { vm.resetManualContext(); manualContextOpen = true },
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    bottomFollowState = bottomFollowState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onStartupReady = onStartupReady,
                )
            }
        }

        else -> {
            // Drawer opens from the right; only its container uses RTL. Chat and drawer
            // content retain the actual language direction, not mirrored text/actions.
            val contentDirection = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides
                if (BuildConfig.ORBIS_ENABLED) LayoutDirection.Rtl else contentDirection) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                    Box(Modifier.onSizeChanged { drawerWidthPx = it.width }) {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        onClose = { scope.launch { drawerState.close() } },
                        drawerOpen = drawerState.isOpen,
                    )
                    }
                    }
                }
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    onManualContext = { vm.resetManualContext(); manualContextOpen = true },
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    bottomFollowState = bottomFollowState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onStartupReady = onStartupReady,
                )
                }
            }
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    onManualContext: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    bottomFollowState: ChatBottomFollowState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onStartupReady: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val batchVoiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val assistant = setting.getCurrentAssistant()
    val startupAppearance = setting.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
    val startupInheritBackground = !startupAppearance.backgroundEnabled &&
        (assistant.background != null || assistant.useGradientBackground)
    val startupImage = when {
        BuildConfig.ORBIS_ENABLED && !startupInheritBackground ->
            startupAppearance.backgroundImage?.takeIf { startupAppearance.backgroundEnabled && it.isNotBlank() }
        assistant.useGradientBackground -> null
        else -> assistant.background?.toString()
    }
    var startupImageSettled by remember(startupImage) { mutableStateOf(startupImage == null) }
    var startupLaidOut by remember { mutableStateOf(false) }
    val currentStartupImage by rememberUpdatedState(startupImage)
    val reportStartupReady by rememberUpdatedState(onStartupReady)
    val imageSettled: (() -> Unit)? = if (onStartupReady == null) null else remember(startupImage) {
        { if (currentStartupImage == startupImage) startupImageSettled = true }
    }
    // Settings and Conversation initially contain placeholders. A completed repository load
    // alone is not enough: wait until this UI has collected the actual current snapshots.
    if (onStartupReady != null) LaunchedEffect(setting, conversation, vm.initialLoadSettled,
        vm.initialLoadFailed, startupImageSettled, startupLaidOut) {
        fun contentReady() = orbisStartupContentReady(!setting.init, vm.initialLoadSettled,
            vm.initialLoadFailed, setting === vm.settings.value && conversation === vm.conversation.value,
            setting.getCurrentAssistant().id == conversation.assistantId, startupImageSettled, startupLaidOut)
        if (contentReady()) {
            withFrameNanos { }
            withFrameNanos { }
            if (contentReady()) reportStartupReady?.invoke()
        }
    }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showOrbisExtensions by remember { mutableStateOf(false) }
    var showOrbisPrompt by rememberSaveable(conversation.id.toString()) { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        setting.getCurrentChatModel()?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize().then(if (onStartupReady != null)
            Modifier.onGloballyPositioned { startupLaidOut = it.size.width > 0 && it.size.height > 0 } else Modifier)
    ) {
        if (BuildConfig.ORBIS_ENABLED) {
            val appearance = setting.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
            val inheritBackground = !appearance.backgroundEnabled &&
                (assistant.background != null || assistant.useGradientBackground)
            if (inheritBackground) AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState),
                onImageSettled = imageSettled)
            OrbisChatBackdrop(appearance = appearance, drawBackground = !inheritBackground,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState), onImageSettled = imageSettled)
            // A translucent status-area scrim protects white system icons on arbitrary photos;
            // neither it nor the floating controls forms an opaque title/status bar.
            val variableBackground = inheritBackground ||
                (appearance.backgroundEnabled && !appearance.backgroundImage.isNullOrBlank())
            OrbisStatusBarScrim(
                visible = variableBackground &&
                    orbisChatUsesLightHeader(appearance, LocalDarkMode.current, inheritBackground),
            )
        } else AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState),
            onImageSettled = imageSettled)
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    compactionState = vm.compactionUiState,
                    onOpenCompaction = vm::refreshCompactionState,
                    onSaveCompactionThreshold = vm::saveCompactionThreshold,
                    onDeleteCompactionEvent = vm::deleteCompactionEvent,
                    onRollbackCompaction = vm::rollbackLatestCompaction,
                    onManualContext = onManualContext,
                    isGenerating = loadingJob != null,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val gatewayStopNotice by vm.gatewayStopNotice.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                Column(if (BuildConfig.ORBIS_ENABLED) Modifier.navigationBarsPadding().imePadding() else Modifier) {
                ChatInput(
                    onStartVoiceMode = onStartVoiceMode,
                    voiceState = voiceState,
                    onStopVoiceMode = vm.voiceRuntime::hangUp,
                    state = inputState,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    onStopGatewayWait = vm::stopGeneration,
                    gatewayStopNotice = gatewayStopNotice,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onUpdateSearchMode = { mode ->
                        val current = setting.getCurrentAssistant()
                        val model = setting.getCurrentChatModel()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                                    } else {
                                        assistant
                                    }
                                },
                                providers = if (model == null) {
                                    setting.providers
                                } else {
                                    setting.providers.map { provider ->
                                        provider.editModel(
                                            model.copy(
                                                tools = if (mode == SearchMode.BUILT_IN) {
                                                    model.tools + BuiltInTools.Search
                                                } else {
                                                    model.tools - BuiltInTools.Search
                                                }
                                            )
                                        )
                                    }
                                },
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            navController.navigate(Screen.SettingProvider)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            if (!vm.handleMessageSend(inputState.getContents(), orbisQuote = inputState.orbisQuote)) return@ChatInput
                            bottomFollowState.followForSend(chatListState.isScrollInProgress)
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            if (!vm.handleMessageSend(content = inputState.getContents(), answer = false,
                                orbisQuote = inputState.orbisQuote)) return@ChatInput
                            bottomFollowState.followForSend(chatListState.isScrollInProgress)
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    onOrbisPromptClick = { showOrbisPrompt = true },
                    orbisPromptActive = conversation.orbisPrompt.isActive(),
                    onOrbisSendSticker = { id ->
                        vm.sendOrbisSticker(id).also { accepted ->
                            if (accepted) bottomFollowState.followForSend(chatListState.isScrollInProgress)
                        }
                    },
                    onOrbisSendTextEmotion = { text ->
                        vm.sendOrbisTextEmotion(text).also { accepted ->
                            if (accepted) bottomFollowState.followForSend(chatListState.isScrollInProgress)
                        }
                    },
                    onOrbisCapability = { action ->
                        dispatchOrbisCapability(
                            action = action,
                            takePicture = attachmentPickerActions.onTakePicture,
                            pickImage = attachmentPickerActions.onPickImage,
                            pickFile = attachmentPickerActions.onPickFile,
                            openMcpSettings = { navController.navigate(Screen.OrbisMcpSettings) { launchSingleTop = true } },
                            openContext = onManualContext,
                            openExtensions = { showOrbisExtensions = true },
                        )
                    },
                )
                if (BuildConfig.ORBIS_ENABLED && !WindowInsets.isImeVisible) OrbisChatDock()
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                bottomFollowState = bottomFollowState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    // Never put hidden original content in an editable draft, or save a redacted
                    // presentation over the assistant's original tool/context record.
                    if (!it.hasPrivateRoomToolContent()) {
                        inputState.orbisQuote = null // Editing keeps the original message's quote, not a new draft quote.
                        inputState.editingMessage = it.id
                        inputState.setContents(it.parts)
                    }
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onEventPresentation = { edit -> vm.saveOrbisEventPresentation(conversation.id, edit) },
                onDeleteToolRecord = { messageId, toolCallId ->
                    scope.launch {
                        try {
                            vm.deleteToolRecord(messageId, toolCallId)
                            toaster.show("已删除工具记录，可在该回复下撤销")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            toaster.show(error.message ?: "删除失败，原记录未变", type = ToastType.Error)
                        }
                    }
                },
                onRestoreToolRecord = { messageId, toolCallId ->
                    scope.launch {
                        try {
                            vm.restoreToolRecord(messageId, toolCallId)
                            toaster.show("工具记录已恢复，不会重新执行")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            toaster.show(error.message ?: "恢复失败", type = ToastType.Error)
                        }
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
                },
                onVoiceNotePlayed = vm::saveVoiceNotePlayed,
                onClickSuggestion = { suggestion ->
                    inputState.orbisQuote = null
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    bottomFollowState.stopForNavigation()
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(orbisTimelineIndex(conversation.messageNodes, index, BuildConfig.ORBIS_ENABLED))
                    }
                },
                onToolApproval = { toolCallId, approved, reason, remember ->
                    vm.handleToolApproval(toolCallId, approved, reason, remember)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                historyMutationDisabledReason = when {
                    loadingJob != null -> "正在生成，请等待当前回复结束。"
                    batchVoiceState.isActive -> "正在通话，请结束通话后再整理消息。"
                    else -> null
                },
                onPreviewMessageBatch = vm::previewMessageBatch,
                onApplyMessageBatch = vm::applyMessageBatch,
                onQuote = { node ->
                    try {
                        inputState.orbisQuote = vm.prepareQuotedReply(node)
                    } catch (error: Exception) {
                        toaster.show(error.message ?: "无法引用这条消息", type = ToastType.Error)
                    }
                },
                onQuoteJump = { quote ->
                    val index = if (quote.sourceConversationId == conversation.id)
                        conversation.messageNodes.indexOfFirst { it.id == quote.sourceNodeId &&
                            it.messages.any { message -> message.id == quote.sourceMessageId } } else -1
                    if (index < 0) {
                        toaster.show("原消息不在当前窗口或已删除，引用正文快照仍保留。")
                    } else {
                        bottomFollowState.stopForNavigation()
                        previewMode = false
                        chatListState.requestScrollToItem(orbisTimelineIndex(conversation.messageNodes, index, BuildConfig.ORBIS_ENABLED))
                        if (conversation.messageNodes[index].currentMessage.id != quote.sourceMessageId)
                            toaster.show("已定位消息；当前显示另一个回答，引用仍是当时选中的正文。")
                    }
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (BuildConfig.ORBIS_ENABLED && showOrbisPrompt) {
            OrbisConversationPromptSheet(
                conversationId = conversation.id.toString(),
                prompt = conversation.orbisPrompt,
                generating = loadingJob != null,
                composerOpacity = setting.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).composerOpacity,
                onSave = vm::saveOrbisPrompt,
                onDismiss = { showOrbisPrompt = false },
                assistant = setting.assistants.firstOrNull { it.id == conversation.assistantId },
                onSaveGenerationParameters = vm::saveOrbisGenerationParameters,
            )
        }
        if (BuildConfig.ORBIS_ENABLED && showOrbisExtensions) {
            InjectionQuickConfigSheet(
                conversation = conversation, assistant = assistant, settings = setting,
                onUpdateAssistant = { updated ->
                    vm.updateSettings(setting.copy(assistants = setting.assistants.map { if (it.id == updated.id) updated else it }))
                },
                onUpdateConversation = { updated -> vm.updateConversation(updated); vm.saveConversationAsync() },
                onDismiss = { showOrbisExtensions = false },
                onDismissAll = { showOrbisExtensions = false },
            )
        }
        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            // The starter performs the shared local preflight and explains a missing setting.
            // Do not hide this entry solely because a provider uses local rather than server VAD.
            onStartVoiceMode = if (
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    compactionState: OrbisCompactionUiState,
    onOpenCompaction: () -> Unit,
    onSaveCompactionThreshold: (Int) -> Unit,
    onDeleteCompactionEvent: (Uuid) -> Unit,
    onRollbackCompaction: () -> Unit,
    onManualContext: () -> Unit,
    isGenerating: Boolean,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    if (BuildConfig.ORBIS_ENABLED) {
        var manualVisible by rememberSharedPreferenceBoolean("orbis_chat_manual_visible", true)
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        // A 350K conversation must not be re-serialized on the main thread for every streamed token.
        val estimate by produceState<CompactionContextEstimate?>(null, conversation.id,
            conversation.compactionEpoch, model?.id, isGenerating,
            if (isGenerating) null else conversation.messageNodes) {
            value = null
            if (!isGenerating) value = withContext(Dispatchers.Default) {
                estimateCurrentContext(conversation.currentMessages, model?.id)
            }
        }
        val budget = remember(conversation.messageNodes, conversation.compactionEpoch, model?.id, model?.modelId,
            assistant.compactionThresholdTokens, isGenerating, estimate) {
            deriveOrbisContextBudget(
                currentMessages = conversation.currentMessages,
                currentModelId = model?.id,
                referenceLimit = model?.modelId?.let { ModelRegistry.MODEL_CONTEXT_LENGTH.getData(it) },
                isGenerating = isGenerating,
                reminderThresholdTokens = assistant.compactionThresholdTokens,
                currentUsageTokens = estimate?.tokens,
                currentUsageSource = estimate?.basis,
                currentEstimatePending = !isGenerating && estimate == null,
            )
        }
        val lightHeader = orbisChatUsesLightHeader(settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current),
            LocalDarkMode.current, assistant.background != null || assistant.useGradientBackground)
        OrbisVisualTheme(darkTheme = lightHeader) {
        Column {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable { titleState.open(conversation.title) }, contentAlignment = Alignment.CenterStart) {
                    Text(conversation.title.ifBlank { "和${assistant.name.ifBlank { "AI" }}说话" },
                        modifier = Modifier.background(OrbisTheme.colors.raisedPanel.copy(alpha = .72f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1,
                        color = OrbisTheme.colors.ink, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = OrbisTheme.colors.raisedPanel.copy(alpha = .72f), shape = CircleShape) {
                    OrbisContextBudgetButton(
                        budget = budget,
                        modelLabel = model?.let { it.displayName.ifBlank { it.modelId } } ?: "未选择",
                        compactionState = compactionState.copy(busy = compactionState.busy || isGenerating),
                        onSaveThreshold = onSaveCompactionThreshold,
                        onDeleteEvent = onDeleteCompactionEvent,
                        onRollbackLatest = onRollbackCompaction,
                        onOpen = onOpenCompaction,
                        onManualContext = onManualContext.takeIf { conversation.messageNodes.isNotEmpty() },
                    )
                }
                OrbisHeaderIconButton(onClick = onClickMenu) {
                    Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                        "切换对话目录")
                }
                if (!bigScreen) OrbisHeaderIconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(HugeIcons.Menu03, "打开右侧会话栏")
                }
            }
        if (manualVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OrbisManualEntry(Modifier.weight(1f))
            IconButton(onClick = { manualVisible = false }, modifier = Modifier.size(48.dp)) {
                Icon(HugeIcons.Cancel01, "关闭说明书提示", modifier = Modifier.size(16.dp))
            }
        }
        }
        }
    } else TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
