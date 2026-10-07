package me.rerere.rikkahub.ui.pages.orbis

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.withNativeToolSelection
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.gallery.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.uuid.Uuid

private data class GalleryImportPreview(val title: String, val original: String, val form: GalleryQuestionnaire)

/** Only the artwork dialog owns immersive bars; leaving it restores the prior window state. */
@Composable
private fun GalleryImmersiveBars(fullScreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullScreen, view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (!fullScreen || window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = ViewCompat.getRootWindowInsets(view)
        val previousStatus = previous?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val previousNavigation = previous?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val behavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = behavior
            if (previousStatus) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (previousNavigation) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

@Composable
internal fun OrbisGalleryPage(assistantId: String, assistantName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(assistantId) { openGallery(context, assistantId) }
    val scope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    var snapshot by remember { mutableStateOf(GalleryIndex()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var navigationExpanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var fullScreen by remember(selected) { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var urlEntry by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<GalleryImportPreview?>(null) }
    var importRespondent by remember { mutableStateOf("human") }
    var composeText by remember { mutableStateOf<String?>(null) }
    var draftsOpen by remember { mutableStateOf(false) }
    var pendingDrafts by remember { mutableStateOf<List<GalleryDraft>>(emptyList()) }
    var discardDraft by remember { mutableStateOf<GalleryDraft?>(null) }
    fun task(work: suspend () -> String?) {
        busy = true; notice = null
        scope.launch {
            try { notice = work(); snapshot = withContext(Dispatchers.IO) { repo.snapshot() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = galleryErrorText(error) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        try { snapshot = withContext(Dispatchers.IO) { repo.snapshot() } }
        catch (error: Exception) { notice = galleryErrorText(error) }
    }
    suspend fun source(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.')?.filterNot(Char::isISOControl)?.let { galleryTextPrefix(it, 100) }?.ifBlank { null } ?: "导入的作品"
        val content = context.contentResolver.openInputStream(uri)?.use { galleryImportText(galleryUtf8(it.galleryReadBounded(GalleryLimits.BODY_BYTES))) } ?: error("gallery_file_unavailable")
        name to content
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) task {
            var saved = 0; var failed = 0
            for (uri in uris) {
                try {
                    val (name, content) = source(uri)
                    val kind = if (content.trimStart().startsWith("<")) "html" else "text"
                    withContext(Dispatchers.IO) { repo.save(name, kind, content) }; saved++
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed++ }
            }
            "已保存 $saved 件作品" + if (failed > 0) "；$failed 件未导入，请单独核对格式、大小和空间。原文件未改动。" else "，原文件未改动。"
        }
    }
    val questionnaire = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            val (name, text) = source(uri)
            val form = withContext(Dispatchers.IO) { extractGalleryQuestions(text, "human") }
            preview = GalleryImportPreview(name, text, form); importRespondent = "human"; null
        }
    }
    val title = (snapshot.customName.ifBlank { assistantName.ifBlank { "伙伴" } }) + "的格子"
    Dialog(onDismissRequest = { if (fullScreen) fullScreen = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        GalleryImmersiveBars(fullScreen)
        OrbisPageSurface {
            Surface(Modifier.fillMaxSize(), color = OrbisTheme.colors.page) {
                Column(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.safeDrawingPadding())) {
                    if (!fullScreen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (selected != null) selected = null else onClose() }) { Text("返回") }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("A LITTLE PLACE FOR US", fontSize = 9.sp, letterSpacing = 2.sp, color = OrbisTheme.colors.mutedInk)
                        }
                        if (selected == null) TextButton(enabled = !busy, onClick = { newName = snapshot.customName; rename = true }) { Text("命名") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    notice?.let { value -> Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .75f), shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 14.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { notice = null }) { Text("知道了") } }
                    } }
                    }
                    val item = snapshot.items.firstOrNull { it.id == selected && it.deletedAt == null }
                    if (selected != null && item != null) {
                        GalleryDetail(repo, item, snapshot.answers.filter { it.itemId == item.id }, busy,
                            onTask = { task(it) }, onReturn = { composeText = it }, onDeleted = { selected = null },
                            onReload = { task { null } }, fullScreen = fullScreen, onFullScreen = { fullScreen = it })
                    } else {
                        BackHandler { onClose() }
                        GalleryNavigationPanel(navigationExpanded, { navigationExpanded = !navigationExpanded },
                            activeFilter = query.isNotBlank() || filter != "all",
                            onClearFilter = { query = ""; filter = "all" }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("留住一份心意", style = MaterialTheme.typography.titleMedium)
                                    Text("作品仅存本机，打开不会发送给 AI。", style = MaterialTheme.typography.bodySmall, color = OrbisTheme.colors.mutedInk) }
                                Switch(checked = LocalToolOption.LocalGallery in assistant?.localTools.orEmpty(), enabled = assistant != null && !busy,
                                    onCheckedChange = { enabled -> task {
                                        settingsStore.update { it.withNativeToolSelection(Uuid.parse(assistantId), LocalToolOption.LocalGallery, enabled) }
                                        if (enabled) "已允许这位 AI 使用格子写作工具。首次执行仍需批准，可选择以后允许。" else "已关闭格子写作工具；按需只读仍可用。"
                                    } })
                            }
                            Text("右侧开关：允许 AI 写 / 改 / 删、填写给 AI 的问卷；正文与答案仅在 AI 按需调用工具时交给模型。", style = MaterialTheme.typography.labelSmall)
                            OutlinedTextField(query, { query = galleryTextPrefix(it, 120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("按作品名字搜索") }, shape = RoundedCornerShape(20.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("all" to "全部", "questionnaire" to "问卷", "html" to "礼物", "favorite" to "收藏").forEach { (key, label) ->
                                    FilterChip(filter == key, { filter = key }, label = { Text(label) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(enabled = !busy, onClick = { files.launch(arrayOf("text/html", "text/plain", "application/json")) }) { Text("导入作品") }
                                TextButton(enabled = !busy, onClick = { questionnaire.launch(arrayOf("text/html", "application/json", "text/plain")) }) { Text("导入问卷") }
                                TextButton(enabled = !busy, onClick = { urlEntry = true }) { Text("问卷网址") }
                            }
                            TextButton(enabled = !busy, onClick = { task { pendingDrafts = withContext(Dispatchers.IO) { repo.drafts() }; draftsOpen = true; null } }) { Text("管理未发布草稿") }
                        }
                        val shown = snapshot.items.filter { it.deletedAt == null && it.title.contains(query, true) && when (filter) { "all" -> true; "favorite" -> it.favorite; else -> it.kind == filter } }.sortedByDescending { it.updatedAt }
                        if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("✧", fontSize = 56.sp, color = OrbisTheme.colors.indigo)
                                Text("空格子也在等一个惊喜", style = MaterialTheme.typography.titleMedium)
                                Text("请 AI 写一件作品，或导入已有的 HTML / 文本。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                        } else LazyVerticalGrid(GridCells.Adaptive(155.dp), Modifier.weight(1f), contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shown, key = { it.id }) { entry -> GalleryTile(entry, onClick = { selected = entry.id }) }
                        }
                    }
                }
            }
        }
        if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("谁的格子？") }, text = {
            OutlinedTextField(newName, { newName = galleryTextPrefix(it, 40) }, label = { Text("留空跟随当前助手名称") }, suffix = { Text("的格子") })
        }, confirmButton = { TextButton(onClick = { rename = false; task { withContext(Dispatchers.IO) { repo.rename(newName) }; null } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } })
        if (urlEntry) AlertDialog(onDismissRequest = { urlEntry = false }, title = { Text("从网址提取问卷") }, text = { Column {
            Text("仅访问你填写的公开 HTTPS 地址，网站会看到这次访问。不带登录信息、不运行脚本、不提交答案，也不自动发给 AI。动态或需登录的问卷请先另存为文件。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { url = galleryTextPrefix(it, 2048) }, label = { Text("https://…") })
        } }, confirmButton = { TextButton(enabled = url.isNotBlank() && !busy, onClick = { urlEntry = false; task {
            val text = withContext(Dispatchers.IO) { fetchGalleryQuestionnaire(url) }
            val form = withContext(Dispatchers.IO) { extractGalleryQuestions(text, "human") }
            preview = GalleryImportPreview("导入的问卷", text, form); importRespondent = "human"; null
        } }) { Text("读取并预览") } }, dismissButton = { TextButton(onClick = { urlEntry = false }) { Text("取消") } })
        preview?.let { candidate -> GalleryQuestionImportPreview(candidate, importRespondent,
            onRespondent = { importRespondent = it }, onCancel = { preview = null }, onSave = { name ->
                task { withContext(Dispatchers.IO) { repo.save(name, "questionnaire", galleryJson.encodeToString(candidate.form.copy(respondent = importRespondent))) }
                    preview = null; "问卷已保存。答案只留在 Orbis，不会回传原网站。" }
            }) }
        composeText?.let { text -> GalleryChatPicker(assistantId, text, onClose = { composeText = null }, onNavigate = { composeText = null; onClose() }) }
        if (draftsOpen) AlertDialog(onDismissRequest = { draftsOpen = false }, title = { Text("未发布草稿") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (pendingDrafts.isEmpty()) item { Text("没有未发布草稿。未完成的分块创作会留在这里；丢弃不会删除任何已发布作品。") }
                items(pendingDrafts, key = { it.id }) { draft -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(draft.title); Text("${if (draft.actor == "ai") "AI" else "人类"} · ${draft.createdAt}", style = MaterialTheme.typography.labelSmall) }
                    TextButton(enabled = !busy, onClick = { discardDraft = draft }) { Text("丢弃") }
                } }
            }
        }, confirmButton = { TextButton(onClick = { draftsOpen = false }) { Text("关闭") } })
        discardDraft?.let { draft -> AlertDialog(onDismissRequest = { discardDraft = null }, title = { Text("丢弃这个草稿？") }, text = { Text("${draft.title}\n只删除尚未发布的草稿，不动已有作品、版本或答案。") },
            confirmButton = { TextButton(onClick = { discardDraft = null; task { withContext(Dispatchers.IO) { repo.discardDraftByHuman(draft.id) }; pendingDrafts = withContext(Dispatchers.IO) { repo.drafts() }; "草稿已丢弃。" } }) { Text("确认丢弃") } },
            dismissButton = { TextButton(onClick = { discardDraft = null }) { Text("取消") } }) }
    }
}

/** Collapsed on entry; expanding never changes a search, permission or import state. */
@Composable
internal fun GalleryNavigationPanel(expanded: Boolean, onToggle: () -> Unit, activeFilter: Boolean,
    onClearFilter: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
                Text(if (expanded) "收起导航与设置  ▴" else "搜索、筛选与设置  ▾")
            }
            if (activeFilter) TextButton(onClick = onClearFilter) { Text("清除筛选") }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 310.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun GalleryTile(item: GalleryItem, onClick: () -> Unit) {
    val palette = when (item.kind) { "questionnaire" -> listOf(Color(0xffd9d4f4), Color(0xffede8f8)); "html" -> listOf(Color(0xfff2d9d3), Color(0xfff7e9d2)); else -> listOf(Color(0xffc9ddd6), Color(0xffe4ede0)) }
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, OrbisTheme.colors.border), color = OrbisTheme.colors.panel) {
        Column {
            Column(Modifier.fillMaxWidth().height(145.dp).background(Brush.linearGradient(palette)).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (item.kind == "questionnaire") "✎" else if (item.kind == "html") "✦" else "≋", fontSize = 30.sp, color = Color(0xff4c475f))
                Text(item.preview.ifBlank { "一份属于我们的作品" }, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xff484050), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (item.favorite) "♡ " else "") + item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${when (item.kind) { "html" -> "互动礼物"; "questionnaire" -> "问卷"; else -> "文字" }} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = OrbisTheme.colors.mutedInk)
            }
        }
    }
}

@Composable private fun GalleryQuestionImportPreview(value: GalleryImportPreview, respondent: String, onRespondent: (String) -> Unit, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var title by remember(value) { mutableStateOf(value.title) }
    var showOriginal by remember(value) { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("核对问卷再保存", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(title, { title = galleryTextPrefix(it, 120) }, label = { Text("名称") })
                Row { FilterChip(respondent == "human", { onRespondent("human") }, label = { Text("由我本人填写") }); Spacer(Modifier.width(8.dp)); FilterChip(respondent == "ai", { onRespondent("ai") }, label = { Text("问 AI 本人") }) }
                TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "返回提取题目" else "查看原文（纯文本，不执行）") }
                LazyColumn(Modifier.weight(1f)) {
                    if (showOriginal) item { SelectionContainer { Text(galleryTextPrefix(value.original, 32000), style = MaterialTheme.typography.bodySmall) }; if (value.original.length > 32000) Text("这里只预览前 32000 字，原文件未修改。") }
                    else {
                        if (value.form.description.isNotBlank()) item { Text(value.form.description, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                        items(value.form.questions) { q -> Column(Modifier.padding(vertical = 8.dp)) { Text(q.text); q.options.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) } } }
                    }
                }
                Row { TextButton(onClick = onCancel) { Text("取消") }; Spacer(Modifier.weight(1f)); Button(enabled = GalleryLimits.title(title), onClick = { onSave(title) }) { Text("保存 ${value.form.questions.size} 道题") } }
            }
        }
    }
}

@Composable private fun GalleryChatPicker(assistantId: String, text: String, onClose: () -> Unit, onNavigate: () -> Unit) {
    val repository = koinInject<ConversationRepository>()
    val navigator = LocalNavController.current
    var entries by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var next by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(assistantId, offset) {
        try { val page = repository.getConversationsOfAssistantPage(Uuid.parse(assistantId), offset, 20); entries = page.items; next = page.nextOffset }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = true }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("带回哪个聊天？") }, text = { Column {
        Text("先放入输入框，你确认发送后 AI 才会收到。只带作品 / 答案编号，不自动塞入整份长文件。", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max = 350.dp)) { items(entries, key = { it.id.toString() }) { entry -> TextButton(onClick = { onNavigate(); navigator.navigate(Screen.Chat(entry.id.toString(), text = text)) }) { Text(entry.title.ifBlank { "未命名窗口" }, maxLines = 2) } } }
        if (error) Text("未读到窗口，请关闭后重试。")
        if (entries.isEmpty() && !error) Text("请先为当前助手建立聊天窗口。")
        Row { if (offset > 0) TextButton(onClick = { offset = (offset - 20).coerceAtLeast(0) }) { Text("上一页") }; next?.let { page -> TextButton(onClick = { offset = page }) { Text("下一页") } } }
    } }, confirmButton = { TextButton(onClick = onClose) { Text("取消") } })
}
