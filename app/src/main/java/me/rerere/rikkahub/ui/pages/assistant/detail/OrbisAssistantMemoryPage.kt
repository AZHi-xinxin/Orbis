package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryStats
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.orbis.OrbisMemoryAtlasPage
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar
import org.koin.compose.koinInject

/** Human resource controls only. This page never subscribes to a body/summary/version stream. */
@Composable
internal fun OrbisAssistantMemoryPage(id: String) {
    val context = LocalContext.current
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id.toString() == id }
    val database = koinInject<AppDatabase>()
    val repository = remember(database) { OrbisMemoryRepository(database) }
    val scope = rememberCoroutineScope()
    var stats by remember(id) { mutableStateOf<OrbisMemoryStats?>(null) }
    var notice by remember(id) { mutableStateOf<String?>(null) }
    var saving by remember(id) { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportPending by rememberSaveable { mutableStateOf<String?>(null) }
    var exportStarted by rememberSaveable { mutableStateOf(false) }
    var showAtlas by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id, assistant?.id, repository) {
        stats = null
        if (assistant == null) return@LaunchedEffect
        try { repository.observeStats(id).collect { stats = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "记忆数量暂未读完，未将其当作空库。" }
    }
    LaunchedEffect(Unit) {
        if (exportStarted) {
            notice = "上次导出可能中断，请检查目标文件；未确认完整前不要当作备份。"
            exportStarted = false
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val owner = exportPending.also { exportPending = null }
        if (uri != null && (owner != id || store.settingsFlow.value.assistants.none { it.id.toString() == owner })) {
            notice = "AI 归属已变化，未导出；所选位置可能留下空文件。"
        } else if (uri != null && owner != null) scope.launch {
            exporting = true; exportStarted = true
            var finished = false
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        repository.export(owner, output)
                    }
                }
                notice = "已导出保全文件，原记忆未修改。"
                finished = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "导出未完成，目标文件可能不完整；原记忆未修改，请重新导出。"
                finished = true
            } finally { exporting = false; if (finished) exportStarted = false }
        }
    }
    fun update(mode: OrbisMemoryMode? = null, autoInject: Boolean? = null) {
        if (saving || assistant == null) return
        saving = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    store.update { latest ->
                        check(!latest.init && latest.assistants.any { it.id.toString() == id })
                        latest.copy(assistants = latest.assistants.map { target ->
                            if (target.id.toString() == id) target.copy(
                                orbisMemoryMode = mode ?: target.orbisMemoryMode,
                                orbisMemoryAutoInject = autoInject ?: target.orbisMemoryAutoInject,
                            ) else target
                        })
                    }
                }
                notice = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "设置尚未确认保存成功，请重试；记忆内容未改动。" }
            finally { saving = false }
        }
    }
    if (showAtlas) {
        OrbisMemoryAtlasPage(assistantIdOverride = id, onNavigateBack = { showAtlas = false })
        return
    }
    OrbisSettingsScaffold(topBar = {
        OrbisSettingsTopBar(title = { Text("本机助手记忆") }, navigationIcon = { BackButton() },
            subtitle = { Text("仅此 AI · 同一 AI 跨会话延续") })
    }) { padding ->
        if (settings.init || assistant == null) {
            Text(if (settings.init) "正在读取 AI 配置…" else "此 AI 已不存在，未读取其他 AI 的记忆。",
                Modifier.padding(padding).padding(20.dp))
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OrbisAssistantMemorySettingsContent(assistant.orbisMemoryMode, assistant.orbisMemoryAutoInject,
                stats, enabled = !saving, onMode = { update(mode = it) },
                onAutoInject = { update(autoInject = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAtlas = true }, modifier = Modifier.testTag("local_memory_atlas")) { Text("记忆星图") }
                OutlinedButton(onClick = {
                    if (!exporting && exportPending == null) {
                        exportPending = id
                        export.launch("orbis-memory-backup.json")
                    }
                }, enabled = !exporting && exportPending == null, modifier = Modifier.testTag("local_memory_export")) {
                    Text(if (exporting) "正在导出…" else "导出保全")
                }
            }
            Text("导出文件包含此 AI 的完整记忆与保留版本，请妥善保存；这里只提供保全，不展示内容。",
                style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun OrbisAssistantMemorySettingsContent(
    mode: OrbisMemoryMode,
    autoInject: Boolean,
    stats: OrbisMemoryStats?,
    enabled: Boolean = true,
    onMode: (OrbisMemoryMode) -> Unit,
    onAutoInject: (Boolean) -> Unit,
) {
    Text("AI 管理自己的随身便签", style = MaterialTheme.typography.titleMedium)
    Text("写、改、删、恢复、标签和浮现规则由 AI 决定。需要某条暂停时，请在对话中告诉 AI；这里不提供内容列表或审批。",
        style = MaterialTheme.typography.bodySmall)
    OrbisMemoryMode.entries.forEach { option ->
        val label = when (option) {
            OrbisMemoryMode.LIGHT -> "轻档"
            OrbisMemoryMode.STANDARD -> "标准档"
            OrbisMemoryMode.INDEPENDENT -> "独立档"
        }
        val explanation = when (option) {
            OrbisMemoryMode.LIGHT -> "已有 ST 等外置记忆：本机只保存和按需查询，零自动带入。"
            OrbisMemoryMode.STANDARD -> "需要主动查记忆：常驻带入，条件命中最多 900 token。"
            OrbisMemoryMode.INDEPENDENT -> "以本机为主：常驻与条件命中合计最多 2000 token。"
        }
        Row(Modifier.fillMaxWidth().testTag("local_memory_mode_${option.name.lowercase()}")
            .clickable(enabled = enabled) { onMode(option) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == option, enabled = enabled, onClick = { onMode(option) })
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("停止自动带入", style = MaterialTheme.typography.titleSmall)
            Text("只停注入；不删除、不更改记忆，AI 仍可保存和查询。", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = !autoInject, onCheckedChange = { onAutoInject(!it) }, enabled = enabled,
            modifier = Modifier.testTag("local_memory_stop_auto"))
    }
    Text(if (stats == null) "正在读取匿名数量…" else
        "本机 ${stats.total - stats.deleted} 条 · 已暂停 ${stats.paused} 条 · 软删保留 ${stats.deleted} 条",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("local_memory_counts"))
    stats?.let {
        Text("已置顶 ${it.pinned} 条" + when {
            mode == OrbisMemoryMode.LIGHT -> "，当前档位不注入"
            !autoInject -> "，自动带入已停止"
            else -> " · 最多 7 条、350 token"
        }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("local_memory_pinned_count"))
    }
    Text("切档只改变预算，不删除记忆、不替 AI 改规则。", style = MaterialTheme.typography.bodySmall)
}
