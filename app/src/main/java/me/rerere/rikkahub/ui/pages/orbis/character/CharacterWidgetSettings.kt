package me.rerere.rikkahub.ui.pages.orbis.character

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.orbis.character.*
import me.rerere.rikkahub.ui.widget.OrbisCharacterWidget
import me.rerere.rikkahub.ui.widget.characterFaceComposite
import me.rerere.rikkahub.ui.widget.characterWidgetSprite
import me.rerere.rikkahub.ui.widget.characterColoredSprite

private val emotionLabels = linkedMapOf("calm" to "平静", "smile" to "笑", "cry" to "哭", "think" to "思考", "angry" to "怒", "shy" to "害羞", "sleepy" to "困", "excited" to "激动")
private val cardLabels = characterCardNames.zip(listOf("晴空蓝", "蜜桃奶油", "鼠尾草绿", "浅紫夜色", "拿铁沙色")).toMap()
private val poseLabels = mapOf("neutral" to "自然站立", "invite" to "伸手邀请", "wave" to "挥手", "arms" to "抱臂", "fold" to "抱臂", "cup" to "捧杯", "mug" to "捧杯", "think" to "思考", "heart" to "比心", "stretch" to "舒展", "listen" to "倾听", "explain" to "讲解")

/** A standalone editor: no room renderer, TTS session, model call or animation is created. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterWidgetSettings(owner: String, assistantName: String, onClose: () -> Unit, onWorkshop: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(owner) { openCharacterStore(context, owner) }
    var profile by remember(owner) { mutableStateOf<CharacterProfile?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var notice by remember(owner) { mutableStateOf<String?>(null) }
    var section by rememberSaveable(owner) { mutableIntStateOf(0) }
    var greeting by rememberSaveable(owner) { mutableStateOf("") }
    var greetingDirty by rememberSaveable(owner) { mutableStateOf(false) }
    var emotion by rememberSaveable(owner) { mutableStateOf("calm") }
    var kind by rememberSaveable(owner) { mutableStateOf(CharacterAssetKind.POSE) }
    var importKind by rememberSaveable(owner) { mutableStateOf<CharacterAssetKind?>(null) }
    var draft by remember(owner) { mutableStateOf<CharacterDraft?>(null) }
    var builtinDraft by remember(owner) { mutableStateOf(false) }
    var faceSetup by remember(owner) { mutableStateOf(false) }
    var exportChoice by remember(owner) { mutableStateOf(false) }
    var exportLines by rememberSaveable(owner) { mutableStateOf(false) }
    var confirmLeave by remember(owner) { mutableStateOf(false) }

    suspend fun reload() {
        val fresh = withContext(Dispatchers.IO) { ensureBuiltinCharacter(context, store) }
        profile = fresh
        if (!greetingDirty) greeting = fresh.presentation.greeting
    }
    fun task(work: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                notice = characterError(e)
                // Refresh a conflict without silently replacing the human's unsaved greeting.
                if (e.message == "character_conflict") runCatching { reload() }
            } finally { busy = false }
        }
    }
    fun save(p: CharacterProfile) = task {
        withContext(Dispatchers.IO) { store.save(p, p.revision) }
        reload(); OrbisCharacterWidget.refresh(context)
    }
    fun close() { if (greetingDirty) confirmLeave = true else onClose() }
    LaunchedEffect(owner) { task { reload() } }
    BackHandler { close() }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importKind; importKind = null
        val original = profile
        if (uri != null && target != null && original != null) task {
            withContext(Dispatchers.IO) {
                val image = importCharacterImage(context, uri)
                val id = "custom_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                val asset = CharacterAsset(id, target, id, image.pack.sha256, image.pack.width, image.pack.height, emotion)
                val p = original.presentation.copy(assets = original.presentation.assets + asset,
                    card = if (target == CharacterAssetKind.CARD) id else original.presentation.card,
                    widgetAssetId = if (target == CharacterAssetKind.POSE || original.presentation.widgetAssetId == null && target == CharacterAssetKind.EXPRESSION) id else original.presentation.widgetAssetId)
                // First Q sprite can bootstrap the pack; a full-screen portrait is not required.
                store.install(CharacterDraft(original.pack ?: image.pack, original.interactions,
                    original.pack?.let(store::imageBytes) ?: image.png, p, mapOf(asset.sha256 to image.png)), original.revision)
            }
            reload(); OrbisCharacterWidget.refresh(context); notice = "素材已保存，可以在上方预览。"
        }
    }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            builtinDraft = false
            draft = withContext(Dispatchers.IO) {
                (context.contentResolver.openInputStream(uri)?.use(CharacterArchive::read) ?: error("character_archive"))
                    .also(::verifyCharacterDraft)
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) task {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { store.export(it, exportLines) } ?: error("character_storage")
            }
            notice = if (exportLines) "素材与台词已导出；分享前请检查私人内容。" else "素材已导出，不含私人台词与助手身份。"
        }
    }
    fun importImage(target: CharacterAssetKind) { importKind = target; imagePicker.launch(arrayOf("image/*")) }
    fun pin() {
        val widgets = AppWidgetManager.getInstance(context)
        notice = if (widgets.isRequestPinAppWidgetSupported && widgets.requestPinAppWidget(ComponentName(context, OrbisCharacterWidget::class.java), null, null))
            "请在桌面确认添加，再选择要绑定的助手。" else "请长按桌面，在小部件中选择 Orbis。"
    }

    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("小部件设置"); Text(assistantName + " · 让 TA 陪在桌面上", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            navigationIcon = { TextButton(onClick = ::close, modifier = Modifier.semantics { contentDescription = "返回" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) } })
    }, bottomBar = {
        Surface(shadowElevation = 4.dp) {
            Button(onClick = ::pin, enabled = !busy && profile != null,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) { Text("添加到桌面") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) } }
            val p = profile
            if (p != null) {
                WidgetSettingsCard("效果预览", "离线预览 · 桌面占位以启动器为准") {
                    CharacterWidgetPreview(store, p.presentation.copy(greeting = greeting), assistantName,
                        onCycle = { target -> save(p.copy(presentation = p.presentation.cycle(target, System.currentTimeMillis()))) })
                    Text("点头换表情 · 点手换动作 · 桌面点卡片聊天", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("外观", "人物与素材", "高级设置").forEachIndexed { index, label ->
                        SegmentedButton(selected = section == index, onClick = { section = index }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                when (section) {
                    0 -> {
                        WidgetSettingsCard("卡片背景", "点缩略图立即查看并应用") {
                            val cards = (characterCardNames + p.presentation.assets.filter { it.kind == CharacterAssetKind.CARD }.map { it.tag }).distinct()
                            cards.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { id -> WidgetImageTile(store, p.presentation, card = id, label = cardLabels[id] ?: "自定义",
                                    selected = p.presentation.card == id, enabled = !busy, modifier = Modifier.weight(1f),
                                    onClick = { save(p.copy(presentation = p.presentation.copy(card = id))) }) }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            } }
                            OutlinedButton(onClick = { importImage(CharacterAssetKind.CARD) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("＋ 导入卡片底图") }
                        }
                        WidgetSettingsCard("桌面短句", "输入时可预览，保存后同步到桌面") {
                            OutlinedTextField(value = greeting, onValueChange = { if (it.length <= 120) { greeting = it; greetingDirty = true } }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("widget-greeting"), minLines = 2, maxLines = 4,
                                supportingText = { Text("${greeting.length}/120") }, shape = RoundedCornerShape(16.dp))
                            Button(enabled = !busy && greetingDirty, onClick = { task {
                                withContext(Dispatchers.IO) { store.save(p.copy(presentation = p.presentation.copy(greeting = greeting)), p.revision) }
                                greetingDirty = false; reload(); OrbisCharacterWidget.refresh(context); notice = "短句已保存。"
                            } }) { Text("保存短句") }
                        }
                    }
                    1 -> {
                        WidgetSettingsCard("基础形象", "无需画图或导入，内置完整动作与表情") {
                            Text("知序 · 蓝发 AI 助手。已有私人形象不会自动替换；切换前可先导出当前角色包。")
                            OutlinedButton(onClick = { task {
                                builtinDraft = true
                                draft = withContext(Dispatchers.IO) { loadBuiltinCharacter(context).let { base ->
                                    base.copy(pack = p.pack ?: base.pack, png = p.pack?.let(store::imageBytes) ?: base.png,
                                        interactions = p.interactions, presentation = base.presentation?.copy(
                                            assets = base.presentation.assets + p.presentation.assets.filter {
                                                it.kind !in setOf(CharacterAssetKind.POSE, CharacterAssetKind.EXPRESSION)
                                            },
                                            greeting = p.presentation.greeting, colors = p.presentation.colors,
                                            scene = p.presentation.scene, card = p.presentation.card))
                                } }
                            } }, enabled = !busy) { Text("选择内置基础形象") }
                        }
                        if (p.presentation.assets.any { it.colorModel != null }) WidgetSettingsCard("发色与瞳色", "分别选择 · 不改变原始图片") {
                            Text("发色")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { characterPalettes.forEach { palette ->
                                FilterChip(selected = p.presentation.colors.hair == palette.id, enabled = !busy,
                                    onClick = { save(p.copy(presentation = p.presentation.copy(colors = p.presentation.colors.copy(hair = palette.id)))) },
                                    label = { Text(palette.label) }, modifier = Modifier.testTag("character-hair-${palette.id}"))
                            } }
                            Text("日常瞳色")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { characterPalettes.forEach { palette ->
                                FilterChip(selected = p.presentation.colors.eyes == palette.id, enabled = !busy,
                                    onClick = { save(p.copy(presentation = p.presentation.copy(colors = p.presentation.colors.copy(eyes = palette.id)))) },
                                    label = { Text(palette.label) }, modifier = Modifier.testTag("character-eyes-${palette.id}"))
                            } }
                            Text("生气和激动时暂时使用深红瞳色，恢复其他表情后回到你的选择。配色名称只表示外观，不会切换模型。")
                        }
                        WidgetSettingsCard("人物素材", "动作和表情分开管理，原图不会被裁掉") {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(CharacterAssetKind.POSE to "动作", CharacterAssetKind.EXPRESSION to "表情").forEachIndexed { index, (target, label) ->
                                    SegmentedButton(selected = kind == target, onClick = { kind = target }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                                }
                            }
                            val assets = p.presentation.assets.filter { it.kind == kind }
                            val layers = p.presentation.widgetLayers(System.currentTimeMillis())
                            if (assets.isEmpty()) Text("还没有${if (kind == CharacterAssetKind.POSE) "动作" else "表情"}图片，可以导入透明 PNG。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            assets.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { asset -> WidgetImageTile(store, p.presentation, asset = asset,
                                    label = if (kind == CharacterAssetKind.POSE) poseLabels[asset.tag.removePrefix("pose-")] ?: "自定义动作" else emotionLabels[asset.emotion] ?: "表情",
                                    selected = asset.id == (if (kind == CharacterAssetKind.POSE) layers.pose?.id else layers.expression?.id),
                                    enabled = !busy, modifier = Modifier.weight(1f), onClick = { task {
                                        val selected = p.presentation.selectSprite(asset.id, System.currentTimeMillis())
                                        withContext(Dispatchers.IO) { store.save(p.copy(presentation = selected), p.revision) }
                                        reload(); OrbisCharacterWidget.refresh(context)
                                    } }) }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            } }
                            Text("新素材的情绪标签", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { emotionLabels.forEach { (id, label) ->
                                FilterChip(selected = emotion == id, onClick = { emotion = id }, label = { Text(label) })
                            } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { importImage(CharacterAssetKind.POSE) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("＋ 添加动作") }
                                OutlinedButton(onClick = { importImage(CharacterAssetKind.EXPRESSION) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("＋ 添加表情") }
                            }
                            Text("表情需先在高级设置完成面部配准，才能换脸而保持姿势。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        onWorkshop?.let { open -> FilledTonalButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("✦ AI 生图工坊 · 制作小部件素材") } }
                        WidgetSettingsCard("素材包", "导入可在确认前取消；导出默认不含私人台词") {
                            OutlinedButton(onClick = { packagePicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入角色包") }
                            OutlinedButton(onClick = { exportChoice = true }, enabled = !busy && p.pack != null, modifier = Modifier.fillMaxWidth()) { Text("导出角色包") }
                        }
                    }
                    else -> {
                        WidgetSettingsCard("面部配准", "换表情，保持身体姿势") {
                            Text("给动作和表情设置同组面部区域。未配准的素材不会被强行拼脸。", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { faceSetup = true }, enabled = !busy && p.presentation.assets.isNotEmpty()) { Text("调整面部区域") }
                        }
                        p.presentation.expressionPolicy?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { policy ->
                            WidgetSettingsCard("临时表情范围", "由伙伴设置的限时状态") {
                                Text(policy.allowed.joinToString(" · ") { emotionLabels[it] ?: it })
                                TextButton(onClick = { save(p.copy(presentation = p.presentation.copy(expressionPolicy = null))) }, enabled = !busy) { Text("解除限制") }
                            }
                        }
                        WidgetSettingsCard("使用说明", "小部件与全屏互动分别管理") {
                            Text("小部件外侧透明，会透出手机壁纸。这里的浅色衬底只用于预览，不会更改系统壁纸。")
                            Text("全屏互动开发中，入口暂未开放；已有房间素材与台词保留。")
                            Text("分享前请检查素材授权。角色包可单独导出，不含模型密钥或聊天记录。")
                        }
                    }
                }
            } else if (!busy) TextButton(onClick = { task { reload() } }) { Text("重新读取设置") }
            Spacer(Modifier.height(8.dp))
        }
    }
    val current = profile
    if (faceSetup && current != null) CharacterFaceSetup(store, current, onDismiss = { faceSetup = false }, onSave = { faceSetup = false; save(it) })
    draft?.let { imported -> AlertDialog(onDismissRequest = { if (!busy) draft = null }, title = { Text("确认导入角色包？") },
        text = { Text(if (builtinDraft) "将替换本助手的小部件动作与表情图库；已有全屏立绘、台词、桌面短句和聊天都会保留。建议先导出当前素材包。" else "将替换本助手现有素材与包内台词，聊天不会改变。建议先导出当前素材包。") },
        confirmButton = { TextButton(enabled = !busy && current != null, onClick = { task {
            withContext(Dispatchers.IO) { store.install(imported, requireNotNull(current).revision) }
            draft = null; greetingDirty = false; reload(); OrbisCharacterWidget.refresh(context)
        } }) { Text("确认替换") } }, dismissButton = { TextButton(enabled = !busy, onClick = { draft = null }) { Text("取消") } }) }
    if (exportChoice) AlertDialog(onDismissRequest = { exportChoice = false }, title = { Text("导出角色包") },
        text = { Text("默认仅导出素材。若用于自己的备份，可以同时导出台词与桌面短句。") },
        confirmButton = { TextButton(onClick = { exportChoice = false; exportLines = false; exporter.launch("orbis-character.zip") }) { Text("仅素材") } },
        dismissButton = { TextButton(onClick = { exportChoice = false; exportLines = true; exporter.launch("orbis-character-with-lines.zip") }) { Text("包含台词") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("短句尚未保存") }, text = { Text("离开会放弃本次短句编辑，已保存的素材不受影响。") },
        confirmButton = { TextButton(onClick = onClose) { Text("放弃并离开") } }, dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
}

@Composable
private fun WidgetSettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private fun decodeWidgetImage(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })

@Composable
private fun WidgetImageTile(store: CharacterStore, p: CharacterPresentation, label: String, selected: Boolean, enabled: Boolean,
                            modifier: Modifier, onClick: () -> Unit, card: String? = null, asset: CharacterAsset? = null) {
    val context = LocalContext.current
    val isSelected = selected
    val bitmap by produceState<Bitmap?>(null, store, asset, card, p.assets, p.colors) {
        value = withContext(Dispatchers.IO) { runCatching {
            val custom = asset ?: p.assets.firstOrNull { it.kind == CharacterAssetKind.CARD && it.tag == card }
            if (custom != null) decodeWidgetImage(store.assetBytes(custom))?.let { original ->
                val colored = characterColoredSprite(original, custom, p.colors)
                if (colored !== original) original.recycle()
                colored
            } else context.assets.open("orbis-character/cards/${card?.takeIf { it in characterCardNames } ?: "cloud-blue"}.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
        }.getOrNull() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("widget-tile-${card ?: asset?.id}").semantics { this.selected = isSelected },
            border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (card != null) 1.35f else 1f).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = if (card != null) ContentScale.Crop else ContentScale.Fit) }
                    ?: Text("暂无预览", style = MaterialTheme.typography.labelSmall)
                if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) { Text("✓", Modifier.padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Uses the actual widget XML and the same face compositor/framing as the launcher. */
@Composable
private fun CharacterWidgetPreview(store: CharacterStore, p: CharacterPresentation, name: String, onCycle: (CharacterAssetKind) -> Unit) {
    val context = LocalContext.current
    val layers = p.widgetLayers(System.currentTimeMillis())
    val images by produceState<Triple<String, Bitmap?, Bitmap?>?>(null, store, layers, p.card, p.assets, p.colors) {
        value = withContext(Dispatchers.IO) {
            val sprite = runCatching {
                layers.pose?.let { pose ->
                    var image = decodeWidgetImage(store.assetBytes(pose)) ?: return@let null
                    layers.expression?.let { face -> decodeWidgetImage(store.assetBytes(face))?.let { expression ->
                        val combined = characterFaceComposite(image, expression, requireNotNull(pose.faceAnchor), requireNotNull(face.faceAnchor))
                        image.recycle(); expression.recycle(); image = combined
                    } }
                    val colored = characterColoredSprite(image, pose, p.colors, layers.expression?.emotion ?: pose.emotion)
                    if (colored !== image) { image.recycle(); image = colored }
                    val framed = characterWidgetSprite(image, 180, pose.trimBodyAtCardEdge)
                    if (framed !== image) image.recycle()
                    framed
                }
            }.getOrNull()
            val card = runCatching {
                val custom = p.assets.firstOrNull { it.kind == CharacterAssetKind.CARD && it.tag == p.card }
                if (custom != null) decodeWidgetImage(store.assetBytes(custom)) else context.assets.open("orbis-character/cards/${p.card.takeIf { it in characterCardNames } ?: "cloud-blue"}.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
            }.getOrNull()
            Triple(p.card, sprite, card)
        }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("widget-effect-preview")) {
        AndroidView(factory = { LayoutInflater.from(it).inflate(R.layout.orbis_character_widget, null) }, modifier = Modifier.fillMaxWidth().height(200.dp).padding(vertical = 10.dp)
            .testTag(if (images?.first == p.card && images?.third != null) "widget-preview-ready-${p.card}" else "widget-preview-loading"), update = { view ->
            view.findViewById<TextView>(R.id.character_widget_title).text = name
            view.findViewById<TextView>(R.id.character_widget_hint).text = p.greeting.ifBlank { "点卡片，进入聊天" }
            view.findViewById<ImageView>(R.id.character_widget_image).setImageBitmap(images?.second)
            view.findViewById<ImageView>(R.id.character_widget_card).setImageBitmap(images?.third)
            view.findViewById<TextView>(R.id.character_widget_head).setOnClickListener { onCycle(CharacterAssetKind.EXPRESSION) }
            view.findViewById<TextView>(R.id.character_widget_hand).setOnClickListener { onCycle(CharacterAssetKind.POSE) }
        })
    }
}
