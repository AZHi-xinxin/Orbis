package me.rerere.rikkahub.ui.pages.orbis.toy

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BluetoothSquare
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Settings02
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.toy.OrbisToyController
import me.rerere.rikkahub.data.orbis.toy.lab.*
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageHeader
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageSurface
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrbisToyLabPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ToyLabController.get(context) }
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    ToyLabScreen(controller, assistant?.id?.toString(), assistant?.name, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToyLabScreen(controller: ToyLabController, assistantId: String?, assistantName: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val running by controller.runState.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var actionId by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("2") }
    var reaction by remember { mutableStateOf("不确定") }
    var stopped by remember { mutableStateOf("不确定") }
    var note by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<String?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var selectedDevice by remember { mutableStateOf<LabNearby?>(null) }
    var confirmRun by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var channelDetails by remember { mutableStateOf(false) }
    fun launch(block: suspend () -> Unit) { scope.launch {
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; controller.message(e.message ?: "操作未完成") }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (controller.hasScanPermissions()) controller.startScan() else controller.message("未获得附近设备权限，请在系统权限页允许后再扫描。")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) launch {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (buffer.size() <= 512 * 1024) { val count = input.read(chunk); if (count < 0) break; buffer.write(chunk,0,count) }
                val bytes = buffer.toByteArray(); require(bytes.size <= 512 * 1024) { "档案超过512KiB" }; bytes.toString(Charsets.UTF_8)
            } ?: error("无法读取档案")
        }
        controller.importArchive(text); tab = 2
    } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport; pendingExport = null
        if (uri != null && text != null) launch {
            withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            controller.message("档案已导出，不包含蓝牙地址、助手身份或授权。")
        }
    }
    LaunchedEffect(state.archive?.latest()?.revision, state.archiveId) {
        actionId = state.archive?.latest()?.profile?.actions?.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(running.trial?.id) { reaction = "不确定"; stopped = "不确定"; note = "" }
    DisposableEffect(Unit) { onDispose { controller.stopScan() } }
    val back = { launch { controller.stop(); onBack() } }
    BackHandler(onBack = back)
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink, topBar = {
            OrbisPageHeader(title = when(tab) { 1 -> "调试与观察"; 2 -> "设备适配档案"; else -> "自定义设备调试台" },
                subtitle = "一起认识你的设备", navigationIcon = { TextButton(onClick = back) { Text("返回") } },modifier = Modifier.statusBarsPadding())
        },bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 20.dp,vertical = 10.dp)) {
                    if (running.busy) Text("试调中 · 实际反应请本人观察",style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = { launch { controller.stop() } },modifier = Modifier.fillMaxWidth().testTag("toy_lab_stop"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("停止并断开") }
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("toy_lab_content"),contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("独立调试 · 原直连保留",color = MaterialTheme.colorScheme.primary,style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.padding(top = 12.dp),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("设备","调试","档案").forEachIndexed { index,label ->
                            FilterChip(selected = tab == index,onClick = { tab = index },label = { Text(label) },modifier = Modifier.weight(1f).testTag("toy_lab_tab_$index"))
                        }
                    }
                }
                item {
                    LabCard {
                        Text(state.archive?.report?.name ?: "尚未选择设备",style = MaterialTheme.typography.titleMedium)
                        Text(if (state.connecting) "◌ 正在连接" else if (state.connected) "● 已连接 · 只读检查完成" else "○ 未连接", color = MaterialTheme.colorScheme.primary)
                        Text(state.message,style = MaterialTheme.typography.bodySmall)
                        if (state.archive != null && !state.connected && !state.connecting) {
                            TextButton(onClick = { launch { controller.reconnect() } }) { Text("重新连接已选设备") }
                        }
                    }
                }
                if (tab == 0) {
                    item {
                        LabCard(hero = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("连接你的设备",style = MaterialTheme.typography.headlineSmall)
                                    Text("发现 · 连接 · 理解",style = MaterialTheme.typography.labelLarge)
                                }
                                Surface(shape = RoundedCornerShape(36.dp),color = MaterialTheme.colorScheme.primary.copy(alpha = .1f)) {
                                    Icon(HugeIcons.BluetoothSquare,null,Modifier.padding(18.dp).size(34.dp),tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("先检查能否连接，再由你确认实际反应。")
                            Button(enabled = !state.scanning && !state.connecting && !running.busy,onClick = {
                                if (controller.hasScanPermissions()) controller.startScan() else permission.launch(OrbisToyController.requiredScanPermissions())
                            },modifier = Modifier.testTag("toy_lab_scan")) { Text(if (state.scanning) "正在扫描…" else "扫描附近设备") }
                            if (state.scanning) TextButton(onClick = controller::stopScan) { Text("停止扫描") }
                        }
                    }
                    state.nearby.forEach { device -> item(key = device.id) {
                        LabCard { Text(device.name,style = MaterialTheme.typography.titleSmall)
                            TextButton(enabled = !state.connecting,onClick = { selectedDevice = device }) { Text("选择并只读检查") } }
                    } }
                    item { LabCard {
                        Text("连接  →  试调  →  记录  →  交给 AI",style = MaterialTheme.typography.titleSmall)
                        Text("不认识的设备也可以先检查通道。若缺少可用协议，Orbis 会明确说明，不自动轮流试包。",style = MaterialTheme.typography.bodySmall)
                    } }
                    state.archive?.let { archive -> item { LabCard {
                        Text("通道与协议",style = MaterialTheme.typography.titleMedium)
                        Text("型号：${archive.report.model.ifBlank { "设备未提供" }} · 固件：${archive.report.firmware.ifBlank { "设备未提供" }}")
                        Text("${archive.report.channels.size} 个通道 · ${archive.report.channels.count { it.properties and 12 != 0 }} 个可写")
                        Text("能发送，不代表设备已经动作。",style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { channelDetails = !channelDetails }) { Text(if (channelDetails) "收起通道" else "查看通道") }
                        if (channelDetails) archive.report.channels.forEach { ch ->
                            Text("服务 ${ch.service}\n通道 ${ch.characteristic}\n" + listOfNotNull(
                                "可读".takeIf { ch.properties and 2 != 0 },"可写·响应".takeIf { ch.properties and 8 != 0 },
                                "可写·无响应".takeIf { ch.properties and 4 != 0 },"通知".takeIf { ch.properties and 16 != 0 }).joinToString(" · "),style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                    } } }
                }
                if (tab == 1) {
                    state.archive?.let { archive ->
                        val rev = archive.latest()
                        item { LabCard {
                            LabHeading("适配方案", HugeIcons.Settings02)
                            Text(rev?.let { "${it.profile.name} · 第${it.revision}版" } ?: "尚未安装方案")
                            if (rev == null) Text("可选择已知候选、导入档案，或在档案页允许 AI 帮你配置。有相同通道不等于已经兼容。")
                            labCandidates(archive.report).forEach { p ->
                                TextButton(enabled = !running.busy,onClick = { editor = ToyLabJson.encodeToString(p) }) { Text("查看 ${p.name}") }
                            }
                            OutlinedButton(enabled = !running.busy,onClick = {
                                val p = rev?.profile
                                editor = p?.let { ToyLabJson.encodeToString(it) } ?: "{\n  \"name\": \"我的方案\",\n  \"model\": \"待核实\",\n  \"firmware\": \"待核实\",\n  \"protocol\": \"待核实\",\n  \"source\": \"请填写参数来源\",\n  \"actions\": []\n}"
                            }) { Text(if (rev == null) "粘贴 / 编辑方案" else "查看 / 修改方案") }
                            Text("支持直接粘贴信道台导出的完整JSON，也可在档案页导入文件；不用让AI手工补型号或重写格式。",style = MaterialTheme.typography.bodySmall)
                            Text("需动态鉴权或解析通知才能工作的协议，当前调试台不能直接执行；可保留诊断后扩展适配。",style = MaterialTheme.typography.bodySmall)
                        } }
                        if (rev != null) item { LabCard {
                            Text("单次试调",style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { rev.profile.actions.forEach { action ->
                                FilterChip(selected = action.id == actionId,onClick = { actionId = action.id },label = { Text(action.label) })
                            } }
                            Text(rev.profile.actions.firstOrNull { it.id == actionId }?.meaning.orEmpty(),style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = seconds,onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) seconds = it },
                                label = { Text("本次时长（秒）") },modifier = Modifier.fillMaxWidth(),singleLine = true)
                            Text("新方案先从短时试调开始（最多10秒）。退出Orbis前台请求停止；系统强杀时不能保证停机，请保留实体开关。不会自动保存观察。",style = MaterialTheme.typography.bodySmall)
                            Button(enabled = state.connected && !running.busy && actionId.isNotBlank(),onClick = { confirmRun = true }) { Text("开始试调") }
                            Text(running.message,style = MaterialTheme.typography.bodySmall)
                        } }
                    } ?: item { LabCard { Text("先到“设备”页选择自己的设备并检查通道。") } }
                    running.trial?.let { trial -> item { LabCard {
                        Text("你观察到了什么？",style = MaterialTheme.typography.titleMedium)
                        Text("方案第${trial.revision}版 · ${trial.actionId} · ${trial.seconds}秒",style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("有反应","没反应","不确定").forEach { value ->
                            FilterChip(selected = reaction == value,onClick = { reaction = value },label = { Text(value) })
                        } }
                        OutlinedTextField(value = note,onValueChange = { note = it.take(1000) },label = { Text("反应或数值，例如：轻微振动") },modifier = Modifier.fillMaxWidth())
                        Text("结束后，实物是否停止？",style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("确认已停","未停止","不确定").forEach { value ->
                            FilterChip(selected = stopped == value,onClick = { stopped = value },label = { Text(value) })
                        } }
                        Button(enabled = !running.busy,onClick = { launch { controller.observe(reaction,note,stopped) } },modifier = Modifier.fillMaxWidth()) { Text("保存这次记录（可选）") }
                    } } }
                }
                if (tab == 2) {
                    state.archive?.let { archive ->
                        item { LabCard {
                            Text("档位与实际反应",style = MaterialTheme.typography.titleMedium)
                            Text("方案 ${archive.revisions.size} 个版本 · ${archive.observations.size} 条观察记录")
                            if (archive.observations.isEmpty()) Text("还没有保存观察记录。未观察的结果不会自动填入。",style = MaterialTheme.typography.bodySmall)
                            archive.observations.asReversed().take(20).forEach { observation ->
                                HorizontalDivider()
                                Text("${observation.trial.actionId} · 第${observation.trial.revision}版    ${observation.reaction}",style = MaterialTheme.typography.titleSmall)
                                Text("${observation.note}\n${observation.stopped} · ${observation.trial.seconds}秒",style = MaterialTheme.typography.bodySmall)
                                if (observation.origin == "imported") Text("导入观察 · 未在本机验证",style = MaterialTheme.typography.labelSmall)
                                Text(observation.trial.transportResult,style = MaterialTheme.typography.bodySmall)
                            }
                            if (archive.observations.size > 20) Text("显示最近20条，导出包含全部记录。")
                            TextButton(onClick = { launch { controller.saveReport() } }) { Text("保存当前档案到本机") }
                        } }
                        item { LabCard(hero = true) {
                            LabHeading("交给 AI 一起调试", HugeIcons.Sparkles)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("允许 ${assistantName ?: "当前助手"}\n读取并配置",modifier = Modifier.weight(1f))
                                Switch(checked = assistantId != null && state.sharedWith == assistantId,enabled = assistantId != null && !state.connecting,
                                    onCheckedChange = { enabled -> launch { controller.share(if (enabled) assistantId else null) } },modifier = Modifier.testTag("toy_lab_share"))
                            }
                            Text("开启后，所选AI可查询此档案、安装修改方案、连接你选的设备并请求试调。实际反应仍由你确认；只有查询时才会交给当前模型。",style = MaterialTheme.typography.bodySmall)
                            if (state.sharedWith != null && state.sharedWith != assistantId) TextButton(onClick = { launch { controller.share(null) } }) { Text("收回其他助手的授权") }
                            Text("重开 Orbis 后需重新选设备并授权。关闭开关会停止调试；已进入模型的历史内容不会被远端抹除。",style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    item { LabCard {
                        LabHeading("档案管理", HugeIcons.Folder01)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = state.archive != null,onClick = { launch { pendingExport = controller.exportArchive(); exporter.launch("Orbis-设备适配档案.json") } }) { Text("导出档案") }
                            OutlinedButton(enabled = state.archive != null && !running.busy,onClick = { importer.launch(arrayOf("application/json","text/plain","application/octet-stream")) }) { Text("导入档案") }
                        }
                        TextButton(onClick = { launch { saved = controller.savedArchives() } }) { Text("查看本机已存档案") }
                        saved.forEach { (id,name) -> TextButton(enabled = state.archive != null,onClick = { launch { controller.loadArchive(id) } }) { Text("打开 $name") } }
                        Text("先选设备，再导入对应档案。导入不继承AI权限或本机验证；修改有版本，随时可收回授权。",style = MaterialTheme.typography.bodySmall)
                    } }
                }
            }
        }
    }
    selectedDevice?.let { device -> AlertDialog(onDismissRequest = { selectedDevice = null },title = { Text("检查这台设备？") },
        text = { Text("${device.name}\n请确认是自己的设备。这里只连接并读取通道，不发送动作或握手指令。") },
        confirmButton = { TextButton(onClick = { selectedDevice = null; controller.choose(device.id) }) { Text("连接并检查") } },
        dismissButton = { TextButton(onClick = { selectedDevice = null }) { Text("取消") } }) }
    if (confirmRun) AlertDialog(onDismissRequest = { confirmRun = false },title = { Text("开始本次试调？") },
        text = { Text("只发送所选方案第${state.archive?.latest()?.revision}版的 $actionId，持续 $seconds 秒，结束后按方案请求停止并断开。\n请在能观察设备、能操作实体开关时测试；发送成功不能证明实际动作或停止。") },
        confirmButton = { TextButton(onClick = { confirmRun = false; launch { controller.start(actionId,seconds.toIntOrNull() ?: 0) } }) { Text("开始") } },
        dismissButton = { TextButton(onClick = { confirmRun = false }) { Text("取消") } })
    editor?.let { text -> AlertDialog(onDismissRequest = { editor = null; editorError = null },title = { Text("适配方案 · JSON") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只安装配置，不会开始运行。不要用不明包猜测功能；首次可让已授权AI帮你整理。",style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = text,onValueChange = { if (it.length <= 32000) editor = it },modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
            editorError?.let { Text(it,color = MaterialTheme.colorScheme.error) }
        } },confirmButton = { TextButton(onClick = { scope.launch { try {
            controller.install(decodeLabProfile(text, state.archive?.report ?: error("请先选择设备"))); editor = null; editorError = null
        } catch (e: Exception) { if (e is CancellationException) throw e; editorError = e.message ?: "格式错误" } } }) { Text("保存为新版本") } },
        dismissButton = { TextButton(onClick = { editor = null; editorError = null }) { Text("取消") } }) }
}

@Composable
private fun LabCard(hero: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(24.dp),color = if (hero) colors.primaryContainer.copy(alpha = .55f) else colors.surfaceContainerLowest.copy(alpha = .95f),
        border = BorderStroke(1.dp,colors.outlineVariant.copy(alpha = .45f))) {
        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp),content = content)
    }
}

@Composable
private fun LabHeading(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
            Icon(icon,null,Modifier.padding(10.dp).size(22.dp),tint = MaterialTheme.colorScheme.primary)
        }
        Text(text,style = MaterialTheme.typography.titleMedium)
    }
}
