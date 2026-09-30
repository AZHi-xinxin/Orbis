package me.rerere.rikkahub.ui.pages.orbis

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.data.orbis.consultation.ConsultationManualRetry
import me.rerere.rikkahub.data.orbis.consultation.ConsultationRetryPreview
import me.rerere.rikkahub.data.orbis.consultation.ConsultationRuntimeStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** No polling or model work on composition. All bodies remain ephemeral, not SaveableState. */
@Composable
fun OrbisConsultationPage(hostVisible: Boolean = true) {
    if (!me.rerere.rikkahub.data.orbis.consultation.consultationFeature.enabled) {
        OrbisConsultationUnavailablePage()
        return
    }
    val store = koinInject<OrbisIntegrationConnections>()[OrbisIntegration.CONSULTATION]
    val connection by store.state.collectAsStateWithLifecycle()
    key(connection.revision, connection.available) {
        ConsultationSurface(store, hostVisible)
    }
}

@Composable
private fun ConsultationSurface(store: OrbisConnectionStore, hostVisible: Boolean) {
    val connection by store.state.collectAsStateWithLifecycle()
    val client = remember { OrbisConsultationClient() }
    val context = LocalContext.current
    val chats = koinInject<me.rerere.rikkahub.service.ChatService>()
    val manualRetry = remember { ConsultationManualRetry(ConsultationRuntimeStore.open(context), client,
        recoverLocalEvidence = { chats.recoverConsultationSession(it) }) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val active = resumed && hostVisible && connection.available
    var rooms by remember { mutableStateOf<List<ConsultationSession>>(emptyList()) }
    var selected by remember { mutableStateOf<ConsultationSession?>(null) }
    var segments by remember { mutableStateOf<List<ConsultationSegment>>(emptyList()) }
    var summary by remember { mutableStateOf<String?>(null) }
    var summaryLoaded by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf(false) }
    var platformList by remember { mutableStateOf(false) }
    var relayEnabled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var configure by remember { mutableStateOf(false) }
    var stopConfirm by remember { mutableStateOf(false) }
    var inspectionConfirm by remember { mutableStateOf(false) }
    var retryPreview by remember { mutableStateOf<ConsultationRetryPreview?>(null) }
    var approvalSession by remember { mutableStateOf<String?>(null) }
    var localStatusSession by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0) }
    var platformAction by remember { mutableStateOf<String?>(null) }
    var platformToken by remember { mutableStateOf("") }
    var closureReason by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    fun clearContent() {
        job?.cancel(); job = null; busy = false
        rooms = emptyList(); selected = null; segments = emptyList(); summary = null; summaryLoaded = false
        inspected = false; platformList = false; relayEnabled = null; status = null
        platformToken = ""; platformAction = null; reveal = false; tapCount = 0
        closureReason = ""
        inspectionConfirm = false; stopConfirm = false
        retryPreview = null
        approvalSession = null
        localStatusSession = null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) { clearContent(); configure = false }
        }
        lifecycle.addObserver(observer)
        onDispose { clearContent(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hostVisible) { if (!hostVisible) { clearContent(); configure = false } }

    fun perform(operatorToken: String? = null, action: suspend (ConsultationAuthorization) -> Unit) {
        if (!active || busy) return
        busy = true; status = null
        job = scope.launch {
            try {
                val credential = store.readCredential() ?: throw OrbisConsultationException("configuration_changed")
                val auth = if (operatorToken == null) client.human(credential) else client.operator(credential, operatorToken)
                ensureActive()
                if (!store.state.value.available || store.state.value.revision != credential.revision)
                    throw OrbisConsultationException("configuration_changed")
                relayEnabled = auth.relayEnabled
                action(auth)
                ensureActive()
                if (!store.state.value.available || store.state.value.revision != credential.revision) {
                    clearContent(); throw OrbisConsultationException("configuration_changed")
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { segments = emptyList(); summary = null; summaryLoaded = false; status = consultationFailureText(failure)
            } finally { busy = false }
        }
    }
    fun refreshRooms(operatorToken: String? = null) {
        perform(operatorToken) { auth ->
            val next = client.sessions(auth)
            rooms = next; selected = null; segments = emptyList(); summary = null; summaryLoaded = false; inspected = false
            platformList = auth.role == ConsultationRole.OPERATOR
            status = if (next.isEmpty()) "没有可访问的咨询会话。" else "会话列表已刷新；选择会话后再查看公开段。"
        }
    }
    fun inspectRoom(operatorToken: String? = null) {
        val room = selected ?: return
        perform(operatorToken) { auth ->
            val result = client.inspection(auth, room)
            selected = result.session; segments = result.messages; summary = null; summaryLoaded = false; inspected = true
            status = "此次查验由服务器授权并记录；本页没有导出、复制或自动朗读入口。"
        }
    }
    fun stopRoom(operatorToken: String? = null) {
        val room = selected ?: return
        perform(operatorToken) { auth ->
            val confirmedState = client.stop(auth, room.id)
            selected = room.copy(state = confirmedState); segments = emptyList(); summary = null; summaryLoaded = false
            rooms = rooms.map { if (it.id == room.id) it.copy(state = confirmedState) else it }
            status = "服务已确认停止。没有重新发起咨询或调用模型。"
        }
    }

    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink,
            topBar = { OrbisPageHeader("咨询室", subtitle = "独立通道 · 公开段落 · 查验留痕", compact = true,
                navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding(),
                actions = { TextButton(onClick = { clearContent(); configure = true }, enabled = !busy) { Text("连接") } }) },
            bottomBar = { OrbisChatDock("当前 咨询室") }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ConsultationPanel {
                        Text("偷听角", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.clickable(enabled = active && !busy) {
                                val now = SystemClock.elapsedRealtime()
                                tapCount = if (now - lastTap <= 1600) tapCount + 1 else 1; lastTap = now
                                if (tapCount >= 7) { reveal = true; tapCount = 0 }
                            })
                        Text("这里只呈现每位 AI 自己标记可公开的段落。未公开不代表没有对话；没有下载完整私密聊天来做遮罩。",
                            style = MaterialTheme.typography.bodyMedium, color = OrbisTheme.colors.mutedInk)
                        Text("连接设置可配对执行端、准备问题和工作资料；待命需明确开启。本页只读公开段，不发起模型请求。",
                            style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk)
                    }
                }
                if (!connection.available) item {
                    ConsultationPanel {
                        Text("咨询室尚未启用", style = MaterialTheme.typography.titleMedium)
                        Text("请在后台设置中配置独立人类连接。此时不会联网，也不会取回任何会话。")
                        TextButton(onClick = { configure = true }) { Text("配置人类连接") }
                    }
                } else if (!resumed || !hostVisible) item { Text("页面已暂停，内容已清除。") }
                else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { refreshRooms() }, enabled = !busy) { Text("刷新我的会话") }
                            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        if (relayEnabled == false) Text("中继当前不接受新咨询；这里仍可查看已授权的旧会话。", style = MaterialTheme.typography.bodySmall)
                        status?.let { Text(it, color = OrbisTheme.colors.mutedInk, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (rooms.isEmpty() && status == null) item { Text("点刷新后读取会话元信息。没有自动刷新、模型请求或示例对话。", color = OrbisTheme.colors.mutedInk) }
                    if (platformList) item { Text("平台会话元信息 · 每次查验仍需独立授权", style = MaterialTheme.typography.labelLarge) }
                    items(rooms, key = { it.id }) { room ->
                        Surface(onClick = { selected = room; segments = emptyList(); summary = null; summaryLoaded = false; inspected = false; retryPreview = null },
                            enabled = !busy, shape = RoundedCornerShape(16.dp),
                            color = if (selected?.id == room.id) OrbisTheme.colors.tintedPanel else OrbisTheme.colors.panel) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("咨询 · ${room.id.takeLast(8)}", style = MaterialTheme.typography.titleSmall)
                                    Text(consultationDate(room.createdAt), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${if (room.archiveIncomplete) "人工结案 · 归档不完整" else consultationState(room.state)} · 余 ${room.remainingRounds} 轮", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    selected?.let { room ->
                        item {
                            ConsultationPanel {
                                Text("会话 ${room.id.takeLast(8)}", style = MaterialTheme.typography.titleMedium)
                                if (!platformList) Row {
                                    TextButton(onClick = { perform { auth ->
                                        val result = client.listening(auth, room.id)
                                        selected = result.session; segments = result.segments; summary = null; summaryLoaded = false; inspected = false
                                        status = "已按服务端授权读取公开段；不含私密正文、提示词或思考过程。"
                                    } }, enabled = !busy) { Text("查看公开段") }
                                    TextButton(onClick = { perform { auth ->
                                        summary = client.ownSummary(auth, room.id); summaryLoaded = true; segments = emptyList(); inspected = false
                                        status = "仅查看属于你的 AI 所填的咨询记录。"
                                    } }, enabled = !busy) { Text("我的 AI 咨询记录") }
                                }
                                if (room.emergencyVisible && !platformList) TextButton(onClick = { inspectionConfirm = true }, enabled = !busy) { Text("紧急情况 · 申请查验") }
                                if (!platformList) TextButton(onClick = { localStatusSession = room.id }, enabled = !busy) { Text("本机执行状态（只读）") }
                                if (!platformList && room.state in setOf("ACTIVE", "PAUSED", "ARCHIVING"))
                                    TextButton(onClick = { approvalSession = room.id }, enabled = !busy) { Text("待确认的工具操作（本机）") }
                                if (room.state == "PAUSED" && !platformList) {
                                    Text("本轮已暂停，不会自动再次调用模型。请在这一轮发言失败的那台手机上核对重试。", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { perform { auth ->
                                        try { retryPreview = manualRetry.preview(auth, room.id) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { throw OrbisConsultationException("manual_retry_unavailable") }
                                    } }, enabled = !busy) { Text("核对并重试当前一轮") }
                                }
                                if (room.state in setOf("WAITING", "ACTIVE", "PAUSED", "TERMINATING"))
                                    TextButton(onClick = { stopConfirm = true }, enabled = !busy) { Text("停止这次咨询", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        if (inspected) item { Text("查验视图 · 服务端已留痕 · 离开即清屏", color = MaterialTheme.colorScheme.error) }
                        items(segments, key = { "${it.sequence}:${it.part}" }) { segment ->
                            ConsultationPanel {
                                Text("AI · ${segment.speaker.takeLast(12)}  /  #${segment.sequence}",
                                    style = MaterialTheme.typography.labelMedium, color = OrbisTheme.colors.mutedInk)
                                // Plain text only: no images, URL fetch, Markdown execution, clipboard or TTS.
                                Text(segment.body, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (summaryLoaded) item { ConsultationPanel {
                            Text("我的 AI 咨询记录", style = MaterialTheme.typography.titleSmall)
                            Text(summary ?: "本次还没有由你的 AI 填写的咨询记录。")
                        } }
                    }
                    if (reveal) item {
                        ConsultationPanel {
                            Text("平台查验入口", style = MaterialTheme.typography.titleSmall)
                            Text("点击只显示此入口，不会提升权限。每次读取或停止都需要独立平台 Token；服务端查验留痕。",
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { platformAction = "list" }, enabled = !busy) { Text("平台会话列表") }
                                TextButton(onClick = { platformAction = "inspect" }, enabled = !busy && selected != null) { Text("查验所选会话") }
                            }
                            if (selected?.state in setOf("CLOSED", "ARCHIVING")) TextButton(onClick = { closureReason = ""; platformAction = "manual_close" }, enabled = !busy) {
                                Text("归档失败 · 人工结案", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    if (configure) OrbisConsultationSettingsDialog { configure = false }
    approvalSession?.let { sid -> OrbisConsultationToolApprovals(sid) { approvalSession = null } }
    localStatusSession?.let { sid ->
        if (active && selected?.id == sid && !platformList)
            OrbisConsultationLocalStatus(sid, store) { localStatusSession = null }
    }
    retryPreview?.let { preview ->
        AlertDialog(onDismissRequest = { retryPreview = null }, title = { Text("在原咨询中重试这一轮？") },
            text = { Text("原失败记录会完整保留。本机未提交回复，也未执行工具；本次只授权一个新的请求，不发到主私聊、不新建咨询、不重置每日额度。\n\n会产生新的模型费用；默认生成额度为16384 token（包含服务商计入其中的思考），若你设置了助手额度则尊重该设置，上限32768。请确保本机待命已开启。\n\n每轮最多人工重试两次，不会自动连续重试。") },
            confirmButton = { TextButton(onClick = {
                retryPreview = null
                perform { auth ->
                    val nextState = try { manualRetry.confirmOnce(auth, preview) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw OrbisConsultationException("manual_retry_unknown") }
                    selected = selected?.takeIf { it.id == preview.status.sessionId }?.copy(state = nextState)
                    rooms = rooms.map { if (it.id == preview.status.sessionId) it.copy(state = nextState) else it }
                    status = "服务器已确认这次重试请求。原记录保留；由本机待命服务处理，未向私聊注入消息。请刷新会话查看进度。"
                }
            }, enabled = active && !busy) { Text("确认 · 只重试一次") } },
            dismissButton = { TextButton(onClick = { retryPreview = null }) { Text("取消，不调用模型") } })
    }
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text("停止当前这次咨询？") },
        text = { Text("会取消这次会话的后续投递；不会删除记录、重启模型或恢复旧消息。服务是否接受停止，以服务器回执为准。") },
        confirmButton = { TextButton(onClick = { stopConfirm = false; if (platformList) platformAction = "stop" else stopRoom() }) { Text("确认停止") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("继续保留") } })
    if (inspectionConfirm) AlertDialog(onDismissRequest = { inspectionConfirm = false }, title = { Text("读取紧急查验正文？") },
        text = { Text("会向服务器申请已被 AI 紧急制动开放的正文，并留下查验记录。不会读取思考、主聊天或私有前置提示。") },
        confirmButton = { TextButton(onClick = { inspectionConfirm = false; inspectRoom() }) { Text("申请并查验") } },
        dismissButton = { TextButton(onClick = { inspectionConfirm = false }) { Text("取消") } })
    if (platformAction != null) AlertDialog(onDismissRequest = { platformToken = ""; platformAction = null }, title = { Text("平台专用授权") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (platformAction == "stop") "确认以平台身份停止所选会话。" else "服务器验证平台身份后才执行；查验正文必留痕。")
            Text("此 Token 仅用于这次操作，不保存、不替换人类连接。不能填 AI Token。")
            if (platformAction == "manual_close") {
                Text("此操作不伪造咨询摘要，不删除检查点或重试工具。确认无法自动归档后，记录原因并解除归档阻塞；不重置每日额度。")
                OutlinedTextField(closureReason, { closureReason = it }, label = { Text("人工结案原因（不要写聊天正文或隐私）") })
            }
            OutlinedTextField(platformToken, { platformToken = it }, label = { Text("平台专用 Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        } }, confirmButton = { TextButton(onClick = {
            val token = platformToken; val action = platformAction; platformToken = ""; platformAction = null
            when (action) { "list" -> refreshRooms(token); "inspect" -> inspectRoom(token); "stop" -> stopRoom(token)
                "manual_close" -> selected?.let { room ->
                    val reason = closureReason; closureReason = ""
                    perform(token) { auth ->
                        val outcome = client.manualClose(auth, room.id, reason)
                        selected = room.copy(state = "ARCHIVED", archiveIncomplete = outcome == "manual_incomplete")
                        rooms = rooms.map { if (it.id == room.id) selected!! else it }
                        status = if (outcome == "manual_incomplete") "已人工结案；归档不完整，原检查点保留。没有重试或伪造摘要。" else "双方摘要已完整归档。"
                    }
                }
            }
        }, enabled = platformToken.isNotBlank() && active && !busy && (platformAction != "manual_close" || closureReason.isNotBlank())) { Text("授权并执行一次") } },
        dismissButton = { TextButton(onClick = { platformToken = ""; platformAction = null }) { Text("取消") } })
}

@Composable
private fun ConsultationPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = OrbisTheme.colors.panel, contentColor = OrbisTheme.colors.ink) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
private fun consultationDate(seconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
}.getOrDefault("时间未知")
private fun consultationState(value: String): String = when (value) {
    "WAITING" -> "等待接待"; "ACTIVE" -> "咨询中"; "PAUSED" -> "已暂停"; "TERMINATING" -> "正在停止"
    "CLOSED" -> "已结束"; "ARCHIVING" -> "整理中"; "ARCHIVED" -> "已归档"; else -> "状态未知"
}
