package me.rerere.rikkahub.ui.pages.orbis.character

import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.orbis.character.*
import me.rerere.rikkahub.ui.widget.OrbisCharacterWidget
import me.rerere.tts.controller.TtsController
import me.rerere.tts.provider.TTSManager
import org.koin.compose.koinInject
import java.time.LocalTime
import kotlin.math.roundToInt

/** Offline stage. Touches never invoke a model, upload images or write chat memory. */
@Composable
fun CharacterStage(owner: String, assistantName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(owner) { openCharacterStore(context, owner) }
    val settings = koinInject<SettingsStore>()
    val manager = koinInject<TTSManager>()
    val voice = remember(owner) { TtsController(context, manager) }
    var speaking by remember(owner) { mutableStateOf(false) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var profile by remember(owner) { mutableStateOf<CharacterProfile?>(null) }
    var bitmap by remember(owner) { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var line by remember(owner) { mutableStateOf("轻触人物，听听预备好的回应。") }
    var panel by remember { mutableStateOf(false) }
    var faceSetup by remember(owner) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<CharacterProfile?>(null) }
    var draft by remember { mutableStateOf<CharacterDraft?>(null) }
    var draftBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingExport by remember { mutableStateOf<Boolean?>(null) }
    var exportChoice by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(LocalTime.now()) }
    var assetTarget by remember { mutableStateOf<CharacterAssetKind?>(null) }
    var importEmotion by remember { mutableStateOf("calm") }
    val gate = remember(owner) { CharacterTapGate(SystemClock::elapsedRealtime) }
    val glow = remember { Animatable(0f) }
    var reactionRegion by remember(owner) { mutableStateOf<CharacterRegion?>(null) }
    val lineIndices = remember(owner) { mutableMapOf<String, Int>() }
    val voiceError by voice.error.collectAsState()

    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { speaking = false; voice.stop() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.dispose() }
    }
    fun task(work: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = characterError(failure) }
            finally { busy = false }
        }
    }
    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { store.read() }
        bitmap = withContext(Dispatchers.IO) { loaded.pack?.let { val bytes = store.imageBytes(it); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("character_image") } }
        profile = loaded
        lineIndices.clear()
    }
    LaunchedEffect(owner) { task { reload() } }
    // Live AI changes appear while the room is open; do not clobber a human's unsaved editor.
    LaunchedEffect(active, editing, busy) {
        if (active && !editing && !busy) while (true) {
            time = LocalTime.now()
            val fresh = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }
            if (fresh != null && fresh.revision != profile?.revision) {
                if (fresh.pack == profile?.pack) profile = fresh else task { reload() }
            }
            delay(2000)
        }
    }
    fun save(value: CharacterProfile) { task {
        withContext(Dispatchers.IO) { store.save(value, value.revision) }
        reload(); OrbisCharacterWidget.refresh(context)
    } }
    suspend fun preview(value: CharacterDraft) {
        val decoded = withContext(Dispatchers.IO) { verifyCharacterDraft(value); BitmapFactory.decodeByteArray(value.png, 0, value.png.size) ?: error("character_image") }
        draftBitmap = decoded; draft = value; panel = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task { preview(withContext(Dispatchers.IO) { importCharacterImage(context, uri) }) }
    }
    val assetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = assetTarget; assetTarget = null
        val current = profile
        if (uri != null && target != null && current?.pack != null) task {
            withContext(Dispatchers.IO) {
                val image = importCharacterImage(context, uri)
                val id = "custom_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                val tag = when (target) {
                    CharacterAssetKind.ROOM_DAY, CharacterAssetKind.ROOM_NIGHT -> "custom-room"
                    CharacterAssetKind.CARD -> id
                    else -> importEmotion
                }
                val asset = CharacterAsset(id, target, tag, image.pack.sha256, image.pack.width, image.pack.height, importEmotion)
                val previous = current.presentation
                val kept = if (target in setOf(CharacterAssetKind.ROOM_DAY, CharacterAssetKind.ROOM_NIGHT)) previous.assets.filterNot { it.kind == target && it.tag == tag } else previous.assets
                val presentation = previous.copy(assets = kept + asset,
                    scene = if (target in setOf(CharacterAssetKind.ROOM_DAY, CharacterAssetKind.ROOM_NIGHT)) tag else previous.scene,
                    card = if (target == CharacterAssetKind.CARD) tag else previous.card,
                    widgetAssetId = if (target in setOf(CharacterAssetKind.POSE, CharacterAssetKind.EXPRESSION)) id else previous.widgetAssetId)
                store.install(CharacterDraft(current.pack, current.interactions, store.imageBytes(current.pack), presentation, mapOf(asset.sha256 to image.png)), current.revision)
            }
            reload(); OrbisCharacterWidget.refresh(context); notice = "素材已保存；人物、卡片与房间背景分别管理。"
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task { preview(withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { CharacterArchive.read(it) } ?: error("character_archive") }) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val include = pendingExport ?: false; pendingExport = null
        if (uri != null) task {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { store.export(it, include) } ?: error("character_storage") }
            notice = if (include) "已导出素材和台词；分享前请检查私人内容。" else "已导出素材，不含台词、问候语和助手身份。"
        }
    }
    fun toggleVoice() {
        if (speaking) { speaking = false; voice.stop() }
        else {
            val provider = settings.settingsFlow.value.getSelectedTTSProvider()
            if (provider == null) notice = "请先配置 TTS；文字互动不受影响。"
            else { voice.setProvider(provider); voice.setSpeed(settings.settingsFlow.value.defaultTTSPlaybackSpeed); speaking = true; notice = "朗读可能联网计费；离开房间会自动关闭。" }
        }
    }
    fun react(region: CharacterRegion) {
        if (busy || !active || !gate.accept()) return
        reactionRegion = region
        if (profile?.motion == true && ValueAnimator.areAnimatorsEnabled()) scope.launch { glow.snapTo(1f); glow.animateTo(0f, tween(900)) }
        val lines = profile?.interactions?.lines?.get(region.id).orEmpty()
        if (lines.isEmpty()) { line = "${region.label}：尚未设置回应，可让 AI 使用 orbis_character 设置台词。"; return }
        val index = lineIndices.getOrDefault(region.id, 0)
        line = lines[index % lines.size]; lineIndices[region.id] = (index + 1) % lines.size
        if (speaking) {
            if (context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL) {
                val voiceSettings = settings.settingsFlow.value
                voice.setProvider(voiceSettings.getSelectedTTSProvider())
                voice.setSpeed(voiceSettings.defaultTTSPlaybackSpeed)
                voice.speak(line, mixerConfig = voiceSettings.voiceMixer)
            }
            else { voice.stop(); speaking = false; notice = "设备正在通话，已保持静音。" }
        }
    }
    BackHandler { when { draft != null -> { draft = null; draftBitmap = null }; editing -> editing = false; panel -> panel = false; else -> onClose() } }
    val current = profile
    val portrait = draftBitmap ?: bitmap
    val pack = draft?.pack ?: current?.pack
    Box(Modifier.fillMaxSize()) {
        CharacterScene(store, current?.presentation ?: CharacterPresentation(),
            characterNightFraction(time.hour, time.minute, current?.room ?: RoomMode.AUTO), Modifier.fillMaxSize())
        if (portrait != null && pack != null && !editing) {
            CharacterPortrait(portrait, pack.regions, showRegions = draft != null, stageFill = draft == null, onTap = { if (draft == null) react(it) },
                animate = active && !panel && !faceSetup && draft == null && current?.motion == true,
                reaction = glow.value, reactionRegion = reactionRegion,
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 32.dp))
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("‹") }
                Text(assistantName + "的房间", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 2f), 8f)))
                TextButton(onClick = { panel = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("⚙", style = MaterialTheme.typography.headlineSmall) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) { Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall) } }
            if (voiceError != null && speaking) Text("朗读未完成；文字仍可使用。", color = Color.White)
            if (editing && edit?.pack != null && bitmap != null) {
                CharacterEditor(edit!!, bitmap!!, onChange = { edit = it }, onSave = {
                    val candidate = edit!!
                    task { withContext(Dispatchers.IO) { store.save(candidate, candidate.revision) }; reload(); editing = false; OrbisCharacterWidget.refresh(context) }
                }, busy = busy, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
                if (pack == null) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("把你的 TA 带到这里", style = MaterialTheme.typography.headlineSmall)
                        Text("全屏人物、桌面Q版与背景分别导入。素材仅留在本机。")
                        Button(enabled = !busy && current != null, onClick = { picker.launch(arrayOf("image/*")) }) { Text("选择全屏人物图") }
                        TextButton(enabled = !busy && current != null, onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入完整角色包") }
                    }
                }
                if (draft != null) Surface(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("确认导入", style = MaterialTheme.typography.titleMedium)
                        Text("将替换本助手的人物与台词；完整包也替换其素材库。聊天不变。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy, onClick = { draft = null; draftBitmap = null }) { Text("取消") }
                            Button(enabled = !busy && current != null, onClick = { val d = draft!!; task {
                                withContext(Dispatchers.IO) { store.install(d, current!!.revision) }; draft = null; draftBitmap = null; reload(); OrbisCharacterWidget.refresh(context)
                            } }) { Text("确认安装") }
                        }
                    }
                } else if (pack != null) {
                    Text(line, Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                        color = Color.White, style = MaterialTheme.typography.titleLarge.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 2f), 10f)))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("离线预备台词", Modifier.weight(1f), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = ::toggleVoice, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(if (speaking) "声音：开" else "声音：关") }
                    }
                }
            }
        }
    }
    if (panel && current != null) androidx.compose.ui.window.Dialog(onDismissRequest = { panel = false }) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("房间设置", style = MaterialTheme.typography.titleLarge)
                Text("AI 工具：orbis_character · 可设置分区台词、桌面短句与限时表情范围。", style = MaterialTheme.typography.bodySmall)
                if (current.pack != null) TextButton(enabled = !busy, onClick = { edit = current; editing = true; panel = false }) { Text("调整触摸区域与台词") }
                Row {
                    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("image/*")) }) { Text("全屏人物") }
                    TextButton(enabled = !busy, onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入包") }
                    TextButton(enabled = !busy && current.pack != null, onClick = { exportChoice = true }) { Text("导出包") }
                }
                Text("房间场景 · 昼夜渐变")
                val sceneLabels = mapOf("living" to "窗边客厅", "study" to "暖木书房", "terrace" to "花园露台", "custom-room" to "自定义")
                FlowRow { (characterSceneNames + current.presentation.assets.filter { it.kind in setOf(CharacterAssetKind.ROOM_DAY, CharacterAssetKind.ROOM_NIGHT) }.map { it.tag }).distinct().forEach { scene ->
                    TextButton(enabled = !busy, onClick = { save(current.copy(presentation = current.presentation.copy(scene = scene))) }) { Text((if (current.presentation.scene == scene) "● " else "") + (sceneLabels[scene] ?: scene)) }
                } }
                FlowRow { RoomMode.entries.forEach { mode -> TextButton(enabled = !busy, onClick = { save(current.copy(room = mode)) }) { Text((if (current.room == mode) "● " else "") + when (mode) { RoomMode.AUTO -> "随时间"; RoomMode.DAY -> "白天"; RoomMode.NIGHT -> "夜晚" }) } } }
                Text("自动在 06–08 时淡入白天，17–19 时淡入夜晚，按手机本地时间。", style = MaterialTheme.typography.labelSmall)
                if (current.pack != null) Row { listOf(CharacterAssetKind.ROOM_DAY to "导入白天", CharacterAssetKind.ROOM_NIGHT to "导入夜晚").forEach { (kind, label) ->
                    TextButton(enabled = !busy, onClick = { assetTarget = kind; assetPicker.launch(arrayOf("image/*")) }) { Text(label) }
                } }
                Text("桌面卡片底图")
                val cards = (characterCardNames + current.presentation.assets.filter { it.kind == CharacterAssetKind.CARD }.map { it.tag }).distinct()
                val labels = listOf("晴空蓝", "蜜桃奶油", "鼠尾草绿", "浅紫夜色", "拿铁沙色")
                FlowRow { cards.forEach { card -> TextButton(enabled = !busy, onClick = { save(current.copy(presentation = current.presentation.copy(card = card))) }) { Text((if (current.presentation.card == card) "● " else "") + characterCardNames.indexOf(card).takeIf { it >= 0 }?.let { labels[it] }.orEmpty().ifBlank { "自定义" }) } } }
                var greeting by remember(current.revision) { mutableStateOf(current.presentation.greeting) }
                OutlinedTextField(greeting, { if (it.length <= 120) greeting = it }, label = { Text("桌面短句") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !busy, onClick = { save(current.copy(presentation = current.presentation.copy(greeting = greeting))) }) { Text("保存短句") }
                Text("导入Q版时选择情绪标签（真人图请用上面的全屏人物）", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { panel = false; faceSetup = true }) { Text("面部配准 · 换表情保持姿势") }
                Text("没有配准的旧图不会被强行拼脸；先给动作和表情设置同组面部区域。", style = MaterialTheme.typography.labelSmall)
                val emotions = mapOf("calm" to "平静", "smile" to "笑", "cry" to "哭", "think" to "思考", "angry" to "怒", "shy" to "害羞", "sleepy" to "困", "excited" to "激动")
                FlowRow { emotions.forEach { (id, label) -> TextButton(onClick = { importEmotion = id }) { Text((if (id == importEmotion) "● " else "") + label) } } }
                if (current.pack != null) FlowRow { listOf(CharacterAssetKind.POSE to "添加Q版动作", CharacterAssetKind.EXPRESSION to "添加Q版表情", CharacterAssetKind.CARD to "导入卡片底图").forEach { (kind, label) ->
                    TextButton(enabled = !busy, onClick = { assetTarget = kind; assetPicker.launch(arrayOf("image/*")) }) { Text(label) }
                } }
                current.presentation.expressionPolicy?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { policy ->
                    Text("当前允许：" + policy.allowed.joinToString { emotions[it] ?: it }, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { save(current.copy(presentation = current.presentation.copy(expressionPolicy = null))) }) { Text("解除限时表情范围") }
                }
                TextButton(onClick = {
                    val widgets = AppWidgetManager.getInstance(context)
                    notice = if (widgets.isRequestPinAppWidgetSupported && widgets.requestPinAppWidget(ComponentName(context, OrbisCharacterWidget::class.java), null, null)) "请在桌面确认添加，再选择助手；旧竖版建议移除后重新添加横版。" else "请长按手机桌面，从小部件中选择 Orbis 互动。"
                    panel = false
                }) { Text("添加横向桌面小部件") }
                Text("素材与台词请用「导出包」单独备份；公开分享默认不含私人台词。开启TTS可能联网计费。", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { panel = false }) { Text("完成") }
            }
        }
    }
    if (faceSetup && current != null) CharacterFaceSetup(store, current, onDismiss = { faceSetup = false }, onSave = { faceSetup = false; save(it) })
    if (exportChoice) AlertDialog(onDismissRequest = { exportChoice = false }, title = { Text("导出角色包") },
        text = { Text("默认不包含台词、桌面短句和助手身份。所有图片会导出，分享前请确认形象授权。") },
        confirmButton = { TextButton(onClick = { exportChoice = false; pendingExport = false; exporter.launch("orbis-character.zip") }) { Text("仅素材") } },
        dismissButton = { TextButton(onClick = { exportChoice = false; pendingExport = true; exporter.launch("orbis-character-with-lines.zip") }) { Text("含台词") } })
}
