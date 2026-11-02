package me.rerere.rikkahub.ui.pages.orbis.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.music.*
import kotlin.math.roundToInt

internal val LocalMusicLight = staticCompositionLocalOf { false }
internal val MusicInk: Color @Composable get() = if(LocalMusicLight.current) Color(0xFF23364B) else Color(0xFFF0F2FF)
internal val MusicMuted: Color @Composable get() = if(LocalMusicLight.current) Color(0xFF5E7288) else Color(0xFFACB7CF)
internal val MusicMint: Color @Composable get() = if(LocalMusicLight.current) Color(0xFF2A6672) else Color(0xFFB4E6E2)
internal val MusicGold: Color @Composable get() = if(LocalMusicLight.current) Color(0xFF896830) else Color(0xFFE8CD91)
internal val MusicNight: Color @Composable get() = if(LocalMusicLight.current) Color(0xFFF0F4FA) else Color(0xFF101B31)

@Composable internal fun rememberMusicRuntime(): MusicRuntime? {
    val context = LocalContext.current.applicationContext
    return produceState<MusicRuntime?>(null, context) { value = withContext(Dispatchers.IO) { MusicRuntime.get(context) } }.value
}

@Composable internal fun MusicEntry() {
    val runtime = rememberMusicRuntime() ?: return
    val playback by runtime.playback.collectAsState()
    IconButton(onClick = { runtime.appearance { it.copy(mode = "expanded") } }, modifier = Modifier.semantics { contentDescription = "打开星河音乐" }) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Text("♫", fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
            if(playback.playing) Box(Modifier.size(6.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable internal fun GalaxySurface(modifier: Modifier = Modifier, opacity: Float = .95f, content: @Composable BoxScope.() -> Unit) {
    val gold = MusicGold; val ink = MusicInk; val light = LocalMusicLight.current
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(
        (if(light) Color(0xFFF5F7FF) else Color(0xFF182440)).copy(alpha = opacity), (if(light) Color(0xFFE5EBF6) else Color(0xFF26334F)).copy(alpha = opacity), (if(light) Color(0xFFDAEDEF) else Color(0xFF132C3D)).copy(alpha = opacity))))
        .border(1.dp, MusicGold.copy(alpha = .22f), RoundedCornerShape(26.dp))) {
        Canvas(Modifier.matchParentSize()) {
            val points = listOf(.47f to .18f, .56f to .14f, .65f to .18f, .74f to .24f, .87f to .22f, .85f to .37f, .74f to .38f, .74f to .24f)
            points.zipWithNext().forEach { (a, b) -> drawLine(gold.copy(alpha = .18f * opacity), Offset(a.first * size.width, a.second * size.height), Offset(b.first * size.width, b.second * size.height), 1.dp.toPx()) }
            points.forEach { (x, y) -> drawCircle(gold.copy(alpha = .7f * opacity), 1.8.dp.toPx(), Offset(x * size.width, y * size.height)) }
            repeat(22) { n -> drawCircle(ink.copy(alpha = .14f * opacity), if(n % 4 == 0) 1.1.dp.toPx() else .6.dp.toPx(), Offset(((n * 43 + 13) % 100) / 100f * size.width, ((n * 29 + 7) % 100) / 100f * size.height)) }
        }
        CompositionLocalProvider(LocalContentColor provides MusicInk) { content() }
    }
}

@Composable internal fun MusicButton(glyph: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = 23.sp, color = MusicMint, textAlign = TextAlign.Center)
    }
}

@Composable internal fun MusicCover(track: MusicTrack?, runtime: MusicRuntime, size: Int = 74) {
    val gold = MusicGold
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF617194), MusicNight)))
        .border(1.dp, MusicGold.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) {
        if(track?.cover == true) AsyncImage(runtime.store.cover(track), "专辑封面", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text("✦", color = MusicGold, fontSize = (size / 2).sp)
        Canvas(Modifier.fillMaxSize().padding(6.dp)) { drawCircle(gold.copy(alpha = .25f), style = Stroke(1.dp.toPx())) }
    }
}

@Composable internal fun MusicOverlay(ownerName: String, modifier: Modifier = Modifier) {
    val runtime = rememberMusicRuntime() ?: return
    val library by runtime.store.state.collectAsState()
    CompositionLocalProvider(LocalMusicLight provides (library.appearance.style == "mist")) { MusicOverlayContent(runtime, ownerName, modifier) }
}
@Composable private fun MusicOverlayContent(runtime: MusicRuntime, ownerName: String, modifier: Modifier) {
    val library by runtime.store.state.collectAsState()
    val playback by runtime.playback.collectAsState()
    val showLibrary by runtime.showLibrary.collectAsState()
    val error by runtime.error.collectAsState()
    var appearance by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(false) }
    val track = library.tracks.firstOrNull { it.id == playback.trackId }
    val a = library.appearance
    if(a.mode != "hidden") BoxWithConstraints(modifier) {
        val availableHeight = maxHeight
        val density = LocalDensity.current
        val width = if(a.mode == "expanded") maxWidth.coerceAtMost(360.dp) else maxWidth.coerceAtMost(300.dp)
        var heightPx by remember { mutableIntStateOf(0) }
        val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val maxY = (with(density) { maxHeight.toPx() } - heightPx).coerceAtLeast(0f)
        var drag by remember(a.x, a.y, maxX, maxY) { mutableStateOf(Offset(maxX * a.x, maxY * a.y)) }
        val dragModifier = if(a.locked) Modifier else Modifier.pointerInput(maxX, maxY) {
            detectDragGestures(onDragEnd = {
                runtime.appearance { it.copy(x = if(maxX > 0) { if(a.mode == "mini") { if(drag.x < maxX / 2) 0f else 1f } else drag.x / maxX } else .5f, y = if(maxY > 0) drag.y / maxY else 0f) }
            }) { change, delta -> change.consume(); drag = Offset((drag.x + delta.x).coerceIn(0f, maxX), (drag.y + delta.y).coerceIn(0f, maxY)) }
        }
        GalaxySurface(Modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }.width(width)
            .onSizeChanged { heightPx = it.height }.then(dragModifier), if(a.mode == "lyrics") a.lyricOpacity else a.opacity) {
            when(a.mode) {
                "expanded" -> Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MusicCover(track, runtime, 52)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(track?.title ?: "星河音乐", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(track?.artist ?: "从本地曲库开始", color = MusicMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if(track != null) "${playback.selectedBy}选的歌" else "你和伙伴的音乐时光", color = MusicGold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MusicButton("−", "缩小播放器") { runtime.appearance { it.copy(mode = "mini") } }
                        MusicButton("×", "隐藏播放器，继续播放") { runtime.appearance { it.copy(mode = "hidden") } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        MusicButton(if(track?.favorite == true) "♥" else "♡", "收藏歌曲") { track?.let { runtime.human { withContext(Dispatchers.IO) { runtime.store.favorite(it.id) } } } }
                        MusicButton("❮", "上一首") { runtime.human { runtime.command("previous") } }
                        MusicButton(if(playback.playing) "Ⅱ" else "▶", if(playback.playing) "暂停音乐" else "播放音乐", Modifier.background(MusicMint.copy(alpha = .15f), CircleShape)) {
                            if(track == null) runtime.library(true) else runtime.human { runtime.command(if(playback.playing) "pause" else "resume") }
                        }
                        MusicButton("❯", "下一首") { runtime.human { runtime.command("next") } }
                        MusicButton("≡", "播放队列") { queue = true }
                    }
                    MusicProgress(runtime, playback)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { runtime.human { runtime.command("mode", repeat = nextRepeat(playback.repeat)) } }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(modeName(playback.repeat), color = MusicMuted, fontSize = 12.sp, maxLines = 1) }
                        TextButton(onClick = { runtime.appearance { it.copy(mode = "lyrics") } }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("只看歌词", color = MusicMint, fontSize = 12.sp, maxLines = 1) }
                        TextButton(onClick = { runtime.library(true) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("我的曲库", color = MusicMint, fontSize = 12.sp, maxLines = 1) }
                        MusicButton("◌", "音乐外观") { appearance = true }
                    }
                    if(playback.loading) Text("正在准备音频…", color = MusicMuted, fontSize = 12.sp)
                    playback.error?.let { Text("播放未完成：$it", color = MusicGold, fontSize = 12.sp) }
                }
                "mini" -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MusicCover(track, runtime, 40)
                    Column(Modifier.weight(1f).clickable { runtime.appearance { it.copy(mode = "expanded") } }.padding(horizontal = 8.dp)) {
                        Text(track?.title ?: "星河音乐", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track?.artist ?: "点开选一首歌", fontSize = 10.sp, color = MusicMuted, maxLines = 1)
                    }
                    MusicButton(if(playback.playing) "Ⅱ" else "▶", "播放或暂停") { if(track == null) runtime.library(true) else runtime.human { runtime.command(if(playback.playing) "pause" else "resume") } }
                    MusicButton("❯", "下一首") { runtime.human { runtime.command("next") } }
                }
                "lyrics" -> {
                    val lines = remember(track?.lyrics) { parseLrc(track?.lyrics ?: "") }
                    val current = lines.indexOfLast { it.time <= playback.position }
                    var toolsVisible by remember { mutableStateOf(true) }
                    LaunchedEffect(toolsVisible, a.locked) { if(toolsVisible) { kotlinx.coroutines.delay(5000); toolsVisible = false } }
                    Column(Modifier.fillMaxWidth().then(if(!a.locked) Modifier.clickable { toolsVisible = true } else Modifier).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(lines.getOrNull(current)?.text ?: if(lines.isNotEmpty()) "♫" else track?.lyrics?.takeIf { it.isNotBlank() }?.take(500) ?: "${track?.title ?: "星河音乐"} · 暂无歌词", fontSize = a.lyricSize.sp, color = MusicMint, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        lines.getOrNull(current + 1)?.let { Text(it.text, fontSize = (a.lyricSize * .7).sp, color = MusicMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if(toolsVisible && !a.locked) Row {
                            MusicButton(if(playback.playing) "Ⅱ" else "▶", "播放或暂停") { runtime.human { runtime.command(if(playback.playing) "pause" else "resume") } }
                            MusicButton(if(a.locked) "▣" else "◇", if(a.locked) "解锁歌词位置" else "锁定歌词位置") { runtime.appearance { it.copy(locked = !it.locked) } }
                            MusicButton("◌", "歌词外观") { appearance = true }
                            MusicButton("⌃", "展开播放器") { runtime.appearance { it.copy(mode = "expanded") } }
                            MusicButton("×", "隐藏歌词") { runtime.appearance { it.copy(mode = "hidden") } }
                        }
                    }
                }
            }
        }
    }
    if(showLibrary) MusicLibraryPage(runtime, ownerName) { runtime.library(false) }
    if(appearance) MusicAppearanceDialog(runtime) { appearance = false }
    if(queue) AlertDialog(onDismissRequest = { queue = false }, title = { Text("播放队列 · ${playback.queue.size} 首") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            playback.queue.forEachIndexed { i, id -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if(i == playback.index) "♫ " else ""}${library.tracks.firstOrNull { it.id == id }?.title ?: "歌曲不可用"}", Modifier.weight(1f).clickable { runtime.human { runtime.command("play", playback.queue, i) }; queue = false }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { if(i > 0) runtime.human { val q = playback.queue.toMutableList(); java.util.Collections.swap(q, i, i - 1); runtime.command("queue", q) } }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { runtime.human { runtime.command("queue", playback.queue.filterIndexed { idx, _ -> idx != i }) } }) { Text("移除") }
            } }
        }
    }, confirmButton = { TextButton(onClick = { queue = false }) { Text("完成") } })
    error?.let { AlertDialog(onDismissRequest = runtime::dismissError, title = { Text("星河音乐") }, text = { Text(it) }, confirmButton = { TextButton(onClick = runtime::dismissError) { Text("知道了") } }) }
}

@Composable private fun MusicProgress(runtime: MusicRuntime, playback: MusicPlayback) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    val duration = playback.duration.coerceAtLeast(1)
    val progressColors = SliderDefaults.colors(thumbColor = MusicGold, activeTrackColor = MusicMint, inactiveTrackColor = MusicMuted.copy(alpha = .2f))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(musicTime(seeking?.let { (it * duration).toLong() } ?: playback.position), color = MusicMuted, fontSize = 10.sp)
    Slider(value = seeking ?: (playback.position.toFloat() / duration).coerceIn(0f, 1f), onValueChange = { seeking = it }, modifier = Modifier.weight(1f),
        onValueChangeFinished = { seeking?.let { value -> runtime.human { runtime.command("seek", position = (value * duration).toLong()) } }; seeking = null },
        enabled = playback.trackId != null, colors = progressColors,
        thumb = { Box(Modifier.size(12.dp).background(MusicGold, CircleShape)) },
        track = { state -> SliderDefaults.Track(sliderState = state, modifier = Modifier.height(4.dp), colors = progressColors, drawStopIndicator = null, thumbTrackGapSize = 0.dp) })
    Text(musicTime(playback.duration), color = MusicMuted, fontSize = 10.sp)
    }
}
@Composable private fun MusicAppearanceDialog(runtime: MusicRuntime, dismiss: () -> Unit) {
    val library by runtime.store.state.collectAsState()
    var a by remember { mutableStateOf(library.appearance) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("星河外观") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Row { FilterChip(a.style == "galaxy", { a = a.copy(style = "galaxy") }, label = { Text("星河 · 深色") }); Spacer(Modifier.width(8.dp)); FilterChip(a.style == "mist", { a = a.copy(style = "mist") }, label = { Text("星雾 · 浅色") }) }
        Text("面板不透明度 ${(a.opacity * 100).toInt()}%")
        Slider(a.opacity, { a = a.copy(opacity = it) }, valueRange = .15f..1f)
        Text("歌词底色不透明度 ${(a.lyricOpacity * 100).toInt()}%")
        Slider(a.lyricOpacity, { a = a.copy(lyricOpacity = it) })
        Text("歌词字号 ${a.lyricSize.toInt()}")
        Slider(a.lyricSize, { a = a.copy(lyricSize = it) }, valueRange = 14f..32f)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("锁定浮窗位置", Modifier.weight(1f)); Switch(a.locked, { a = a.copy(locked = it) }) }
        Text("透明度只调整底色；文字和播放按钮保持清晰。", style = MaterialTheme.typography.bodySmall)
    } }, confirmButton = { TextButton(onClick = { runtime.appearance { it.copy(opacity = a.opacity, lyricOpacity = a.lyricOpacity, lyricSize = a.lyricSize, locked = a.locked, style = a.style) }; dismiss() }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
internal fun musicTime(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
internal fun modeName(mode: String) = when(mode) { "one" -> "单曲循环"; "shuffle" -> "随机播放"; "off" -> "顺序播放"; else -> "列表循环" }
private fun nextRepeat(mode: String) = when(mode) { "all" -> "one"; "one" -> "shuffle"; "shuffle" -> "off"; else -> "all" }
