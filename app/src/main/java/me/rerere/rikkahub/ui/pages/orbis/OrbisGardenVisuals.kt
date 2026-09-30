package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.cos
import kotlin.math.sin
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.orbis.OrbisGardenEntry
import me.rerere.rikkahub.data.orbis.OrbisGardenKind
import me.rerere.rikkahub.data.orbis.gardenCalendarCells

// Visuals adapted from the retained original garden HTML. No remote scripts or private data.
internal val gardenInk = Color(0xFF3D3543)
internal val gardenMuted = Color(0xFF756674)
internal val gardenPeach = Color(0xFFF5C6A0)
private val gardenPaper = Color(0xFFFFF9F3)

@Composable
internal fun GardenSky(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stars = listOf(.08f to .16f, .24f to .08f, .75f to .11f, .89f to .30f,
            .06f to .61f, .93f to .66f, .17f to .86f, .71f to .91f, .43f to .96f)
        stars.forEachIndexed { i, (x, y) ->
            val center = Offset(size.width * x, size.height * y)
            val radius = (if (i % 3 == 0) 3 else 1.5f).toFloat() * density
            drawCircle(Color(0xFFF8E6CB).copy(alpha = .12f), radius * 4, center)
            drawCircle(Color(0xFFF8E6CB).copy(alpha = .65f), radius, center)
        }
    }
}

@Composable
internal fun GardenHeading(config: OrbisCloudHomeConfig) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("OUR LITTLE GARDEN", fontSize = 10.sp, letterSpacing = 2.sp, color = gardenMuted)
            Text(config.gardenName, fontSize = 25.sp, fontWeight = FontWeight.Medium, color = gardenInk)
            Text("${config.companionName}  ·  ${config.humanName}", fontSize = 13.sp,
                color = gardenMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.size(64.dp).clearAndSetSemantics { }) {
            val points = listOf(.1f to .5f, .32f to .18f, .53f to .55f, .79f to .33f, .92f to .64f)
                .map { Offset(it.first * size.width, it.second * size.height) }
            points.zipWithNext().forEach { (a, b) -> drawLine(gardenMuted.copy(alpha = .32f), a, b, 1.dp.toPx()) }
            points.forEachIndexed { i, point ->
                drawCircle(gardenPeach.copy(alpha = .25f), 7.dp.toPx(), point)
                drawCircle(if (i == 1) Color(0xFFBF805C) else gardenPeach, 2.5.dp.toPx(), point)
            }
        }
    }
}

@Composable
internal fun GardenCalendar(
    month: YearMonth, selectedDay: LocalDate?, today: LocalDate,
    days: Map<LocalDate, Int>, kind: OrbisGardenKind, enabled: Boolean,
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit, onToday: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().testTag("garden-calendar").clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFFFFECD0), gardenPeach, Color(0xFFB6ADC8), Color(0xFFFFECD0))))
        .padding(2.dp)) {
        Surface(color = gardenPaper.copy(alpha = .96f), contentColor = gardenInk, shape = shape) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${month.year} 年 ${month.monthValue} 月", fontSize = 19.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).testTag("garden-calendar-month"))
                    TextButton(onClick = onToday, enabled = enabled,
                        contentPadding = PaddingValues(6.dp), modifier = Modifier.testTag("garden-calendar-today")) {
                        Text("今天", color = gardenMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = enabled && month > YearMonth.of(1, 1),
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "上个月" }.testTag("garden-calendar-prev")) {
                        Text("‹", fontSize = 29.sp, color = gardenMuted)
                    }
                    IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = enabled && month < YearMonth.of(9999, 12),
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "下个月" }.testTag("garden-calendar-next")) {
                        Text("›", fontSize = 29.sp, color = gardenMuted)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFFEDE0D6))
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 11.sp, color = gardenMuted)
                        }
                    }
                }
                gardenCalendarCells(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            val count = days[day] ?: 0
                            val selected = day != null && day == selectedDay
                            Box(Modifier.weight(1f).heightIn(min = 46.dp).padding(2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) gardenPeach.copy(alpha = .72f) else Color.Transparent)
                                .then(if (day == null) Modifier else Modifier
                                    .testTag("garden-day-$day")
                                    .semantics {
                                        contentDescription = "${day.monthValue}月${day.dayOfMonth}日，$count 条${kind.label}" +
                                            (if (day == today) "，今天" else "")
                                        this.selected = selected
                                    }
                                    .clickable(enabled = enabled, role = Role.Button) { onDay(day) }),
                                contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    Text(day.dayOfMonth.toString(), fontSize = 15.sp,
                                        fontWeight = if (day == today || selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (count > 0 && !selected) Color(0xFFAB633A) else gardenInk,
                                        modifier = Modifier.padding(vertical = 10.dp))
                                    if (count > 0) Text("✦", fontSize = 7.sp, color = Color(0xFFAC693D),
                                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 3.dp).clearAndSetSemantics { })
                                    if (day == today) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                                        .size(4.dp).background(Color(0xFFBB7848), CircleShape))
                                }
                            }
                        }
                    }
                }
                Text("✦ 有${kind.label}的日子  ·  按创建日期归档", color = gardenMuted, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp))
            }
        }
    }
}

@Composable
internal fun GardenKindRow(kind: OrbisGardenKind, enabled: Boolean, onKind: (OrbisGardenKind) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbisGardenKind.entries.forEachIndexed { index, item ->
            Surface(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                .testTag("garden-kind-${item.name}")
                .semantics { selected = item == kind }
                .clickable(enabled = enabled, role = Role.Tab) { onKind(item) },
                color = if (item == kind) Color(0xFFFFE3C9) else gardenPaper.copy(alpha = .9f),
                contentColor = gardenInk, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .6f))) {
                Column(Modifier.padding(vertical = 13.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GardenSmallGlyph(index, Modifier.size(24.dp))
                    Text(item.label, fontSize = 13.sp, fontWeight = if (item == kind) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun GardenSmallGlyph(index: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width; val h = size.height
        val stroke = Stroke(1.4.dp.toPx())
        val color = if (index == 1) Color(0xFFAB8659) else Color(0xFF907187)
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(w*.15f, h*.12f), androidx.compose.ui.geometry.Size(w*.7f,h*.76f),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, Offset(w*.3f,h*.12f), Offset(w*.3f,h*.88f), stroke.width)
                drawLine(color, Offset(w*.44f,h*.34f), Offset(w*.7f,h*.34f), stroke.width)
                drawLine(color, Offset(w*.44f,h*.51f), Offset(w*.7f,h*.51f), stroke.width)
            }
            2 -> {
                drawRoundRect(color, Offset(w*.1f,h*.2f), androidx.compose.ui.geometry.Size(w*.8f,h*.6f),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                val path = Path().apply { moveTo(w*.12f,h*.24f); lineTo(w*.5f,h*.54f); lineTo(w*.88f,h*.24f) }
                drawPath(path, color, style = stroke)
            }
            else -> {
                val path = Path()
                repeat(10) { i ->
                    val angle = -Math.PI/2 + i*Math.PI/5
                    val r = w * (if(i%2==0) .44f else .2f)
                    val x = w/2 + cos(angle).toFloat()*r; val y = h/2 + sin(angle).toFloat()*r
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                path.close(); drawPath(path, color, style = stroke)
                if (index == 3) drawCircle(gardenPeach, 2.dp.toPx(), center)
            }
        }
    }
}

@Composable
internal fun GardenEntryCard(entry: OrbisGardenEntry, humanName: String, date: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick),
        color = gardenPaper.copy(alpha = .96f), contentColor = gardenInk, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(48.dp).background(
                if (entry.author == humanName) gardenPeach else Color(0xFFAEAAC2), CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("${entry.author} · $date", fontSize = 11.sp, color = gardenMuted)
                Text(entry.body, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
