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
import me.rerere.rikkahub.ui.components.message.LocalBubbleBackdrop
import me.rerere.rikkahub.data.orbis.theme.hasMotion
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

internal data class ChatTheme(val body: ThemePackage, val image: String?, val onImageError: (() -> Unit)? = null, val assets: Map<String, String> = emptyMap(), val followHumanFont: Boolean = true, val followHumanAccent: Boolean = false, val followHumanChrome: Boolean = followHumanAccent)
internal val LocalChatTheme = staticCompositionLocalOf<ChatTheme?> { null }
/** Resolve interface ink at display time; published packages and chosen bubble text stay unchanged. */
internal fun ChatTheme.withInterfaceColors(colors: OrbisColors): ChatTheme {
    if (!followHumanAccent) return this
    fun Color.hex() = "#%06X".format(java.util.Locale.ROOT, toArgb() and 0xFFFFFF)
    return copy(body=body.copy(colors=body.colors.copy(text=colors.ink.hex(),textSecondary=colors.mutedInk.hex()),
        chrome=if(followHumanChrome) body.chrome.copy(onComposer=colors.ink.hex(),onDock=colors.onDock.hex()) else body.chrome))
}
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
                val body = state.displayBody() ?: return@runCatching null
                body.assets.forEach { check(store.asset(it).isFile) }
                ChatTheme(body, body.background.asset?.let { store.asset(it).absolutePath }, followHumanFont = state.followHumanFont,
                    followHumanAccent = MaterialThemePreset.fromId(state.active?.id) != null,
                    followHumanChrome = MaterialThemePreset.fromId(state.active?.id) != null && state.materialTextColors[state.active?.id]?.chrome == null,
                    assets = body.assets.associateWith { store.asset(it).absolutePath }, onImageError = {
                    scope.launch(Dispatchers.IO) { runCatching { state.active?.let(store::renderFailed) } }
                })
            }.getOrNull()
        }
    }
    val displayedTheme=theme?.withInterfaceColors(OrbisTheme.colors)
    CompositionLocalProvider(LocalChatTheme provides displayedTheme,
        LocalChatFontFamily provides (theme?.takeUnless { it.followHumanFont }?.body?.typography?.fontFamily?.takeUnless { it == "default" }?.let(::themeFont) ?: LocalChatFontFamily.current)) {
        ThemeMotionProvider(theme?.body?.hasMotion() == true, content)
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
    var draftPreview by remember(store) { mutableStateOf<ThemeEntry?>(null) }
    var textColorEdit by remember(store) { mutableStateOf<Pair<ThemeRef, MaterialTextTarget>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var page by rememberSaveable(store) { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(store) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, signal) {
        try { state = withContext(Dispatchers.IO) { store.snapshot() }; error = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "主题文件暂时无法读取，聊天使用原来的外观；原文件保留。" }
    }
    fun task(after: () -> Unit = {}, block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; state = withContext(Dispatchers.IO) { store.snapshot() }; error = null; after() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is ThemeAssetException) "${e.field} 引用的图片不可用。请重新导入图片，让AI更新这处引用，或改用纯色背景。"
                else "这次更改没有完成，请刷新后检查主题版本或图片。" }
            finally { busy = false }
        }
    }
    val colors = OrbisTheme.colors
    val context = LocalContext.current
    var backgroundTarget by remember { mutableStateOf<ThemeRef?>(null) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = backgroundTarget
        backgroundTarget = null
        if (uri != null && target != null) task {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.bounded(10 * 1024 * 1024) }
                ?: error("theme_asset_invalid")
            val asset = importThemeImage(store, bytes)
            store.setMaterialBackground(target, asset)
        }
    }
    fun defaultWallpaper(preset: MaterialThemePreset): String {
        val resource = if (preset == MaterialThemePreset.ICE) me.rerere.rikkahub.R.drawable.market_cover_rain
            else me.rerere.rikkahub.R.drawable.market_cover_cafe
        return importThemeImage(store, context.resources.openRawResource(resource).use { it.readBytes() })
    }
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("主题工作室", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("让伙伴布置你们的聊天空间", color = colors.mutedInk, style = MaterialTheme.typography.bodySmall)
        }
        val active = state.resolve()
        Surface(color=colors.panel,shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("内置材质主题",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text("选一款预览，再换上你自己的背景图片。",color=colors.mutedInk,style=MaterialTheme.typography.bodySmall)
                Text("按钮、辅助文字和默认输入字色跟随界面主题；手选字色会保留。",color=colors.mutedInk,style=MaterialTheme.typography.bodySmall)
                MaterialThemePreset.entries.forEach { preset ->
                    OutlinedButton(enabled=!busy,onClick={
                        var ref: ThemeRef? = null
                        task(after={preview=ref}) { ref=store.prepareMaterialPreset(preset,defaultWallpaper(preset)) }
                    },modifier=Modifier.fillMaxWidth().testTag("material-preset-${preset.key}"),shape=RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                            Text(preset.title,fontWeight=FontWeight.SemiBold)
                            Text(preset.description,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val preset=MaterialThemePreset.fromId(state.active?.id)
                Button(enabled=!busy && preset!=null,onClick={
                    backgroundTarget=state.active
                    wallpaperPicker.launch("image/*")
                },modifier=Modifier.fillMaxWidth().testTag("material-wallpaper"),shape=RoundedCornerShape(14.dp)) {
                    Text("选择我的背景图片")
                }
                if(preset==null) Text("应用上面任意一款后，即可直接更换背景。",color=colors.mutedInk,style=MaterialTheme.typography.bodySmall)
                else TextButton(enabled=!busy,onClick={val ref=state.active ?: return@TextButton
                    task { store.setMaterialBackground(ref,defaultWallpaper(preset)) }
                },modifier=Modifier.testTag("material-wallpaper-reset")) { Text("使用自带背景") }
                if (preset != null) {
                    Text("文字颜色", style = MaterialTheme.typography.titleSmall)
                    MaterialTextTarget.entries.forEach { target ->
                        OutlinedButton(enabled = !busy, onClick = {
                            state.active?.let { textColorEdit = it to target }
                        }, modifier = Modifier.fillMaxWidth().testTag("material-text-${target.name.lowercase()}")) {
                            Text(target.title)
                        }
                    }
                }
            }
        }
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
        Surface(color = colors.accent.copy(alpha = .05f), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("字体跟随我的设置", style = MaterialTheme.typography.titleMedium)
                    Text("开启后，AI 气泡也使用你选择的聊天字体；选择系统默认即可恢复手机字体。关闭才使用 AI 主题指定的字体。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.followHumanFont, enabled = !busy, onCheckedChange = { task { store.followHumanFont(it) } }, modifier = Modifier.testTag("theme-follow-human-font"))
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
                    val body=entry.draft ?: entry.versions.lastOrNull()?.let { state.displayBody(ThemeRef(entry.id,it.revision)) }
                    Surface(Modifier.weight(1f).testTag("theme-tile-${entry.id}").clickable { selectedId=entry.id; if (entry.draft != null) draftPreview=entry },
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
                if (entry.draft != null) TextButton(enabled=!busy,onClick={draftPreview=entry}) { Text("预览草稿") }
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
    draftPreview?.let { entry -> entry.draft?.let { body ->
        AlertDialog(onDismissRequest = { if (!busy) draftPreview = null }, title = { Text("草稿 · ${body.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath }, assets = body.assets.associateWith { store.asset(it).absolutePath }, animate = true)
                Text("先查看设计。发布后可预览并选择应用。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { Button(enabled = !busy, onClick = {
                var published: ThemeRef? = null
                task(after = { draftPreview = null; preview = published }) {
                    val next = store.publish(entry.id, entry.revision)
                    published = ThemeRef(next.id, next.revision)
                }
            }) { Text("发布并预览") } }, dismissButton = { TextButton(enabled = !busy, onClick = { draftPreview = null }) { Text("返回") } })
    } }
    textColorEdit?.let { (ref, target) -> state.resolve(ref)?.let { body ->
        key(ref, target) {
            MaterialThemeTextColorDialog(body, state.materialTextColors[ref.id] ?: MaterialTextColors(), target,
                body.background.asset?.let { store.asset(it).absolutePath }, body.assets.associateWith { store.asset(it).absolutePath },
                busy, error, onDismiss = { textColorEdit = null }, onSave = { color ->
                    task(after = { textColorEdit = null }) { store.setMaterialTextColor(ref, target, color) }
                })
        }
    } }
    preview?.let { ref -> state.displayBody(ref)?.let { body ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(body.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChatPreview(body, body.background.asset?.let { store.asset(it).absolutePath }, assets = body.assets.associateWith { store.asset(it).absolutePath }, animate = true,
                    followHumanAccent = MaterialThemePreset.fromId(ref.id) != null,
                    followHumanChrome = MaterialThemePreset.fromId(ref.id) != null && state.materialTextColors[ref.id]?.chrome == null)
                Text("这只是示例对话。确认后用于当前助手，随时可回退。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(enabled = !busy, onClick = { task { store.confirm(ref) }; preview = null }) { Text("应用这个主题") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("先不更换") } })
    } }
}

/** Uses the same background and bubble renderer as the real conversation, never an HTML mock. */
@Composable internal fun ThemeChatPreview(body: ThemePackage, image: String? = null, compact: Boolean = false, assets: Map<String, String> = emptyMap(), animate: Boolean = false, followHumanAccent: Boolean = false, followHumanChrome: Boolean = followHumanAccent) {
    val family = themeFont(body.typography.fontFamily)
    val backdrop = rememberHazeState()
    val displayed=ChatTheme(body, image, assets = assets, followHumanAccent = followHumanAccent,followHumanChrome=followHumanChrome).withInterfaceColors(OrbisTheme.colors)
    CompositionLocalProvider(LocalBubbleBackdrop provides backdrop, LocalChatTheme provides displayed, LocalChatFontFamily provides family) {
      ThemeMotionProvider(animate && body.hasMotion()) {
        Box(Modifier.fillMaxWidth().heightIn(min = if(compact) 155.dp else 220.dp).clip(RoundedCornerShape(20.dp)).background(Color(themeArgb(body.colors.background)), RoundedCornerShape(20.dp))) {
            OrbisChatBackdrop(OrbisAppearance(backgroundEnabled = true, floatingStars = false), Modifier.matchParentSize().hazeSource(backdrop), isVisible = false)
            Column(Modifier.fillMaxWidth().padding(if(compact) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if(compact) 10.dp else 18.dp)) {
                if(!compact) Text("我们的小小宇宙", color = Color(themeArgb(body.colors.text)), fontFamily = family)
                OrbisMessageBubble(false, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("风和星光都在这里。", style=themePreviewTextStyle(body.bubbles.assistant.typography ?: body.typography))
                }
                OrbisMessageBubble(true, OrbisAppearance(), Modifier.fillMaxWidth()) {
                    Text("嗯，慢慢说。", style=themePreviewTextStyle(body.bubbles.user.typography ?: body.typography))
                }
                if (!compact && listOf(body.chrome.surface,body.chrome.composerSurface,body.chrome.dockSurface,body.chrome.starSurface).any { it != null }) {
                    me.rerere.rikkahub.ui.components.ai.ChatComposerSurface(Modifier.fillMaxWidth(),RoundedCornerShape(23.dp),
                        Color(themeArgb(body.chrome.composer ?: body.colors.surface)),
                        Color(themeArgb(displayed.body.chrome.onComposer ?: displayed.body.colors.text)),
                        BorderStroke(1.dp,Color(themeArgb(body.colors.primary)).copy(alpha=.25f)),true) {
                        Text("＋  输入消息…                 ↑",Modifier.padding(14.dp),fontSize=14.sp)
                    }
                    OrbisVisualTheme {
                        OrbisBottomDock("预览",{}, {},windowInsets=WindowInsets(0,0,0,0))
                    }
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
