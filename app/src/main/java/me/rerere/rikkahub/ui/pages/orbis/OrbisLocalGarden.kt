package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.model.validOrbisGardenName
import me.rerere.rikkahub.data.orbis.*

/** Native local garden using the original dawn/calendar visual language, without cloud scripts. */
@Composable
internal fun OrbisLocalGarden(config: OrbisCloudHomeConfig) {
    val context = LocalContext.current
    val store = remember(context) { OrbisGardenStore.open(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(OrbisGardenKind.DIARY) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var dayText by rememberSaveable { mutableStateOf<String?>(today.toString()) }
    val month = YearMonth.parse(monthText)
    val day = dayText?.let(LocalDate::parse)
    var dayCounts by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var calendarError by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<OrbisGardenEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OrbisGardenEntry?>(null) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var exportConfirm by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(kind, month, zone, revision, reload) {
        dayCounts = emptyMap(); calendarError = false
        try { dayCounts = store.monthDays(kind, month, zone) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { calendarError = true }
    }
    LaunchedEffect(kind, day, zone, page, revision, reload) {
        loading = true; error = null; entries = emptyList()
        try { entries = if (day == null) store.list(kind, 31, page * 30) else store.listOnDate(kind, day, zone, 31, page * 30) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本地花园暂未读取成功。原数据库保留，没有自动清空或重新建库。" }
        finally { loading = false }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && uri.scheme == "content" && bytes != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        (context.contentResolver.openOutputStream(uri, "w") ?: error("missing_output")).use { it.write(bytes); it.flush() }
                    }
                    notice = "花园备份已写入你选择的位置。请妥善保管这份明文文件。"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { notice = "导出未完成，目标位置可能有不完整文件；本机原记录未改动。"
                } finally { busy = false }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && uri.scheme == "content") {
            busy = true
            scope.launch {
                try {
                    pendingImport = withContext(Dispatchers.IO) {
                        (context.contentResolver.openInputStream(uri) ?: error("missing_input")).use { stream ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(out.size() + count <= GARDEN_BACKUP_BYTES)
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray().also(::decodeGardenBackup)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { notice = "无法读取这份花园备份。只接受本地花园专用 JSON（8 MiB、5000 条以内），未写入任何记录。"
                } finally { busy = false }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GardenSky(Modifier.matchParentSize())
        LazyColumn(Modifier.widthIn(max = 520.dp).fillMaxSize().testTag("orbis-local-garden"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { GardenHeading(config) }
            item { GardenCalendar(month, day, today, dayCounts, kind, !busy,
                onMonth = { monthText = it.toString(); dayText = it.atDay(1).toString(); page = 0 },
                onDay = { dayText = it.toString(); page = 0 },
                onToday = { monthText = YearMonth.from(today).toString(); dayText = today.toString(); page = 0 }) }
            item { GardenKindRow(kind, !busy) { kind = it; page = 0 } }
            item {
                Surface(color = Color(0xFFFFF8EF).copy(alpha = .96f), contentColor = gardenInk,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (day == null) "全部${kind.label}" else "${day.monthValue} 月 ${day.dayOfMonth} 日",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(if (day == null) "按最近编辑排列" else "这一天留下的${kind.label}", fontSize = 11.sp, color = gardenMuted)
                            }
                            TextButton(onClick = { dayText = null; page = 0 }, enabled = !busy,
                                modifier = Modifier.testTag("garden-all-records")) { Text("全部记录", fontSize = 12.sp, color = gardenMuted) }
                            Box {
                                TextButton(onClick = { options = true }, modifier = Modifier.testTag("garden-options")) {
                                    Text("管理", fontSize = 12.sp, color = gardenMuted)
                                }
                                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                                    DropdownMenuItem(text = { Text(if (tools) "收起 AI 读写授权" else "AI 读写授权") },
                                        onClick = { options = false; tools = !tools })
                                    DropdownMenuItem(text = { Text("导出花园备份") }, enabled = !busy && pendingExport == null && error == null,
                                        onClick = { options = false; exportConfirm = true })
                                    DropdownMenuItem(text = { Text("导入花园备份") }, enabled = !busy && error == null,
                                        onClick = { options = false; import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                                    DropdownMenuItem(text = { Text("本地空间与隐私说明") }, onClick = { options = false; help = true })
                                }
                            }
                        }
                        Button(onClick = { selected = null; editing = true }, enabled = !busy && !loading && error == null,
                            colors = ButtonDefaults.buttonColors(containerColor = gardenPeach, contentColor = gardenInk),
                            modifier = Modifier.fillMaxWidth().testTag("orbis-garden-new")) { Text("＋  写今天的${kind.label}") }
                        if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        notice?.let { Text(it, fontSize = 12.sp, color = gardenMuted) }
                        if (calendarError) TextButton(onClick = { reload++ }) { Text("日期标记未读取成功 · 重试") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("重新读取") } }
                        if (!loading && error == null && entries.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("✧", fontSize = 26.sp, color = Color(0xFFB18B6F))
                                Text(if (day == null) "还没有${kind.label}" else "这一天，还没有${kind.label}", fontSize = 15.sp)
                                Text("让日常慢慢长成一座花园。", fontSize = 12.sp, color = gardenMuted)
                            }
                        }
                    }
                }
            }
            if (tools) item {
                Surface(color = OrbisTheme.colors.panel, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(14.dp)) {
                        Text("仅为你明确选中的助手授权；花园是本机共用空间。", style = MaterialTheme.typography.bodySmall)
                        OrbisNativeToolSelectionPanel(OrbisNativeToolSection.GARDEN)
                    }
                }
            }
            items(entries.take(30), key = { it.id }) { entry ->
                GardenEntryCard(entry, config.humanName, gardenDate(entry.createdAt), !busy) { selected = entry; editing = true }
            }
            if (page > 0 || entries.size > 30) item {
                Surface(color = Color(0xFFFFF8EF).copy(alpha = .94f), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { page-- }, enabled = page > 0 && !loading && !busy) { Text("上一页") }
                        Text("第 ${page + 1} 页", color = gardenInk, fontSize = 12.sp)
                        TextButton(onClick = { page++ }, enabled = entries.size > 30 && !loading && !busy && page < 33332) { Text("下一页") }
                    }
                }
            }
            item {
                TextButton(onClick = { help = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("✦  保存在此手机 · 不自动同步  ✦", fontSize = 11.sp, color = Color(0xFFFFE9D3))
                }
            }
        }
    }
    if (editing) OrbisGardenEditor(selected, kind, config.humanName,
        onDismiss = { editing = false },
        onSave = { title, body, author ->
            val original = selected
            val saved = if (original == null) store.create(kind, title, body, author) else store.update(original, title, body, author)
            val savedDay = Instant.ofEpochMilli(saved.createdAt).atZone(zone).toLocalDate()
            monthText = YearMonth.from(savedDay).toString(); dayText = savedDay.toString(); page = 0
            notice = "已保存到本机。"; editing = false
        }, onDelete = {
            selected?.let { store.delete(it) }; notice = "该条本地记录已删除；只能从你之前导出的备份恢复。"; editing = false
        })
    if (exportConfirm) AlertDialog(onDismissRequest = { exportConfirm = false }, title = { Text("导出本地花园备份？") },
        text = { Text("将导出全部分类的明文文字（最多 8 MiB、5000 条；超限明确失败，不删原记录）。选用云盘位置时，该文件由你选择的云盘应用处理。不要把备份发给不信任的人。附件引用原样保留，但不复制附件。") },
        confirmButton = { TextButton(onClick = {
            exportConfirm = false; busy = true
            scope.launch {
                try { pendingExport = store.exportBackup(); export.launch("orbis-local-garden-${System.currentTimeMillis()}.json") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { pendingExport = null; notice = "无法准备完整备份：请检查记录、空间及大小限制。未导出部分数据，也未修改原记录。" }
                finally { busy = false }
            }
        }) { Text("选择保存位置") } }, dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("取消") } })
    if (pendingImport != null) AlertDialog(onDismissRequest = { if (!busy) pendingImport = null }, title = { Text("合并这份本地花园备份？") },
        text = { Text("只新增不存在的记录，相同 ID 与内容会跳过；同 ID 内容冲突则整份拒绝。不覆盖、删除现有记录，不导入远端登录或助手配置。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val bytes = pendingImport ?: return@TextButton
            busy = true
            scope.launch {
                try { val count = store.importBackup(bytes); notice = "已合并 $count 条新记录，其余相同记录跳过。"; page = 0 }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "未导入：备份存在冲突、格式错误或存储不可用；原记录保留。" }
                finally { busy = false; pendingImport = null; reload++ }
            }
        }) { Text("确认合并") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pendingImport = null }) { Text("取消") } })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("本地空间与隐私") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("文字保存在这部手机，断网也能使用。不读取你的私聊、助手人格或工作区，也不自动复制远端。")
            Text("新主页可选择日期，在当天写日记、锚点、信件、心愿和歌曲。旧记录按创建时刻在设备当前时区归档；新记录记住所选日期，编辑不改日期。这里的管理入口新增记录仍写在今天。")
            Text("这是本机共用花园，不按聊天或助手分库。AI 默认不能读写；‘管理 → AI 读写授权’只为你明确选中的助手开启。允许阅读后，读取的内容会进入该助手当前模型的上下文。")
            Text("此处备份仅含花园各日期文字和引用，不包含藏书阁或海龟汤。书库请在藏书阁单独备份。不会额外读取附件本体、聊天、模型 Key 或站点登录状态；正文中的敏感信息会原样导出，请自行保管。聊天 ZIP 不代替这些专用备份。")
            Text("本地藏书阁和游戏机在后花园首页，无需 VPS；AI 共读和主持模型仍需你已有的模型连接。旧 VPS 数据不自动迁移。本地与自建云端两种模式各自保存，切换不会自动上传、同步或合并。")
        } }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

@Composable
internal fun OrbisGardenEditor(original: OrbisGardenEntry?, kind: OrbisGardenKind, defaultAuthor: String,
    onDismiss: () -> Unit, onSave: suspend (String, String, String) -> Unit, onDelete: suspend () -> Unit) {
    var title by rememberSaveable { mutableStateOf(original?.title.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(original?.body.orEmpty()) }
    var author by rememberSaveable { mutableStateOf(original?.author ?: defaultAuthor) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = title != original?.title.orEmpty() || body != original?.body.orEmpty() || author != (original?.author ?: defaultAuthor)
    fun dismiss() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    val valid = title.isNotBlank() && title.length <= 120 && title.none(Char::isISOControl) &&
        body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= GARDEN_BODY_BYTES && '\u0000' !in body && validOrbisGardenName(author)
    AlertDialog(onDismissRequest = ::dismiss, title = { Text(if (original == null) "写${kind.label}" else "${original.kind.label} · 本机文字") },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, enabled = !busy, label = { Text("标题（1–120 字）") },
                    modifier = Modifier.fillMaxWidth().testTag("garden-editor-title"))
                OutlinedTextField(author, { author = it }, enabled = !busy, label = { Text("署名（1–32 字）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, enabled = !busy, label = { Text("正文（最多 32 KiB）") }, minLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("garden-editor-body"))
                if (original != null) SelectionContainer { Text("ID：${original.id}\n保存在本机；不是自动生成的 AI 回复。", style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (original != null) TextButton(onClick = { delete = true }, enabled = !busy,
                    modifier = Modifier.testTag("garden-editor-delete")) { Text("删除这条本地记录") }
            }
        }, confirmButton = { TextButton(enabled = valid && !busy, modifier = Modifier.testTag("garden-editor-save"), onClick = {
            busy = true; error = null
            scope.launch {
                try { onSave(title, body, author) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "保存未确认成功，草稿保留。可能记录已被更新；取消后重新读取核对，不自动覆盖。" }
                finally { busy = false }
            }
        }) { Text(if (busy) "保存中…" else "保存到本机") } },
        dismissButton = { TextButton(enabled = !busy, onClick = ::dismiss) { Text("关闭") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃尚未保存的改动？") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃改动") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (delete) AlertDialog(onDismissRequest = { if (!busy) delete = false }, title = { Text("删除这条本地记录？") },
        text = { Text("只删除当前这一条。不能撤销；如需恢复，须使用你之前导出的备份。不会删除远端或聊天内容。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { onDelete() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "删除未确认成功，请关闭后重新读取。没有覆盖其他记录。" }
                finally { busy = false; delete = false }
            }
        }) { Text("确认删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { delete = false }) { Text("保留") } })
}

private fun gardenDate(epoch: Long): String = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
