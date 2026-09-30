package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.orbis.group.*
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.components.richtext.MarkdownNew
import me.rerere.rikkahub.ui.components.message.LanguageSelectionDialog
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/** A separate conversation store: entering this page never selects or sends to a private chat. */
@Composable
fun OrbisAiGroupsPage(hostVisible: Boolean = true) {
    val groups = koinInject<OrbisGroupChats>()
    val state by groups.state.collectAsStateWithLifecycle()
    val settings by koinInject<SettingsStore>().settingsFlow.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    var actionBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var addingRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var managingRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<Pair<String, OrbisGroupMember>?>(null) }
    var retryTarget by remember { mutableStateOf<OrbisGroupMessage?>(null) }
    // Drafts may be large across many rooms: never serialize this map into saved-instance state.
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    var attachmentDrafts by remember { mutableStateOf(mapOf<String, List<OrbisGroupAttachment>>()) }
    val room = state.rooms.find { it.id == state.selectedRoomId }
    val appearance = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized()

    fun act(block: suspend () -> Unit) {
        if (actionBusy || !hostVisible) return
        actionBusy = true
        actionError = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                actionError = (error as? OrbisGroupException)?.message ?: "这次操作没有完成，请核对状态后再试。"
            } finally { actionBusy = false }
        }
    }
    LaunchedEffect(hostVisible) {
        if (!hostVisible) {
            creating = false; addingRoom = null; managingRoom = null; removeTarget = null; retryTarget = null
            return@LaunchedEffect
        }
        try { groups.reload() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { actionError = "群聊记录暂时无法读取；没有重新发送消息。" }
    }

    OrbisCommunityBackdrop(settings, hostVisible) {
        Scaffold(
            modifier = Modifier.imePadding(), containerColor = Color.Transparent,
            contentColor = OrbisTheme.colors.ink,
            topBar = {
                Surface(color = OrbisTheme.colors.panel.copy(alpha = appearance.composerOpacity),
                    modifier = Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (room == null) BackButton()
                        else TextButton(onClick = { act { groups.selectRoom(null) } }, enabled = !actionBusy) { Text("群列表") }
                        Text(room?.title ?: "AI 群聊", modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { actionError = null; creating = true }, enabled = state.loaded && !actionBusy) { Text("新建") }
                    }
                }
            },
            bottomBar = { OrbisChatDock("当前 AI 群聊") },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                (actionError ?: state.error)?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                            TextButton(onClick = { act { groups.reload() } }, enabled = !actionBusy) { Text("重新读取") }
                        }
                    }
                }
                when {
                    !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    room == null -> GroupRoomList(state.rooms, state.runningRoomIds, !actionBusy && hostVisible,
                        onOpen = { id -> act { groups.selectRoom(id) } }, onCreate = { actionError = null; creating = true })
                    else -> key(room.id) {
                        GroupRoomContent(
                            room = room, state = state, settings = settings,
                            enabled = !actionBusy && hostVisible, hostVisible = hostVisible,
                            draft = drafts[room.id].orEmpty(), onDraft = { drafts = drafts + (room.id to it) },
                            attachments = attachmentDrafts[room.id].orEmpty(),
                            onAttachments = { attachmentDrafts = attachmentDrafts + (room.id to it) },
                            onAdd = { actionError = null; addingRoom = room.id }, onManage = { actionError = null; managingRoom = room.id },
                            onEarlier = { act { groups.loadEarlier() } },
                            onLatest = { act { groups.selectRoom(room.id) } },
                            onSend = { text, target, selectedAttachments, preserveDraft ->
                                if (actionBusy || !hostVisible) false else {
                                    actionBusy = true; actionError = null
                                    try {
                                        groups.send(room.id, text, target, selectedAttachments)
                                        // Acknowledged only after the human row and round were committed.
                                        if (!preserveDraft && drafts[room.id] == text) drafts = drafts - room.id
                                        if (!preserveDraft && attachmentDrafts[room.id] == selectedAttachments) attachmentDrafts = attachmentDrafts - room.id
                                        true
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) {
                                        actionError = (error as? OrbisGroupException)?.message ?: "发送状态未确认，请先核对群记录；不会自动重试。"
                                        false
                                    } finally { actionBusy = false }
                                }
                            },
                            onStop = { act { groups.stop(room.id) } }, onRetry = { actionError = null; retryTarget = it },
                        )
                    }
                }
            }
        }
    }

    if (creating) CreateGroupDialog(
        busy = actionBusy, error = actionError, onDismiss = { if (!actionBusy) creating = false },
        onCreate = { title -> act { groups.createRoom(title); creating = false } },
    )
    addingRoom?.let { id ->
        val targetRoom = state.rooms.find { it.id == id }
        if (targetRoom != null) AddGroupMemberDialog(
            settings = settings, room = targetRoom, busy = actionBusy || id in state.runningRoomIds, error = actionError,
            onDismiss = { if (!actionBusy) addingRoom = null },
            onConnections = { addingRoom = null; navigator.navigate(Screen.SettingProvider) },
            onAdd = { assistantId, modelId -> act { groups.addMember(id, assistantId, modelId); addingRoom = null } },
        )
    }
    managingRoom?.let { id -> state.rooms.find { it.id == id }?.let { targetRoom ->
        AlertDialog(onDismissRequest = { managingRoom = null }, title = { Text("群成员") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("移除只影响之后的群聊，已经保存的发言不会删除。", style = MaterialTheme.typography.bodySmall)
                    targetRoom.members.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UIAvatar(member.name, member.avatar, Modifier.size(32.dp))
                            Text(member.name, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { removeTarget = id to member; managingRoom = null },
                                enabled = !actionBusy && id !in state.runningRoomIds) { Text("移除") }
                        }
                    }
                    if (id in state.runningRoomIds) Text("正在回复；先停止本轮，才能调整成员。", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { managingRoom = null }) { Text("关闭") } })
    } }
    removeTarget?.let { (id, member) ->
        AlertDialog(onDismissRequest = { if (!actionBusy) removeTarget = null }, title = { Text("移除 ${member.name}？") },
            text = { Column { Text("保留这位成员的所有既有发言；不会删除对应的 AI 配置或私人聊天。")
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(onClick = { act { groups.removeMember(id, member.id); removeTarget = null } },
                enabled = !actionBusy && id !in state.runningRoomIds) { Text("确认移除") } },
            dismissButton = { TextButton(onClick = { removeTarget = null }, enabled = !actionBusy) { Text("取消") } })
    }
    retryTarget?.let { message ->
        val regenerate = message.status == OrbisGroupMessageStatus.COMPLETE
        AlertDialog(onDismissRequest = { if (!actionBusy) retryTarget = null }, title = { Text("${if (regenerate) "再次生成" else "单独重试"} ${message.name}？") },
            text = { Column { Text("只让这位成员新增一次回复，可能再次产生模型费用。原回复完整保留，其他成员不会重发。")
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { TextButton(onClick = { act {
                if (regenerate) groups.regenerate(message.roomId, message.id) else groups.retry(message.roomId, message.id)
                retryTarget = null
            } },
                enabled = !actionBusy && message.roomId !in state.runningRoomIds) { Text("确认重试一次") } },
            dismissButton = { TextButton(onClick = { retryTarget = null }, enabled = !actionBusy) { Text("取消") } })
    }
}

@Composable
private fun GroupRoomList(rooms: List<OrbisGroupRoom>, running: Set<String>, enabled: Boolean,
    onOpen: (String) -> Unit, onCreate: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("让每一种声音，都有自己的位置", style = MaterialTheme.typography.titleLarge)
                Text("群聊与 TechHub 分开。每位成员绑定自己的 AI 配置与模型连接；不会把私人聊天或记忆自动带进群里。",
                    style = MaterialTheme.typography.bodyMedium, color = OrbisTheme.colors.mutedInk)
                Text("群聊独立保存在本机；现有普通聊天导出暂不包含群聊。", style = MaterialTheme.typography.bodySmall,
                    color = OrbisTheme.colors.mutedInk)
                if (rooms.isEmpty()) Button(onClick = onCreate, enabled = enabled) { Text("创建第一个 AI 群") }
            }
        }
        items(rooms, key = { it.id }) { room ->
            Surface(onClick = { onOpen(room.id) }, enabled = enabled, shape = RoundedCornerShape(20.dp),
                color = OrbisTheme.colors.panel, border = BorderStroke(1.dp, OrbisTheme.colors.border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(room.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        room.members.take(8).forEach { member -> UIAvatar(member.name, member.avatar, Modifier.size(28.dp)) }
                    }
                    Text("${room.members.size} 位 AI" + if (room.id in running) " · 正在回复" else " · 点击进入",
                        style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.GroupRoomContent(
    room: OrbisGroupRoom, state: OrbisGroupState, settings: Settings, enabled: Boolean, hostVisible: Boolean,
    draft: String, onDraft: (String) -> Unit, onAdd: () -> Unit, onManage: () -> Unit,
    attachments: List<OrbisGroupAttachment>, onAttachments: (List<OrbisGroupAttachment>) -> Unit,
    onEarlier: () -> Unit, onLatest: () -> Unit, onSend: suspend (String, String?, List<OrbisGroupAttachment>, Boolean) -> Boolean,
    onStop: () -> Unit, onRetry: (OrbisGroupMessage) -> Unit,
) {
    var target by rememberSaveable(room.id) { mutableStateOf<String?>(null) }
    var details by rememberSaveable(room.id) { mutableStateOf(false) }
    var followLatest by remember(room.id) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val running = room.id in state.runningRoomIds
    val appearance = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized()
    val inputTooLarge = remember(draft) { draft.toByteArray(Charsets.UTF_8).size > 32 * 1024 }
    val messages = state.messages.filter { it.roomId == room.id }
    val latest = messages.lastOrNull()
    LaunchedEffect(room.members) { if (target != null && room.members.none { it.id == target }) target = null }
    LaunchedEffect(dragged) { if (dragged) followLatest = false }
    LaunchedEffect(latest?.id, messages.sumOf { it.text.length + it.attachments.size }, latest?.status,
        followLatest, hostVisible, state.hasLater) {
        if (hostVisible && followLatest && !state.hasLater && messages.isNotEmpty()) list.scrollToItem(messages.size)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        item("everyone") {
            FilterChip(selected = target == null, onClick = { target = null }, label = { Text("全员") })
        }
        items(room.members, key = { it.id }) { member ->
            FilterChip(selected = target == member.id, onClick = { target = if (target == member.id) null else member.id },
                leadingIcon = { UIAvatar(member.name, member.avatar, Modifier.size(20.dp), loading = state.runningMemberId == member.id && running) },
                label = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp)) })
        }
        item("add") { TextButton(onClick = onAdd, enabled = enabled && !running && room.members.size < ORBIS_GROUP_MEMBER_LIMIT) { Text("＋成员") } }
        item("manage") { TextButton(onClick = onManage, enabled = enabled && room.members.isNotEmpty()) { Text("－成员") } }
        item("details") { TextButton(onClick = { details = true }) { Text("说明") } }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("群聊说明") }, text = {
        Text(state.contextInfo + "\n每群最多 8 位 AI；发送给全员后每位各回复一次，点头像可只向一位发言。" +
        "\n图片、表情包与文件只进入本群，不进入私人聊天。每条最多 4 个，单个 8 MiB，合计 20 MiB。可解析文本以及小型 PDF / DOCX / PPTX / EPUB；超限或无法解析的文件仍保留原件，并明确告知模型未解析。" +
        "\n当前轮图片发送给具备图片输入能力的模型；文字模型会明确提示不支持，不会假装看见。更早的图片不会每轮重复发送。" +
        "\n群聊独立保存在本机；现有普通聊天导出暂不包含群聊。" +
        "\n私聊只可经授权工具主动查询本人的群记录，不会合并上下文。没有多人语音通话。未发送草稿在离开页面或重启后可能清空。",
        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("知道了") } })

    LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("history-navigation") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.hasEarlier) TextButton(onClick = { followLatest = false; onEarlier() }, enabled = enabled) { Text("更早记录（固定分页）") }
                if (state.hasLater) TextButton(onClick = { followLatest = true; onLatest() }, enabled = enabled) { Text("回到最新记录") }
                if (messages.isEmpty()) Text(if (room.members.isEmpty()) "先添加 AI 成员，再说第一句话。" else "这里还没有消息。群成员不会读取你们的私人聊天。",
                    style = MaterialTheme.typography.bodyMedium, color = OrbisTheme.colors.mutedInk)
            }
        }
        items(messages, key = { it.id }, contentType = { "group-message" }) { message ->
            GroupMessageBubble(message, room.members.find { it.id == message.memberId }, settings,
                retryEnabled = enabled && !running && !state.hasLater && message.roundId == latest?.roundId &&
                    message.memberId != null && room.members.any { it.id == message.memberId },
                onRetry = { onRetry(message) }, actionsEnabled = enabled && hostVisible,
                onReply = { onDraft(draft + (if (draft.isBlank()) "" else "\n") + groupQuotedReply(message)) })
        }
    }
    if (!followLatest && !state.hasLater) TextButton(onClick = { followLatest = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回到最新") }
    if (inputTooLarge) Text("单条文字最多 32 KiB，请缩短后发送。", color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.labelSmall)
    GroupAttachmentComposer(draft, onDraft, attachments, onAttachments, settings, appearance,
        enabled = enabled, inputEnabled = hostVisible, running = running,
        sendEnabled = room.members.isNotEmpty() && (draft.isNotBlank() || attachments.isNotEmpty()) && !inputTooLarge,
        placeholder = if (target == null) "向群里说话…" else "对 ${room.members.find { it.id == target }?.name.orEmpty()} 说…",
        onSend = { text, items, preserveDraft -> onSend(text, target, items, preserveDraft).also { if (it) followLatest = true } }, onStop = onStop)
}

@Composable
private fun GroupMessageBubble(message: OrbisGroupMessage, member: OrbisGroupMember?, settings: Settings,
    retryEnabled: Boolean, onRetry: () -> Unit, actionsEnabled: Boolean, onReply: () -> Unit) {
    val human = message.memberId == null
    val clipboard = LocalClipboardManager.current
    val appearance = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized()
    val font = rememberChatFontFamily(settings.displaySetting)
    val bubbleColor = communityMemberColor(message.memberId ?: "human", LocalDarkMode.current)
        .copy(alpha = appearance.bubbleOpacityForRole(human))
    val tts = LocalTTSState.current
    val ttsAvailable by tts.isAvailable.collectAsState()
    val speaking by tts.isSpeaking.collectAsState()
    val scope = rememberCoroutineScope()
    var chooseLanguage by remember { mutableStateOf(false) }
    var translateLanguage by remember { mutableStateOf<java.util.Locale?>(null) }
    var page by rememberSaveable(message.id) { mutableIntStateOf(0) }
    // Bound Text measurement for very long replies; copying always retains the entire original.
    val chunkSize = 4_000
    val pages = ((message.text.length + chunkSize - 1) / chunkSize).coerceAtLeast(1)
    val shownPage = page.coerceIn(0, pages - 1)
    val start = groupTextBoundary(message.text, shownPage * chunkSize)
    val end = groupTextBoundary(message.text, (shownPage + 1) * chunkSize)
    val timestamp = remember(message.createdAt) {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.createdAt))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (human) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UIAvatar(message.name, message.avatar ?: if (human) settings.displaySetting.userAvatar else member?.avatar ?: Avatar.Dummy, Modifier.size(28.dp))
            Text(message.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(max = 200.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$timestamp · #${message.sequence}", style = MaterialTheme.typography.labelSmall, color = OrbisTheme.colors.mutedInk)
        }
        Surface(shape = RoundedCornerShape(18.dp), color = bubbleColor,
            border = BorderStroke(1.dp, OrbisTheme.colors.border), modifier = Modifier.padding(top = 5.dp).widthIn(max = 620.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (message.text.isNotEmpty()) SelectionContainer {
                    MarkdownNew(message.text.substring(start, end), style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = font, color = appearance.chatTextColor?.let { Color(it) } ?: OrbisTheme.colors.ink))
                }
                message.attachments.forEach { GroupAttachmentView(it) }
                val status = when (message.status) {
                    OrbisGroupMessageStatus.QUEUED -> "等待本轮发言"
                    OrbisGroupMessageStatus.GENERATING -> "正在回复…"
                    OrbisGroupMessageStatus.FAILED -> "回复失败 · 没有自动重试"
                    OrbisGroupMessageStatus.INTERRUPTED -> "已中断 · 已收到的内容保留"
                    OrbisGroupMessageStatus.COMPLETE -> null
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = OrbisTheme.colors.mutedInk) }
                message.errorReason?.let { code ->
                    Text(OrbisGroupException(code).message.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    message.errorDetails?.sanitized()?.let { detail ->
                        val markers = listOfNotNull(detail.httpStatus?.let { "HTTP $it" }, detail.providerCode)
                        if (markers.isNotEmpty()) Text(markers.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (pages > 1) {
                    Text("长消息分段显示：${shownPage + 1} / $pages · 原文完整保留", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text("上一段") }
                        TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage + 1 < pages) { Text("下一段") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.text.isNotEmpty()) TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text("复制") }
                    if (message.errorReason != null) TextButton(onClick = {
                        clipboard.setText(AnnotatedString(groupFailureDiagnostic(message)))
                    }) { Text("复制诊断") }
                    TextButton(onClick = onReply, enabled = actionsEnabled) { Text("引用") }
                    if (message.text.isNotBlank() && ttsAvailable) TextButton(onClick = { scope.launch {
                        if (speaking) tts.stop() else tts.speak(message.text)
                    } }) { Text(if (speaking) "停止朗读" else "朗读") }
                    if (message.text.isNotBlank()) TextButton(onClick = { chooseLanguage = true }, enabled = actionsEnabled) { Text("翻译") }
                    if (!human && message.status in setOf(OrbisGroupMessageStatus.COMPLETE, OrbisGroupMessageStatus.FAILED, OrbisGroupMessageStatus.INTERRUPTED)) {
                        TextButton(onClick = onRetry, enabled = retryEnabled) {
                            Text(if (message.status == OrbisGroupMessageStatus.COMPLETE) "再次生成" else "单独重试")
                        }
                    }
                }
            }
        }
    }
    if (chooseLanguage) LanguageSelectionDialog(
        onLanguageSelected = { chooseLanguage = false; translateLanguage = it },
        onClearTranslation = { chooseLanguage = false; translateLanguage = null },
        onDismissRequest = { chooseLanguage = false },
    )
    translateLanguage?.let { language -> GroupTranslationDialog(message, settings, language) { translateLanguage = null } }
}

/** Adjacent pages share the same boundary, so emoji are neither split, duplicated nor omitted. */
private fun groupTextBoundary(text: String, offset: Int): Int {
    val boundary = offset.coerceIn(0, text.length)
    return if (boundary > 0 && boundary < text.length && Character.isLowSurrogate(text[boundary]) &&
        Character.isHighSurrogate(text[boundary - 1])) boundary - 1 else boundary
}

@Composable
private fun CreateGroupDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建 AI 群聊") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("新群拥有独立的消息记录；创建后再选择成员及各自的模型。")
            OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("群名称") }, singleLine = true, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { onCreate(title.trim()) }, enabled = !busy && title.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun AddGroupMemberDialog(settings: Settings, room: OrbisGroupRoom, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onConnections: () -> Unit, onAdd: (Uuid, Uuid) -> Unit) {
    var assistantId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    val assistant = settings.assistants.find { it.id.toString() == assistantId }
    val providers = settings.providers.filter { it.enabled }
    val model = providers.flatMap { it.models }.find { it.id.toString() == modelId && it.type == ModelType.CHAT }
    val provider = model?.let { chosen -> providers.find { p -> p.models.any { it.id == chosen.id } } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 AI 成员 · ${room.members.size} / 8") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择一个现有 AI 身份，再明确选择这位成员使用的连接和模型；不会切换你的私人聊天模型。")
                Box {
                    OutlinedButton(onClick = { pickerOpen = true }, enabled = !busy && !settings.init, modifier = Modifier.fillMaxWidth()) {
                        Text(assistant?.name?.ifBlank { "未命名 AI" } ?: "选择 AI 身份", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        settings.assistants.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.name.ifBlank { "未命名 AI" }) },
                                leadingIcon = { UIAvatar(choice.name, choice.avatar, Modifier.size(28.dp)) },
                                onClick = { assistantId = choice.id.toString(); modelId = null; pickerOpen = false })
                        }
                    }
                }
                if (settings.assistants.isEmpty()) Text("暂无 AI 身份，请先在 AI 设置中添加。", color = MaterialTheme.colorScheme.error)
                if (!busy) ModelSelector(modelId = model?.id, providers = providers, type = ModelType.CHAT,
                    modifier = Modifier.fillMaxWidth(), onSelect = { modelId = it.id.toString() })
                else Text(model?.displayName ?: "正在保存…")
                provider?.let { Text("连接：${it.name}\n模型：${model?.displayName.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onConnections, enabled = !busy) { Text("配置新的连接 / API 与模型") }
                Text("最多 8 位 AI。成员保留各自名字与头像；只使用本群上下文，不自动带入私聊记忆和工具。支持图片与文件，不提供多人语音通话。",
                    style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { if (assistant != null && model != null) onAdd(assistant.id, model.id) },
            enabled = !busy && !settings.init && assistant != null && model != null && room.members.size < ORBIS_GROUP_MEMBER_LIMIT) { Text("添加成员") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
