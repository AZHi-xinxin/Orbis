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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.em
import me.rerere.rikkahub.ui.components.message.ThemeMotionProvider
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
        LocalChatFontFamily provides (theme?.body?.typography?.fontFamily?.let(::themeFont) ?: LocalChatFontFamily.current)) {
        ThemeMotionProvider(theme?.body?.let { listOf(it.bubbles.assistant,it.bubbles.user).any { b -> b.drawing?.layers.orEmpty().any { layer -> layer.motion != null } } } == true, content)
    }
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
    var page by rememberSaveable(store) { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(store) { mutableStateOf<String?>(null) }
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
        Surface(color = colors.panel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前外观", color=colors.mutedInk, style=MaterialTheme.typography.labelSmall)
                        Text(active?.name ?: "原来的手工外观", maxLines=1, overflow=TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.titleSmall, fontWeight=FontWeight.SemiBold)
                    }
                    if(state.active!=null) TextButton(onClick={ preview=state.active }) { Text("查看") }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled=!busy && state.previous.isNotEmpty(),onClick={task { store.revert() }}) { Text("回到上一套") }
                    TextButton(enabled=!busy && state.active!=null,onClick={task { store.useOriginal() }}) { Text("使用手工外观") }
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
        val entries=state.entries.sortedByDescending { it.id == state.active?.id }
        val pageCount=((entries.size+5)/6).coerceAtLeast(1)
        val shownPage=page.coerceIn(0,pageCount-1)
        LaunchedEffect(pageCount) { page=page.coerceIn(0,pageCount-1) }
        val selected=state.entries.firstOrNull { it.id==selectedId } ?: entries.firstOrNull()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns=if(maxWidth>=280.dp) 3 else 2
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
              entries.drop(shownPage*6).take(6).chunked(columns).forEach { row ->
               Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                    val body=entry.draft ?: entry.versions.lastOrNull()?.body
                    Surface(Modifier.weight(1f).testTag("theme-tile-${entry.id}").clickable { selectedId=entry.id },
                        color=colors.panel,shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(if(selected?.id==entry.id) 1.5.dp else .5.dp,if(selected?.id==entry.id) colors.accent else colors.border)) {
                        Column(Modifier.padding(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(body!=null) ThemeMiniSwatch(body)
                            Text(body?.name.orEmpty(),maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis,
                                style=MaterialTheme.typography.labelMedium)
                            Text(if(entry.id==state.active?.id) "当前使用" else if(entry.draft!=null) "草稿" else "第 ${entry.revision} 版",
                                color=colors.mutedInk,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
               }
              }
            }
        }
        if(entries.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(enabled=shownPage>0,onClick={page=shownPage-1;selectedId=entries.getOrNull((shownPage-1)*6)?.id},modifier=Modifier.testTag("theme-page-prev")) { Text("上一页") }
            Text("${shownPage+1} / $pageCount",style=MaterialTheme.typography.labelMedium,modifier=Modifier.testTag("theme-page-number"))
            TextButton(enabled=shownPage+1<pageCount,onClick={page=shownPage+1;selectedId=entries.getOrNull((shownPage+1)*6)?.id},modifier=Modifier.testTag("theme-page-next")) { Text("下一页") }
        }
        selected?.let { entry ->
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                entry.versions.lastOrNull()?.let { version ->
                    TextButton(enabled=!busy,onClick={preview=ThemeRef(entry.id,version.revision)}) { Text("预览主题") }
                }
                if(entry.authorRole=="ai") TextButton(enabled=!busy,onClick={task {store.delete(entry.id,entry.revision)}}) { Text("删除所选") }
            }
        }
        Button(onClick = {
            val prompt = "请先用 orbis_theme list/read 查看当前主题。微调请 patch 原主题 ID，不要新建；可用 drawing 自己绘制轮廓、渐变和动画关键帧，分别设计两边气泡与字体。明确需要另一套才新建。按现有授权 publish→apply。"
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("主题设计请求", prompt))
            copied = true
        }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if(copied) "设计请求已复制，回聊天粘贴" else "✦  让 AI 设计") }
        importImage { read -> task { importThemeImage(store, read()) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("自由绘制", "动态气泡", "独立字体", "原主题微调").forEach { label ->
                Surface(color = colors.accent.copy(alpha = .06f), shape = RoundedCornerShape(12.dp)) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.mutedInk) }
            }
        }
    }
    preview?.let { ref -> state.resolve(ref)?.let { body ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(body.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath }, assets = body.assets.associateWith { store.asset(it).absolutePath }, animate = true)
                Text("这只是示例对话。确认后用于当前助手，随时可回退。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(enabled = !busy, onClick = { task { store.confirm(ref) }; preview = null }) { Text("应用这个主题") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("先不更换") } })
    } }
}

/** Uses the same background and bubble renderer as the real conversation, never an HTML mock. */
@Composable internal fun ThemeChatPreview(body: ThemePackage, image: String? = null, compact: Boolean = false, assets: Map<String, String> = emptyMap(), animate: Boolean = false) {
    val family = themeFont(body.typography.fontFamily)
    CompositionLocalProvider(LocalChatTheme provides ChatTheme(body, image, assets = assets), LocalChatFontFamily provides family) {
      ThemeMotionProvider(animate) {
        Box(Modifier.fillMaxWidth().heightIn(min = if(compact) 155.dp else 220.dp).clip(RoundedCornerShape(20.dp)).background(Color(themeArgb(body.colors.background)), RoundedCornerShape(20.dp))) {
            OrbisChatBackdrop(OrbisAppearance(backgroundEnabled = true, floatingStars = false), Modifier.matchParentSize(), isVisible = false)
            Column(Modifier.fillMaxWidth().padding(if(compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if(compact) 10.dp else 18.dp)) {
                if(!compact) Text("我们的小小宇宙", color = Color(themeArgb(body.colors.text)), fontFamily = family)
                OrbisMessageBubble(false, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("风和星光都在这里。", style=themePreviewTextStyle(body.bubbles.assistant.typography ?: body.typography))
                }
                OrbisMessageBubble(true, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("嗯，慢慢说。", style=themePreviewTextStyle(body.bubbles.user.typography ?: body.typography))
                }
            }
        }
    }
  }
}

internal fun themePreviewTextStyle(type: ThemeTypography) = androidx.compose.ui.text.TextStyle(
    fontFamily=themeFont(type.fontFamily),fontSize=(14*type.fontSizeScale).sp,
    lineHeight=(14*type.fontSizeScale*type.lineHeight).sp,fontWeight=FontWeight(type.weight),
    fontStyle=if(type.italic) FontStyle.Italic else FontStyle.Normal,letterSpacing=type.letterSpacing.em)

@Composable private fun ThemeMiniSwatch(body: ThemePackage) {
    CompositionLocalProvider(LocalChatTheme provides ChatTheme(body,null)) {
        ThemeMotionProvider(false) {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(themeArgb(body.colors.background))).padding(4.dp),contentAlignment=Alignment.Center) {
                OrbisMessageBubble(false,OrbisAppearance(),Modifier.fillMaxWidth()) {
                    Text("Aa",maxLines=1,style=themePreviewTextStyle(body.bubbles.assistant.typography ?: body.typography).copy(fontSize=12.sp,lineHeight=14.sp))
                }
            }
        }
    }
}
