package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.chat.conversationComposerWithModel
import coil3.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.OrbisVoiceNotePlayedEdit
import me.rerere.rikkahub.data.model.voiceNotePlaybackKey
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.orbis.GARDEN_QUICK_CHAT_MAX_TEXT_BYTES
import me.rerere.rikkahub.data.orbis.canClearGardenQuickChatDraft
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.components.message.OrbisChatMessageLayout
import me.rerere.rikkahub.ui.components.message.OrbisEventMessageCard
import me.rerere.rikkahub.ui.components.message.OrbisVoiceNoteRows
import me.rerere.rikkahub.ui.components.message.orbisVoiceNoteDisplay
import me.rerere.rikkahub.ui.components.message.rememberChatMessageTextStyle
import me.rerere.rikkahub.ui.components.richtext.LocalImportedHistory
import me.rerere.rikkahub.ui.components.richtext.isDeepSeekHistory
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/** Local AND self-hosted garden: native overlay, deliberately no JavaScript message bridge. */
@Composable
internal fun OrbisGardenQuickChat(visible: Boolean, ownerAssistantId: String? = null, openRequest: Int = 0, initialConversationId: String? = null, onConversationSelected: ((Conversation) -> Unit)? = null, tabletopPresentation: Boolean = false, onOpenChanged: ((Boolean) -> Unit)? = null) {
    val settingsStore = koinInject<SettingsStore>()
    val storedSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val owner = if (ownerAssistantId == null) storedSettings.assistantId
        else runCatching { Uuid.parse(ownerAssistantId) }.getOrNull() ?: return
    val settings = storedSettings.copy(assistantId = owner)
    val assistant = settings.getAssistantById(settings.assistantId) ?: return
    val repository = koinInject<ConversationRepository>()
    // Survives closing/changing the inner drawer. A receipt can clear only its own unchanged draft.
    val submissionScope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    val reportOpen by rememberUpdatedState(onOpenChanged)
    LaunchedEffect(open,visible) { reportOpen?.invoke(open && visible) }
    DisposableEffect(Unit) { onDispose { reportOpen?.invoke(false) } }
    var picking by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    // One bounded draft, not one full ChatVM/history per visited window. Closing never clears it.
    var draft by rememberSaveable { mutableStateOf("") }
    var imageDraft by rememberSaveable { mutableStateOf(listOf<String>()) }
    var draftRevision by rememberSaveable { mutableIntStateOf(0) }
    var pendingSelection by remember { mutableStateOf<Conversation?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun close() { focus.clearFocus(); keyboard?.hide(); open = false }
    fun select(value: Conversation) {
        selected = value.id.toString(); selectedOwner = value.assistantId.toString()
        title = value.title.ifBlank { "未命名聊天" }; draft = ""; imageDraft = emptyList(); draftRevision++; picking = false
        onConversationSelected?.invoke(value)
    }
    LaunchedEffect(initialConversationId, assistant.id) {
        if(selected==null && initialConversationId!=null) {
            repository.getConversationById(Uuid.parse(initialConversationId))?.takeIf { it.assistantId==assistant.id }?.let(::select)
        }
    }
    LaunchedEffect(openRequest) { if (openRequest > 0 && visible) { picking = selected == null || selectedOwner != assistant.id.toString(); open = true } }
    LaunchedEffect(visible) { if (!visible) close() }
    BackHandler(visible && open) { close() }
    if (!visible) return
    Box(Modifier.fillMaxSize().zIndex(3f)) {
        if (!open) Surface(
            modifier = Modifier.align(Alignment.CenterEnd).safeDrawingPadding(),
            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .91f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 2.dp,
        ) {
            Box(Modifier.width(28.dp).height(48.dp).testTag("garden-quick-chat-handle")
                .semantics { contentDescription = "展开与当前 AI 的快捷聊天" }
                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    picking = selected == null || selectedOwner != assistant.id.toString(); open = true
                }, contentAlignment = Alignment.Center) {
                Text("‹", fontSize = 26.sp)
            }
        }
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .19f))
                .clickable { close() }.testTag("garden-quick-chat-scrim"))
        }
        AnimatedVisibility(open, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
            GardenQuickChatTheme(settings,assistant.id) {
            val shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
            Surface(onClick = {}, modifier = Modifier.fillMaxHeight().fillMaxWidth(.92f).widthIn(max = 600.dp)
                .safeDrawingPadding().clip(shape)
                .testTag("garden-quick-chat-panel"), shape = shape,
                color = MaterialTheme.colorScheme.background,
                contentColor = OrbisTheme.colors.ink,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
            ) {
                // The same appearance/background resolution as the main chat. Never capture
                // the Android WebView underneath into a live blur layer: that invalidates its
                // hardware surface every frame on some devices, even after closing the drawer.
                OrbisCommunityBackdrop(settings, hostVisible = open) {
                Column(Modifier.fillMaxSize().imePadding()) {
                    Surface(color = OrbisTheme.colors.raisedPanel.copy(alpha = .72f),
                        contentColor = OrbisTheme.colors.ink) {
                    Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("快捷聊天", style = MaterialTheme.typography.titleMedium)
                            Text(if (picking) assistant.name.ifBlank { "当前 AI" } else title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!picking) TextButton(onClick = { picking = true }) { Text("换窗口") }
                        TextButton(onClick = { close() }) { Text("收起") }
                    }
                    Text("消息存回原窗口 · 只发送你输入的内容", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                    }
                    }
                    Box(Modifier.weight(1f)) {
                        if (picking) GardenChatPicker(assistant.id, repository, selected,
                            onSelect = { item ->
                                if (selected == item.id.toString()) picking = false
                                else if (draft.isNotBlank() || imageDraft.isNotEmpty()) pendingSelection = item else select(item)
                            }, onCancel = if (selectedOwner == assistant.id.toString() && selected != null) ({ picking = false }) else null)
                        else selected?.let { id -> key(id) {
                            val revision = draftRevision
                            GardenChatConversation(Uuid.parse(id), Uuid.parse(checkNotNull(selectedOwner)),
                                settings, draft, imageDraft, submissionScope, tabletopPresentation,
                                onImages = { if (selected == id) { imageDraft = it; draftRevision++ } },
                                onDraft = { if (selected == id) { draft = it; draftRevision++ } },
                                onCommitted = { text, sentImages ->
                                    if (canClearGardenQuickChatDraft(selected, id, draftRevision, revision, draft, text, sentImages.isNotEmpty()) && imageDraft == sentImages) {
                                        draft = ""; imageDraft = emptyList(); draftRevision++
                                    }
                                })
                        } }
                    }
                }
                }
            }
            }
        }
    }
    pendingSelection?.let { item -> AlertDialog(
        onDismissRequest = { pendingSelection = null }, title = { Text("切换聊天窗口？") },
        text = { Text("当前窗口有未发送的文字或图片。切换会丢弃这份草稿，已发送的聊天不受影响。") },
        confirmButton = { TextButton(onClick = { select(item); pendingSelection = null }) { Text("丢弃草稿并切换") } },
        dismissButton = { TextButton(onClick = { pendingSelection = null }) { Text("保留草稿") } },
    ) }
}

@Composable
private fun GardenChatPicker(owner: Uuid, repository: ConversationRepository, selected: String?,
    onSelect: (Conversation) -> Unit, onCancel: (() -> Unit)?) {
    var query by rememberSaveable(owner.toString()) { mutableStateOf("") }
    var offset by remember(owner, query) { mutableIntStateOf(0) }
    var next by remember(owner, query) { mutableStateOf<Int?>(null) }
    var items by remember(owner) { mutableStateOf<List<Conversation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner, query, offset, reload) {
        loading = true; failure = false; items = emptyList(); next = null
        try {
            val page = if (query.isBlank()) repository.getConversationsOfAssistantPage(owner, offset, 20)
                else repository.searchConversationsOfAssistantPage(owner, query, offset, 20)
            items = page.items.filter { it.assistantId == owner }; next = page.nextOffset
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(),
            label = { Text("选择这个 AI 的已有窗口") }, singleLine = true)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failure) TextButton(onClick = { reload++ }) { Text("窗口列表未读到，点击重读") }
        else if (!loading && items.isEmpty()) Text("没有符合条件的窗口。可先回主聊天创建，再来这里选择。", Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id.toString() }) { item ->
                Surface(onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = if (selected == item.id.toString())
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f) else Color.Transparent) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.title.ifBlank { "未命名聊天" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.updateAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { offset = (offset - 20).coerceAtLeast(0) }, enabled = offset > 0 && !loading) { Text("上一页") }
            TextButton(onClick = { next?.let { offset = it } }, enabled = next != null && !loading) { Text("下一页") }
        }
        onCancel?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("回到已选窗口") } }
    }
}

@Composable
private fun GardenChatConversation(id: Uuid, owner: Uuid, settings: Settings, draft: String, images: List<String>,
    submissionScope: CoroutineScope, tabletopPresentation:Boolean=false, onImages: (List<String>) -> Unit, onDraft: (String) -> Unit, onCommitted: (String, List<String>) -> Unit) {
    val service = koinInject<ChatService>()
    val settingsStore = koinInject<SettingsStore>()
    val repo = koinInject<ConversationRepository>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversationFlow = remember(id) { service.getDisplayedConversationFlow(id) }
    val conversation by conversationFlow.collectAsStateWithLifecycle()
    val jobs = remember(id) { service.getGenerationJobStateFlow(id) }
    val job by jobs.collectAsStateWithLifecycle(null)
    val queue by remember(id) { service.getMessageQueueFlow(id) }.collectAsStateWithLifecycle()
    val processing by remember(id) { service.getProcessingStatusFlow(id) }.collectAsStateWithLifecycle()
    val errors by service.errors.collectAsStateWithLifecycle()
    val voice by remember { OrbisVoiceCallRuntime.get(context).callState }.collectAsStateWithLifecycle()
    var ready by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var sendAttempt by remember { mutableIntStateOf(0) }
    var shown by rememberSaveable(id.toString()) { mutableIntStateOf(30) }
    var retrying by remember { mutableStateOf(false) }
    val assistant = settings.getAssistantById(owner)
    val deepSeek = LocalOrbisDeepSeekStyle.current
    val valid = ready && assistant != null && settings.assistantId == owner && conversation.assistantId == owner
    // Typing a draft does not need to rescan a long imported conversation on every keypress.
    val pending = remember(conversation.messageNodes) {
        conversation.currentMessages.any { m -> m.parts.any { it is UIMessagePart.Tool && it.isPending } }
    }
    val model = assistant?.let { settings.findModelById(it.chatModelId ?: settings.chatModelId) }
    val attachmentInput = remember(id) { ChatInputState().also { input -> input.messageContent = images.map { UIMessagePart.Image(it) } } }
    LaunchedEffect(images) { attachmentInput.messageContent = images.map { UIMessagePart.Image(it) } }
    val picker = rememberChatAttachmentPickerActions(attachmentInput, settings) {
        onImages(attachmentInput.messageContent.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }
    DisposableEffect(id) {
        service.addConversationReference(id)
        onDispose { service.removeConversationReference(id) } // Never stop the process-owned generation here.
    }
    LaunchedEffect(id, owner) {
        try {
            check(repo.getConversationSummaryOfAssistant(id, owner) != null)
            service.initializeDisplayedConversation(id)
            service.requireGardenQuickChatTarget(id, owner)
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "原窗口暂时无法完整读取。未新建、未发送，请重新选择。" }
    }
    fun approve(tool: String, approved: Boolean, reason: String = "", remember: Boolean = false, answer: String? = null) {
        if (!valid || submitting || voice.isActive) return
        scope.launch {
            try {
                service.requireGardenQuickChatTarget(id, owner)
                service.handleToolApproval(id, tool, approved, reason, answer, remember, expectedAssistantId = owner)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "窗口或授权已变化，未确认工具操作，请重新打开核对。" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!ready && notice == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ready && !valid) Text("当前 AI 或窗口归属已改变，请重新选择。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        if (valid) {
            val nodes = remember(conversation.messageNodes, shown) { conversation.messageNodes.takeLast(shown) }
            CompositionLocalProvider(LocalSettings provides settings.copy(assistantId = owner)) {
                GardenQuickChatTimeline(Modifier.weight(1f).fillMaxWidth(),
                    followOutput = settings.displaySetting.enableAutoScroll,
                    output = nodes.lastOrNull()?.currentMessage) {
                    item(key = "progress") { if (job != null) Text(processing ?: "正在回复…", style = MaterialTheme.typography.bodySmall) }
                    items(nodes.asReversed(), key = { it.id.toString() }) { node ->
                        val message = node.currentMessage
                        Column {
                        GardenQuickChatMessage(message, assistant, model,
                            loading = job != null && node == nodes.lastOrNull(),
                            onToolApproval = { tool, allowed, reason, remembered -> approve(tool, allowed, reason, remembered) },
                            onToolAnswer = { tool, answer -> approve(tool, true, answer = answer) },
                            onVoiceNotePlayed = { partIndex, outputIndex, playbackKey ->
                                scope.launch {
                                    try {
                                        service.requireGardenQuickChatTarget(id, owner)
                                        service.saveVoiceNotePlayed(id, OrbisVoiceNotePlayedEdit(node.id, message.id,
                                            partIndex, outputIndex, playbackKey), expectedAssistantId = owner)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { notice = "语音已播放，但已听标记未确认保存。" }
                                }
                            },
                            onEventPresentation = { metadata ->
                                service.requireGardenQuickChatTarget(id, owner)
                                service.saveOrbisEventPresentation(id, OrbisEventPresentationEdit(
                                    nodeId = node.id, messageId = message.id,
                                    expected = checkNotNull(message.orbisEvent), originalText = message.toText(),
                                    read = metadata.read, collapsed = metadata.collapsed,
                                ), expectedAssistantId = owner)
                            },
                            onEventOpacityChange = { settingsStore.updateOrbisEventOpacity(it, deepSeek) }, tabletopPresentation = tabletopPresentation)
                        if (message.orbisEvent == null && message.role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                            TextButton(enabled = !retrying && !submitting && job == null && queue.messages.isEmpty() && !pending && !voice.isActive,
                                modifier = Modifier.testTag("garden-quick-chat-retry-${message.id}"),
                                onClick = {
                                    retrying = true; notice = null
                                    submissionScope.launch {
                                        try { service.regenerateGardenQuickChat(id, owner, message.id) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: IllegalStateException) { notice = failure.message ?: "暂时无法重新生成，请回原窗口核对。" }
                                        catch (_: Exception) { notice = "重新生成未确认，请先核对原窗口，不会自动重试。" }
                                        finally { retrying = false }
                                    }
                                }) { Text(if (message.role == MessageRole.USER) "重说" else "重新生成") }
                        }
                        }
                    }
                    item(key = "history") { if (conversation.messageNodes.size > shown) TextButton(onClick = { shown += 30 }) { Text("查看更早的消息") } }
                }
            }
        } else Spacer(Modifier.weight(1f))
        errors.filter { it.conversationId == id }.lastOrNull()?.let { error ->
            Text(error.title ?: "本次回复未完成，请回原窗口查看诊断。", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error)
        }
        notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (queue.messages.isNotEmpty()) Text("这个窗口有待处理队列，请回原聊天核对；这里不会自动继续旧消息。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        if (voice.isActive) Text("通话期间暂停快捷发送，请先结束通话。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        if (valid && !submitting && job == null && !voice.isActive) {
            ModelSelector(modelId = model?.id, providers = settings.providers, type = ModelType.CHAT,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("garden-quick-chat-model")) { chosen ->
                scope.launch {
                    service.requireGardenQuickChatTarget(id, owner)
                    settingsStore.update { current -> conversationComposerWithModel(current, owner, chosen.id) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = valid && !submitting, onClick = picker.onPickImage,
                modifier = Modifier.testTag("garden-quick-chat-gallery")) { Text("图片") }
            TextButton(enabled = valid && !submitting, onClick = picker.onTakePicture,
                modifier = Modifier.testTag("garden-quick-chat-camera")) { Text("拍照") }
            if (images.isNotEmpty()) Text("已选 ${images.size} 张", style = MaterialTheme.typography.labelSmall)
        }
        if (images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            images.forEachIndexed { index, url ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(model = url, contentDescription = "待发送图片 ${index + 1}", modifier = Modifier.size(64.dp))
                    TextButton(enabled = !submitting, onClick = { onImages(images.filterIndexed { i, _ -> i != index }) }) { Text("移除") }
                }
            }
        }
        GardenQuickChatInput(draft, { value ->
            if (value.toByteArray().size <= GARDEN_QUICK_CHAT_MAX_TEXT_BYTES) onDraft(value)
            else notice = "快捷消息最多 32 KiB，未接收超长输入。"
        }, enabled = !submitting)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (job != null) "收起后仍会继续回复" else "仅发送你输入的文字和所选图片", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            if (job != null) TextButton(onClick = { service.stopGardenQuickChat(id) }) { Text("停止") }
            Button(enabled = valid && model != null && !submitting && !retrying && job == null && queue.messages.isEmpty() && !pending && !voice.isActive && (draft.isNotBlank() || images.isNotEmpty()),
                onClick = {
                    val text = draft
                    val sentImages = images.toList()
                    submitting = true; notice = null
                    val attempt = ++sendAttempt
                    submissionScope.launch {
                        try {
                            if (service.trySendGardenQuickChat(id, owner, text, sentImages.map { UIMessagePart.Image(it) }, onSavedLater = {
                                onCommitted(text, sentImages)
                                if (sendAttempt == attempt) notice = null
                            })) onCommitted(text, sentImages)
                            else notice = "本次发送未完成，草稿已保留。可返回原聊天查看具体原因。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (rejected: me.rerere.rikkahub.data.orbis.GardenQuickChatSendRejected) { notice = rejected.message }
                        catch (_: Exception) { notice = "未确认发送成功，草稿保留，请先核对原窗口，不会自动重试。" }
                        finally { submitting = false }
                    }
                }, modifier = Modifier.testTag("garden-quick-chat-send")) { Text("发送") }
        }
        if (ready && model == null) Text("请在上方为这个 AI 选择模型。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** The bottom has a stable index even when markdown remeasures, history loads or the IME resizes.
 * Only an actual scroll changes follow intent; growing output must not be mistaken for a reader
 * scrolling away. Reverse layout also avoids estimating every earlier variable-height message.
 */
@Composable
internal fun GardenQuickChatTimeline(modifier: Modifier = Modifier, followOutput: Boolean,
    output: Any?, content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollBackward }.collect { (scrolling, away) ->
            if (scrolling) following = !away
        }
    }
    LaunchedEffect(output, followOutput) {
        if (followOutput && following && !list.isScrollInProgress) list.requestScrollToItem(0)
    }
    LazyColumn(modifier.testTag("garden-quick-chat-messages"), state = list, reverseLayout = true,
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** Same main-chat renderer/settings, without its destructive action menu or view-model effects. */
@Composable
internal fun GardenQuickChatMessage(
    message: UIMessage,
    assistant: Assistant?,
    model: Model?,
    loading: Boolean,
    onToolApproval: ((String, Boolean, String, Boolean) -> Unit)? = null,
    onToolAnswer: ((String, String) -> Unit)? = null,
    onEventPresentation: (suspend (OrbisEventMetadata) -> Unit)? = null,
    onEventOpacityChange: (suspend (Float) -> Unit)? = null,
    onVoiceNotePlayed: ((Int, Int?, String) -> Unit)? = null,
    tabletopPresentation: Boolean = false,
) {
    val original = message.privateRoomSafePresentation()
    if(tabletopPresentation && original.orbisEvent?.let { me.rerere.rikkahub.data.orbis.isTabletopEventSource(it.source) }==true) {
        Text("圆桌游戏哨兵已送达",style=MaterialTheme.typography.bodySmall)
        return
    }
    // Display only: model/history retain the complete role materials and reasoning.
    val message = if(tabletopPresentation) original.copy(parts=original.parts.filterNot {
        it is UIMessagePart.Reasoning || it is UIMessagePart.Tool && (it.toolName.startsWith("script_") || it.toolName.startsWith("werewolf_"))
    }) else original
    val appearance = LocalSettings.current.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
    val segmented = BuildConfig.ORBIS_ENABLED && message.role == MessageRole.ASSISTANT && appearance.chatFlow.enabled
    val textStyle = rememberChatMessageTextStyle(user = message.role == MessageRole.USER)
    // Use provenance, never keywords in ordinary prose. Folding is a durable display edit;
    // it must not rewrite the payload, replay a sentinel or save a paged conversation snapshot.
    message.orbisEvent?.let { event ->
        if (me.rerere.rikkahub.data.orbis.isImageWorkshopSource(event.source)) {
            ProvideTextStyle(textStyle) {
                me.rerere.rikkahub.ui.components.message.WorkshopResultCard(event.recordId,
                    message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
                    event.source, event.eventId, assistant?.id?.toString())
            }
            return
        }
        ProvideTextStyle(textStyle) {
            OrbisEventMessageCard(event = event, originalText = message.toText(), appearance = appearance,
                onUpdate = { checkNotNull(onEventPresentation)(it) },
                onOpacityChange = { checkNotNull(onEventOpacityChange)(it) })
        }
        return
    }
    val content: @Composable (List<UIMessagePart>) -> Unit = { displayedParts ->
        CompositionLocalProvider(LocalImportedHistory provides message.parts.isDeepSeekHistory()) {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(assistant, message.role, model, displayedParts, message.annotations,
                    loading = loading, segmentedReply = segmented, messageKey = message.id.toString(),
                    onToolApproval = onToolApproval, onToolAnswer = onToolAnswer,
                    deletedCitationTools = message.parts.filterIsInstance<UIMessagePart.Tool>() + message.deletedToolRecords.map { it.tool })
            }
        }
    }
    val voiceRows = remember(message.parts, message.role) {
        if (message.role == MessageRole.ASSISTANT) message.parts.orbisVoiceNoteDisplay() else null
    }
    if (voiceRows != null) {
        OrbisVoiceNoteRows(message, voiceRows, model, assistant, loading, segmented,
            onPlayed = { row -> onVoiceNotePlayed?.invoke(row.partIndex, row.outputIndex,
                row.audio.voiceNotePlaybackKey(message.id.toString(), row.occurrenceKey)) }, renderParts = content)
    } else if (BuildConfig.ORBIS_ENABLED && !message.parts.isEmptyUIMessage()) {
        OrbisChatMessageLayout(message, model, assistant, loading, segmented) { content(message.parts) }
    } else content(message.parts)
}

/** The main composer's palette, opacity and shape; a small text-only input, not a second ChatInputState. */
@Composable
internal fun GardenQuickChatInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val appearance = LocalSettings.current.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
    val colors = OrbisTheme.colors
    val inputStyle = if (BuildConfig.ORBIS_ENABLED) MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp)
        else MaterialTheme.typography.bodyLarge
    val ink=LocalChatTheme.current?.body?.chrome?.onComposer?.let { Color(me.rerere.rikkahub.data.orbis.theme.themeArgb(it)) } ?: colors.ink
    me.rerere.rikkahub.ui.components.message.ThemeChromeSurface(role="composer",radius=23,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("garden-quick-chat-composer"),
        shape = RoundedCornerShape(23.dp), color = colors.raisedPanel.copy(alpha = appearance.composerOpacity),
        contentColor = ink,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("garden-quick-chat-input"),
            enabled = enabled, minLines = 1, maxLines = 4,
            textStyle = inputStyle.copy(color = ink),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner -> Box {
                if (value.isEmpty()) Text("在这个窗口里聊聊…", color = colors.mutedInk, style = inputStyle)
                inner()
            } })
    }
}

/** The drawer resolves the same persisted assistant package as the main conversation. */
@Composable internal fun GardenQuickChatTheme(settings: Settings, owner: Uuid, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettings provides settings.copy(assistantId=owner)) {
        AssistantThemeProvider(owner.toString()) { OrbisVisualTheme(content=content) }
    }
}
