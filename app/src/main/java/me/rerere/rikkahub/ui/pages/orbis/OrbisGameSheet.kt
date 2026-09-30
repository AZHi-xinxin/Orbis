package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.orbis.GomokuRules
import me.rerere.rikkahub.data.orbis.OrbisGameMatch
import me.rerere.rikkahub.data.orbis.OrbisGameRecord
import me.rerere.rikkahub.data.orbis.OrbisGameRepository
import me.rerere.rikkahub.data.orbis.OrbisGames
import me.rerere.rikkahub.data.orbis.OrbisMiniGames
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import me.rerere.rikkahub.data.orbis.OrbisMiniGameSession
import me.rerere.rikkahub.data.orbis.OrbisInstalledGame
import me.rerere.rikkahub.data.orbis.OrbisGameModelOpponent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.ProviderManager
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

/** One game-machine entry: native verified chess and separately sandboxed, game-reported HTML. */
@Composable
internal fun OrbisGameSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var repository by remember { mutableStateOf<OrbisGameRepository?>(null) }
    var miniRepository by remember { mutableStateOf<OrbisMiniGameRepository?>(null) }
    val settingsStore = koinInject<SettingsStore>()
    val providerManager = koinInject<ProviderManager>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val selectedAssistant = settings.assistants.find { it.id == settings.assistantId }
    val modelOpponent = remember(settingsStore, providerManager) { OrbisGameModelOpponent(settingsStore, providerManager) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(context, retry) {
        loadFailed = false
        try {
            repository = withContext(Dispatchers.IO) { OrbisGames.open(context) }
            miniRepository = withContext(Dispatchers.IO) { OrbisMiniGames.open(context) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { loadFailed = true }
    }
    val colors = OrbisTheme.colors
    // Keep one dialog and one GameContent composition while switching library/play views. A game
    // must not restart merely because its surrounding chrome becomes full-screen or is resized.
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      Surface(Modifier.fillMaxSize(), color = colors.panel) {
       Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val ready = repository
        val miniReady = miniRepository
        if (ready != null && miniReady != null) GameContent(ready, miniReady,
            selectedAssistant?.id?.toString(), selectedAssistant?.name?.ifBlank { "当前 AI" } ?: "未选择 AI",
            modelOpponent::chooseMove, onDismiss)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GameHeading("游戏机", "原生五子棋与 AI 写的小游戏", onDismiss)
            Text(if (loadFailed) "无法读取本地游戏记录。原文件保留，未重置、覆盖或生成示例战绩。" else "正在读取本地游戏记录…",
                fontSize = 13.sp, lineHeight = 20.sp, color = colors.mutedInk)
            if (loadFailed) TextButton(onClick = { retry++ }) { Text("重试读取") }
        }
       }
      }
    }
}

private data class OpenHtmlGame(val session: OrbisMiniGameSession, val source: OrbisInstalledGame)

@Composable
internal fun GameContent(
    repository: OrbisGameRepository,
    miniRepository: OrbisMiniGameRepository,
    assistantId: String?,
    assistantName: String,
    chooseModelMove: suspend (OrbisGameMatch) -> Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val miniState by miniRepository.state.collectAsStateWithLifecycle()
    val writeBlocked by repository.writeBlocked.collectAsStateWithLifecycle()
    var viewedId by rememberSaveable { mutableStateOf<String?>(null) }
    var htmlGame by remember { mutableStateOf<OpenHtmlGame?>(null) }
    var htmlCrashed by remember { mutableStateOf(false) }
    var useModel by rememberSaveable { mutableStateOf(false) }
    var callLimit by rememberSaveable { mutableStateOf(40) }
    var busy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var modelBusy by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var modelJob by remember { mutableStateOf<Job?>(null) }
    var modelAttempt by remember { mutableStateOf(0) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val gameListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val colors = OrbisTheme.colors
    val lifecycleOwner = LocalLifecycleOwner.current
    fun stopModel() {
        modelAttempt++
        modelJob?.cancel()
        modelJob = null
        modelBusy = false
    }
    fun requestModel(matchId: String) {
        if (modelBusy || busy || writeBlocked || viewedId != matchId ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        modelBusy = true
        modelError = null
        val attempt = ++modelAttempt
        modelJob = scope.launch {
            try {
                val ticket = withContext(Dispatchers.IO) { repository.reserveModelTurn(matchId) }
                val cell = chooseModelMove(ticket)
                ensureActive()
                withContext(Dispatchers.IO) {
                    ensureActive()
                    repository.applyModelMove(matchId, ticket.moves, ticket.modelCalls, cell)
                }
            } catch (_: TimeoutCancellationException) {
                if (attempt == modelAttempt) modelError = "模型等待已超时，本次已计入次数，没有替它落棋。"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (attempt == modelAttempt) modelError = gameModelErrorLabel(error.message) }
            finally { if (attempt == modelAttempt) { modelBusy = false; modelJob = null } }
        }
    }
    fun leaveBoard() { stopModel(); viewedId = null; htmlGame = null; modelError = null; detailsExpanded = false }
    fun perform(action: () -> Unit, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        saveFailed = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                busy = false
                onSuccess()
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { saveFailed = true }
            finally { busy = false }
        }
    }
    fun startNative() {
        stopModel()
        perform({ repository.start(
            opponent = if (useModel) GomokuRules.MODEL_OPPONENT else GomokuRules.BOT_VERSION,
            opponentLabel = if (useModel) assistantName.take(200) else "本地规则程序",
            opponentAssistantId = if (useModel) assistantId else null,
            modelCallLimit = callLimit,
        ) }, { viewedId = repository.state.value.active?.id; modelError = null; detailsExpanded = false })
    }
    fun startHtml(gameId: String) {
        if (busy) return
        busy = true
        saveFailed = false
        scope.launch {
            try {
                // Same monitor as the repository mutations: source and session revision are atomic.
                val opened = withContext(Dispatchers.IO) { synchronized(miniRepository) {
                    val session = miniRepository.start(gameId)
                    val source = miniRepository.readSnapshot().games.single {
                        it.id == session.gameId && it.sha256 == session.gameSha256
                    }
                    OpenHtmlGame(session, source)
                } }
                htmlCrashed = false
                detailsExpanded = false
                htmlGame = opened
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { saveFailed = true }
            finally { busy = false }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopModel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stopModel() }
    }
    val active = state.active?.takeIf { it.id == viewedId }
    val finished = state.records.find { it.match.id == viewedId }
    val match = active ?: finished?.match
    val opened = htmlGame
    val htmlSession = opened?.let { shown -> miniState.sessions.find { it.id == shown.session.id } ?: shown.session }
    LaunchedEffect(viewedId, detailsExpanded) {
        if (opened == null) gameListState.scrollToItem(0)
    }
    BackHandler(enabled = viewedId != null || opened != null) { leaveBoard() }
    BoxWithConstraints(modifier.fillMaxSize().testTag("orbis-game-content")) {
     // Notices remain readable by scrolling without taking the entire play viewport on short
     // windows. With no notice this wrapper consumes no height and normal boards stay unchanged.
     val noticeMaxHeight = minOf(maxHeight * .2f, 144.dp).coerceAtLeast(1.dp)
     Column(Modifier.fillMaxSize()) {
        GameHeading(opened?.source?.title ?: if (match != null) "九路五子棋" else "游戏机",
            when {
                opened != null -> "${opened.source.authorName} 写的游戏 · 独立本地沙箱"
                match != null -> "你执黑先手 · ${match.opponentLabel}"
                else -> "原生五子棋与 AI 写的小游戏，收藏在这里。"
            }, { stopModel(); onDismiss() },
            onDetails = if (match != null || opened != null) ({ detailsExpanded = !detailsExpanded }) else null,
            detailsExpanded = detailsExpanded)
        if (writeBlocked || saveFailed) {
          Column(Modifier.fillMaxWidth().heightIn(max = noticeMaxHeight)
              .verticalScroll(rememberScrollState()).testTag("orbis-game-storage-notice")) {
            Text(if (writeBlocked) "上次保存结果尚不确定，已暂停落子和记账，避免旧状态覆盖新记录。请先重新读取并核验本地记录；核验失败时不会解锁或清空。"
                else "操作未完成，未显示为成功。请确认当前棋局状态后再试。",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp,
                lineHeight = 18.sp, color = colors.onSand)
            TextButton(onClick = { perform({ repository.reloadFromStorage(); miniRepository.reloadFromStorage() }) },
                enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                Text("重新读取并核验记录")
            }
          }
        }
        if (opened != null && htmlSession != null) {
          BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("orbis-html-play-area")) {
           // The game gets at least 55% of the remaining viewport even with large fonts or an
           // expanded explanation. The same WebView stays mounted when chrome is folded/scrolled.
           val chromeMaxHeight = minOf(maxHeight * .45f, 240.dp).coerceAtLeast(1.dp)
           Column(Modifier.fillMaxSize()) {
            if (htmlCrashed) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("游戏画面已停止。已保存记录保留；可以回游戏库重新开局。", Modifier.padding(24.dp), color = colors.ink)
                }
            } else OrbisMiniGameWebView(opened.session.id, opened.source.html,
                onFinish = { result ->
                    try { miniRepository.finish(opened.session.id, result.result, result.moveCount) }
                    catch (error: Exception) { scope.launch { saveFailed = true }; throw error }
                }, onRendererFailure = { htmlCrashed = true },
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("orbis-html-game"))
            Column(Modifier.fillMaxWidth().heightIn(max = chromeMaxHeight)
                .verticalScroll(rememberScrollState()).testTag("orbis-html-controls")) {
             // Exit controls come first, so expanding details never buries the way out.
             FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { leaveBoard() }, modifier = Modifier.weight(1f)) { Text("回游戏库") }
                if (htmlSession.finishedAt == null) Button(onClick = {
                    perform({ miniRepository.abandon(opened.session.id) }, { leaveBoard() })
                }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("结束本局") }
             }
             if (htmlSession.finishedAt != null) Text("${resultLabel(htmlSession.result ?: "")} · ${if (htmlSession.result == "abandoned") "主动结束" else "游戏上报 / 宿主计时"}",
                 Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag("orbis-html-result"), color = colors.mutedInk, fontSize = 11.sp)
             if (detailsExpanded) GameExplanation(
                 "这是 ${opened.source.authorName} 写的自包含游戏。结果由游戏上报，非宿主验胜负；时长由宿主记录。关闭画面不自动判输，也不保存游戏内部进度；重新打开会开新局。")
            }
           }
          }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("orbis-game-play-area")) {
          // The finite viewport is measured outside the scrolling list. Portrait boards use nearly
          // all its width; short/landscape windows use its height instead and never stretch a square.
          val boardSide = minOf(maxWidth - 8.dp, maxHeight - 4.dp).coerceAtLeast(1.dp)
          LazyColumn(Modifier.fillMaxSize().testTag("orbis-game-list"), state = gameListState,
            contentPadding = if (match != null) PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                else PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (match != null) 6.dp else 12.dp)) {
            if (match != null) {
                item("board-status") {
                    Text(when {
                        busy -> "正在核验并保存…"
                        modelBusy -> "${match.opponentLabel} 正在落子…"
                        writeBlocked -> "显示的是上次确认状态 · 先重读记录才能继续"
                        finished != null -> "${resultLabel(finished.result)} · 宿主已核验并保存"
                        !GomokuRules.replay(match.moves).humanTurn -> "轮到模型 · 可继续模型落子，或改用本地对手"
                        else -> "轮到你了 · 黑棋先手，连成五子获胜"
                    }, modifier = Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = colors.ink)
                }
                if (detailsExpanded) item("native-game-details") {
                    GameExplanation("你执黑先手，连成五子获胜。回游戏库或关闭会保留未完棋局并停止模型请求；点结束本局才记为退出，退出不计胜负。模型请求失败或取消也计次，可以重试或转本地继续。")
                }
                item("board") {
                    val position = remember(match.moves) { GomokuRules.replay(match.moves) }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                     GomokuBoard(position, match.moves.lastOrNull(),
                        enabled = active != null && position.humanTurn && !busy && !modelBusy && !writeBlocked,
                        modifier = Modifier.size(boardSide)) { cell ->
                        perform({ repository.play(match.id, cell) }, {
                            val next = repository.state.value.active
                            if (viewedId == match.id && next?.id == match.id && next.opponent == GomokuRules.MODEL_OPPONENT && !GomokuRules.replay(next.moves).humanTurn)
                                requestModel(match.id)
                        })
                    }
                    }
                }
                if (active?.opponent == GomokuRules.MODEL_OPPONENT) item("model-controls") {
                    Text("模型请求 ${active.modelCalls}/${active.modelCallLimit} 次 · 失败或取消也计次",
                        fontSize = 11.sp, color = colors.mutedInk)
                    modelError?.let { Text(it,
                        Modifier.padding(top = 6.dp), fontSize = 12.sp, color = colors.ink)
                    }
                    if (active.modelCalls >= active.modelCallLimit) Text("本局模型次数已用完，仍可切回本地把本局下完。",
                        Modifier.padding(top = 6.dp), fontSize = 12.sp, color = colors.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (modelBusy) TextButton(onClick = { stopModel() }) { Text("停止等待") }
                        else if (!GomokuRules.replay(active.moves).humanTurn) TextButton(onClick = { requestModel(active.id) },
                            enabled = !busy && !writeBlocked && active.modelCalls < active.modelCallLimit) {
                            Text(if (modelError != null) "重试模型落子" else "继续模型落子")
                        }
                        TextButton(onClick = { stopModel(); perform({ repository.switchToLocal(active.id) }) }, enabled = !busy && !writeBlocked) {
                            Text("关闭模型，转本地")
                        }
                    }
                }
                item("board-actions") {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { leaveBoard() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("回游戏库", fontSize = 12.sp)
                        }
                        if (active != null) {
                            Button(onClick = { stopModel(); perform({ repository.abandon(active.id) }) }, enabled = !busy && !writeBlocked,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                                Text("结束本局", fontSize = 12.sp)
                            }
                        } else {
                            Button(onClick = { startNative() }, enabled = !busy && !writeBlocked, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("再开一局", fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                item("built-in-game") {
                    Surface(shape = RoundedCornerShape(20.dp), color = colors.panel,
                        border = BorderStroke(1.dp, colors.border)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), color = colors.accent) {
                                    Box(contentAlignment = Alignment.Center) { Text("●○", fontSize = 23.sp, color = colors.onAccent) }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("九路五子棋", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                                    Text("内置原生游戏 · 本地规则或当前 AI 对手", fontSize = 10.sp, lineHeight = 16.sp, color = colors.mutedInk)
                                }
                            }
                            if (!state.collected) {
                                Text("这是随开发版提供的内置作品，并非当前聊天模型新写的游戏。",
                                    fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                                OutlinedButton(onClick = { perform({ repository.collect() }) }, enabled = !busy && !writeBlocked,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("收藏到游戏机") }
                            }
                            val current = state.active
                            if (current != null) {
                                Text("有一局尚未结束 · 已落 ${current.moves.size} 子", fontSize = 12.sp, color = colors.mutedInk)
                                Button(onClick = { viewedId = current.id }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续本局") }
                            } else {
                                ModelOpponentOptions(useModel, assistantName, assistantId != null, callLimit,
                                    onEnabled = { useModel = it }, onLimit = { callLimit = it })
                                Button(onClick = { startNative() }, enabled = !busy && !writeBlocked && (!useModel || assistantId != null),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("开局") }
                            }
                        }
                    }
                }
                item("html-heading") {
                    Text("AI 写的小游戏", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text("让 AI 把自包含 HTML/JS 游戏放进游戏机，点开就能玩。安装不运行；游戏不能联网、读文件或调用其他本机能力。",
                        Modifier.padding(top = 6.dp), fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                    if (miniState.games.isEmpty()) Text("游戏架还空着，等一份新作品。", Modifier.padding(top = 12.dp), color = colors.mutedInk, fontSize = 12.sp)
                }
                items(miniState.games.chunked(2), key = { "html-grid-${it.first().id}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { game -> MiniGameCard(game, !busy, Modifier.weight(1f)) { startHtml(game.id) } }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                if (miniState.sessions.isNotEmpty()) item("html-records") {
                    Text("小游戏记录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    miniState.sessions.asReversed().take(30).forEach { session ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${session.gameTitle} · ${session.result?.let(::resultLabel) ?: "未结算"}", fontSize = 12.sp, color = colors.ink)
                            Text(if (session.finishedAt == null) "未上报结果；离开页面不会自动判负。"
                                else "${session.moveCount ?: 0} 步 · ${(session.finishedAt - session.startedAt) / 1000} 秒 · ${if (session.result == "abandoned") "主动结束" else "游戏上报 / 宿主计时"}",
                                fontSize = 10.sp, color = colors.mutedInk)
                            if (session.finishedAt == null) TextButton(onClick = { perform({ miniRepository.abandon(session.id) }) }, enabled = !busy) { Text("结束这局") }
                        }
                    }
                }
                item("stats") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("win", "loss", "draw", "abandoned").forEach { result ->
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = colors.tintedPanel) {
                                Text("${resultLabel(result)} ${state.records.count { it.result == result }}",
                                    Modifier.padding(horizontal = 7.dp, vertical = 11.dp), fontSize = 12.sp, lineHeight = 17.sp, color = colors.ink)
                            }
                        }
                    }
                }
                item("record-heading") {
                    Text("最近对局", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink,
                        modifier = Modifier.semantics { heading() })
                    Text("只保存此设备上的原生五子棋对局。完成、平局和主动退出分别统计；异常关闭不自动判输。",
                        Modifier.padding(top = 6.dp), fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                    if (state.records.isEmpty()) Text("还没有结束的对局。", Modifier.padding(top = 12.dp),
                        fontSize = 12.sp, color = colors.mutedInk)
                }
                items(state.records.asReversed().take(100), key = { it.match.id }) { record ->
                    RecordRow(record) { viewedId = record.match.id }
                }
                item("game-boundaries") {
                    Text("游戏与记录保存在本应用私有目录。AI 查询战绩时会收到结果摘要；开启模型五子棋会发送该局棋盘，不发送聊天记录。HTML 游戏结果来自游戏自身上报，并不等于宿主验胜负。游戏及记录尚未纳入备份 ZIP，卸载或清除数据会丢失。",
                        fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                    if (state.records.size > 100) Text("此处显示最近 100 局，较早记录仍保留在本地。", fontSize = 11.sp, color = colors.mutedInk)
                }
            }
        }
        }
    }
    }
}

@Composable
internal fun ModelOpponentOptions(enabled: Boolean, assistantName: String, available: Boolean, limit: Int,
    onEnabled: (Boolean) -> Unit, onLimit: (Int) -> Unit) {
    val colors = OrbisTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("跟当前 AI 下", fontSize = 14.sp, color = colors.ink)
            Text(if (enabled) "使用 $assistantName 配置的模型 · 独立棋局，不携带主聊天历史" else "关闭时使用本地规则程序，不请求模型",
                fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
        }
        StarSwitch(checked = enabled, onCheckedChange = onEnabled, enabled = available,
            modifier = Modifier.testTag("orbis-game-model-toggle").semantics { contentDescription = "跟当前 AI 下" })
    }
    if (enabled) {
        Text("每局模型请求上限（重试也计次）", fontSize = 11.sp, color = colors.mutedInk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 40).forEach { count -> FilterChip(selected = count == limit, onClick = { onLimit(count) }, label = { Text("$count 次") }) }
        }
    }
}

@Composable
private fun MiniGameCard(game: OrbisInstalledGame, enabled: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val colors = OrbisTheme.colors
    Surface(modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Button,
        onClickLabel = "开一局${game.title}", onClick = onOpen), shape = RoundedCornerShape(18.dp), color = colors.tintedPanel,
        border = BorderStroke(1.dp, colors.border)) {
        Column(Modifier.padding(14.dp).heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✧", fontSize = 26.sp, color = colors.accent)
            Text(game.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.ink)
            Text(game.description.ifBlank { "点开游玩" }, maxLines = 3, fontSize = 11.sp, color = colors.mutedInk)
            Text("${game.authorName} · 本地游戏", maxLines = 1, fontSize = 10.sp, color = colors.mutedInk)
        }
    }
}

@Composable
private fun GameHeading(title: String, subtitle: String, onDismiss: () -> Unit,
    onDetails: (() -> Unit)? = null, detailsExpanded: Boolean = false) {
    val colors = OrbisTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = colors.ink, modifier = Modifier.semantics { heading() })
            Text(subtitle, fontSize = 10.sp, lineHeight = 14.sp, color = colors.mutedInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDetails != null) TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("orbis-game-info-toggle")) {
            Text(if (detailsExpanded) "收起" else "说明", fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭游戏机并保留未完局", tint = colors.mutedInk) }
    }
}

@Composable
private fun GomokuBoard(position: GomokuRules.Position, lastMove: Int?, enabled: Boolean,
    modifier: Modifier = Modifier, onMove: (Int) -> Unit) {
    Box(modifier.aspectRatio(1f).testTag("orbis-gomoku-board")
        .clip(RoundedCornerShape(12.dp)).background(Color(0xFFE2CCA6)).padding(4.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / GomokuRules.SIZE
            val start = cell / 2f
            val end = size.width - start
            for (index in 0 until GomokuRules.SIZE) {
                val offset = start + index * cell
                drawLine(Color(0xFF9D845F), Offset(start, offset), Offset(end, offset), 1.dp.toPx())
                drawLine(Color(0xFF9D845F), Offset(offset, start), Offset(offset, end), 1.dp.toPx())
            }
            listOf(20, 24, 40, 56, 60).forEach { point ->
                drawCircle(Color(0xFF806B4E), 2.dp.toPx(), Offset(start + point % 9 * cell, start + point / 9 * cell))
            }
        }
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until GomokuRules.SIZE) Row(Modifier.weight(1f)) {
                for (col in 0 until GomokuRules.SIZE) {
                    val cell = row * 9 + col
                    val piece = position.board[cell]
                    val label = "第${row + 1}行第${col + 1}列，${when (piece) { 1 -> "黑棋"; 2 -> "白棋"; else -> "空位" }}"
                    Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label }
                        .clickable(enabled = enabled && piece == 0, role = Role.Button,
                            onClickLabel = "在此落黑棋") { onMove(cell) }, contentAlignment = Alignment.Center) {
                        if (piece != 0) {
                            Surface(Modifier.fillMaxSize(.8f).aspectRatio(1f), shape = CircleShape,
                                color = if (piece == 1) Color(0xFF282732) else Color(0xFFFFFCF2),
                                border = BorderStroke(1.dp, Color(0xFF8B7761)), shadowElevation = 2.dp) {
                                if (cell == lastMove) Box(contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(5.dp).background(Color(0xFFBE7B56), CircleShape))
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
private fun GameExplanation(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().testTag("orbis-game-explanation").padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize = 11.sp, lineHeight = 17.sp, color = OrbisTheme.colors.mutedInk)
}

@Composable
private fun RecordRow(record: OrbisGameRecord, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    val date = remember(record.finishedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.finishedAt)) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button,
        onClickLabel = "查看棋谱", onClick = onClick).heightIn(min = 48.dp).padding(vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(date, fontSize = 11.sp, color = colors.mutedInk)
            Text("${resultLabel(record.result)} · 已核验", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
        Text("${record.match.moves.size} 手 · ${record.match.opponentLabel} · 点按查看棋谱", Modifier.padding(top = 4.dp),
            fontSize = 10.sp, color = colors.mutedInk)
        HorizontalDivider(Modifier.padding(top = 9.dp), color = colors.border.copy(alpha = .6f))
    }
}

private fun resultLabel(result: String) = when (result) {
    "win" -> "胜"; "loss" -> "负"; "draw" -> "平"; "completed" -> "完成"; else -> "退出"
}

/** Known machine codes only; provider exceptions may contain private endpoint/account details. */
internal fun gameModelErrorLabel(code: String?): String = when (code) {
    "game_model_auxiliary_alias_missing" -> "当前网关缺少这个模型对应的辅助任务连接。请补齐连接，或先切回本地对手。"
    "game_model_assistant_missing", "game_model_not_configured", "game_model_provider_missing", "game_model_provider_disabled", "game_model_not_chat" ->
        "这局绑定的 AI 或模型连接暂不可用。可以修复连接后重试，或切回本地对手。"
    "game_model_call_limit_reached" -> "本局模型次数已用完，可以切回本地把本局下完。"
    "game_model_invalid_reply", "game_model_illegal_move", "game_model_unexpected_tool" ->
        "模型没有返回合法落子，没有替它落棋。可重试（再计一次）或切回本地。"
    else -> "这次模型落子未完成，没有替它落棋。可重试（再计一次）或切回本地。"
}
