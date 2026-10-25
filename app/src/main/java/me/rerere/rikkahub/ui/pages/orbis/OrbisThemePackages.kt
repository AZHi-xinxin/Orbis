package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.activities.bounded
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.OrbisMessageBubble
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily

internal data class ChatTheme(val body: ThemePackage, val image: String?, val onImageError: (() -> Unit)? = null, val assets: Map<String, String> = emptyMap())
internal val LocalChatTheme = staticCompositionLocalOf<ChatTheme?> { null }
internal fun themeFont(name: String) = when (name) {
    "serif", "NotoSerifSC" -> FontFamily.Serif
    "monospace" -> FontFamily.Monospace
    "sans" -> FontFamily.SansSerif
    else -> FontFamily.Default
}

@Composable internal fun AssistantThemeProvider(owner: String, content: @Composable () -> Unit) {
    key(owner) { AssistantThemeOwnerProvider(owner, content) }
}
@Composable private fun AssistantThemeOwnerProvider(owner: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(owner, context) { openThemes(context, owner) }
    val signal by store.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val theme by produceState<ChatTheme?>(null, store, signal) {
        value = if (!BuildConfig.ORBIS_ENABLED) null else withContext(Dispatchers.IO) {
            runCatching {
                val state = store.snapshot()
                val body = state.resolve() ?: return@runCatching null
                body.assets.forEach { check(store.asset(it).isFile) }
                ChatTheme(body, body.background.asset?.let { store.asset(it).absolutePath }, assets = body.assets.associateWith { store.asset(it).absolutePath }, onImageError = {
                    scope.launch(Dispatchers.IO) { runCatching { state.active?.let(store::renderFailed) } }
                })
            }.getOrNull()
        }
    }
    CompositionLocalProvider(LocalChatTheme provides theme,
        LocalChatFontFamily provides (theme?.body?.typography?.fontFamily?.let(::themeFont) ?: LocalChatFontFamily.current), content = content)
}

@Composable internal fun ThemePackageManager(owner: String) {
    val context = LocalContext.current
    val store = remember(context, owner) { openThemes(context, owner) }
    ThemePackageManagerContent(store, importImage = { onBytes ->
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onBytes { context.contentResolver.openInputStream(uri)?.use { it.bounded(10 * 1024 * 1024) } ?: error("theme_asset_invalid") }
        }
        OutlinedButton(onClick = { picker.launch("image/*") }, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("导入主题素材") }
    })
}

@Composable internal fun ThemePackageManagerContent(store: ThemeRepository,
    importImage: @Composable (((() -> ByteArray) -> Unit)) -> Unit = {}) {
    val signal by store.changes.collectAsState()
    var state by remember(store) { mutableStateOf(ThemeState()) }
    var error by remember(store) { mutableStateOf<String?>(null) }
    var preview by remember(store) { mutableStateOf<ThemeRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, signal) {
        try { state = withContext(Dispatchers.IO) { store.snapshot() }; error = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "主题文件暂时无法读取，聊天使用原来的外观；原文件保留。" }
    }
    fun task(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; state = withContext(Dispatchers.IO) { store.snapshot() }; error = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "这次更改没有完成，请刷新后检查主题版本或图片。" }
            finally { busy = false }
        }
    }
    val colors = OrbisTheme.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("主题工作室", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("让伙伴布置你们的聊天空间", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
        }
        val active = state.resolve()
        Surface(color = colors.panel, shape = RoundedCornerShape(24.dp), shadowElevation = 1.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(active ?: ThemePackage(name = "原来的手工外观"), active?.background?.asset?.let { store.asset(it).absolutePath },
                    assets = active?.assets.orEmpty().associateWith { store.asset(it).absolutePath })
                Text(active?.name ?: "原来的手工外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val palette = active?.colors ?: ThemeColors()
                    listOf(palette.background, palette.primary, palette.surface, palette.text, palette.textSecondary).forEach { hex ->
                        Box(Modifier.size(26.dp).background(Color(themeArgb(hex)), androidx.compose.foundation.shape.CircleShape))
                    }
                    Spacer(Modifier.weight(1f)); Text("Aa", style = MaterialTheme.typography.titleLarge, fontFamily = themeFont(active?.typography?.fontFamily ?: "default"))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !busy && state.previous.isNotEmpty(), onClick = { task { store.revert() } }) { Text("回到上一套") }
                    TextButton(enabled = !busy && state.active != null, onClick = { task { store.useOriginal() } }) { Text("使用手工外观") }
                }
            }
        }
        Surface(color = colors.accent.copy(alpha = .08f), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("交给 AI 自由装扮", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("允许它创建并切换新主题，随时可关闭。", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.allowAiApply, enabled = !busy, onCheckedChange = { allowed -> task { store.allowAiApply(allowed) } }, modifier = Modifier.testTag("theme-ai-auto-apply"))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if(state.pending != null) Surface(color = colors.sand, shape = RoundedCornerShape(16.dp)) {
            TextButton(onClick = { preview = state.pending }, modifier = Modifier.fillMaxWidth()) { Text("有一份新主题等你预览确认") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的主题", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${state.entries.size} 套", color = colors.mutedInk, style = MaterialTheme.typography.labelMedium)
        }
        if (state.entries.isEmpty()) Text("还没有主题。开启当前助手的“AI 自定义美化”工具后，让它设计第一套吧。", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if(maxWidth >= 360.dp) 2 else 1
            val width = (maxWidth - 12.dp * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.entries.sortedByDescending { it.id == state.active?.id }.forEach { entry ->
                    val version = entry.versions.lastOrNull()
                    val body = version?.body ?: entry.draft
                    Surface(Modifier.width(width), color = colors.panel, shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(if(entry.id == state.active?.id) 1.5.dp else .5.dp, if(entry.id == state.active?.id) colors.accent else colors.border)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if(body != null) ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath },
                                compact = true, assets = body.assets.associateWith { store.asset(it).absolutePath })
                            Text(body?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(if(entry.id == state.active?.id) "当前使用" else if(entry.draft != null) "设计草稿" else "第 ${entry.revision} 版", color = colors.mutedInk, style = MaterialTheme.typography.labelSmall)
                            FlowRow {
                                if(version != null) TextButton(enabled = !busy, onClick = { preview = ThemeRef(entry.id, version.revision) }) { Text("预览主题") }
                                if(entry.authorRole == "ai") TextButton(enabled = !busy, onClick = { task { store.delete(entry.id, entry.revision) } }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = {
            val prompt = "请用 orbis_theme 为我们设计一套新的聊天主题，包含气泡形状、角标、背景与底栏配色。先读取工具授权；允许自动装扮时，发布后直接 apply。"
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("主题设计请求", prompt))
            copied = true
        }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if(copied) "设计请求已复制，回聊天粘贴" else "✦  让 AI 设计") }
        importImage { read -> task { importThemeImage(store, read()) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("猫耳 / 兔耳气泡", "角标与贴图", "纹样与背景", "底栏配色").forEach { label ->
                Surface(color = colors.accent.copy(alpha = .06f), shape = RoundedCornerShape(12.dp)) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.mutedInk) }
            }
        }
    }
    preview?.let { ref -> state.resolve(ref)?.let { body ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(body.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath }, assets = body.assets.associateWith { store.asset(it).absolutePath })
                Text("这只是示例对话。确认后用于当前助手，随时可回退。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(enabled = !busy, onClick = { task { store.confirm(ref) }; preview = null }) { Text("应用这个主题") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("先不更换") } })
    } }
}

/** Uses the same background and bubble renderer as the real conversation, never an HTML mock. */
@Composable internal fun ThemeChatPreview(body: ThemePackage, image: String? = null, compact: Boolean = false, assets: Map<String, String> = emptyMap()) {
    val family = themeFont(body.typography.fontFamily)
    CompositionLocalProvider(LocalChatTheme provides ChatTheme(body, image, assets = assets), LocalChatFontFamily provides family) {
        Box(Modifier.fillMaxWidth().heightIn(min = if(compact) 155.dp else 220.dp).clip(RoundedCornerShape(20.dp)).background(Color(themeArgb(body.colors.background)), RoundedCornerShape(20.dp))) {
            OrbisChatBackdrop(OrbisAppearance(backgroundEnabled = true, floatingStars = false), Modifier.matchParentSize(), isVisible = false)
            Column(Modifier.fillMaxWidth().padding(if(compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if(compact) 10.dp else 18.dp)) {
                if(!compact) Text("我们的小小宇宙", color = Color(themeArgb(body.colors.text)), fontFamily = family)
                OrbisMessageBubble(false, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("风和星光都在这里。", fontFamily = family, fontSize = (14 * body.typography.fontSizeScale).sp,
                        lineHeight = (14 * body.typography.fontSizeScale * body.typography.lineHeight).sp)
                }
                OrbisMessageBubble(true, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("嗯，慢慢说。", fontFamily = family, fontSize = (14 * body.typography.fontSizeScale).sp)
                }
            }
        }
    }
}
