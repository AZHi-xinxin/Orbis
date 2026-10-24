package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

internal data class ChatTheme(val body: ThemePackage, val image: String?, val onImageError: (() -> Unit)? = null)
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
                ChatTheme(body, body.background.asset?.let { store.asset(it).absolutePath }, onImageError = {
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
        TextButton(onClick = { picker.launch("image/*") }) { Text("添加主题图片") }
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
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 主题工作室", style = MaterialTheme.typography.titleMedium)
            Text("让伙伴设计，再看一眼预览。只改变当前助手的聊天外观。", style = MaterialTheme.typography.bodySmall)
            Text("当前：${state.resolve()?.name ?: "原来的手工外观"}", style = MaterialTheme.typography.labelLarge)
            if (state.pending != null) Text("有一份新主题等你预览确认", color = MaterialTheme.colorScheme.primary)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.entries.isEmpty()) Text("还没有主题。可在当前助手的“本地工具”开启 AI 自定义美化，再告诉伙伴想要的风格。", style = MaterialTheme.typography.bodySmall)
            state.entries.sortedByDescending { it.id == state.pending?.id }.forEach { entry ->
                val version = entry.versions.lastOrNull()
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(entry.draft?.name ?: version?.body?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    Text("${if (entry.authorRole == "ai") "AI 创建" else "人类创建"} · ${if (entry.draft != null) "有草稿" else "已发布"} · 第 ${entry.revision} 版", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (version != null) TextButton(enabled = !busy, onClick = { preview = ThemeRef(entry.id, version.revision) }) { Text("预览主题") }
                        if (entry.authorRole == "ai") TextButton(enabled = !busy, onClick = { task { store.delete(entry.id, entry.revision) } }) { Text("删除") }
                    }
                    if (version != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("允许此主题以后自动应用", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(entry.id in state.autoApply, enabled = !busy, onCheckedChange = { task { store.allowAuto(entry.id, it) } })
                    }
                }
            }
            importImage { read -> task { importThemeImage(store, read()) } }
            Row {
                TextButton(enabled = !busy && state.previous.isNotEmpty(), onClick = { task { store.revert() } }) { Text("回到上个外观") }
                TextButton(enabled = !busy && state.active != null, onClick = { task { store.useOriginal() } }) { Text("使用手工外观") }
            }
        }
    }
    preview?.let { ref -> state.resolve(ref)?.let { body ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(body.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath })
                Text("这只是示例对话。确认后用于当前助手，随时可回退。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(enabled = !busy, onClick = { task { store.confirm(ref) }; preview = null }) { Text("应用这个主题") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("先不更换") } })
    } }
}

/** Uses the same background and bubble renderer as the real conversation, never an HTML mock. */
@Composable internal fun ThemeChatPreview(body: ThemePackage, image: String? = null) {
    val family = themeFont(body.typography.fontFamily)
    CompositionLocalProvider(LocalChatTheme provides ChatTheme(body, image), LocalChatFontFamily provides family) {
        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).background(Color(themeArgb(body.colors.background)), RoundedCornerShape(20.dp))) {
            OrbisChatBackdrop(OrbisAppearance(backgroundEnabled = true, floatingStars = false), Modifier.matchParentSize(), isVisible = false)
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("我们的小小宇宙", color = Color(themeArgb(body.colors.text)), fontFamily = family)
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
