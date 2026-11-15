package me.rerere.rikkahub.ui.pages.orbis

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.withNativeToolSelection
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.agentwork.*
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable internal fun AgentWorkbenchPage(owner: String, close: () -> Unit) {
    val context = LocalContext.current
    val settings = koinInject<SettingsStore>()
    val live by settings.settingsFlow.collectAsStateWithLifecycle()
    val assistant = live.assistants.firstOrNull { it.id.toString() == owner }
    val chat = koinInject<ChatService>()
    val tasks = remember(owner) { agentTasks(context, owner) }
    val grants = remember(owner) { PhoneGrants(context, owner) }
    val change by tasks.changes.collectAsStateWithLifecycle()
    var entries by remember(owner) { mutableStateOf<List<AgentTask>>(emptyList()) }
    var allowed by remember(owner) { mutableStateOf(emptySet<String>()) }
    var catalog by remember(owner) { mutableStateOf<AgentCatalogReceipt?>(null) }
    var apps by remember(owner) { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var showApps by remember { mutableStateOf(false) }
    var phoneConsent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var connected by remember { mutableStateOf(PhoneControlService.instance != null) }
    val scope = rememberCoroutineScope()
    val agentEnabled = assistant?.localTools?.contains(LocalToolOption.AgentWork) == true
    val phoneEnabled = assistant?.localTools?.contains(LocalToolOption.PhoneControl) == true
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "操作未完成或记录已更新，请重新查看后再试。原记录保留。" }
            finally { busy = false }
        }
    }
    fun toggle(option: LocalToolOption, enabled: Boolean) = perform {
        settings.update { it.withNativeToolSelection(Uuid.parse(owner), option, enabled) }
    }
    LaunchedEffect(owner, change) {
        try {
            withContext(Dispatchers.IO) { tasks.list() to grants.read() }.let { entries = it.first; allowed = it.second }
            catalog = catalogPrefs(context).getString(owner, null)?.let { Json.decodeFromString<AgentCatalogReceipt>(it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "任务或目录暂时无法读取；请保留原数据。" }
    }
    // While this visible page is open, refresh the connection indicator on return from system settings.
    LaunchedEffect(owner) { while (true) { connected = PhoneControlService.instance != null; delay(1000) } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("任务与能力", style = MaterialTheme.typography.titleLarge)
                        Text(assistant?.name ?: "当前助手不可用", style = MaterialTheme.typography.labelMedium) }
                    TextButton(onClick = close) { Text("返回") }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("工具与诊断", "任务卡", "手机操作").forEachIndexed { index, title ->
                        FilterChip(tab == index, { tab = index }, label = { Text(title) }, modifier = Modifier.testTag("agent-tab-$index"))
                    }
                }
                error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxSize().testTag("agent-workbench"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == 0) {
                        item { AgentSwitch("允许工具发现与任务卡", "开启后，AI可按用途查找真实工具、检查故障，并记录多步任务；不会因此打开其他工具权限。",
                            agentEnabled, !busy && assistant != null) { toggle(LocalToolOption.AgentWork,it) } }
                        item { SelfCheckCard(owner, LocalToolOption.SelfCheck in assistant?.localTools.orEmpty(),
                            { toggle(LocalToolOption.SelfCheck,it) }) }
                        item {
                            Text("最近一次生成准备的工具目录", style = MaterialTheme.typography.titleMedium)
                            Text(catalog?.let { "${it.names.size} 项 · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.at))}。${it.scope}。设置变化以后，以新一轮记录为准。" }
                                ?: "尚无目录记录。开启工具发现后，在此助手的聊天发起一轮生成即可记录。", style = MaterialTheme.typography.bodySmall)
                            if ((catalog?.names?.size ?: 0) > 128) Text("目录超过128项，部分服务商可能拒绝。可在当前助手设置中收起暂不用的工具；目录查询不会绕过服务商上限。", color = MaterialTheme.colorScheme.error)
                            OutlinedTextField(filter,{ filter = it.take(80) },label = { Text("筛选工具名") },modifier = Modifier.fillMaxWidth())
                        }
                        items(catalog?.names.orEmpty().filter { it.contains(filter,true) }.take(300)) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (tab == 1) {
                        item { Text("多步任务由AI建卡；标题、步骤和完成声明由AI填写，实际工具回执由Orbis记录。关闭应用后卡片保留，不会自动重放任务。", style = MaterialTheme.typography.bodySmall) }
                        if (!agentEnabled) item { Text("先在“工具与诊断”开启任务卡；已有记录仍可查看。") }
                        if (entries.isEmpty()) item { Text("还没有任务卡。开启后可以在聊天里请AI：先建任务卡，再逐步执行并记录结果。") }
                        items(entries.sortedByDescending { it.updatedAt }, key = { it.id }) { task ->
                            AgentTaskCard(task, busy) { action -> perform {
                                withContext(Dispatchers.IO) { tasks.control(task.id,task.revision,action) }
                                if (action != "resume") chat.stopGeneration(Uuid.parse(task.conversation), stopGatewayWait = true)
                                notice = if (action == "resume") "已恢复任务记录。请回原聊天发送继续，AI会先核对现有记录；此按钮不会重放操作。"
                                    else "已${if (action == "pause") "暂停" else "取消"}并请求停止该聊天。已提交的外部动作无法撤回，结果不明时请先核对。"
                            } }
                        }
                    }
                    if (tab == 2) {
                        item { AgentSwitch("允许当前助手操作手机", "限你选择的应用；界面文字按需交给当前模型。随时关闭，不影响其他助手的设置。",
                            phoneEnabled, !busy && assistant != null) { if (it) phoneConsent = true else toggle(LocalToolOption.PhoneControl,false) } }
                        item { Text(if (connected) "系统通道：Orbis手机操作已连接" else "系统通道：尚未连接", style = MaterialTheme.typography.titleMedium)
                            Text("系统无障碍开关、当前助手开关和应用范围需同时满足。不会替你开启系统权限。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("前往系统无障碍设置") } }
                        item { Text("允许的应用（${allowed.size}）", style = MaterialTheme.typography.titleMedium)
                            Text("读取界面、点击、填写文字、滚动和返回均使用同一范围；只允许你愿意交给这位AI操作的应用。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { perform {
                                apps = withContext(Dispatchers.IO) { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                                    .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                                    .distinctBy { it.first }.filter { PhoneControlPolicy.allowedPackage(it.first,context.packageName) }.sortedBy { it.second } }
                                showApps = true
                            } }) { Text("选择应用") }
                        }
                        items(allowed.sorted()) { pkg -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pkg,Modifier.weight(1f),style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy,onClick = { perform { withContext(Dispatchers.IO) { grants.set(allowed - pkg) }; allowed = grants.read() } }) { Text("移除") }
                        } }
                        item { Text("先用计算器等简单应用验证。页面变化或票据过期时，AI需要重新读屏；系统接受点击不代表目标已完成。锁屏、密码、系统授权页面不支持。", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    if (phoneConsent) AlertDialog(onDismissRequest = { phoneConsent = false }, title = { Text("允许这位助手操作手机？") },
        text = { Text("开启并选择应用、完成系统授权后，AI可按需读取这些应用的界面并执行点击、输入、滚动。读到的内容会交给你配置的模型服务；执行沿用你的工具确认设置。不会持续记录屏幕，你可以随时关闭。") },
        confirmButton = { TextButton(onClick = { phoneConsent = false; toggle(LocalToolOption.PhoneControl,true) }) { Text("了解并开启") } },
        dismissButton = { TextButton(onClick = { phoneConsent = false }) { Text("取消") } })
    if (showApps) AlertDialog(onDismissRequest = { showApps = false }, title = { Text("选择允许的应用") },
        text = { LazyColumn(Modifier.heightIn(max = 500.dp)) { items(apps,key = { it.first }) { (pkg,label) ->
            Row(Modifier.fillMaxWidth()) { Checkbox(pkg in allowed, enabled = !busy, onCheckedChange = { value -> perform {
                withContext(Dispatchers.IO) { grants.set(if(value) allowed + pkg else allowed - pkg) }; allowed = grants.read()
            } }); Column { Text(label); Text(pkg,style = MaterialTheme.typography.labelSmall) } }
        } } }, confirmButton = { TextButton(onClick = { showApps = false }) { Text("完成") } })
}

@Composable private fun AgentSwitch(title: String, hint: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row { Text(title,Modifier.weight(1f),style = MaterialTheme.typography.titleMedium); Switch(checked,change,enabled = enabled) }
        Text(hint,style = MaterialTheme.typography.bodySmall)
    } }
}
@Composable internal fun AgentTaskCard(task: AgentTask, busy: Boolean, control: (String) -> Unit) {
    val labels = mapOf("active" to "待推进", "waiting" to "等待条件", "blocked" to "遇到阻碍", "paused" to "已暂停", "completed" to "AI报告完成", "cancelled" to "已取消")
    Card(Modifier.fillMaxWidth().testTag("agent-task-${task.id}")) { Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.title,style = MaterialTheme.typography.titleMedium)
        Text(labels[task.state].orEmpty(),style = MaterialTheme.typography.labelLarge)
        task.steps.forEachIndexed { i, step -> Text("${i+1}. ${when(step.state) { "done" -> "✓"; "running" -> "进行中 ·"; else -> "待做 ·" }} ${step.title}", style = MaterialTheme.typography.bodyMedium) }
        if(task.summary.isNotBlank()) Text(task.summary,style = MaterialTheme.typography.bodySmall)
        Text("宿主工具记录 ${task.receipts.size} 条（最多保留100条）",style = MaterialTheme.typography.labelMedium)
        task.receipts.takeLast(5).forEach { Text("${it.tool} · ${when(it.state) { "returned" -> "已返回，请核对结果"; "failed" -> "返回失败"; else -> "结果未确认，请勿重放" }}",style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(task.state in setOf("active","waiting","blocked")) TextButton(enabled = !busy,onClick = { control("pause") }) { Text("暂停") }
            if(task.state == "paused") TextButton(enabled = !busy,onClick = { control("resume") }) { Text("恢复记录") }
            if(task.state in AgentTaskStore.openStates) TextButton(enabled = !busy,onClick = { control("cancel") }) { Text("取消任务") }
        }
    } }
}
