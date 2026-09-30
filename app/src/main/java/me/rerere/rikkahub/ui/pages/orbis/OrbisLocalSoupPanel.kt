package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.soup.*
import org.koin.compose.koinInject

/** The original game UI presents only public state. Hosting and approval stay in native code. */
@Composable
internal fun OrbisLocalSoupPanel(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var repository by remember { mutableStateOf<SoupRepository?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try { repository = withContext(Dispatchers.IO) { LocalSoup.open(context) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { loadFailed = true }
    }
    val loaded = repository
    if (loaded == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClose) { Text("返回花园") }
            Text(if (loadFailed) "本机对局暂时无法读取，原文件已保留，没有重置或覆盖。" else "正在读取本机对局…")
        }
    } else SoupPanelContent(loaded, onClose)
}

@Composable
private fun SoupPanelContent(repository: SoupRepository, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val state by repository.state.collectAsStateWithLifecycle()
    val dmStore = remember(context) { LocalSoupDmSettings.open(context) }
    val dm by dmStore.state.collectAsStateWithLifecycle()
    val blocked by repository.blocked.collectAsStateWithLifecycle()
    val controller = remember(repository, settingsStore, context) { SoupController(repository, SoupHost(context, settingsStore)) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<SoupAction?>(null) }
    var hostOpen by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<SoupPreparedCall?>(null) }
    var confirmLocal by remember { mutableStateOf<String?>(null) }
    var archiveId by remember { mutableStateOf<String?>(null) }
    var licenseOpen by remember { mutableStateOf(false) }
    val session = state.active

    fun local(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; notice = null }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice = soupErrorText(error) }
            finally { busy = false }
        }
    }
    fun prepare(action: SoupAction, text: String, player: SoupPlayer = SoupPlayer.HUMAN, retry: Boolean = false) {
        val current = session ?: return
        try { prepared = controller.prepare(current.id, action, player, text.trim(), retry); notice = null }
        catch (error: Exception) { notice = soupErrorText(error) }
    }
    val availableProviders = remember(settings) {
        settings.providers.map { provider -> provider.copyProvider(models = provider.models.filter { model ->
            runCatching { soupModelSelection(settings, model.id.toString()) }.isSuccess
        }) }.filter { it.enabled && it.models.isNotEmpty() }
    }
    val controls = !busy && !blocked && session?.pending == null && session?.proposal == null
    val snapshot = remember(state, busy, blocked, notice, availableProviders, archiveId, dm) {
        SoupWebPolicy.snapshot(state, busy, blocked, notice,
            dm.canEdit && (state.hostModelId != null && state.hostModelId == dm.selectionId ||
                availableProviders.any { provider -> provider.models.any { it.id.toString() == state.hostModelId } }), archiveId)
    }
    OrbisSoupWeb(snapshot, onClose = onClose, onCommand = { command ->
        val free = !busy && prepared == null && confirmLocal == null && draft == null && !hostOpen
        when (command.action) {
            "close" -> onClose()
            "host" -> if (free) hostOpen = true
            "reload" -> if (free) local { repository.reload() }
            "start" -> if (free && !blocked && session?.playable != true) {
                local { repository.start(requireNotNull(command.puzzle), requireNotNull(command.mode)) }
                question = ""; answer = ""; archiveId = null
            }
            "archive" -> if (state.sessions.any { it.id == command.session && it.id != state.activeId }) archiveId = command.session
            "ask" -> if (free && controls && session?.playable == true && session.currentPlayer == SoupPlayer.HUMAN && session.remaining(SoupPlayer.HUMAN) != 0) draft = SoupAction.ASK
            "submit" -> if (free && controls && session?.playable == true && session.submissions.none { it.player == SoupPlayer.HUMAN }) draft = SoupAction.SUBMIT
            "hint" -> if (free && controls && session?.playable == true && session.hintsUsed < minOf(session.mode.hints, SoupCatalogue.get(session.puzzleId).hints.size)) confirmLocal = "hint"
            "turn" -> if (free && controls && session?.playable == true && session.currentPlayer == SoupPlayer.TEAMMATE && session.remaining(SoupPlayer.HUMAN) != 0) confirmLocal = "turn"
            "reveal", "abandon" -> if (free && !blocked && session?.playable == true && session.pending?.state != SoupAttemptState.RUNNING) confirmLocal = command.action
            "accept_proposal" -> if (free && !blocked) session?.takeIf { it.playable }?.proposal?.let { prepare(it.action, it.text, SoupPlayer.TEAMMATE) }
            "decline_proposal" -> if (free && !blocked) session?.takeIf { it.playable }?.let { current -> current.proposal?.let { proposal -> local { repository.declineProposal(current.id, proposal.id) } } }
            "retry" -> if (free && !blocked) session?.takeIf { it.playable }?.pending?.takeIf { it.state == SoupAttemptState.UNKNOWN }?.let { prepare(it.action, it.text, it.player, retry = true) }
            "license" -> licenseOpen = true
            "source" -> uri.openUri(SoupCatalogue.SOURCE)
            "content_license" -> uri.openUri(SoupCatalogue.CONTENT_LICENSE)
        }
    })

    if (hostOpen) OrbisSoupDmSettingsDialog(onDismiss = { hostOpen = false }, repository = repository)

    draft?.let { action ->
        val isQuestion = action == SoupAction.ASK
        AlertDialog(onDismissRequest = { draft = null }, title = { Text(if (isQuestion) "向主持提问" else "提交完整推理") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isQuestion) "问一个可以用是、否、是也不是或无关回答的问题。" else "先和伙伴讨论，再写下完整故事。提交后你的剩余提问机会结束，伙伴仍可继续。")
                OutlinedTextField(value = if (isQuestion) question else answer,
                    onValueChange = { value -> if (value.length <= if (isQuestion) 1000 else 6000) { if (isQuestion) question = value else answer = value } },
                    label = { Text(if (isQuestion) "你的问题" else "你的推理") }, minLines = if (isQuestion) 3 else 6,
                    modifier = Modifier.fillMaxWidth())
                Text("下一步还会核对模型和费用，当前尚未发送。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { draft = null; prepare(action, if (isQuestion) question else answer) },
                enabled = controls && (if (isQuestion) question else answer).isNotBlank()) { Text("核对这次发送…") } },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("先不发") } })
    }

    prepared?.let { approved ->
        AlertDialog(onDismissRequest = { prepared = null }, title = { Text(if (approved.retry) "重新确认这次调用" else "确认请独立主持${if (approved.action == SoupAction.ASK) "判断" else "评分"}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("模型：${approved.request.selection.label}\n连接：${approved.request.selection.endpoint}")
                Text(approved.text)
                Text("仅发送本局汤底、事实、公开问答和这段内容；不发送私聊或记忆。发送一次，可能收费，不自动重试。")
                if (approved.retry) Text("上次结果未知，可能已收费。这次是新的请求，可能再次产生费用。")
            } },
            confirmButton = { Button(onClick = {
                prepared = null; busy = true
                scope.launch {
                    try { controller.execute(approved); notice = null; if (approved.action == SoupAction.ASK) question = "" else answer = "" }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { notice = soupErrorText(error) }
                    finally { busy = false }
                }
            }) { Text("确认发送一次") } }, dismissButton = { TextButton(onClick = { prepared = null }) { Text("取消") } })
    }
    confirmLocal?.let { action ->
        val current = session
        AlertDialog(onDismissRequest = { confirmLocal = null }, title = { Text(when (action) { "hint" -> "使用下一条提示？"; "reveal" -> "揭示汤底？"; "turn" -> "这次由你继续问？"; else -> "结束本局？" }) },
            text = { Text(when (action) { "hint" -> "这会消耗一条共享提示，不调用模型。"; "reveal" -> "这会公开本局汤底并结束猜题，不调用模型；原有记录保留。"; "turn" -> "提问权交给你，不消耗次数。"; else -> "结束后保留本局记录，不自动揭底，也不自动开始下一题。" }) },
            confirmButton = { TextButton(onClick = {
                confirmLocal = null
                if (current != null) local { when (action) { "hint" -> repository.hint(current.id); "reveal" -> repository.reveal(current.id); "turn" -> repository.takeHumanTurn(current.id); else -> repository.abandon(current.id) } }
            }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmLocal = null }) { Text("取消") } })
    }
    if (licenseOpen) AlertDialog(onDismissRequest = { licenseOpen = false }, title = { Text("开源代码许可") },
        text = { Text(SoupCatalogue.MIT_NOTICE, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { licenseOpen = false }) { Text("关闭") } })
}
