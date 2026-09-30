package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.data.orbis.integration.*
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val AtlasInk = Color(0xFFEDDFC8)
internal val AtlasGold = Color(0xFFD4B77C)
internal val AtlasColors = listOf(Color(0xFFCDB7ED), Color(0xFFF0CE92), Color(0xFFECACC5), Color(0xFFA6D2D3))

/** Explicit demo or authenticated ST metadata, never private memory prose or a write endpoint. */
@Composable
fun OrbisMemoryAtlasPage(hostVisible: Boolean = true) {
    val connections = koinInject<OrbisIntegrationConnections>()
    val store = connections[OrbisIntegration.ST_ATLAS]
    val bootstrap by connections.atlasBootstrap.state.collectAsStateWithLifecycle()
    val connection by store.state.collectAsStateWithLifecycle()
    val canRead = connection.available && bootstrap.readAllowed
    val client = remember { OrbisAtlasClient() }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    var configure by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember(connection.revision, bootstrap.phase) { mutableStateOf<Atlas.Snapshot?>(null) }
    var error by remember(connection.revision) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val example = remember { Atlas.demo() }
    val graph: Atlas.Graph? = if (showDemo) example else snapshot.takeIf { canRead }
    var yaw by rememberSaveable { mutableDoubleStateOf(-.3) }
    var pitch by rememberSaveable { mutableDoubleStateOf(-.62) }
    var zoom by rememberSaveable { mutableDoubleStateOf(1.0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var clock by remember { mutableDoubleStateOf(0.0) }
    val systemReducedMotion = atlasSystemReducedMotion()
    val reducedMotion = LocalSettings.current.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).reduceMotion || systemReducedMotion
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(connection.revision, canRead, showDemo, refresh, resumed, hostVisible) {
        selected = null
        if (!resumed || !hostVisible || showDemo || !canRead) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            val credential = store.readCredential() ?: return@LaunchedEffect
            val loaded = client.load(credential)
            if (credential.revision == store.state.value.revision && store.state.value.available &&
                connections.atlasBootstrap.state.value.readAllowed) snapshot = loaded
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            error = when ((failure as? OrbisAtlasException)?.reason) {
                "unauthorized" -> "授权无效，请检查星盘专用 Token。"
                "endpoint_missing" -> "此地址没有星盘只读接口，请配置 ST 星盘服务。"
                "invalid_metadata" -> "服务返回的不是合规元信息，已拒绝显示。"
                else -> "暂时无法连接 ST，可稍后刷新；不会改用演示数据。"
            }
        } finally { loading = false }
    }
    fun camera() = Atlas.Camera(yaw, pitch, zoom)
    fun updateCamera(value: Atlas.Camera) { yaw = value.yaw; pitch = value.pitch; zoom = value.zoom }
    fun reset() { updateCamera(Atlas.Camera()); selected = null }
    val animate = graph != null && hostVisible && resumed && !paused && !reducedMotion && selected == null && !dragging
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        // A composition-owned frame loop: cancelled on pause, selection, touch, background or exit.
        // Throttle drawing to about 30fps; never catch up hidden/background time on resume.
        var last = 0L
        while (isActive) withFrameNanos { now ->
            if (last == 0L) last = now
            else if (now - last >= 30_000_000L) {
                val seconds = ((now - last) / 1_000_000_000.0).coerceAtMost(Atlas.MAX_FRAME_SECONDS)
                updateCamera(Atlas.advance(camera(), seconds))
                clock += seconds
                last = now
            }
        }
    }
    val motionLabel = when {
        reducedMotion -> "减小动效已开启 · 可手动旋转"
        selected != null -> "已聚焦星点 · 关闭卡片继续漫游"
        paused -> "自转已暂停 · 可手动旋转"
        else -> "拖动旋转 · 双指缩放 · 轻触星点"
    }
    OrbisVisualTheme {
        Scaffold(containerColor = Color(0xFF080A17),
            topBar = {
                OrbisPageHeader(title = "记忆星盘", subtitle = "ST · 时间、类型与关联",
                    avatar = { Text("✦", fontSize = 21.sp) }, navigationIcon = { BackButton() },
                    modifier = Modifier.background(OrbisTheme.colors.page).statusBarsPadding())
            },
            bottomBar = { OrbisChatDock(currentLabel = "当前 星盘") },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val compact = maxHeight < 490.dp
                // Keep every control reachable with large system text or a short/landscape viewport.
                // Normal portrait keeps the original inline metadata card; constrained layouts use a dialog.
                val constrained = maxHeight < 440.dp || LocalDensity.current.fontScale > 1.4f
                if (graph != null) OrbisMemoryAtlasCanvas(
                    demo = graph, camera = { camera() }, clock = { clock }, selected = selected,
                    motionLabel = motionLabel,
                    onCamera = ::updateCamera, onSelected = { selected = it },
                    onDragging = { dragging = it }, onReset = ::reset,
                    onToggleMotion = { paused = !paused }, modifier = Modifier.fillMaxSize(),
                )
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = if (compact) 16.dp else 25.dp)) {
                    Column(Modifier.padding(horizontal = 22.dp)) {
                        Text("ST · MEMORY ATLAS", color = Color(0xFFBAA484), fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 2.sp)
                        if (!constrained) Text("记忆星盘", color = AtlasInk, fontSize = if (compact) 25.sp else 28.sp,
                            fontFamily = FontFamily.Serif, lineHeight = 36.sp, letterSpacing = 6.sp,
                            modifier = Modifier.padding(top = 9.dp, bottom = 7.dp).semantics { heading() })
                        Text(if (showDemo) "本地演示 · 非真实记忆" else "真实 ST · 只读元信息", color = Color(0xFFB6ACB2), fontSize = 12.sp, lineHeight = 17.sp)
                        Text(when {
                            showDemo -> "56 个合成示例星点，不代表你的记忆"
                            connection.error != null -> "本机授权读取失败，请进入连接设置"
                            !bootstrap.readAllowed -> "授权待确认或已撤销 · 读取已暂停，请进入连接设置"
                            !connection.available -> "尚未启用连接 · 请配置星盘专用服务"
                            loading -> "正在读取元信息…"
                            error != null -> (if (snapshot != null) "保留上次快照 · " else "") + error
                            snapshot != null -> "${snapshot!!.stars.size} 个星点 · ${snapshot!!.edges.size} 条关联" +
                                (if (snapshot!!.truncated) " · 已达到显示上限" else "")
                            else -> "等待连接"
                        }, color = Color(0xFFB6ACB2), fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 3.dp))
                        Row {
                            TextButton(onClick = { configure = true }) { Text("连接设置", color = AtlasInk) }
                            TextButton(onClick = { refresh++ }, enabled = canRead && !showDemo && !loading) { Text("刷新", color = AtlasInk) }
                            TextButton(onClick = { showDemo = !showDemo; selected = null }) { Text(if (showDemo) "返回真实 ST" else "查看演示", color = AtlasInk) }
                        }
                    }
                    Row(Modifier.align(Alignment.End).padding(end = 10.dp, top = 6.dp)) {
                        AtlasControl(if (paused) "▷" else "Ⅱ", if (paused) "继续星盘自转" else "暂停星盘自转",
                            enabled = !reducedMotion, onClick = { paused = !paused })
                        AtlasControl("⌂", "重置星盘视角", onClick = ::reset)
                        AtlasControl("+", "放大星盘", enabled = zoom < Atlas.MAX_ZOOM,
                            onClick = { updateCamera(Atlas.zoomTo(camera(), zoom + .15)) })
                        AtlasControl("−", "缩小星盘", enabled = zoom > Atlas.MIN_ZOOM,
                            onClick = { updateCamera(Atlas.zoomTo(camera(), zoom - .15)) })
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected?.let { index -> graph?.stars?.getOrNull(index)?.let { star ->
                        if (!constrained) AtlasMetadata(star, showDemo, onClose = { selected = null }, modifier = Modifier
                            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp))
                    } }
                    FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Atlas.TYPES.forEachIndexed { index, name ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(5.dp).background(AtlasColors[index], CircleShape))
                                Text(name, color = Color(0xFFCFBEC4), fontSize = 11.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                    Text(motionLabel, color = Color(0xFFB09E92), fontSize = 10.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp))
                }
                if (!showDemo && snapshot?.stars?.isEmpty() == true && error == null && !loading) {
                    Text("此 ST 范围暂无可显示的记忆", color = AtlasInk, modifier = Modifier.align(Alignment.Center))
                }
                if (constrained) selected?.let { index -> graph?.stars?.getOrNull(index)?.let { star ->
                    Dialog(onDismissRequest = { selected = null }) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AtlasMetadata(star, showDemo, onClose = { selected = null })
                        }
                    }
                } }
            }
        }
        if (configure) OrbisIntegrationSettingsDialog(OrbisIntegration.ST_ATLAS) { configure = false }
    }
}

@Composable
private fun AtlasControl(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Surface(shape = CircleShape, color = Color(0xA6171521),
            border = BorderStroke(1.dp, AtlasGold.copy(alpha = if (enabled) .27f else .12f)), modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(symbol, fontSize = 19.sp, color = AtlasInk.copy(alpha = if (enabled) 1f else .3f), fontFamily = FontFamily.Serif)
            }
        }
    }
}

@Composable
private fun AtlasMetadata(star: Atlas.Star, demo: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val localTime = remember(star.storedAt) {
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(star.storedAt))
    }
    Surface(modifier.fillMaxWidth(), color = Color(0xF21E1A28), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AtlasGold.copy(alpha = .34f))) {
        Box(Modifier.padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 15.dp)) {
            Column(Modifier.padding(end = 42.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (demo) "演示星点" else "ST 记忆元信息 · 不含正文", fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFB6A78F))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("记录时间", color = Color(0xFFB6A78F), fontSize = 13.sp, lineHeight = 19.sp)
                    Text(localTime, color = AtlasInk, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("记忆类型", color = Color(0xFFB6A78F), fontSize = 13.sp, lineHeight = 19.sp)
                    Text(star.type, color = AtlasInk, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                .semantics { contentDescription = "关闭星点信息" }) { Text("×", color = AtlasInk, fontSize = 22.sp) }
        }
    }
}

/** Observes the existing Android animation preference; never writes system settings. */
@Composable
private fun atlasSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            reduced = read()
        }.isSuccess
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
