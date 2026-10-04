package me.rerere.rikkahub.ui.pages.orbis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.privacy.*
import org.koin.compose.koinInject

/** Owner navigation is local to this secure UI; it never changes the ordinary-chat assistant. */
@Composable
fun OrbisPrivateRoomPage(assistantId: String, assistantName: String, onClose: () -> Unit,
                        suppliedRepository: PrivateVaultRepository? = null,
                        suppliedSettings: (() -> Settings)? = null,
                        suppliedRepositoryForOwner: ((String) -> PrivateVaultRepository)? = null) {
    val context = LocalContext.current
    val settingsStore = if (suppliedSettings == null) koinInject<SettingsStore>() else null
    val settings = if (suppliedSettings == null) checkNotNull(settingsStore).settingsFlow.collectAsState().value
        else suppliedSettings()
    val owners = privateRoomOwners(settings.assistants, assistantId, assistantName)
    var selectedId by remember(assistantId) { mutableStateOf(assistantId) }
    var screen by remember(assistantId) { mutableStateOf(PrivateRoomScreen.HOME) }
    val selected = owners.firstOrNull { it.id == selectedId } ?: PrivateRoomOwner(selectedId, "AI")
    // Disposing this key cancels work and clears plaintext before another owner's UI is created.
    key(selectedId) {
        val repository = remember(selectedId, suppliedRepository, suppliedRepositoryForOwner) {
            suppliedRepositoryForOwner?.invoke(selectedId)
                ?: suppliedRepository?.takeIf { privateRoomUsesInitialRepository(selectedId, assistantId) }
                ?: AndroidPrivateVaults.open(context, selectedId)
        }
        PrivateRoomContent(selected, owners, screen, { screen = it }, { owner ->
            val destination = privateRoomOwnerDestination(screen)
            selectedId = owner.id
            screen = destination
        }, onClose, repository)
    }
}

@Composable
private fun PrivateRoomContent(owner: PrivateRoomOwner, owners: List<PrivateRoomOwner>, screen: PrivateRoomScreen,
                               navigate: (PrivateRoomScreen) -> Unit, selectOwner: (PrivateRoomOwner) -> Unit,
                               onClose: () -> Unit, repo: PrivateVaultRepository) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val refreshGate = remember { PrivateRoomRefreshGate(foreground) }
    var status by remember { mutableStateOf<PrivateVaultStatus?>(null) }
    var requests by remember { mutableStateOf<List<PrivateVaultAccessRequest>>(emptyList()) }
    var requestsKnown by remember { mutableStateOf(false) }
    // No sensitive text, title, purpose, recovery code or grant enters Android saved state.
    var record by remember { mutableStateOf<PrivateVaultRecord?>(null) }
    var pageOffset by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf<List<PrivateVaultRecordInfo>>(emptyList()) }
    var selectedRequest by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf("") }
    var enteredCode by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var importedUri by rememberSaveable(owner.id) { mutableStateOf<String?>(null) }
    var exportPendingOwner by rememberSaveable(owner.id) { mutableStateOf<String?>(null) }
    var importPendingOwner by rememberSaveable(owner.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun clearVisibleSecrets() {
        record = null; granted = emptyList(); selectedRequest = null; pageOffset = 0
        recoveryCode = ""; enteredCode = ""; purpose = ""
    }
    suspend fun refresh(): Boolean {
        val previousRequests = requests
        return privateRoomRefreshLatest(refreshGate, read = {
            withContext(Dispatchers.IO) {
                val current = repo.status()
                current to privateRoomRefreshRequests(current, previousRequests, repo::requestsForHuman)
            }
        }, publish = { pair ->
            status = pair.first; requests = pair.second.requests; requestsKnown = pair.second.known
            if (pair.first.availability == PrivateVaultAvailability.READY && !pair.second.known)
                notice = "暂时无法核对已有申请，原申请没有被删除。请刷新申请状态后再提交新申请。"
        }, failed = {
            clearVisibleSecrets()
            status = null; requests = emptyList(); requestsKnown = false
            notice = "暂时无法核对空间状态，原密文保留。未确认不存在前不能创建，请重新核对。"
        })
    }
    fun go(destination: PrivateRoomScreen) {
        if (!busy) { clearVisibleSecrets(); notice = ""; navigate(destination) }
    }
    fun close() { refreshGate.invalidate(false); job?.cancel(); clearVisibleSecrets(); onClose() }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        if (!foreground) { notice = "页面暂未就绪，没有执行操作。请返回前台后再试。"; return }
        // A status read started before this mutation must not publish a pre-commit snapshot.
        refreshGate.invalidate(true)
        busy = true; notice = ""
        job = scope.launch {
            try { block(); refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                clearVisibleSecrets()
                // A preceding request commit may have succeeded even if its receipt/refresh failed.
                requestsKnown = false
                val failure = privateRoomFailureNotice(error)
                val refreshed = refresh()
                if (foreground) notice = failure + if (refreshed) "已只读核对当前空间状态。"
                    else "暂时无法核对空间状态，请先刷新；不能据此重新创建。"
            }
            finally { busy = false }
        }
    }
    DisposableEffect(lifecycle, owner.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false; refreshGate.invalidate(false); status = null
                job?.cancel(); clearVisibleSecrets(); requests = emptyList(); requestsKnown = false
            }
            if (event == Lifecycle.Event.ON_START) {
                foreground = true; refreshGate.invalidate(true); status = null
                requestsKnown = false
                scope.launch { refresh() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); refreshGate.invalidate(false); job?.cancel(); clearVisibleSecrets() }
    }
    LaunchedEffect(owner.id) {
        refresh()
    }
    LaunchedEffect(selectedRequest, status?.enabled) {
        val requestId = selectedRequest ?: return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val stillGranted = withContext(Dispatchers.IO) {
                runCatching { repo.isAccessGranted(requestId) }.getOrDefault(false)
            }
            if (!stillGranted) {
                clearVisibleSecrets(); notice = "许可已到期或撤销，内容已收起。"; refresh(); break
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val expectedOwner = exportPendingOwner
        exportPendingOwner = null
        if (uri != null && expectedOwner == owner.id) act {
            withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                savePrivateRoomExport(write = { repo.exportEncrypted(it) },
                    open = { context.contentResolver.openOutputStream(uri, "wt") ?: error("private_export_unavailable") },
                    reopen = { context.contentResolver.openInputStream(uri) ?: error("private_export_verification_unavailable") },
                    checkCancelled = { coroutine.ensureActive() })
            }
            notice = "密文备份已保存并读回校验。它不含恢复码；请将恢复码另行离线保管。"
        } else if (uri != null) notice = "保存来源已失效，不能确认导出成功；请重新导出。"
    }
    fun exportCiphertext() {
        clearVisibleSecrets(); exportPendingOwner = owner.id
        try { export.launch("Orbis-private-${owner.id.take(8)}.orpv") }
        catch (_: Exception) { exportPendingOwner = null; notice = "无法打开保存位置，没有导出成功。" }
    }
    val chooseImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val expectedOwner = importPendingOwner
        importPendingOwner = null
        if (uri != null && expectedOwner == owner.id) { importedUri = uri.toString(); enteredCode = "" }
        else if (uri != null) notice = "导入来源已失效，没有导入或覆盖任何空间。"
    }

    Dialog(onDismissRequest = {
        if (!busy) { if (screen == PrivateRoomScreen.HOME) close() else go(PrivateRoomScreen.HOME) }
    }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn,
        dismissOnClickOutside = false, dismissOnBackPress = !busy)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${owner.name}的隐私室", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::close) { Text("关闭") }
                }
                if (screen != PrivateRoomScreen.HOME) TextButton(enabled = !busy,
                    onClick = { go(PrivateRoomScreen.HOME) }) { Text("返回隐私室") }
                // Keep progress/errors outside the scrollable form. A failed local creation
                // must be visible where the user tapped, including large-font/small screens.
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("private-room-progress"))
                    Text(privateRoomBusyLabel(screen), style = MaterialTheme.typography.bodySmall)
                }
                if (notice.isNotBlank()) Box(Modifier.fillMaxWidth().heightIn(max = 132.dp)
                    .verticalScroll(rememberScrollState()).testTag("private-room-notice")) {
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
                key(screen) {
                    LazyColumn(Modifier.weight(1f).testTag("private-room-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (screen) {
                            PrivateRoomScreen.HOME -> {
                                item {
                                    Text("默认上锁。人类须申请，由 AI 决定是否分享；未获准的标题和正文不会显示。")
                                    Text("这里提供应用内阅读权限与本机加密保护。助手读取的内容仍会由所用模型服务处理；不保证聊天或服务商不留存，也不保证防拆包。离线恢复码是人类的紧急解密能力。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                item { PrivateRoomHomeCard("选择隐私室主人", "当前：${owner.name}。每个助手有独立房间，选择不改变聊天中的助手。",
                                    "选择主人", !busy) { go(PrivateRoomScreen.OWNERS) } }
                                item { PrivateRoomHomeCard("进入申请", "填写来意，由 AI 决定是否批准和分享哪些条目。本次获准内容只在限时阅读中显示。",
                                    "填写进入申请", !busy) { go(PrivateRoomScreen.REQUEST) } }
                                item { PrivateRoomHomeCard("新建隐私室", "为现有助手设置独立房间。已有房间只会打开，不会覆盖。",
                                    "选择助手新建", !busy) { go(PrivateRoomScreen.NEW_OWNER) } }
                                item {
                                    when (status?.availability) {
                                        PrivateVaultAvailability.READY -> PrivateRoomStatusPanel(status!!.enabled, status!!.recoveryConfirmed)
                                        PrivateVaultAvailability.ABSENT -> Text("这位助手尚未创建隐私室。")
                                        PrivateVaultAvailability.RECOVERY_REQUIRED -> Text("房间需要恢复码重新绑定；请先进入房间设置保留原密文。")
                                        PrivateVaultAvailability.UNREADABLE -> Text("房间暂不可读；请先保留原文件，勿卸载或清数据。")
                                        null -> Text("正在检查加密空间…")
                                    }
                                    TextButton(enabled = !busy, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("房间设置") }
                                }
                            }
                            PrivateRoomScreen.OWNERS, PrivateRoomScreen.NEW_OWNER -> {
                                item {
                                    Text(if (screen == PrivateRoomScreen.NEW_OWNER) "选择要创建房间的助手" else "选择隐私室主人",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("仅从现有助手中选择。每个房间绑定原主人；切换不会搬走原内容，也不会改变聊天助手。")
                                }
                                owners.forEach { candidate -> item(candidate.id) {
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                        clearVisibleSecrets(); selectOwner(candidate)
                                    }) { Text(candidate.name) }
                                } }
                            }
                            PrivateRoomScreen.CREATE -> {
                                item {
                                    Text("第 1 步 · 创建属于 ${owner.name} 的加密空间", style = MaterialTheme.typography.titleMedium)
                                    Text("只在本机创建空间，不需要先选模型，也不会联网或自动启用。")
                                }
                                when (status?.availability) {
                                    PrivateVaultAvailability.ABSENT -> item {
                                        Button(enabled = !busy && foreground, modifier = Modifier.fillMaxWidth(), onClick = { act {
                                            recoveryCode = withContext(Dispatchers.IO) { repo.create().recoveryCode }
                                            notice = "空间已创建，仍未启用。请离线保存恢复码。"
                                            // Unlike go(), this forward step retains the code only in memory.
                                            navigate(PrivateRoomScreen.RECOVERY_CODE)
                                        } }) { Text("创建加密隐私室") }
                                        Text("下一步会单独显示恢复码。没有确认离线保管前，不能开启。")
                                    }
                                    PrivateVaultAvailability.READY -> item {
                                        PrivateRoomStatusPanel(status!!.enabled, status!!.recoveryConfirmed)
                                        Text("这位主人已有空间，没有重复创建或覆盖。")
                                        Button(enabled = !busy && foreground, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("打开已有房间设置") }
                                    }
                                    PrivateVaultAvailability.RECOVERY_REQUIRED, PrivateVaultAvailability.UNREADABLE -> item {
                                        Text("已有空间需要恢复或暂不可读；不会新建空空间覆盖原件。")
                                        Button(enabled = !busy && foreground, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("查看原空间状态") }
                                    }
                                    null -> item {
                                        Text("正在检查空间状态；未确认不存在前不能创建。")
                                        TextButton(enabled = !busy && foreground, onClick = { act { refresh() } }) { Text("重新核对空间状态") }
                                    }
                                }
                            }
                            PrivateRoomScreen.RECOVERY_CODE -> {
                                item {
                                    Text("第 2 步 · 离线保存恢复码", style = MaterialTheme.typography.titleMedium)
                                    Text("空间已创建但仍暂停。请抄写到应用外安全位置，不与密文备份一起存放，不发给 AI。")
                                    Text("恢复码用于换机或重装后配合原密文备份恢复，不是日常密码。只有恢复码、没有原密文文件，无法找回内容。")
                                    Text("关闭、返回或退到后台会隐藏此码；本机仍可解密时，可回房间设置明确重新签发。")
                                }
                                if (recoveryCode.isNotEmpty()) {
                                    item { Text(recoveryCode, Modifier.testTag("private-room-recovery-code"), style = MaterialTheme.typography.bodyLarge) }
                                    item {
                                        Button(enabled = !busy && foreground, modifier = Modifier.fillMaxWidth(), onClick = { act {
                                            withContext(Dispatchers.IO) { repo.confirmRecoverySaved() }
                                            recoveryCode = ""
                                            notice = "已确认离线保管，空间仍暂停。请单独决定是否开启。"
                                            navigate(PrivateRoomScreen.COMPLETE)
                                        } }) { Text("我已在应用外安全保管恢复码") }
                                    }
                                } else item {
                                    Text("恢复码已收起，未自动确认你已保存。")
                                    Button(enabled = !busy && foreground, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("返回设置核对 / 重新签发") }
                                }
                            }
                            PrivateRoomScreen.COMPLETE -> {
                                item {
                                    Text(if (status?.availability == PrivateVaultAvailability.READY && status?.recoveryConfirmed == true)
                                        "第 3 步 · 创建流程完成" else "第 3 步 · 核对完成状态", style = MaterialTheme.typography.titleMedium)
                                    Text("只有明确确认离线保管后才能开启；开启本身不会自动调用模型。")
                                }
                                item {
                                    if (status?.availability == PrivateVaultAvailability.READY) {
                                        PrivateRoomStatusPanel(status!!.enabled, status!!.recoveryConfirmed)
                                        if (status!!.recoveryConfirmed) Button(enabled = !busy && foreground,
                                            modifier = Modifier.fillMaxWidth(), onClick = { act {
                                                withContext(Dispatchers.IO) { repo.setEnabled(!status!!.enabled) }
                                            } }) { Text(if (status!!.enabled) "暂停并撤销当前许可" else "开启隐私室") }
                                    } else Text("当前状态尚未确认，请先到设置核对；没有自动开启。")
                                    TextButton(enabled = !busy && foreground, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("房间设置") }
                                }
                            }
                            PrivateRoomScreen.SETTINGS -> {
                                item {
                                    Text("房间设置", style = MaterialTheme.typography.titleMedium)
                                    PrivateRoomModelPanel(owner.name)
                                    Text("这是应用内阅读权限与本机加密保护；所用模型服务仍处理助手读取的内容，不保证防拆包或服务商零留存。恢复码请离线保管，不要发给 AI 或放进聊天。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                when (status?.availability) {
                                    PrivateVaultAvailability.ABSENT -> item {
                                        Text("这位助手尚未创建隐私室。创建不需要先配置模型。")
                                        Button(enabled = !busy && foreground, onClick = { go(PrivateRoomScreen.CREATE) }) { Text("开始创建本机空间") }
                                        OutlinedButton(enabled = !busy, onClick = {
                                            importPendingOwner = owner.id
                                            try { chooseImport.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }
                                            catch (_: Exception) { importPendingOwner = null; notice = "无法打开备份选择器，没有导入。" }
                                        }) { Text("选择已有密文备份") }
                                    }
                                    PrivateVaultAvailability.RECOVERY_REQUIRED -> item {
                                        Text("设备密钥不可用或刚完成救援恢复。原密文仍在；请用独立恢复码重新绑定本机。")
                                        OutlinedButton(enabled = !busy, onClick = ::exportCiphertext) { Text("先导出原密文备份") }
                                    }
                                    PrivateVaultAvailability.UNREADABLE -> item {
                                        Text("隐私室文件不可读。没有自动重置；请先用紧急备份保住原文件，勿卸载或清数据。")
                                    }
                                    PrivateVaultAvailability.READY -> item {
                                        PrivateRoomStatusPanel(status!!.enabled, status!!.recoveryConfirmed)
                                        if (status!!.recoveryConfirmed) {
                                            if (!status!!.enabled) Text("第 3 步 · 确认开启。不会自动发起模型请求。")
                                            Button(enabled = !busy, onClick = { act {
                                                clearVisibleSecrets()
                                                withContext(Dispatchers.IO) { repo.setEnabled(!status!!.enabled) }
                                            } }) { Text(if (status!!.enabled) "暂停并撤销当前许可" else "开启隐私室") }
                                        } else if (recoveryCode.isEmpty()) Text("第 2 步未完成。若未保管恢复码，可重新签发；不要清除空间。")
                                        OutlinedButton(enabled = !busy, onClick = { act {
                                            clearVisibleSecrets()
                                            recoveryCode = withContext(Dispatchers.IO) { repo.issueRecoveryCode().recoveryCode }
                                            notice = "新恢复码需离线保存。旧备份配套的旧码仍可解密旧备份，无法远程撤销。"
                                            navigate(PrivateRoomScreen.RECOVERY_CODE)
                                        } }) { Text("重新签发恢复码") }
                                        OutlinedButton(enabled = !busy, onClick = ::exportCiphertext) { Text("导出密文备份（不含恢复码）") }
                                        Text("适合少量文字；密文导出上限 64 MiB，超限不会删除原件。", style = MaterialTheme.typography.bodySmall)
                                    }
                                    null -> item {
                                        Text("正在检查加密空间…")
                                        TextButton(enabled = !busy && foreground, onClick = { act { refresh() } }) { Text("重新核对空间状态") }
                                    }
                                }
                                if (status?.availability == PrivateVaultAvailability.RECOVERY_REQUIRED || importedUri != null) item {
                                    OutlinedTextField(value = enteredCode, onValueChange = { enteredCode = it.take(100) },
                                        label = { Text("离线恢复码") }, visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                                    Button(enabled = !busy && enteredCode.isNotBlank(), onClick = { act {
                                        val code = enteredCode.trim(); val uri = importedUri
                                        enteredCode = ""
                                        withContext(Dispatchers.IO) {
                                            if (uri == null) repo.recoverLocal(code)
                                            else context.contentResolver.openInputStream(Uri.parse(uri))?.use { repo.importEncrypted(it, code) }
                                                ?: error("private_import_unavailable")
                                        }
                                        importedUri = null
                                        notice = "已恢复并绑定本机，旧许可已清除。保持暂停，请自行决定是否开启。"
                                    } }) { Text("用恢复码恢复（紧急解密）") }
                                }
                            }
                            PrivateRoomScreen.REQUEST -> {
                                item {
                                    Text("进入申请", style = MaterialTheme.typography.titleMedium)
                                    Text("人类只填写来意，不能自行批准。AI 可拒绝；获准条目仅限本次申请，最长十分钟。关闭、退后台或到期会收起显示。")
                                    Text("提交只在本机登记申请，不调用模型、不自动发送聊天。请回到这位助手的聊天，让他查看隐私室申请；是否批准、分享哪些条目由他决定。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (status?.availability != PrivateVaultAvailability.READY || status?.enabled != true) item {
                                    Text("当前房间尚未开启或需要恢复，请先完成房间设置。")
                                    TextButton(enabled = !busy, onClick = { go(PrivateRoomScreen.SETTINGS) }) { Text("前往房间设置") }
                                } else {
                                    item {
                                        OutlinedTextField(value = purpose, onValueChange = { purpose = it.take(1000) },
                                            label = { Text("申请目的（AI 可拒绝）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                        if (status!!.recordCount == 0) Text("当前没有可申请阅读的记录。AI 可以在隐私室自行决定是否写入。")
                                        val pending = requests.any { it.status == PrivateVaultRequestStatus.PENDING }
                                        Button(enabled = privateRoomCanSubmitRequest(busy, requestsKnown, purpose,
                                            status!!.recordCount, requests),
                                            onClick = { act {
                                                val submittedPurpose = purpose
                                                withContext(Dispatchers.IO) { repo.requestAccess(submittedPurpose, accessDurationMs = 10 * 60_000L) }
                                                purpose = ""
                                                notice = "已提交。请回到${owner.name}的聊天，请他查看隐私室申请；没有自动发送消息或另起模型请求。"
                                            } }) { Text("提交进入申请") }
                                        if (pending) Text("已有待批申请。请回到这位助手的聊天，请他查看隐私室申请；无需反复提交。")
                                        if (!requestsKnown) Text("尚未核对已有申请，请先刷新状态；不能把读取失败当作没有申请。")
                                        TextButton(enabled = !busy, onClick = { act { refresh() } }) { Text("刷新申请状态（不调用模型）") }
                                    }
                                    requests.forEach { request -> item(request.id) {
                                        Text("申请 ${request.id.take(8)} · ${privateRequestLabel(request.status)}")
                                        Text(request.purpose, style = MaterialTheme.typography.bodySmall)
                                        if (request.status == PrivateVaultRequestStatus.APPROVED) OutlinedButton(enabled = !busy, onClick = { act {
                                            val items = withContext(Dispatchers.IO) { repo.listGranted(request.id) }
                                            selectedRequest = request.id; granted = items; record = null
                                        } }) { Text("查看本次获准内容") }
                                        if (request.status in setOf(PrivateVaultRequestStatus.APPROVED, PrivateVaultRequestStatus.PENDING)) TextButton(enabled = !busy, onClick = { act {
                                            clearVisibleSecrets(); withContext(Dispatchers.IO) { repo.revokeAccess(request.id) }
                                        } }) { Text("撤销申请 / 结束阅读") }
                                    } }
                                    if (selectedRequest != null) item {
                                        Text("仅本次获准条目", style = MaterialTheme.typography.titleSmall)
                                        TextButton(onClick = { clearVisibleSecrets() }) { Text("收起本次获准内容") }
                                    }
                                    granted.forEach { info -> item("record-${info.id}") {
                                        OutlinedButton(enabled = !busy, onClick = { act {
                                            val requestId = checkNotNull(selectedRequest)
                                            record = withContext(Dispatchers.IO) { repo.readGranted(requestId, info.id) }
                                            pageOffset = 0
                                        } }) { Text(info.title) }
                                    } }
                                    record?.let { opened -> item("private-text") {
                                        Text(opened.title, style = MaterialTheme.typography.titleMedium)
                                        val page = privateRoomTextPage(opened.body, pageOffset)
                                        Text(page.first) // Bounded plain text only, no HTML/Markdown links or execution.
                                        Text("正文 ${pageOffset + 1}–${page.second} / ${opened.body.length} 字符", style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(enabled = pageOffset > 0, onClick = { pageOffset = (pageOffset - 16_384).coerceAtLeast(0) }) { Text("上一段") }
                                            TextButton(enabled = page.second < opened.body.length, onClick = { pageOffset = page.second }) { Text("下一段") }
                                        }
                                        TextButton(onClick = { record = null }) { Text("收起正文") }
                                    } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateRoomHomeCard(title: String, description: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            TextButton(enabled = enabled, onClick = onClick) { Text(action) }
        }
    }
}

@Composable
internal fun PrivateRoomStatusPanel(enabled: Boolean, recoveryConfirmed: Boolean) {
    Text(if (enabled) "隐私室已开启 · 内容仍上锁" else "隐私室已暂停 · 助手不能访问房间")
    if (!recoveryConfirmed) Text("尚未确认恢复码离线保管，不能开启。")
    Text("获准阅读最长十分钟，应用重启需重新申请；新增或修改内容不自动加入旧许可。", style = MaterialTheme.typography.bodySmall)
}

private fun privateRequestLabel(status: PrivateVaultRequestStatus) = when (status) {
    PrivateVaultRequestStatus.PENDING -> "等待 AI 决定"
    PrivateVaultRequestStatus.APPROVED -> "已批准（有时限）"
    PrivateVaultRequestStatus.DENIED -> "AI 已拒绝"
    PrivateVaultRequestStatus.REVOKED -> "已撤销"
    PrivateVaultRequestStatus.EXPIRED -> "已到期"
}

internal data class PrivateRoomRequestRefresh(val requests: List<PrivateVaultAccessRequest>, val known: Boolean)

/** Failed reads never prove absence. Also avoid creating a lock directory for an absent room. */
internal fun privateRoomRefreshRequests(status: PrivateVaultStatus, previous: List<PrivateVaultAccessRequest>,
    read: () -> List<PrivateVaultAccessRequest>): PrivateRoomRequestRefresh {
    if (status.availability != PrivateVaultAvailability.READY) return PrivateRoomRequestRefresh(emptyList(), false)
    return try { PrivateRoomRequestRefresh(read(), true) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { PrivateRoomRequestRefresh(previous, false) }
}

internal fun privateRoomCanSubmitRequest(busy: Boolean, requestsKnown: Boolean, purpose: String, recordCount: Int,
    requests: List<PrivateVaultAccessRequest>): Boolean = !busy && requestsKnown && purpose.isNotBlank() &&
    recordCount > 0 && requests.none { it.status == PrivateVaultRequestStatus.PENDING }
