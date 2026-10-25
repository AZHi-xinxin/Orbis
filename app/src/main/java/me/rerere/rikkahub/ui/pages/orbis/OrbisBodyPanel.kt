package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.body.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.writeClipboardText
import java.util.Locale

/** No file IO, timers or repository subscription until the assistant explicitly enables this. */
@Composable fun OrbisBodyEntry(owner: String, conversation: String, modifier: Modifier = Modifier) {
    var open by remember(owner, conversation) { mutableStateOf(false) }
    var records by remember(owner, conversation) { mutableStateOf(false) }
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(margin) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
            (anchorBounds.left - margin).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            (anchorBounds.bottom + margin / 2).coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
    } }
    Box(modifier) {
        IconButton(onClick = { open = !open }, modifier = Modifier.size(44.dp)) {
            Icon(HugeIcons.Favourite, if (open) "收起身体状态" else "展开身体状态",
                Modifier.size(23.dp), tint = if (open) Color(0xFFD9808C) else MaterialTheme.colorScheme.primary)
        }
        if(open) Popup(popupPositionProvider = position, onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true)) {
            val p = bodyPalette()
            val display = rememberBodyDisplay(owner)
            Surface(shape = RoundedCornerShape(28.dp), color = p.background.copy(alpha = .98f),
                border = BorderStroke(1.dp, p.line), shadowElevation = 12.dp,
                modifier = Modifier.widthIn(max = 340.dp).padding(4.dp)) {
                Column(Modifier.padding(16.dp).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("此刻的状态", Modifier.weight(1f), fontSize = 18.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
                        Surface(color = p.blue.copy(alpha = .1f), shape = RoundedCornerShape(20.dp)) {
                            Text("模拟", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 10.sp, color = p.muted)
                        }
                        IconButton(onClick = { open = false }, modifier = Modifier.size(40.dp)) { Icon(HugeIcons.Cancel01, "收起身体状态", Modifier.size(18.dp)) }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BodyPanelContents(owner, conversation, controls = false, privateVisible = display.visible)
                    }
                    HorizontalDivider(color = p.line)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = display.visible, onClick = { display.change(!display.visible) }, enabled = display.ready,
                            label = { Text(if (display.visible) "收起私密层" else "查看私密层", fontSize = 12.sp) },
                            leadingIcon = { Icon(HugeIcons.Favourite, null, Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.rose.copy(alpha = .14f), selectedLabelColor = p.rose))
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { open = false; records = true }, colors = ButtonDefaults.buttonColors(containerColor = p.blue)) {
                            Text("记录", fontSize = 13.sp); Spacer(Modifier.width(4.dp)); Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
                        }
                    }
                    Text("模拟 · 非生理测量", Modifier.align(Alignment.CenterHorizontally), fontSize = 10.sp, color = p.muted)
                }
            }
        }
    }
    if (records) OrbisBodyDetails(owner, conversation) { records = false }
}

private data class BodyDisplayUi(val visible: Boolean, val ready: Boolean, val change: (Boolean) -> Unit)
@Composable private fun rememberBodyDisplay(owner: String): BodyDisplayUi {
    val context = LocalContext.current
    var store by remember(owner) { mutableStateOf<BodyDisplayStore?>(null) }
    var saving by remember(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        try { store = withContext(Dispatchers.IO) { AndroidBodyDisplay.open(context, owner) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.widget.Toast.makeText(context, "本机显示偏好暂不可读，私密层保持关闭。", android.widget.Toast.LENGTH_LONG).show() }
    }
    val value = store?.state?.collectAsStateWithLifecycle()?.value ?: BodyDisplayPreference()
    return BodyDisplayUi(value.privateVisible, store != null && !saving) { visible ->
        if (!saving) scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) { store?.setPrivateVisibleFromHuman(visible) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(context, "显示偏好未保存，请稍后再试。", android.widget.Toast.LENGTH_LONG).show() }
            finally { saving = false }
        }
    }
}

/** The header and workspace use the same owner-bound controls; never switch the global assistant. */
@Composable fun OrbisBodyDetails(owner: String, conversation: String?, onClose: () -> Unit) {
    val store = org.koin.compose.koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id.toString() == owner }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (assistant == null) { LaunchedEffect(owner) { onClose() }; return }
    OrbisBodySettings(owner, conversation, assistant.orbisBodyEnabled,
        onToggle = { value -> scope.launch {
            val paused = withContext(Dispatchers.IO) { runCatching { OrbisBody.open(context, owner).setRunning(false) }.isSuccess }
            if (paused || !value) store.update { current -> current.copy(assistants = current.assistants.map {
                if (it.id.toString() == owner) it.copy(orbisBodyEnabled = value) else it
            }) } else android.widget.Toast.makeText(context, "状态暂未能保存，原记录已保留。", android.widget.Toast.LENGTH_LONG).show()
        } }, onClose = onClose)
}

@Composable fun OrbisBodySettings(owner: String, conversation: String?, enabled: Boolean,
    onToggle: (Boolean) -> Unit, onClose: () -> Unit) {
    val p = bodyPalette()
    val display = rememberBodyDisplay(owner)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = p.background) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(HugeIcons.ArrowLeft01, "返回聊天或工具页") }
                    Text("身体记录", Modifier.weight(1f), fontSize = 22.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
                    Text("模拟", fontSize = 11.sp, color = p.muted)
                }
                if(enabled) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("查看私密维度", color = p.ink, fontSize = 13.sp)
                            Text("只改变本机显示，记录始终继续", color = p.muted, fontSize = 10.sp)
                        }
                        Switch(checked = display.visible, onCheckedChange = display.change, enabled = display.ready)
                    }
                    BodyPanelContents(owner, conversation, controls = true, privateVisible = display.visible)
                } else Text("开启身体表达后，AI 可以定义自己的指标和事件，你可以在这里查看状态与记录。")
                var configuration by remember(owner) { mutableStateOf(!enabled) }
                Surface(shape = RoundedCornerShape(20.dp), color = p.card, border = BorderStroke(1.dp, p.line)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { configuration = !configuration }, modifier = Modifier.fillMaxWidth()) {
                        Text("规则与运行设置", Modifier.weight(1f)); Text(if (configuration) "收起" else "展开")
                    }
                    if (configuration) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用本助手模块", Modifier.weight(1f)); Switch(enabled, onToggle)
                    }
                    Text("暂停、修改规则和继续运行由 AI 自己完成。你可以关闭整个模块，或选择是否查看私密层。", fontSize = 12.sp, lineHeight = 18.sp, color = p.muted)
                var showExplanation by remember { mutableStateOf(false) }
                TextButton(onClick = { showExplanation = !showExplanation }) { Text(if (showExplanation) "收起说明" else "了解规则与隐私") }
                if (showExplanation) Text("AI 定义规则；自动模式由系统匹配新消息中的关键词、近义说法并计算浓度，AI 不能手动加事件。冷静词和反应也由规则配置。AI 改规则前先暂停，发布后自己继续。私密层须 AI 愿意公开且你选择查看；查看开关只存本机，隐藏不停止计算。关闭模块会冻结，此模块不依赖 ST。", fontSize = 12.sp, lineHeight = 18.sp, color = p.muted)
                    }
                    }
                }
            }
        }
    }
}

@Composable private fun BodyPanelContents(owner: String, conversation: String?, controls: Boolean, privateVisible: Boolean) {
    val context = LocalContext.current
    var repository by remember(owner) { mutableStateOf<BodyRepository?>(null) }
    var failure by remember(owner) { mutableStateOf<String?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        try { repository = withContext(Dispatchers.IO) { OrbisBody.open(context, owner) } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { failure = "状态文件暂不可读，未重置。请保留备份，不影响聊天。" }
    }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val repo = repository ?: return
    val state = rememberLiveBodyState(repo) { failure = "暂时无法更新状态，原记录已保留。" }
    var emotionLabels by remember(owner) { mutableStateOf<List<String>>(emptyList()) }
    val memories = org.koin.compose.koinInject<me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository>()
    LaunchedEffect(owner) {
        try { emotionLabels = withContext(Dispatchers.IO) { memories.emotionLabels(owner).map { it.emotion } } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { /* Optional metadata only; no private note bodies are queried. */ }
    }
    fun run(action: () -> Unit) {
        if(busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; failure = null }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { failure = "操作未保存，旧记录已保留。请重新进入核对。" }
            finally { busy = false }
        }
    }
    BodyStateView(state, conversation, controls, busy, emotionLabels,
        onRefresh = { run { repo.refresh() } }, privateVisible = privateVisible)
}

/** Projection only: a visible panel ticks without disk writes, AI calls or a background worker. */
@Composable internal fun rememberLiveBodyState(repo: BodyRepository, onFailure: () -> Unit = {}): BodyState {
    val stored by repo.state.collectAsStateWithLifecycle()
    var state by remember(repo) { mutableStateOf(stored) }
    val failed by rememberUpdatedState(onFailure)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(repo, lifecycle, stored) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { state = withContext(Dispatchers.IO) { repo.viewSnapshot() } }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { failed() }
                delay(1000)
            }
        }
    }
    return state
}

/** Every display surface consumes the same filtered projection; hidden values never reach a card/chart. */
@Composable internal fun BodyStateView(state: BodyState, conversation: String?, controls: Boolean,
    busy: Boolean, emotionLabels: List<String>, onRefresh: () -> Unit, privateVisible: Boolean = false) {
    val p = bodyPalette()
    val version = state.versions.lastOrNull()
    if (version != null) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (state.running) "● 正在运行" else "○ 已暂停", Modifier.weight(1f),
            fontSize = 12.sp, color = if (state.running) Color(0xFF4B9C82) else p.muted)
        Text("第 ${version.version} 版 · ${if (version.definition.triggerMode == "auto") "自动感应" else "手动事件"}", fontSize = 11.sp, color = p.muted)
    }
    BodySetup.notice(state)?.let { notice ->
        val context = LocalContext.current
        BodyCard(Modifier.fillMaxWidth()) {
            Text(notice.title, fontSize = 15.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text(notice.humanMessage, fontSize = 12.sp, lineHeight = 19.sp, color = p.muted)
            TextButton(onClick = {
                context.writeClipboardText(notice.request)
                android.widget.Toast.makeText(context, "已复制，回到聊天发给 AI 即可。", android.widget.Toast.LENGTH_SHORT).show()
            }, contentPadding = PaddingValues(0.dp)) { Text("复制给 AI 的说明", fontSize = 12.sp, color = p.blue) }
        }
    }
    if (conversation == null) {
        Text("从具体聊天进入，可以查看该会话的状态与记录。", fontSize = 13.sp, color = p.muted)
        return
    }
    val session = state.snapshot.sessions[conversation]
    val metrics = BodyPresentation.metrics(state, conversation, privateVisible)
    if (version != null && session?.visible != true) BodyCard(Modifier.fillMaxWidth()) {
        Text("当前会话未公开计算状态", fontSize = 14.sp, color = p.ink)
        Text("AI 记录公开事件后，这里会显示状态。私密维度还需要你打开查看开关。", fontSize = 12.sp, lineHeight = 19.sp, color = p.muted)
    }
    @Composable fun metricGroup(items: List<BodyMetricPresentation>) {
        if (items.isEmpty()) return
        if (controls) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 330.dp && LocalDensity.current.fontScale <= 1.2f) 3
                else if (maxWidth >= 250.dp && LocalDensity.current.fontScale <= 1.5f) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { index, item -> BodyMetricCard(item, rowIndex * columns + index,
                            compact = columns > 1, modifier = Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else items.forEachIndexed { index, item -> BodyMetricCard(item, index, compact = false, modifier = Modifier.fillMaxWidth()) }
    }
    metricGroup(metrics.filterNot { it.isPrivate })
    val privateMetrics = metrics.filter { it.isPrivate }
    if (privateMetrics.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(HugeIcons.Favourite, null, Modifier.size(14.dp), tint = p.rose)
            Text("私密层", fontSize = 12.sp, color = p.rose)
            Text("仅在本机展开", fontSize = 10.sp, color = p.muted)
        }
        metricGroup(privateMetrics)
    } else if (!privateVisible && BodyRules.privateIds(state).isNotEmpty()) {
        Text("私密层已收起，计算和记录照常继续。", fontSize = 11.sp, lineHeight = 17.sp, color = p.muted)
    } else if (privateVisible && BodyRules.privateIds(state).isNotEmpty() && session?.privateShared != true) {
        Text("AI 暂未公开私密层，记录仍在继续。", fontSize = 11.sp, lineHeight = 17.sp, color = p.muted)
    }
    session?.selfReport?.takeIf { it.isNotBlank() }?.let { report ->
        BodyCard(Modifier.fillMaxWidth()) {
            Text("自述", fontSize = 11.sp, color = p.muted)
            Text(report, fontSize = 14.sp, lineHeight = 22.sp, color = p.ink)
        }
    }
    if (controls && version != null) {
        BodyTrendCard(state, conversation, privateVisible)
        BodyHistoryCard(state, conversation, privateVisible)
    }
    if (controls && emotionLabels.isNotEmpty()) BodyCard(Modifier.fillMaxWidth()) {
        Text("便签自标", fontSize = 12.sp, color = p.muted)
        Text(emotionLabels.joinToString(" · "), fontSize = 13.sp, color = p.ink)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onRefresh, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (busy) "更新中…" else "刷新", fontSize = 12.sp, color = p.blue)
        }
        Spacer(Modifier.weight(1f))
        if (controls) Text("模拟 · 非生理测量", fontSize = 10.sp, color = p.muted)
    }
}
