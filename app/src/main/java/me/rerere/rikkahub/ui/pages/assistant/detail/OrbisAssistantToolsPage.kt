package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.withNativeToolSelection
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolCredentialStore
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolDescriptor
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolFamily
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolsEngine
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisNativeToolSelectionCard
import me.rerere.rikkahub.ui.pages.orbis.OrbisSoupDmSettingsDialog
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal val assistantCloudToolFamilies = listOf(CloudToolFamily.READING, CloudToolFamily.ORBIS, CloudToolFamily.TURTLESOUP)
internal fun localToolsForFamily(family: CloudToolFamily): List<LocalToolOption> = listOf(when (family) {
    CloudToolFamily.READING -> LocalToolOption.LocalReading
    CloudToolFamily.ORBIS -> LocalToolOption.LocalGarden
    CloudToolFamily.TURTLESOUP -> LocalToolOption.LocalSoup
})
internal fun cloudToolFamilyTitle(family: CloudToolFamily): String = when (family) {
    CloudToolFamily.READING -> "藏书阁共读"
    CloudToolFamily.ORBIS -> "Orbis工具"
    CloudToolFamily.TURTLESOUP -> "海龟汤"
}

internal fun cloudToolRiskLabel(tool: CloudToolDescriptor): String = when {
    tool.effect == "write" -> "写入 · 需审批"
    tool.needsApproval -> "调用 · 需审批"
    tool.effect == "read" -> "只读"
    else -> "请先核对权限"
}

@Composable
fun OrbisAssistantToolsPage(id: String, familyName: String) {
    val family = remember(familyName) { CloudToolFamily.entries.firstOrNull { it.wireName == familyName } }
    val assistantId = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    val navigator = LocalNavController.current
    val settingsStore = koinInject<SettingsStore>()
    val engine = koinInject<CloudToolsEngine>()
    val credentialStore = koinInject<CloudToolCredentialStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(initialValue = Settings.dummy())
    val credentials by credentialStore.state.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id == assistantId }
    val scope = rememberCoroutineScope()
    var catalog by remember(id, familyName) { mutableStateOf<List<CloudToolDescriptor>>(emptyList()) }
    var loading by remember(id, familyName) { mutableStateOf(false) }
    var error by remember(id, familyName) { mutableStateOf<String?>(null) }
    var refresh by remember(id, familyName) { mutableIntStateOf(0) }
    var saving by remember(id, familyName) { mutableStateOf<Set<String>>(emptySet()) }
    var showCloud by remember(id, familyName) { mutableStateOf(false) }
    var savingLocal by remember(id, familyName) { mutableStateOf(false) }
    var localError by remember(id, familyName) { mutableStateOf<String?>(null) }
    var dmOpen by remember(id, familyName) { mutableStateOf(false) }
    LaunchedEffect(showCloud, family, credentials.loaded, credentials.configured, credentials.baseUrl, refresh) {
        catalog = emptyList()
        error = null
        if (showCloud && family != null && credentials.loaded && credentials.configured) {
            loading = true
            try { catalog = engine.catalog(family) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "工具目录暂未读取成功。请检查网络、服务地址与设备授权后重试；没有启用或调用任何工具。" }
            finally { loading = false }
        }
    }
    OrbisSettingsScaffold(topBar = {
        OrbisSettingsTopBar(title = { Text(family?.let(::cloudToolFamilyTitle) ?: "工具设置") },
            navigationIcon = { BackButton() }, subtitle = { Text("此 AI 的工具选用 · 不是网页快捷方式") })
    }) { padding ->
        if (family == null || assistantId == null || (!settings.init && assistant == null)) {
            Text("未找到此 AI 或工具类别，未更改任何配置。", Modifier.padding(padding).padding(16.dp))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !showCloud, onClick = { showCloud = false }, label = { Text("本机工具") })
                    FilterChip(selected = showCloud, onClick = { showCloud = true }, label = { Text("云端选项") })
                }
            if (!showCloud) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("本机工具无需云端授权或服务器。书库读取与海龟汤公开状态默认可用；下面的选用只影响此 AI，不影响其他 AI，开启本身不执行工具。")
                    OrbisNativeToolSelectionCard(
                        assistantName = assistant?.name?.ifBlank { "未命名 AI" } ?: "正在读取 AI…",
                        options = localToolsForFamily(family), selected = assistant?.localTools.orEmpty(),
                        canEdit = !settings.init && assistant != null && !savingLocal, error = localError,
                    ) { option, enabled ->
                        if (!savingLocal) {
                            savingLocal = true
                            scope.launch {
                                try {
                                    withContext(NonCancellable) { settingsStore.update { current ->
                                        current.withNativeToolSelection(assistantId, option, enabled)
                                    } }
                                    localError = null
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { localError = "工具选用未确认保存成功，请重试；没有执行工具。" }
                                finally { savingLocal = false }
                            }
                        }
                    }
                    if (family == CloudToolFamily.READING) Text("默认可读取本机书库，无书如实返回空；导入后按明确书目与章节读取，每次正文最多32KiB，不自动注入整本书。写批注须开启上方选用并确认。", style = MaterialTheme.typography.bodySmall)
                    if (family == CloudToolFamily.TURTLESOUP) {
                        Text("没有对局时返回空状态；有本机对局时可直接读取公开进度。伙伴建议仅保存在本机，每次真正请主持判断或评分仍由你单独确认。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { dmOpen = true }) { Text("独立 DM API 设置") }
                    }
                }
            } else {
            OrbisCloudToolGrid(
                assistantName = assistant?.name?.ifBlank { "未命名 AI" } ?: "正在读取 AI…",
                tools = catalog,
                selected = assistant?.cloudTools?.enabled(family).orEmpty(),
                configured = credentials.configured,
                loading = loading || !credentials.loaded || settings.init,
                error = error ?: credentials.error,
                canEdit = !settings.init && assistant != null && credentials.loaded && credentials.configured,
                saving = saving,
                onConfigure = { navigator.navigate(Screen.OrbisCloudToolCredentials) },
                onRefresh = { refresh++ },
                onToggle = { tool, enabled ->
                    if (tool.name !in saving) {
                        saving = saving + tool.name
                        scope.launch {
                            try {
                                engine.setEnabled(assistantId, family, tool.name, enabled)
                                error = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "尚未确认工具选用保存成功，请重试；未执行云端操作。" }
                            finally { saving = saving - tool.name }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            }
            }
        }
    }
    if (dmOpen) OrbisSoupDmSettingsDialog(onDismiss = { dmOpen = false })
}

/** Stateless synthetic-test surface. Only an explicit star click can request a selection change. */
@Composable
internal fun OrbisCloudToolGrid(
    assistantName: String,
    tools: List<CloudToolDescriptor>,
    selected: Set<String>,
    configured: Boolean,
    loading: Boolean,
    error: String?,
    canEdit: Boolean,
    saving: Set<String>,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (CloudToolDescriptor, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OrbisTheme.colors
    var pendingWrite by remember { mutableStateOf<CloudToolDescriptor?>(null) }
    var details by remember { mutableStateOf<CloudToolDescriptor?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp), modifier = modifier.fillMaxSize().testTag("orbis-cloud-tools-grid"),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI：$assistantName", fontWeight = FontWeight.SemiBold)
                Text("只影响此 AI 及使用同一配置的会话，不更改其他 AI。所有工具默认关闭；设置开关不会执行工具。",
                    style = MaterialTheme.typography.bodySmall)
                Text("读取云端资料也会把结果交给当前聊天模型。写入需选择此次允许或以后允许；记住授权可在本地工具页撤销，云端凭证仍须有效。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (configured) "本机已保存授权配置；是否有效以目录读取和实际调用结果为准。" else "尚未配置云端工具授权，当前不能调用。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("orbis-cloud-auth-state"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onConfigure) { Text("统一授权设置") }
                    TextButton(onClick = onRefresh, enabled = configured && !loading) { Text("刷新工具目录") }
                }
                if (loading) Text("正在读取工具目录…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("orbis-cloud-tools-error")) }
                if (!loading && configured && error == null && tools.isEmpty()) Text("服务当前未提供此类工具。没有新增选用。")
                val unavailable = selected - tools.map { it.name }.toSet()
                if (unavailable.isNotEmpty()) Text("有 ${unavailable.size} 项旧选用未出现在当前目录，未自动删除。请刷新目录或检查授权。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        items(tools, key = { it.name }) { tool ->
            Surface(color = colors.raisedPanel, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, colors.border)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    StarSwitch(checked = tool.name in selected, enabled = canEdit && !loading && tool.name !in saving,
                        onCheckedChange = { checked ->
                            if (checked && tool.needsApproval) pendingWrite = tool else onToggle(tool, checked)
                        }, modifier = Modifier.testTag("cloud-tool-toggle-${tool.name}").semantics {
                            contentDescription = "${tool.name} · ${cloudToolRiskLabel(tool)}"
                        })
                    Text(tool.name, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(cloudToolRiskLabel(tool), color = if (tool.needsApproval) colors.onSand else colors.mutedInk,
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    Text(tool.description.take(180), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        maxLines = 5)
                    TextButton(onClick = { details = tool }) { Text("查看说明") }
                    if (tool.name in saving) Text("保存中…", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    pendingWrite?.let { tool ->
        AlertDialog(onDismissRequest = { pendingWrite = null }, title = { Text("允许此 AI 提出写入请求？") },
            text = { Text("${tool.name}\n开启后，此 AI 可以请求调用该工具；写入使用统一审批，可选择此次允许或以后允许。现在不会执行任何云端操作。") },
            confirmButton = { TextButton(enabled = canEdit && !loading && tool.name !in saving, onClick = {
                pendingWrite = null; onToggle(tool, true)
            }) { Text("开启工具选用") } },
            dismissButton = { TextButton(onClick = { pendingWrite = null }) { Text("取消") } })
    }
    details?.let { tool ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(tool.name) },
            text = { Text("${cloudToolRiskLabel(tool)}\n\n${tool.description}") },
            confirmButton = { TextButton(onClick = { details = null }) { Text("知道了") } })
    }
}
