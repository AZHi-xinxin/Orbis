package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.health.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable internal fun HealthDashboard(state: HealthState, selected: HealthDay, date: String, month: Boolean,
    busy: Boolean, error: String?, summary: JsonObject, start: LocalDate, end: LocalDate,
    onClose: () -> Unit, onRefresh: () -> Unit, onDate: (String) -> Unit, onMonth: (Boolean) -> Unit,
    onEdit: () -> Unit, onHistory: () -> Unit, deviceSync: @Composable () -> Unit = {}, importScreenshot: @Composable () -> Unit,
    onReview: (HealthProposal) -> Unit, onDismissProposal: (HealthProposal) -> Unit) {
    val colors = OrbisTheme.colors
    var details by rememberSaveable(date) { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var dateInput by remember(date) { mutableStateOf(date) }
    fun value(key: String) = selected.fields[key]?.value?.takeUnless { it == JsonNull }
    val sleep = value("sleepMinutes")?.intOrNull
    val heart = value("averageHeart") ?: value("restingHeart") ?: value("heartRate")
    Surface(Modifier.fillMaxSize(), color = colors.page, contentColor = colors.ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ 返回") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("人类记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("认真记录你的日常", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !busy, onClick = onRefresh) { Text("刷新") }
            }
            LazyColumn(Modifier.weight(1f).testTag("health-dashboard"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Surface(color = colors.panel, shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { onDate(end.minusDays(1).toString()) }) { Text("‹", fontSize = 26.sp) }
                            TextButton(onClick = { datePicker = true }) { Text(if(date == LocalDate.now().toString()) "今天 · ${end.monthValue}月${end.dayOfMonth}日" else date, fontWeight = FontWeight.SemiBold) }
                            IconButton(onClick = { onDate(end.plusDays(1).toString()) }) { Text("›", fontSize = 26.sp) }
                        }
                    }
                }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                item {
                    HealthCard {
                        HealthSectionHeading("☾", "当日睡眠", Color(0xFF8D88B5))
                        if(sleep == null) Text("尚未记录", style = MaterialTheme.typography.headlineMedium, color = colors.mutedInk)
                        else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((sleep / 60).toString(), fontSize = 44.sp, fontWeight = FontWeight.Medium)
                            Text("小时", Modifier.padding(bottom = 8.dp)); Text((sleep % 60).toString(), fontSize = 38.sp)
                            Text("分钟", Modifier.padding(bottom = 8.dp))
                        }
                        value("sleepQuality")?.let { Text(it.content, color = colors.mutedInk) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HealthSmallValue("入睡", value("bedtime")?.content ?: "—", Modifier.weight(1f))
                            HealthSmallValue("起床", value("wakeTime")?.content ?: "—", Modifier.weight(1f))
                        }
                    }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HealthCard(Modifier.weight(1f)) {
                        HealthSectionHeading("↗", "今日步数", colors.accent)
                        Text(value("steps")?.content ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                        Text("步", color = colors.mutedInk, style = MaterialTheme.typography.labelMedium)
                        value("activeMinutes")?.let { Text("活动 ${it.content} 分钟", style = MaterialTheme.typography.bodySmall, color = colors.mutedInk) }
                    }
                    HealthCard(Modifier.weight(1f)) {
                        HealthSectionHeading("♡", if(value("averageHeart") != null) "平均心率" else if(value("restingHeart") != null) "静息心率" else "当次心率", Color(0xFFC8867C))
                        Text(heart?.content ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
                        Text("次 / 分钟", color = colors.mutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                } }
                item { HealthCard {
                    HealthSectionHeading("❋", "感受与身体", Color(0xFF689C97))
                    Text(value("periodStatus")?.let { healthDisplay("periodStatus", it) } ?: "周期尚未记录", color = colors.mutedInk)
                    value("note")?.let { Text(it.content, maxLines = 4, style = MaterialTheme.typography.bodyMedium) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(enabled = !busy, onClick = onEdit, shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("填写或修改当天") }
                        importScreenshot()
                        deviceSync()
                    }
                } }
                if(state.proposals.isNotEmpty()) item { Text("待你确认 · ${state.proposals.size}", style = MaterialTheme.typography.titleMedium) }
                items(state.proposals, key = { it.id }) { p -> HealthCard {
                    Text("${p.date} · ${p.fields.size} 项建议", fontWeight = FontWeight.SemiBold)
                    Text(p.evidence, style = MaterialTheme.typography.bodySmall)
                    FlowRow { TextButton(enabled = !busy, onClick = { onReview(p) }) { Text("检查建议") }; TextButton(enabled = !busy, onClick = { onDismissProposal(p) }) { Text("不采用") } }
                } }
                item { HealthCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("记录趋势", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        FilterChip(!month, { onMonth(false) }, label = { Text("近7天") }); Spacer(Modifier.width(6.dp))
                        FilterChip(month, { onMonth(true) }, label = { Text("本月") })
                    }
                    Text("已记录 ${summary["recorded_days"]} / ${summary["calendar_days"]} 天", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
                    Text("睡眠时长", style = MaterialTheme.typography.labelLarge)
                    HealthRecordChart(state.days, start, end, "sleepMinutes", "小时", 60.0, Color(0xFF918EB9))
                    val minutes = summary["average_sleep_minutes"]?.jsonPrimitive?.doubleOrNull?.toInt()
                    Text(if(minutes == null) "平均睡眠：暂无记录" else "平均睡眠：${minutes/60} 小时 ${minutes%60} 分钟", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = colors.border.copy(alpha = .45f))
                    Text("步数趋势", style = MaterialTheme.typography.labelLarge)
                    HealthRecordChart(state.days, start, end, "steps", "步", 1.0, colors.accent)
                    TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().testTag("health-details-toggle")) {
                        Text("记录详情", Modifier.weight(1f)); Text(if(details) "⌃" else "⌄")
                    }
                    if(details) {
                        healthFields.filter { value(it.id) != null }.forEach { field ->
                            val cell = selected.fields.getValue(field.id)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${healthDisplay(field.id, cell.value)} ${if(field.min != null) field.unit else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("${healthSource(cell.source)} · ${if(cell.actor == "human") "本人确认" else if(cell.actor=="device:hcmcp") "自动同步" else "助手记录"}", style = MaterialTheme.typography.labelSmall, color = colors.mutedInk)
                        }
                        if(selected.fields.values.none { it.value != JsonNull }) Text("还没有数据，不按 0 计算。", color = colors.mutedInk)
                        if(selected.revision > 0) TextButton(onClick = onHistory) { Text("查看修改记录") }
                    }
                } }
                item { Text("最近记录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp)) }
                items(state.days.sortedByDescending { it.date }.take(30), key = { it.date }) { day ->
                    Surface(onClick = { onDate(day.date) }, shape = RoundedCornerShape(16.dp), color = colors.panel) {
                        Row(Modifier.fillMaxWidth().padding(16.dp)) { Text(day.date, Modifier.weight(1f)); Text("${day.fields.values.count { it.value != JsonNull }} 项  ›", color = colors.mutedInk) }
                    }
                }
                item { Text("仅保存在本机 · 空缺不会按零计算", style = MaterialTheme.typography.labelSmall, color = colors.mutedInk, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
            }
        }
    }
    if(datePicker) AlertDialog(onDismissRequest = { datePicker = false }, title = { Text("查看哪一天") }, text = {
        OutlinedTextField(dateInput, { dateInput = it }, singleLine = true, label = { Text("YYYY-MM-DD") }, isError = !validHealthDate(dateInput))
    }, confirmButton = { TextButton(enabled = validHealthDate(dateInput), onClick = { onDate(dateInput); datePicker = false }) { Text("查看") } },
        dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } })
}

@Composable private fun HealthCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = OrbisTheme.colors.panel, shadowElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun HealthSectionHeading(symbol: String, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(34.dp).background(tint.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) { Text(symbol, color = tint, fontSize = 23.sp) }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun HealthSmallValue(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = OrbisTheme.colors.accent.copy(alpha = .06f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) { Text(label, color = OrbisTheme.colors.mutedInk, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleMedium) }
    }
}
@Composable private fun HealthRecordChart(days: List<HealthDay>, from: LocalDate, to: LocalDate, key: String, unit: String, divisor: Double, color: Color) {
    val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
    val values = (0 until count).map { i -> days.firstOrNull { it.date == from.plusDays(i.toLong()).toString() }?.fields?.get(key)?.value?.doubleOrNull?.div(divisor) }
    val observedPeak = values.filterNotNull().maxOrNull()
    val peak = observedPeak?.coerceAtLeast(1.0) ?: 1.0
    val border = OrbisTheme.colors.border
    Canvas(Modifier.fillMaxWidth().height(100.dp).testTag("health-chart-$key")) {
        val slot = size.width / count
        drawLine(border.copy(alpha = .55f), Offset(0f,size.height), Offset(size.width,size.height), 1.dp.toPx())
        values.forEachIndexed { i, value ->
            val left = (i + .2f) * slot
            if(value == null) drawLine(border, Offset(left, size.height - 3.dp.toPx()), Offset(left + slot*.6f, size.height - 3.dp.toPx()), 2.dp.toPx())
            else {
                val height = (size.height * value / peak).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(color.copy(alpha = .8f), Offset(left,size.height-height), Size(slot*.6f,height), CornerRadius(3.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${from.monthValue}/${from.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
        Text(if(observedPeak == null) "暂无记录" else "最高 ${"%.1f".format(java.util.Locale.ROOT,observedPeak)} $unit", style = MaterialTheme.typography.labelSmall)
        Text("${to.monthValue}/${to.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
    }
}
