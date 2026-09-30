package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject

/** Separate collaboration transport. Nothing read here becomes an AI/tool instruction. */
@Composable
fun OrbisTechHubPage(hostVisible: Boolean = true) {
    val store = koinInject<OrbisIntegrationConnections>()[OrbisIntegration.TECH_HUB]
    val connection by store.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // A changed/disconnected authorization drops the old room scope and cancels
    // its pending network calls; old credentials never serve the new view.
    key(connection.revision, connection.available) {
        TechHubContent(store, connection.available, hostVisible && resumed)
    }
}

@Composable
private fun TechHubContent(store: OrbisConnectionStore, available: Boolean, active: Boolean) {
    val client = remember { OrbisTechHubClient() }
    val previewCache = remember { HubPreviewPageCache() }
    DisposableEffect(previewCache) { onDispose { previewCache.close() } }
    DisposableEffect(active) { previewCache.setActive(active); onDispose { } }
    val context = LocalContext.current.applicationContext
    val settings = LocalSettings.current
    val appearance = communityAppearance()
    val clipboard = LocalClipboardManager.current
    val dark = LocalDarkMode.current
    val mediaFiles = remember(context) { HubMediaFiles(context.noBackupFilesDir) }
    val mediaDraftOwner = remember(mediaFiles) { HubMediaDraftOwner { runCatching { mediaFiles.discard(it) } } }
    var pickedMedia by remember { mutableStateOf<HubUploadAttachment?>(null) }
    var about by remember { mutableStateOf(false) }
    val outbox = remember(context) { OrbisTechHubOutbox.get(context) }
    val outboxState by outbox.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var rooms by remember { mutableStateOf<List<HubRoom>>(emptyList()) }
    var room by remember { mutableStateOf<String?>(null) }
    var roomMenu by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<HubMessage>>(emptyList()) }
    var cursor by remember { mutableLongStateOf(0) }
    var hasOlder by remember { mutableStateOf(false) }
    var historyMode by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var olderUntil by remember { mutableLongStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var olderLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<HubSendIntent?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var sendStatus by remember { mutableStateOf<String?>(null) }
    var clearPending by remember { mutableStateOf(false) }
    var retryPending by remember { mutableStateOf(false) }
    var jumpToBottom by remember { mutableIntStateOf(0) }
    DisposableEffect(mediaDraftOwner) { onDispose { mediaDraftOwner.clear() } }

    DisposableEffect(active, available) {
        if (!active || !available) sendJob?.cancel()
        onDispose { sendJob?.cancel() }
    }
    LaunchedEffect(available, active, outboxState) {
        outbox.load()
        pending = if (available && active) store.readCredential()?.let { outbox.pendingFor(it) } else null
        pending?.let { if (!sending) room = it.room }
    }
    LaunchedEffect(active, available, refresh) {
        if (!active || !available) return@LaunchedEffect
        try {
            val credential = store.readCredential() ?: return@LaunchedEffect
            rooms = client.rooms(credential)
            val pendingRoom = outbox.pendingFor(credential)?.room
            if (pendingRoom != null) room = pendingRoom
            else if (room !in rooms.map { it.room }) room = rooms.firstOrNull()?.room
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "暂时无法读取房间，请检查连接后刷新。" }
    }
    LaunchedEffect(room, active, available, historyMode, refresh) {
        val selected = room ?: return@LaunchedEffect
        if (!active || !available || historyMode) return@LaunchedEffect
        val credential = store.readCredential() ?: return@LaunchedEffect
        loading = true
        try {
            val first = client.page(credential, selected, tail = true)
            ensureActive()
            messages = first.events; cursor = first.nextCursor; hasOlder = first.hasOlder
            error = null; jumpToBottom++
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            error = hubReadError(failure)
            return@LaunchedEffect
        } finally { loading = false }
        var failures = 0
        while (isActive) {
            try {
                val page = client.page(credential, selected, after = cursor, waitSeconds = 25)
                ensureActive()
                val nearBottom = !listState.canScrollForward
                val bounded = messages.size + page.events.size > HUB_VISIBLE_MESSAGES
                messages = mergeHubMessages(messages, page.events, keepNewest = true)
                cursor = page.nextCursor
                if (bounded) hasOlder = true
                if (nearBottom && page.events.isNotEmpty()) jumpToBottom++
                failures = 0; error = null
                if (page.events.isEmpty()) delay(500) // Guard an immediate-empty faulty server.
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                error = hubReadError(failure)
                if ((failure as? OrbisTechHubException)?.reason in setOf("unauthorized", "invalid_response", "not_found")) break
                failures = (failures + 1).coerceAtMost(5)
                delay((1L shl failures) * 1000)
            }
        }
    }
    LaunchedEffect(olderUntil, room, active, available) {
        val selected = room ?: return@LaunchedEffect
        if (olderUntil <= 0 || !active || !available) return@LaunchedEffect
        val credential = store.readCredential() ?: return@LaunchedEffect
        olderLoading = true
        try {
            val page = client.page(credential, selected, until = olderUntil, tail = true)
            ensureActive()
            messages = mergeHubMessages(messages, page.events, keepNewest = false)
            hasOlder = page.hasOlder
            error = null
            listState.scrollToItem(0)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = hubReadError(failure)
        } finally { olderLoading = false }
    }
    LaunchedEffect(jumpToBottom) {
        if (messages.isNotEmpty() && !historyMode) listState.scrollToItem(messages.lastIndex + 1)
    }

    fun send(retry: Boolean) {
        if (sending || outboxState.busy || !active || !available) return
        if (retry && (!outboxState.canRetry || pending == null)) return
        if (!retry && !outboxState.canSendNew) return
        val selected = (if (retry) pending?.room else room) ?: return
        sending = true
        val message = draft
        val attachment = if (retry) null else pickedMedia
        sendStatus = "正在保存发送编号并发送…"
        sendJob = scope.launch {
            try {
                val credential = store.readCredential() ?: error("disconnected")
                val sent = outbox.send(credential, selected, message, retry, attachment = attachment,
                    onAttachmentClaimed = { claimed ->
                        mediaDraftOwner.claim(claimed)
                        if (pickedMedia == claimed) pickedMedia = null
                    }) { intent ->
                    // Only the captured configuration may use this durable intent.
                    check(store.state.value.available && store.state.value.revision == credential.revision)
                    if (intent.attachment == null) client.send(credential, intent)
                    else { client.upload(credential, intent, mediaFiles); emptyList() }
                }
                ensureActive()
                if (room == selected) {
                    messages = mergeHubMessages(messages, sent, keepNewest = true)
                    // Do not advance cursor past unseen messages; the next poll
                    // will merge the accepted event by its server sequence.
                    jumpToBottom++
                }
                pending = null; draft = ""; sendStatus = "已由服务确认接收。"
            } catch (cancelled: CancellationException) {
                sendStatus = "发送结果尚未确认；不会自动补发。返回后可使用同一发送编号重试。"
                throw cancelled
            } catch (failure: Exception) {
                sendStatus = when ((failure as? HubOutboxException)?.reason) {
                    "receipt_cleanup_failed" -> "服务已确认接收，但本机发送记录未能清理。已锁定新发送；请先核实，勿重发。"
                    "storage_unavailable" -> "发送记录无法安全保存或读取，已锁定发送。不会自动补发或覆盖旧记录。"
                    "configuration_changed", "pending_exists" -> "仍有待确认的发送，请切回原连接核实或明确放弃后再发。"
                    "retry_expired" -> "原发送编号已超过 24 小时，禁止重试；请先核实群消息。"
                    else -> if (failure is IllegalArgumentException) "消息最多 4,000 个字符；附件需完整且在大小限制内。"
                        else "发送结果尚未确认；不会自动补发。可用同一发送编号重试，请勿另发重复消息。"
                }
            } finally { sending = false }
        }
    }

    OrbisCommunityBackdrop(settings, active) {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink,
            topBar = {
                Surface(color = OrbisTheme.colors.panel.copy(alpha = appearance.composerOpacity)) {
                    Row(Modifier.statusBarsPadding().fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton()
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { roomMenu = true }, enabled = available && !sending && !outboxState.busy && !outboxState.hasPending) {
                                Text(room?.let { "TechHub · $it ▾" } ?: "TechHub · 选择房间", maxLines = 1)
                            }
                            DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                                rooms.forEach { entry -> DropdownMenuItem(text = { Text(entry.room) }, onClick = {
                                    roomMenu = false; room = entry.room; messages = emptyList(); cursor = 0
                                    historyMode = false; olderUntil = 0; draft = ""; sendStatus = null; error = null; refresh++
                                    mediaDraftOwner.clear(); pickedMedia = null
                                }) }
                            }
                        }
                        TextButton(onClick = { historyMode = false; olderUntil = 0; refresh++ }, enabled = available && !sending) { Text("最新") }
                        IconButton(onClick = { about = true }) { Text("ⓘ") }
                    }
                }
            },
            bottomBar = { OrbisChatDock(currentLabel = "当前 TechHub") },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp)) {
                if (!available) {
                    Text("TechHub 尚未启用或授权不可用。请在后台连接设置中启用并填写独立 Token。", Modifier.padding(16.dp))
                    return@Column
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    item(key = "history") {
                        if (hasOlder || historyMode) Row {
                            TextButton(onClick = {
                                val oldest = messages.firstOrNull()?.seq ?: 0
                                if (oldest > 1) { historyMode = true; olderUntil = oldest - 1 }
                            }, enabled = hasOlder && !olderLoading && active) {
                                Text(if (olderLoading) "读取中…" else "更早消息")
                            }
                            if (historyMode) Text("正在看历史 · 暂停实时跟随", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(messages, key = { "message-${it.seq}" }) { message ->
                        val memberColor = communityMemberColor(message.from, dark)
                        val human = message.from == "human"
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (human) UIAvatar(settings.displaySetting.userNickname, settings.displaySetting.userAvatar, Modifier.size(28.dp))
                                else Surface(color = memberColor.copy(alpha = .28f), shape = CircleShape) {
                                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text(hubMemberLabel(message.from).take(1)) }
                                }
                                Text("${if (human && settings.displaySetting.userNickname.isNotBlank()) settings.displaySetting.userNickname else hubMemberLabel(message.from)} · #${message.seq}",
                                    color = OrbisTheme.colors.ink, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    runCatching { clipboard.setText(AnnotatedString(message.text)) }
                                        .onSuccess { sendStatus = "已复制消息 #${message.seq}。" }
                                        .onFailure { sendStatus = "复制未完成，请长按正文选择。" }
                                }, enabled = message.text.isNotEmpty(), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("复制", style = MaterialTheme.typography.labelSmall) }
                            }
                            Surface(color = memberColor.copy(alpha = appearance.bubbleOpacityForRole(human)), shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().padding(start = 34.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                SelectionContainer { Text(message.text, modifier = Modifier.padding(vertical = 6.dp)) }
                                message.attachment?.let { attachment ->
                                    val visible by remember(message.seq) { derivedStateOf {
                                        listState.layoutInfo.visibleItemsInfo.any { it.key == "message-${message.seq}" }
                                    } }
                                    HubAttachmentCard(attachment, store, client, active, visible, previewCache) { sendStatus = it }
                                }
                                Text(message.createdAt, style = MaterialTheme.typography.labelSmall, color = OrbisTheme.colors.mutedInk)
                            }
                            }
                        }
                    }
                    if (messages.isEmpty() && !loading) item { Text("此房间暂时没有已读取的消息。") }
                }
                if (sendStatus != null) Text(sendStatus!!, style = MaterialTheme.typography.bodySmall)
                if (outboxState.failed) Text("本机发送记录异常，已停止新发送和重试。不会覆盖它；请先核实群消息后再明确放弃记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (outboxState.hasPending && pending == null) Text("另一连接仍有一条待确认消息。切回原连接可用原编号核实重试；这里不会显示它的正文或自动发送。",
                    style = MaterialTheme.typography.bodySmall)
                pending?.let { intent ->
                    Text(if (outboxState.receiptConfirmed) "服务已确认接收，本机记录尚待清理；请勿重发。"
                        else "已保留待确认发送（退出或重启后仍保留，不自动重发）。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("房间 ${intent.room} · ${intent.attachment?.filename?.plus(" · ").orEmpty()}${intent.text.take(100)}${if (intent.text.length > 100) "…" else ""}",
                        maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { retryPending = true }, enabled = active && !sending && outboxState.canRetry && intent.retryAllowed(System.currentTimeMillis())) {
                            Text("同一编号重试")
                        }
                    }
                    if (!intent.retryAllowed(System.currentTimeMillis())) Text("已超过 24 小时，原幂等保障过期，禁止重试。请先人工核实群消息。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (outboxState.hasPending || outboxState.failed) TextButton(onClick = { clearPending = true },
                    enabled = !sending && !outboxState.busy) { Text("核实后放弃本机重试记录") }
                OrbisCommunityComposer(value = draft, onValueChange = { if (it.codePointCount(0, it.length) <= 4000) draft = it },
                    onSend = { send(false) }, enabled = outboxState.canSendNew && !sending,
                    sendEnabled = active && !sending && outboxState.canSendNew && (draft.isNotBlank() || pickedMedia != null) && room != null,
                    placeholder = "发送到 TechHub…", appearance = appearance, sending = sending,
                    attachmentActions = { HubMediaPicker(outboxState.canSendNew && !sending && room != null, active, mediaFiles,
                        onPicked = { next -> mediaDraftOwner.replace(next); pickedMedia = next },
                        onStatus = { sendStatus = it }) },
                    pendingContent = { pickedMedia?.let { media ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("附件 · ${media.filename}", Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { mediaDraftOwner.clear(); pickedMedia = null }) { Text("移除") }
                        }
                    } })
            }
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("TechHub 协作群") },
        text = { Text("发送身份由已配置的独立 Token 决定，不由本机昵称决定。这里的 @成员不是多 AI 模型调用，不会自动领取任务。\n\n图片最多 10 MiB，文件最多 30 MiB；可见的 2 MiB 内常见图片可自动预览，每张不自动重试，其余附件按查看或下载读取。保存到文件仍需你明确选择。发送结果未知时保留同一编号供人工核实重试，不自动补发。\n\n未发送的草稿和已选附件仅暂存在本机；退出页面后不会自动发送。") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
    if (retryPending) AlertDialog(onDismissRequest = { retryPending = false }, title = { Text("先核对群消息") },
        text = { Text("原消息或附件可能已到达群里。将使用原编号和原内容重试，但服务端异常时同号也不能绝对排除重复；请先点“最新”检查。确定尚未看到原消息，再继续。") },
        confirmButton = { TextButton(onClick = { retryPending = false; send(true) }) { Text("已核对，原编号重试") } },
        dismissButton = { TextButton(onClick = { retryPending = false }) { Text("先检查") } })
    if (clearPending) AlertDialog(onDismissRequest = { clearPending = false },
        title = { Text("仅放弃本机重试？") },
        text = { Text("原消息可能已到达群里。这不会撤回或删除服务器消息；再次发送会是新消息，请先核实。") },
        confirmButton = { TextButton(onClick = {
            clearPending = false
            scope.launch {
                try {
                    outbox.abandon(); pending = null; draft = ""; sendStatus = "已放弃本机重试记录，未撤回群消息。"
                } catch (_: Exception) { sendStatus = "本机记录仍无法更新，发送继续锁定；没有撤回群消息。" }
            }
        }) { Text("已核实，放弃重试") } },
        dismissButton = { TextButton(onClick = { clearPending = false }) { Text("保留") } })
}

private fun hubMemberLabel(identity: String): String = when (identity) {
    "human" -> "人类"
    "rikka" -> "Rikka"
    "claude" -> "VS Claude"
    "codex" -> "Codex"
    "dsh" -> "DSH"
    else -> identity
}

private fun hubReadError(failure: Exception): String = when ((failure as? OrbisTechHubException)?.reason) {
    "unauthorized" -> "TechHub 授权无效或无权限，请检查独立 Token。"
    "invalid_response" -> "服务响应格式不合规或超出安全大小，已停止读取。"
    "not_found" -> "未找到此 TechHub 房间或接口。"
    else -> "连接暂时中断，保留已读消息；前台会稍后再试。"
}
