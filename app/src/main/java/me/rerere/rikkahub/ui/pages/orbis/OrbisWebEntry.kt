package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.webshortcuts.*
import org.koin.compose.koinInject
import java.util.UUID

@Composable
internal fun OrbisWebEntry(assistant: String, conversation: String, store: SettingsStore = koinInject()) {
    key(assistant, conversation) { WebEntryContent(assistant, conversation, store) }
}

@Composable
private fun WebEntryContent(assistant: String, conversation: String, store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val windows by WebRecommendations.windows.collectAsStateWithLifecycle()
    val window = windows["$assistant/$conversation"] ?: WebWindowState()
    val repo = remember(assistant) { openWebShortcuts(context, assistant) }
    val revision by repo.changes.collectAsStateWithLifecycle()
    var shelf by remember { mutableStateOf(WebShelf()) }
    var shelfReady by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var grantPage by remember { mutableStateOf<WebShortcut?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val sessions = remember { mutableMapOf<String, ShortcutWebSession>() }
    // Reading a link from a restricted page never silently upgrades it to interactive mode.
    val forcedReadOnly = remember { mutableSetOf<String>() }
    val owner = settings.assistants.firstOrNull { it.id.toString() == assistant }
    val mcps = settings.mcpServers.filter { it.commonOptions.enable && it.id in owner?.mcpServers.orEmpty() }
    fun interactive(page: WebShortcut) = page.id !in forcedReadOnly &&
        mcps.any { it.id.toString() == shelf.interactiveOrigins[shortcutOrigin(page.url)] }
    fun change(block: (WebWindowState) -> WebWindowState) = WebRecommendations.change(assistant, conversation, block)
    fun minimize() = change { it.copy(expanded = false) }
    fun show(entry: WebShortcut) {
        try { WebRecommendations.show(assistant, conversation, entry); library = false; notice = null }
        catch (_: Exception) { notice = "最多保留 8 个网页，请先关闭不用的页面。" }
    }
    LaunchedEffect(revision) {
        try { shelf = withContext(Dispatchers.IO) { repo.snapshot() }; shelfReady = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { shelfReady = false; notice = "暂时无法读取收藏与网页授权，请稍后再试。" }
    }
    LaunchedEffect(window.pages, shelf, mcps, shelfReady) {
        val ids = window.pages.map { it.id }.toSet()
        (sessions.keys - ids).forEach { sessions.remove(it)?.destroy(); forcedReadOnly.remove(it) }
        // Revoking a site's MCP permission also tears down a currently hidden interactive page.
        window.pages.forEach { page ->
            val session = sessions[page.id]
            if (session?.binding?.second == true && (!shelfReady || !interactive(page))) session.destroy()
        }
    }
    DisposableEffect(Unit) { onDispose { sessions.values.forEach { it.destroy() }; sessions.clear() } }
    val ink = OrbisTheme.colors.ink
    BadgedBox(badge = { if (window.pages.isNotEmpty()) Badge { Text(window.pages.size.toString()) } }) {
        IconButton(onClick = { if (window.pages.isEmpty()) library = true else change { it.copy(expanded = !it.expanded) } },
            modifier = Modifier.size(40.dp).semantics { contentDescription = "快捷网页${if(window.pages.isNotEmpty()) "，${window.pages.size}页" else "收藏"}" }) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = Stroke(1.5.dp.toPx())
                drawCircle(ink, style = stroke)
                drawOval(ink, topLeft = Offset(size.width * .26f, 0f), size = Size(size.width * .48f, size.height), style = stroke)
                drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = stroke.width)
            }
        }
    }
    val selected = window.pages.firstOrNull { it.id == window.selectedId } ?: window.pages.firstOrNull()
    OrbisInterfaceTheme {
    if (window.expanded && selected != null && owner != null) {
        val config = LocalConfiguration.current
        val small = config.screenHeightDp < 600
        Dialog(onDismissRequest = ::minimize, properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, decorFitsSystemWindows = true)) {
            Surface(modifier = Modifier.fillMaxWidth(if (window.maximized) .98f else .86f)
                .height((config.screenHeightDp * if (window.maximized) .88f else if(small) .76f else .60f).dp),
                shape = RoundedCornerShape(24.dp), color = OrbisTheme.colors.panel,
                contentColor = OrbisTheme.colors.ink, border = BorderStroke(1.dp, OrbisTheme.colors.ink.copy(alpha = .24f)),
                tonalElevation = 8.dp, shadowElevation = 18.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(shortcutOrigin(selected.url), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = OrbisTheme.colors.mutedInk)
                        }
                        TextButton(onClick = ::minimize, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp)
                            .semantics { contentDescription = "最小化网页，保留页面" }) { Text("−") }
                        TextButton(onClick = { change { it.copy(maximized = !it.maximized) } }, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(36.dp).semantics { contentDescription = if(window.maximized) "还原小浮窗" else "放大网页" }) { Text(if(window.maximized) "▣" else "□") }
                        TextButton(onClick = { change { WebWindowState() } }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(36.dp)
                            .semantics { contentDescription = "关闭网页浮窗和全部页面" }) { Text("×") }
                    }
                    HorizontalDivider(color = OrbisTheme.colors.ink.copy(alpha = .12f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (interactive(selected)) "MCP 游戏交互" else "只读阅读", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        TextButton(enabled = shelfReady, onClick = { grantPage = selected }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("网页授权", style = MaterialTheme.typography.labelSmall) }
                    }
                    notice?.let { Text(it, Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (shelfReady) key(selected.id) {
                            OrbisShortcutWebView(assistant, selected.url, interactive(selected),
                                retained = sessions.getOrPut(selected.id) { ShortcutWebSession() }) { link ->
                                val entry = WebShortcut(UUID.randomUUID().toString(), link, shortcutOrigin(link), "human", conversation)
                                forcedReadOnly.add(entry.id); show(entry)
                            }
                        } else Text("正在核对网页授权…", Modifier.padding(12.dp))
                    }
                    HorizontalDivider(color = OrbisTheme.colors.ink.copy(alpha = .12f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { library = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("收藏") }
                        val index = window.pages.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
                        TextButton(enabled = index > 0, onClick = { change { it.copy(selectedId = it.pages[index - 1].id) } },
                            modifier = Modifier.width(36.dp), contentPadding = PaddingValues(0.dp)) { Text("‹") }
                        Text("${index + 1} / ${window.pages.size}", style = MaterialTheme.typography.labelMedium)
                        TextButton(enabled = index < window.pages.lastIndex, onClick = { change { it.copy(selectedId = it.pages[index + 1].id) } },
                            modifier = Modifier.width(36.dp), contentPadding = PaddingValues(0.dp)) { Text("›") }
                        TextButton(onClick = { WebRecommendations.close(assistant, conversation, selected.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("关此页", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    if (library) OrbisWebShortcutsPage(assistant, conversation, onOpenPage = ::show, onClose = { library = false })
    grantPage?.let { page ->
        AlertDialog(onDismissRequest = { grantPage = null }, title = { Text("这个网页的交互授权") }, text = {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp)
                .verticalScroll(rememberScrollState())) {
                Text(shortcutOrigin(page.url))
                Text("普通网页只读。自建 MCP 游戏可开启脚本及该网页自己的登录存储；请选择对应 MCP，并使用相同游戏账号或房间。AI 通过 MCP 查状态，应用不会回传浏览操作。")
                if (mcps.isEmpty()) Text("先在连接设置中启用对应游戏 MCP，再回来关联。")
                mcps.forEach { mcp -> TextButton(onClick = { scope.launch {
                    try { withContext(Dispatchers.IO) { repo.allowInteraction(page.url, mcp.id.toString()) }; forcedReadOnly.remove(page.id); grantPage = null }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "授权未能保存，请稍后重试。" }
                } }) { Text("关联：${mcp.commonOptions.displayTitle}") } }
                TextButton(onClick = { scope.launch {
                    try { withContext(Dispatchers.IO) { repo.allowInteraction(page.url, null) }; grantPage = null }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "关闭授权未能保存，请稍后重试。" }
                } }) { Text("关闭本站交互") }
            }
        }, confirmButton = { TextButton(onClick = { grantPage = null }) { Text("返回") } })
    }
    }
}
