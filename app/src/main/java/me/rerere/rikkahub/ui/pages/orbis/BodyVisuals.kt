package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.orbis.body.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class BodyPalette(val background: Color, val card: Color, val ink: Color,
    val muted: Color, val line: Color, val blue: Color, val rose: Color)
@Composable internal fun bodyPalette(): BodyPalette = if (MaterialTheme.colorScheme.surface.luminance() < .3f)
    BodyPalette(Color(0xFF172331), Color(0xFF223345), Color(0xFFE5EEFA), Color(0xFFA7BCD2),
        Color(0xFF3B5066), Color(0xFF90B9FF), Color(0xFFF0A4B7))
else BodyPalette(Color(0xFFEFF5FC), Color(0xFFFCFDFF), Color(0xFF223A60), Color(0xFF71849F),
    Color(0xFFDDE7F3), Color(0xFF568BE0), Color(0xFFCE819D))

@Composable internal fun BodyCard(modifier: Modifier = Modifier, isPrivate: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val p = bodyPalette()
    Surface(modifier, shape = RoundedCornerShape(20.dp),
        color = if (isPrivate) lerp(p.card, p.rose, .045f) else p.card,
        border = BorderStroke(1.dp, if (isPrivate) p.rose.copy(alpha = .28f) else p.line), shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable internal fun BodyMetricCard(metric: BodyMetricPresentation, index: Int,
    compact: Boolean, modifier: Modifier = Modifier) {
    val p = bodyPalette(); val accent = if (metric.isPrivate) p.rose else listOf(p.blue, Color(0xFF49A894), Color(0xFFD2A54C))[index % 3]
    BodyCard(modifier, metric.isPrivate) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(if (compact) 25.dp else 30.dp).background(accent.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
                Text(metric.dimension.label.take(1), fontSize = 13.sp, lineHeight = 16.sp, color = accent, fontWeight = FontWeight.Bold)
            }
            Text(metric.dimension.label, Modifier.weight(1f), maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 19.sp, color = p.ink)
            if (!compact) BodyValue(metric, p)
        }
        if (compact) BodyValue(metric, p)
        BodySpectrumBar(((metric.value - metric.dimension.min) / (metric.dimension.max - metric.dimension.min)).toFloat())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最高 ${bodyNumber(metric.peak)}", fontSize = 11.sp, lineHeight = 15.sp, color = p.muted)
            if (metric.isPrivate) Text("私密", fontSize = 10.sp, lineHeight = 15.sp, color = p.rose)
        }
    }
}

@Composable private fun BodyValue(metric: BodyMetricPresentation, p: BodyPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(bodyNumber(metric.value), color = p.ink, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
        val rising = metric.delta > .01; val falling = metric.delta < -.01
        Text(if (rising) "↑" else if (falling) "↓" else "→", fontSize = 17.sp,
            color = if (rising) Color(0xFF57AA86) else if (falling) p.blue else p.muted)
    }
}

internal fun bodyNumber(value: Double) = String.format(Locale.ROOT, "%.1f", value)

@Composable private fun BodySpectrumBar(fraction: Float) {
    val value by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "body-progress")
    val p = bodyPalette()
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(p.line.copy(alpha = .7f), cornerRadius = radius)
        if (value > 0f) drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6597F1),
            Color(0xFF76CDB4), Color(0xFFF2D77B), Color(0xFFDC8590)), endX = size.width),
            size = Size(size.width * value, size.height), cornerRadius = radius)
    }
}

@Composable internal fun BodyTrendCard(state: BodyState, conversation: String, privateVisible: Boolean) {
    val dimensions = BodyPresentation.dimensions(state, privateVisible, conversation)
    if (dimensions.isEmpty() || state.snapshot.sessions[conversation]?.visible != true) return
    var chosen by remember(conversation) { mutableStateOf<String?>(null) }
    // Closing private visibility immediately replaces a private selection; no one-frame data leak.
    val dim = dimensions.find { it.id == chosen } ?: dimensions.first()
    val end = maxOf(state.clock.wallMs, System.currentTimeMillis())
    val points = BodyPresentation.chart(state, conversation, dim.id, privateVisible, end - 86_400_000, end)
    val start = maxOf(end - 86_400_000, minOf(points.firstOrNull()?.at ?: end, end - 900_000))
    val p = bodyPalette()
    BodyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("状态走势", Modifier.weight(1f), fontSize = 16.sp, color = p.ink, fontWeight = FontWeight.SemiBold)
            Text("近 24 小时内", fontSize = 11.sp, color = p.muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            dimensions.forEach { item -> FilterChip(selected = dim.id == item.id, onClick = { chosen = item.id },
                label = { Text(item.label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.blue.copy(alpha = .15f), selectedLabelColor = p.blue)) }
        }
        if (points.isEmpty()) {
            Text("还没有这项指标的记录，发生事件后会在这里留下轨迹。", Modifier.padding(vertical = 20.dp), fontSize = 13.sp, lineHeight = 20.sp, color = p.muted)
        } else {
            Row(Modifier.fillMaxWidth().height(136.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    listOf(dim.max, (dim.max + dim.min) / 2, dim.min).forEach { Text(bodyNumber(it), fontSize = 10.sp, color = p.muted) }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                    repeat(5) { i -> val y = size.height * i / 4f
                        drawLine(p.line.copy(alpha = .75f), Offset(0f, y), Offset(size.width, y)) }
                    val xy = points.map { point -> Offset(
                        ((point.at - start).toDouble() / (end - start).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) * (size.width - 8.dp.toPx()) + 4.dp.toPx(),
                        (1f - ((point.values.getValue(dim.id) - dim.min) / (dim.max - dim.min)).toFloat().coerceIn(0f, 1f)) * size.height) }
                    val line = Path().apply { xy.forEachIndexed { i, point -> if(i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
                    if (xy.size > 1) {
                        val area = Path().apply { addPath(line); lineTo(xy.last().x, size.height); lineTo(xy.first().x, size.height); close() }
                        drawPath(area, Brush.verticalGradient(listOf(p.blue.copy(alpha = .18f), p.blue.copy(alpha = .015f))))
                        drawPath(line, p.blue, style = Stroke(2.dp.toPx()))
                    }
                    xy.forEach { drawCircle(p.card, 4.dp.toPx(), it); drawCircle(p.blue, 2.7.dp.toPx(), it) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 35.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(start, start + (end - start) / 2, end).forEach { time -> Text(bodyTime(time), fontSize = 10.sp, color = p.muted) }
            }
            Text(if (state.running) "右端为此刻状态，静默时也会按规则变化。" else "当前已暂停，状态保持不变。", fontSize = 11.sp, color = p.muted)
        }
    }
}

internal fun bodyTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))

@Composable internal fun BodyHistoryCard(state: BodyState, conversation: String, privateVisible: Boolean) {
    var expanded by remember(conversation) { mutableStateOf(false) }; val p = bodyPalette()
    val logs = BodyPresentation.history(state, conversation, privateVisible).takeLast(12).asReversed()
    BodyCard(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
            Text("近期记录", Modifier.weight(1f), fontSize = 15.sp, color = p.ink)
            Text(if (expanded) "收起  ⌃" else "展开  ⌄", fontSize = 12.sp, color = p.muted)
        }
        if (expanded) {
            if (logs.isEmpty()) Text("这里还没有可显示的记录。", fontSize = 12.sp, color = p.muted)
            logs.forEachIndexed { index, log ->
                if (index > 0) HorizontalDivider(color = p.line.copy(alpha = .6f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(7.dp).background(p.blue.copy(alpha = .65f), CircleShape))
                    Text(bodyTime(log.at), color = p.muted, fontSize = 12.sp)
                    Text(when(log.event) {
                        "host_start" -> "继续运行"; "host_pause" -> "暂停运行"; "host_human" -> "收到新的消息"
                        "host_undo" -> "撤回最近一次事件"; "host_self_report" -> "更新自述"
                        "host_cooling" -> "命中冷静词"; "host_visibility" -> "更新展示意愿"
                        else -> log.event.replace('_', ' ')
                    }, color = p.ink, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
