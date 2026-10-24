package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.orbis.spaces.CompanionSpaceSection
import me.rerere.rikkahub.ui.components.ai.OrbisStickerPanel
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.androidx.compose.koinViewModel

/** One entry per capability. Configuration belongs to system settings, not this launcher. */
@Composable
fun OrbisToolsPage(vm: SettingVM = koinViewModel(), ownerAssistantId: String? = null, conversationId: String? = null) {
    val navigator = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ownerScope = orbisToolsScope(settings, ownerAssistantId, conversationId)
    val selected = ownerScope?.assistant
    var showGames by rememberSaveable { mutableStateOf(false) }
    var showStickers by rememberSaveable { mutableStateOf(false) }
    var showPet by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var showStudy by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var showBody by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    val activityScope = rememberCoroutineScope()
    val activitySettings = org.koin.compose.koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
    var showCalls by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var showConnections by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var showGallery by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var showPrivateRoom by rememberSaveable(selected?.id?.toString()) { mutableStateOf(false) }
    var companionSpace by rememberSaveable(selected?.id?.toString()) { mutableStateOf<CompanionSpaceSection?>(null) }
    val context = LocalContext.current
    var galleryName by remember(selected?.id) { mutableStateOf("") }
    LaunchedEffect(selected?.id, showGallery) {
        if (!showGallery && selected != null) galleryName = withContext(Dispatchers.IO) {
            runCatching { me.rerere.rikkahub.data.orbis.gallery.openGallery(context, selected.id.toString()).snapshot().customName }.getOrDefault("")
        }
    }
    val chatService = org.koin.compose.koinInject<me.rerere.rikkahub.service.ChatService>()

    OrbisPageSurface {
        val colors = OrbisTheme.colors
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                OrbisPageHeader(title = "工具与娱乐", subtitle = selected?.let { "内容归属：${it.name.ifBlank { "未命名助手" }}" }
                    ?: if (settings.init) "正在读取助手配置…" else "助手配置不可用，未切换到其他助手",
                    avatar = { Icon(HugeIcons.Package, contentDescription = null) },
                    navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding())
            },
            bottomBar = { OrbisChatDock(currentLabel = "当前 工具", ownerAssistantId = ownerAssistantId, conversationId = conversationId) },
        ) { innerPadding ->
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("WORKSPACE & PLAY", fontSize = 10.sp, lineHeight = 14.sp,
                            letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = colors.onSand)
                        Text("需要的时候，就在这里", fontSize = 23.sp, lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold, color = colors.ink,
                            modifier = Modifier.semantics { heading() })
                        Text("连接与朗读在系统设置；书籍与音乐留在原 Orbis。",
                            fontSize = 12.sp, lineHeight = 18.sp, color = colors.mutedInk)
                        Text(if (ownerAssistantId != null || conversationId != null)
                            if (selected == null && !settings.init) "原助手或会话入口不可用，请从原聊天重新打开；没有切换到其他助手。"
                            else "格子、秘密基地与生图作品归当前会话的助手所有；不会更改全局助手。"
                            else "这是通用工具入口，内容归属上方显示的全局助手。",
                            fontSize = 12.sp, lineHeight = 18.sp, color = colors.mutedInk)
                    }
                }
                OrbisToolGroup.entries.forEach { group ->
                    item(group.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(if (group == OrbisToolGroup.WORK) "工作台" else "休息一下",
                                color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = when {
                                    maxWidth >= 330.dp && LocalDensity.current.fontScale <= 1.3f -> 3
                                    maxWidth >= 220.dp -> 2
                                    else -> 1
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    orbisToolEntries.filter { it.group == group }.chunked(columns).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { item ->
                                                val enabled = item.destination !in setOf(OrbisToolDestination.LOCAL_CAPABILITIES,
                                                    OrbisToolDestination.GALLERY, OrbisToolDestination.PRIVATE_ROOM,
                                                    OrbisToolDestination.SECRET_BASE, OrbisToolDestination.SHARED_SPACE,
                                                    OrbisToolDestination.PHOTO_WALL, OrbisToolDestination.CHARACTER, OrbisToolDestination.IMAGE_WORKSHOP,
                                                    OrbisToolDestination.PET, OrbisToolDestination.STUDY, OrbisToolDestination.BODY) || selected != null
                                                val tile = if (item.destination == OrbisToolDestination.GALLERY) item.copy(title = "${galleryName.ifBlank { selected?.name?.ifBlank { "伙伴" } ?: "伙伴" }}的格子") else item
                                                ToolsTile(tile, enabled, Modifier.weight(1f)) {
                                                    when (item.destination) {
                                                        OrbisToolDestination.PET -> showPet = true
                                                        OrbisToolDestination.STUDY -> showStudy = true
                                                        OrbisToolDestination.BODY -> showBody = true
                                                        OrbisToolDestination.WORKSPACE -> navigator.navigate(Screen.Workspaces)
                                                        OrbisToolDestination.ATTACHMENTS -> navigator.navigate(Screen.SettingFiles)
                                                        OrbisToolDestination.LOCAL_CAPABILITIES -> selected?.id?.toString()?.let {
                                                            navigator.navigate(Screen.AssistantLocalTool(it))
                                                        }
                                                        OrbisToolDestination.IMAGE_WORKSHOP -> ownerScope?.let { navigator.navigate(it.imageWorkshopRoute()) }
                                                        OrbisToolDestination.GAMES -> showGames = true
                                                        OrbisToolDestination.STICKERS -> showStickers = true
                                                        OrbisToolDestination.BLUETOOTH_TOY -> navigator.navigate(Screen.OrbisToy)
                                                        OrbisToolDestination.GALLERY -> showGallery = true
                                                        OrbisToolDestination.SCHEDULE -> navigator.navigate(Screen.OrbisSchedule)
                                                        OrbisToolDestination.PRIVATE_ROOM -> showPrivateRoom = true
                                                        OrbisToolDestination.SECRET_BASE -> companionSpace = CompanionSpaceSection.SECRET
                                                        OrbisToolDestination.SHARED_SPACE -> companionSpace = CompanionSpaceSection.SOCIAL
                                                        OrbisToolDestination.PHOTO_WALL -> companionSpace = CompanionSpaceSection.PHOTOS
                                                        OrbisToolDestination.CHARACTER -> selected?.let {
                                                            me.rerere.rikkahub.ui.activity.OrbisCharacterActivity.launch(context, it.id.toString())
                                                        }
                                                    }
                                                }
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item("permissions") {
                    OutlinedButton(onClick = { showConnections = true }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) {
                        val pending = settings.connectionProposals.count { it.owner == selected?.id?.toString() && it.status == "pending" }
                        Text(if(pending > 0) "AI 连接建议 · $pending 条待查看" else "AI 连接建议 · 原生配置与以后允许")
                    }
                    OutlinedButton(onClick = { showCalls = true }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) {
                        Text("通话记录 · 摘要与完整文字记录")
                    }
                    Surface(color = colors.sand.copy(alpha = .55f), shape = RoundedCornerShape(14.dp)) {
                        Text("工作区集中管理文件、终端与技能。打开入口不代表已执行操作；手机观察与设备权限统一在「后台与权限」管理。",
                            Modifier.padding(12.dp), fontSize = 11.sp, lineHeight = 18.sp, color = colors.onSand)
                    }
                }
            }
        }
        if (showGames) OrbisGameSheet(onDismiss = { showGames = false })
        if (selected != null) key(selected.id) {
            if (showBody) OrbisBodyDetails(selected.id.toString(), conversationId) { showBody = false }
            if (showConnections) OrbisConnectionProposals(selected.id.toString(), onClose = { showConnections = false })
            if (showPet) OrbisPetPage(selected.id.toString(), selected.name, selected.orbisPetEnabled,
                onToggle = { value -> activityScope.launch { activitySettings.update { current ->
                    current.copy(assistants = current.assistants.map { if(it.id == selected.id) it.copy(orbisPetEnabled = value) else it })
                } } }, onClose = { showPet = false }, conversationId = conversationId)
            if (showStudy) OrbisStudyPage(selected.id.toString(), selected.name, selected.orbisStudyEnabled,
                onToggle = { value -> activityScope.launch { activitySettings.update { current ->
                    current.copy(assistants = current.assistants.map { if(it.id == selected.id) it.copy(orbisStudyEnabled = value) else it })
                } } }, onClose = { showStudy = false })
            if (companionSpace != null) OrbisCompanionSpacesPage(selected.id.toString(), selected.name,
                companionSpace!!, onClose = { companionSpace = null })
            if (showPrivateRoom) OrbisPrivateRoomPage(selected.id.toString(), selected.name,
                onClose = { showPrivateRoom = false })
            if (showGallery) OrbisGalleryPage(selected.id.toString(), selected.name, onClose = { showGallery = false })
            if (showCalls) OrbisVoiceCallHistorySheet(chatService.voiceCalls,
                assistantId = selected.id.toString(), onRetry = chatService::retryVoiceCallArchiveIsolated,
                onDismiss = { showCalls = false })
        }
        if (showStickers) AlertDialog(
            onDismissRequest = { showStickers = false }, containerColor = colors.panel,
            title = { Text("你和伙伴的表情库", fontSize = 18.sp) },
            text = { OrbisStickerPanel() },
            confirmButton = { TextButton(onClick = { showStickers = false }) { Text("完成") } },
        )
    }
}

@Composable
internal fun ToolsTile(item: OrbisToolEntry, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    val artwork = when (item.destination) {
        OrbisToolDestination.GAMES -> OrbisEntryArtwork.ARCADE
        OrbisToolDestination.SECRET_BASE -> OrbisEntryArtwork.SEALED_LETTER
        OrbisToolDestination.PRIVATE_ROOM -> OrbisEntryArtwork.PRIVATE_ROOM
        else -> null
    }
    if (artwork != null) {
        OrbisDecorativeEntryCard(artwork, item.title, if (enabled) item.subtitle else "请先选择 AI 配置",
            onClick, modifier, enabled, compact = true)
        return
    }
    val icon = when (item.destination) {
        OrbisToolDestination.WORKSPACE -> HugeIcons.Folder01
        OrbisToolDestination.ATTACHMENTS -> HugeIcons.Files02
        OrbisToolDestination.LOCAL_CAPABILITIES -> HugeIcons.AiBrain01
        OrbisToolDestination.STICKERS -> HugeIcons.Sparkles
        OrbisToolDestination.BLUETOOTH_TOY -> HugeIcons.Connect
        OrbisToolDestination.GALLERY -> HugeIcons.Package
        OrbisToolDestination.SCHEDULE -> HugeIcons.Calendar03
        OrbisToolDestination.SHARED_SPACE, OrbisToolDestination.BODY -> HugeIcons.Favourite
        OrbisToolDestination.PHOTO_WALL, OrbisToolDestination.IMAGE_WORKSHOP -> HugeIcons.Image02
        else -> HugeIcons.Package
    }
    OrbisToolEntryCard(item.title, if (enabled) item.subtitle else "请先选择 AI 配置",
        onClick, modifier, enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = colors.indigo)
    }
}
