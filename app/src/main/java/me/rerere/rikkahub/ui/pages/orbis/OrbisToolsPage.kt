package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.OrbisStickerPanel
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.androidx.compose.koinViewModel

/** One entry per capability. Configuration belongs to system settings, not this launcher. */
@Composable
fun OrbisToolsPage(vm: SettingVM = koinViewModel()) {
    val navigator = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected = if (settings.init) null else settings.assistants.find { it.id == settings.assistantId }
    var showGames by rememberSaveable { mutableStateOf(false) }
    var showStickers by rememberSaveable { mutableStateOf(false) }
    var showCalls by rememberSaveable { mutableStateOf(false) }
    var showGallery by rememberSaveable { mutableStateOf(false) }
    var showPrivateRoom by rememberSaveable { mutableStateOf(false) }
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
                OrbisPageHeader(title = "工具与娱乐", subtitle = "做事与喘气，都有位置",
                    avatar = { Icon(HugeIcons.Package, contentDescription = null) },
                    navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding())
            },
            bottomBar = { OrbisChatDock(currentLabel = "当前 工具") },
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
                    }
                }
                OrbisToolGroup.entries.forEach { group ->
                    item(group.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(if (group == OrbisToolGroup.WORK) "工作台" else "休息一下",
                                color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            orbisToolEntries.filter { it.group == group }.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { item ->
                                        val enabled = item.destination !in setOf(OrbisToolDestination.LOCAL_CAPABILITIES, OrbisToolDestination.GALLERY, OrbisToolDestination.PRIVATE_ROOM) || selected != null
                                        val tile = if (item.destination == OrbisToolDestination.GALLERY) item.copy(title = "${galleryName.ifBlank { selected?.name?.ifBlank { "伙伴" } ?: "伙伴" }}的格子") else item
                                        ToolsTile(tile, enabled, Modifier.weight(1f)) {
                                            when (item.destination) {
                                                OrbisToolDestination.WORKSPACE -> navigator.navigate(Screen.Workspaces)
                                                OrbisToolDestination.ATTACHMENTS -> navigator.navigate(Screen.SettingFiles)
                                                OrbisToolDestination.LOCAL_CAPABILITIES -> selected?.id?.toString()?.let {
                                                    navigator.navigate(Screen.AssistantLocalTool(it))
                                                }
                                                OrbisToolDestination.GAMES -> showGames = true
                                                OrbisToolDestination.STICKERS -> showStickers = true
                                                OrbisToolDestination.BLUETOOTH_TOY -> navigator.navigate(Screen.OrbisToy)
                                                OrbisToolDestination.GALLERY -> showGallery = true
                                                OrbisToolDestination.SCHEDULE -> navigator.navigate(Screen.OrbisSchedule)
                                                OrbisToolDestination.PRIVATE_ROOM -> showPrivateRoom = true
                                            }
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                item("permissions") {
                    OutlinedButton(onClick = { showCalls = true }, modifier = Modifier.fillMaxWidth()) {
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
        if (showPrivateRoom && selected != null) OrbisPrivateRoomPage(selected.id.toString(), selected.name,
            onClose = { showPrivateRoom = false })
        if (showGallery && selected != null) OrbisGalleryPage(selected.id.toString(), selected.name, onClose = { showGallery = false })
        if (showCalls) OrbisVoiceCallHistorySheet(chatService.voiceCalls,
            assistantId = selected?.id?.toString(), onRetry = chatService::retryVoiceCallArchiveIsolated,
            onDismiss = { showCalls = false })
        if (showStickers) AlertDialog(
            onDismissRequest = { showStickers = false }, containerColor = colors.panel,
            title = { Text("你和伙伴的表情库", fontSize = 18.sp) },
            text = { OrbisStickerPanel() },
            confirmButton = { TextButton(onClick = { showStickers = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun ToolsTile(item: OrbisToolEntry, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    Surface(modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, role = Role.Button,
        onClickLabel = "打开${item.title}", onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = colors.raisedPanel,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .7f))) {
        Column(Modifier.heightIn(min = 106.dp).padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(item.glyph, fontSize = 25.sp, lineHeight = 31.sp, color = colors.indigo)
            Text(item.title, fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.ink,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (enabled) item.subtitle else "请先选择 AI 配置", fontSize = 10.sp, lineHeight = 15.sp,
                textAlign = TextAlign.Center, color = colors.mutedInk)
        }
    }
}
