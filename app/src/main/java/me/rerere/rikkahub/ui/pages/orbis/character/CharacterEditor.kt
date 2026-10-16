package me.rerere.rikkahub.ui.pages.orbis.character

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.orbis.character.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun CharacterPortrait(bitmap: Bitmap, regions: List<CharacterRegion>, showRegions: Boolean, glow: Float = 0f,
    onTap: (CharacterRegion) -> Unit = {}, selected: String? = null, onMove: ((Float, Float) -> Unit)? = null, modifier: Modifier = Modifier, stageFill: Boolean = false,
    animate: Boolean = false, reaction: Float = 0f, reactionRegion: CharacterRegion? = null) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)
    val latestRegions by rememberUpdatedState(regions)
    var seconds by remember(bitmap) { mutableFloatStateOf(0f) }
    var look by remember(bitmap) { mutableStateOf(CharacterPoint(0f, 0f)) }
    var target by remember(bitmap) { mutableStateOf(CharacterPoint(0f, 0f)) }
    var lastPointer by remember(bitmap) { mutableLongStateOf(0L) }
    val power = androidx.compose.ui.platform.LocalContext.current.getSystemService(android.os.PowerManager::class.java)
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(animate, bitmap) {
        seconds = 0f; look = CharacterPoint(0f, 0f); running = false
        if (animate) while (true) {
            running = android.animation.ValueAnimator.areAnimatorsEnabled() && power?.isPowerSaveMode != true
            if (running) {
                seconds = (android.os.SystemClock.elapsedRealtime() % 600_000L) / 1000f
                val aim = if (android.os.SystemClock.elapsedRealtime() - lastPointer < 1800) target else CharacterPoint(0f, 0f)
                look = CharacterPoint(look.x + (aim.x - look.x) * .12f, look.y + (aim.y - look.y) * .12f)
            }
            delay(if (running) 33 else 1000)
        }
    }
    val motion = CharacterMotion(seconds, look.x, look.y, reaction, reactionRegion, animate && running)
    val latestMotion by rememberUpdatedState(motion)
    val head = regions.firstOrNull { it.id == "head" }
    val latestHead by rememberUpdatedState(head)
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val vertices = remember { FloatArray(27 * 41 * 2) }
    fun frame(w: Float, h: Float) = if (stageFill) characterStageFrame(w, h, bitmap.width, bitmap.height) else characterFrame(w, h, bitmap.width, bitmap.height)
    Canvas(modifier.clipToBounds().semantics {
        contentDescription = "角色立绘"
        if (onMove == null && !showRegions) customActions = regions.map { region -> CustomAccessibilityAction("轻触${region.label}") { tap(region); true } }
    }.pointerInput(animate) {
        if (animate) awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent()
            event.changes.firstOrNull()?.let { change ->
                target = CharacterPoint((change.position.x / size.width * 2 - 1).coerceIn(-1f, 1f), (change.position.y / size.height * 2 - 1).coerceIn(-1f, 1f))
                lastPointer = android.os.SystemClock.elapsedRealtime()
            }
        } }
    }.pointerInput(bitmap, onMove != null, stageFill) {
        if (onMove != null) detectDragGestures { change, amount ->
            change.consume(); val frame = characterFrame(size.width.toFloat(), size.height.toFloat(), bitmap.width, bitmap.height)
            if (frame.width > 0 && frame.height > 0) move?.invoke(amount.x / frame.width, amount.y / frame.height)
        } else detectTapGestures { position ->
            val drawn = frame(size.width.toFloat(), size.height.toFloat()).point(position.x, position.y) ?: return@detectTapGestures
            val point = characterMotionSource(drawn, latestHead, latestMotion)
            if (point.x !in 0f..1f || point.y !in 0f..1f) return@detectTapGestures
            val x = (point.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val y = (point.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) >= 24) characterHit(latestRegions, point)?.let(tap)
        }
    }) {
        val f = frame(size.width, size.height)
        if (motion.enabled) {
            for (y in 0..40) for (x in 0..26) {
                val p = characterMotionPoint(CharacterPoint(x / 26f, y / 40f), head, motion)
                val i = (y * 27 + x) * 2
                vertices[i] = f.left + p.x * f.width; vertices[i + 1] = f.top + p.y * f.height
            }
            drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(bitmap, 26, 40, vertices, 0, null, 0, paint) }
        } else drawImage(image, dstOffset = IntOffset(f.left.roundToInt(), f.top.roundToInt()), dstSize = IntSize(f.width.roundToInt().coerceAtLeast(1), f.height.roundToInt().coerceAtLeast(1)))
        if (glow > 0) drawRect(Color(0xFFE6CA92).copy(alpha = glow * .6f), Offset(f.left, f.top), Size(f.width, f.height), style = Stroke(4f))
        if (showRegions) regions.forEach { r -> drawRect(if (r.id == selected) Color(0xFFFFCB70) else Color(0xFF70CDBB), Offset(f.left + r.x * f.width, f.top + r.y * f.height), Size(r.width * f.width, r.height * f.height), style = Stroke(if (r.id == selected) 5f else 2f)) }
    }
}

@Composable
internal fun CharacterEditor(profile: CharacterProfile, bitmap: Bitmap, onChange: (CharacterProfile) -> Unit, onSave: () -> Unit, busy: Boolean, modifier: Modifier) {
    val pack = requireNotNull(profile.pack)
    var selected by remember { mutableStateOf(pack.regions.first().id) }
    val region = pack.regions.firstOrNull { it.id == selected } ?: pack.regions.first()
    fun update(r: CharacterRegion) { onChange(profile.copy(pack = pack.copy(regions = pack.regions.map { if (it.id == r.id) r else it }))) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .93f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拖动框的位置，用滑杆调整大小；保存前不会改变原角色。")
                CharacterPortrait(bitmap, pack.regions, true, selected = region.id, onMove = { dx, dy -> update(region.copy(x = (region.x + dx).coerceIn(0f, 1 - region.width), y = (region.y + dy).coerceIn(0f, 1 - region.height))) }, modifier = Modifier.fillMaxWidth().height(240.dp))
                FlowRow { pack.regions.forEach { r -> TextButton(onClick = { selected = r.id }) { Text(if (region.id == r.id) "● ${r.label}" else r.label) } } }
                OutlinedTextField(region.label, { if (it.length <= 24) update(region.copy(label = it)) }, label = { Text("区域名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("宽度"); Slider(region.width, { update(region.copy(width = it, x = region.x.coerceAtMost(1 - it))) }, valueRange = .03f..1f)
                Text("高度"); Slider(region.height, { update(region.copy(height = it, y = region.y.coerceAtMost(1 - it))) }, valueRange = .03f..1f)
                var lines by remember(region.id) { mutableStateOf(profile.interactions.lines[region.id].orEmpty().joinToString("\n")) }
                OutlinedTextField(lines, { value -> if (value.length <= 2410) { lines = value; onChange(profile.copy(interactions = profile.interactions.copy(lines = profile.interactions.lines + (region.id to value.lines().filter(String::isNotBlank))))) } },
                    label = { Text("每行一句，最多 10 句，每句 240 字") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row { TextButton(enabled = pack.regions.size < 12, onClick = {
                    val id = (1..100).map { "region_$it" }.first { candidate -> pack.regions.none { it.id == candidate } }
                    onChange(profile.copy(pack = pack.copy(regions = pack.regions + CharacterRegion(id, "新区域", .4f, .4f, .2f, .2f)))); selected = id
                }) { Text("添加区域") }
                    TextButton(enabled = pack.regions.size > 1, onClick = { onChange(profile.copy(pack = pack.copy(regions = pack.regions.filterNot { it.id == region.id }), interactions = profile.interactions.copy(lines = profile.interactions.lines - region.id))) }) { Text("删除此区域") } }
                Text("居家场景")
                FlowRow { RoomMode.entries.forEach { mode -> TextButton(onClick = { onChange(profile.copy(room = mode)) }) { Text((if (profile.room == mode) "● " else "") + when (mode) { RoomMode.AUTO -> "跟随时间"; RoomMode.DAY -> "固定日间"; RoomMode.NIGHT -> "固定夜间" }) } } }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(profile.motion, { onChange(profile.copy(motion = it)) }); Text("人物呼吸、轻微跟随与触摸动作") }
                Text("自动：06–08 时淡入白天，17–19 时淡入夜晚。按手机本地时间，不读取定位。", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
            }
        }
    }
}
