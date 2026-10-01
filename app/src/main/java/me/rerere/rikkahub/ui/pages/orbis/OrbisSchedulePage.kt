package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.schedule.*

private val scheduleWeekNames = listOf("一", "二", "三", "四", "五", "六", "日")
private data class ScheduleEdit(val entry: OrbisScheduleEntry?, val revision: Int, val day: LocalDate)
private data class ScheduleDelete(val entry: OrbisScheduleEntry, val revision: Int)

/** Local UI and AI tools both use OrbisScheduleStore.open; opening never creates a model request. */
@Composable
fun OrbisSchedulePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    OrbisVisualTheme {
        OrbisScheduleContent(onBack, modifier)
    }
}

@Composable
private fun OrbisScheduleContent(onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { OrbisScheduleStore.open(context) }
    val changes by store.changes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<OrbisScheduleSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var weekly by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<ScheduleEdit?>(null) }
    var deleting by remember { mutableStateOf<ScheduleDelete?>(null) }

    fun adopt(value: OrbisScheduleSnapshot) {
        if (value.revision >= (snapshot?.revision ?: -1)) snapshot = value
    }
    LaunchedEffect(store, changes, refresh) {
        try { adopt(store.load()); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = orbisScheduleErrorText(failure.message) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val colors = MaterialTheme.colorScheme
    val data = snapshot
    val ready = data != null && error == null && !busy
    val dayEntries = remember(data, selected) { data?.entriesOn(selected).orEmpty() }
    val visibleEntries = remember(data, dayEntries, showAll) {
        if (showAll) data?.entries.orEmpty().sortedWith(compareBy<OrbisScheduleEntry> { it.details.kind != OrbisScheduleKind.WEEKLY }
            .thenBy { it.details.date.orEmpty() }.thenBy { it.details.startTime.orEmpty() }.thenBy { it.details.title })
        else dayEntries
    }
    LazyColumn(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = .36f), colors.background)))
        .safeDrawingPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("课表与日程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("把每一天，留一点位置给重要的事", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surface.copy(alpha = .94f), tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !weekly, onClick = { weekly = false }, label = { Text("月历") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = weekly, onClick = { weekly = true }, label = { Text("一周课表") })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected = LocalDate.now(); month = YearMonth.from(selected) }) { Text("今天") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (weekly) "${selected.minusDays((selected.dayOfWeek.value - 1).toLong())} 起的一周" else "${month.year} 年 ${month.monthValue} 月",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            if (weekly) { selected = selected.minusWeeks(1); month = YearMonth.from(selected) }
                            else month = month.minusMonths(1)
                        }, enabled = if (weekly) selected.year > 1 else month > YearMonth.of(1, 1)) { Text("‹") }
                        TextButton(onClick = {
                            if (weekly) { selected = selected.plusWeeks(1); month = YearMonth.from(selected) }
                            else month = month.plusMonths(1)
                        }, enabled = if (weekly) selected.year < 9999 else month < YearMonth.of(9999, 12)) { Text("›") }
                    }
                    if (!weekly) ScheduleMonth(month, selected, data, onSelect = { selected = it; month = YearMonth.from(it) })
                    else ScheduleWeek(selected, data, enabled = ready, onSelect = { selected = it }, onEdit = { entry ->
                        data?.let { editor = ScheduleEdit(entry, it.revision, selected) }
                    })
                    Text("● 有安排   ★ 重要事项（标红）", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (showAll) "全部安排" else "${selected.monthValue} 月 ${selected.dayOfMonth} 日 · 星期${scheduleWeekNames[selected.dayOfWeek.value - 1]}", style = MaterialTheme.typography.titleMedium)
                    Text("${visibleEntries.size} 项安排", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "只看当天" else "查看全部") }
                FilledTonalButton(onClick = { data?.let { editor = ScheduleEdit(null, it.revision, selected) } }, enabled = ready) { Text("＋ 新建") }
            }
        }
        if (error != null) item {
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(error.orEmpty(), color = colors.onErrorContainer)
                    TextButton(onClick = { refresh++ }, enabled = !busy) { Text("重新读取") }
                }
            }
        }
        if (notice != null) item {
            Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(notice.orEmpty(), color = colors.onSecondaryContainer)
                    TextButton(onClick = { notice = null }) { Text("知道了") }
                }
            }
        }
        if (data == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (data != null && visibleEntries.isEmpty()) item {
            Surface(color = colors.surface.copy(alpha = .8f), shape = RoundedCornerShape(20.dp)) {
                Text(if (showAll) "还没有安排。可以从一条课表或日程开始。" else "这一天还留着空白。\n可以写下一件临时安排，也可以建立长期重复的课表。", Modifier.padding(22.dp), color = colors.onSurfaceVariant)
            }
        }
        items(visibleEntries, key = { it.id }) { entry ->
            ScheduleEntryCard(entry, enabled = ready, onEdit = { data?.let { editor = ScheduleEdit(entry, it.revision, selected) } },
                onDelete = { data?.let { deleting = ScheduleDelete(entry, it.revision) } })
        }
        item {
            Text("保存在这部手机，与 AI 共用同一份课表和日程。不会自动上传或创建系统闹铃；AI 读取时，仅所调用的内容交给当前聊天模型。每周课表的修改与删除作用于整条重复规则。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
    editor?.let { edit ->
        OrbisScheduleEditor(edit.entry, edit.day, busy = busy, onDismiss = { if (!busy) editor = null }, onSave = { draft ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val result = if (edit.entry == null) store.create(edit.revision, draft) else store.update(edit.revision, edit.entry.id, draft)
                        adopt(result); error = null; notice = null; editor = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { notice = orbisScheduleErrorText(failure.message); editor = null; refresh++ }
                    finally { busy = false }
                }
            }
        })
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除这条安排？") },
            text = { Text("${target.entry.details.title}\n${if (target.entry.details.kind == OrbisScheduleKind.WEEKLY) "会删除这条每周课表的全部日期安排，不是仅删除当天。" else "只删除这个日期的事项。"}其它日程不受影响。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { adopt(store.delete(target.revision, target.entry.id)); error = null; notice = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { notice = orbisScheduleErrorText(failure.message); refresh++ }
                    finally { busy = false; deleting = null }
                }
            }) { Text("删除", color = colors.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("保留") } })
    }
}

@Composable
private fun ScheduleMonth(month: YearMonth, selected: LocalDate, snapshot: OrbisScheduleSnapshot?, onSelect: (LocalDate) -> Unit) {
    val days = remember(month) { orbisScheduleMonthDays(month) }
    val marks = remember(days, snapshot) { days.associateWith { day -> snapshot?.entriesOn(day).orEmpty() } }
    Row(Modifier.fillMaxWidth()) { scheduleWeekNames.forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    days.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) { week.forEach { day ->
            val entries = marks.getValue(day)
            val important = entries.any { it.details.important }
            val colors = MaterialTheme.colorScheme
            Surface(Modifier.weight(1f).padding(2.dp).semantics { contentDescription = "$day，${entries.size}项安排${if (important) "，有重要事项" else ""}" }
                .clickable(enabled = day.year in 1..9999) { onSelect(day) },
                color = if (day == selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.dayOfMonth.toString(), fontWeight = if (day == selected) FontWeight.Bold else FontWeight.Normal,
                        color = when { important -> colors.error; YearMonth.from(day) != month -> colors.onSurfaceVariant.copy(alpha = .6f); else -> colors.onSurface })
                    Text(if (important) "★" else if (entries.isNotEmpty()) "●" else " ", style = MaterialTheme.typography.labelSmall,
                        color = if (important) colors.error else colors.primary)
                }
            }
        } }
    }
}

@Composable
private fun ScheduleWeek(selected: LocalDate, snapshot: OrbisScheduleSnapshot?, enabled: Boolean, onSelect: (LocalDate) -> Unit, onEdit: (OrbisScheduleEntry) -> Unit) {
    val start = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(7) { index ->
            val day = start.plusDays(index.toLong())
            val entries = remember(snapshot, day) { snapshot?.entriesOn(day).orEmpty() }
            Surface(Modifier.width(150.dp), shape = RoundedCornerShape(16.dp), color = if (day == selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Column(Modifier.padding(8.dp)) {
                    TextButton(onClick = { onSelect(day) }, enabled = day.year in 1..9999, modifier = Modifier.fillMaxWidth()) { Text("周${scheduleWeekNames[index]} · ${day.monthValue}/${day.dayOfMonth}") }
                    LazyColumn(Modifier.height(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isEmpty()) item { Text("暂无安排", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                        items(entries, key = { it.id }) { entry ->
                            Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { onEdit(entry) }, shape = RoundedCornerShape(12.dp),
                                color = if (entry.details.important) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(scheduleTimeLabel(entry.details), style = MaterialTheme.typography.labelSmall)
                                    Text((if (entry.details.important) "★ " else "") + entry.details.title, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        color = if (entry.details.important) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                                    if (entry.details.location.isNotBlank()) Text(entry.details.location, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun ScheduleEntryCard(entry: OrbisScheduleEntry, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val details = entry.details
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), color = colors.surface.copy(alpha = .96f), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (details.important) colors.error.copy(alpha = .6f) else colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(scheduleTimeLabel(details), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text((if (details.important) "★ 重要 · " else "") + details.title, style = MaterialTheme.typography.titleMedium,
                color = if (details.important) colors.error else colors.onSurface)
            Text(scheduleRuleLabel(details), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (details.location.isNotBlank()) Text("地点 · ${details.location}", style = MaterialTheme.typography.bodyMedium)
            if (details.notes.isNotBlank()) Text(details.notes, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除", color = colors.error) }
            }
        }
    }
}

private fun scheduleTimeLabel(draft: OrbisScheduleDraft): String = if (draft.allDay) "全天" else "${draft.startTime} — ${draft.endTime}"
private fun scheduleRuleLabel(draft: OrbisScheduleDraft): String = if (draft.kind == OrbisScheduleKind.DATE) "单次事项 · ${draft.date}" else
    "每周${draft.weekdays.sorted().joinToString("、") { scheduleWeekNames[it - 1] }} · ${draft.validFrom ?: "随时生效"} — ${draft.validUntil ?: "无截止日期"}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrbisScheduleEditor(entry: OrbisScheduleEntry?, day: LocalDate, busy: Boolean, onDismiss: () -> Unit, onSave: (OrbisScheduleDraft) -> Unit) {
    val original = entry?.details
    var weekly by remember(entry?.id) { mutableStateOf(original?.kind == OrbisScheduleKind.WEEKLY) }
    var title by remember(entry?.id) { mutableStateOf(original?.title.orEmpty()) }
    var notes by remember(entry?.id) { mutableStateOf(original?.notes.orEmpty()) }
    var location by remember(entry?.id) { mutableStateOf(original?.location.orEmpty()) }
    var important by remember(entry?.id) { mutableStateOf(original?.important ?: false) }
    var allDay by remember(entry?.id) { mutableStateOf(original?.allDay ?: false) }
    var start by remember(entry?.id) { mutableStateOf(original?.startTime ?: "08:00") }
    var end by remember(entry?.id) { mutableStateOf(original?.endTime ?: "09:00") }
    var date by remember(entry?.id) { mutableStateOf(original?.date ?: day.toString()) }
    var weekdays by remember(entry?.id) { mutableStateOf(original?.weekdays?.takeIf { it.isNotEmpty() } ?: listOf(day.dayOfWeek.value)) }
    var from by remember(entry?.id) { mutableStateOf(original?.validFrom.orEmpty()) }
    var until by remember(entry?.id) { mutableStateOf(original?.validUntil.orEmpty()) }
    var validation by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).fillMaxHeight(.9f).imePadding(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (entry == null) "新建安排" else "编辑安排", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!weekly, { weekly = false }, enabled = !busy, label = { Text("单次日程") })
                        FilterChip(weekly, { weekly = true }, enabled = !busy, label = { Text("每周课表") })
                    }
                    OutlinedTextField(title, { if (it.length <= 160) title = it }, enabled = !busy, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (!weekly) OutlinedTextField(date, { if (it.length <= 10) date = it }, enabled = !busy, label = { Text("日期 · YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    else {
                        Text("每周重复 · 可选多个星期", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            scheduleWeekNames.forEachIndexed { index, name ->
                                val value = index + 1
                                FilterChip(value in weekdays, { weekdays = if (value in weekdays) weekdays - value else (weekdays + value).sorted() }, enabled = !busy, label = { Text("周$name") })
                            }
                        }
                        OutlinedTextField(from, { if (it.length <= 10) from = it }, enabled = !busy, label = { Text("生效日期（可空）· YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(until, { if (it.length <= 10) until = it }, enabled = !busy, label = { Text("截止日期（可空）· YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("不填截止日期就一直按周重复；填写的起止日均包含当天。", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(allDay, { allDay = it }, enabled = !busy); Spacer(Modifier.width(8.dp)); Text("全天") }
                    if (!allDay) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(start, { if (it.length <= 5) start = it }, enabled = !busy, label = { Text("开始 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(end, { if (it.length <= 5) end = it }, enabled = !busy, label = { Text("结束 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(important, { important = it }, enabled = !busy); Spacer(Modifier.width(8.dp)); Text("★ 重要事项，标红", color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(location, { if (it.length <= 160) location = it }, enabled = !busy, label = { Text("地点（可空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(notes, { if (it.length <= 4_000) notes = it }, enabled = !busy, label = { Text("备注（可空）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    if (entry != null && original?.kind == OrbisScheduleKind.WEEKLY) Text("保存会修改整条每周规则，并影响全部匹配日期。", style = MaterialTheme.typography.bodySmall)
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Button(enabled = !busy, onClick = {
                        val draft = OrbisScheduleDraft(kind = if (weekly) OrbisScheduleKind.WEEKLY else OrbisScheduleKind.DATE,
                            title = title.trim(), notes = notes.replace("\r\n", "\n"), location = location.trim(), important = important,
                            allDay = allDay, startTime = if (allDay) null else start.trim(), endTime = if (allDay) null else end.trim(),
                            date = if (weekly) null else date.trim(), weekdays = if (weekly) weekdays else emptyList(),
                            validFrom = if (weekly) from.trim().ifEmpty { null } else null, validUntil = if (weekly) until.trim().ifEmpty { null } else null)
                        try { validateOrbisScheduleDraft(draft); validation = null; onSave(draft) }
                        catch (failure: Exception) { validation = orbisScheduleErrorText(failure.message) }
                    }) { Text(if (busy) "保存中…" else "保存") }
                }
            }
        }
    }
}
