package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.orbis.spaces.*
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

private val spacePapers = linkedMapOf("letter" to "星笺", "kraft" to "牛皮纸", "floral" to "花笺", "journal" to "手账", "midnight" to "夜航")
private val spaceWalls = linkedMapOf("polaroid" to "拍立得", "album" to "相册", "hanging" to "悬挂", "collage" to "拼贴")
internal data class StoryEditor(val story: SpaceStory?, val field: String)
private data class SpaceReply(val post: SpacePost, val comment: SpaceComment? = null)

/** A native page, scoped to one assistant; never sends a model request on open. */
@Composable
fun OrbisCompanionSpacesPage(assistantId: String, assistantName: String, section: CompanionSpaceSection, onClose: () -> Unit) {
    OrbisVisualTheme { CompanionSpacesContent(assistantId, assistantName, section, onClose) }
}

@Composable
private fun CompanionSpacesContent(assistantId: String, assistantName: String, section: CompanionSpaceSection, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(assistantId) { OrbisCompanionSpacesStore.open(context, assistantId) }
    val revision by store.revisions.collectAsStateWithLifecycle()
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    val humanName = settings.displaySetting.userNickname.ifBlank { "我" }
    val humanAvatar = settings.displaySetting.userAvatar
    val aiName = assistant?.name?.ifBlank { assistantName } ?: assistantName
    val aiAvatar = assistant?.avatar ?: Avatar.Dummy
    val scope = rememberCoroutineScope()
    var snapshot by remember(assistantId) { mutableStateOf<CompanionSpaceSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var storyEditor by remember { mutableStateOf<StoryEditor?>(null) }
    var deleteStory by remember { mutableStateOf<SpaceStory?>(null) }
    var deletePhoto by remember { mutableStateOf<SpacePhoto?>(null) }
    var deletePost by remember { mutableStateOf<SpacePost?>(null) }
    var composePost by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<SpaceReply?>(null) }
    var imagePreview by remember { mutableStateOf<String?>(null) }
    var manualRefresh by remember { mutableIntStateOf(0) }

    fun task(work: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { withContext(Dispatchers.IO) { work() }; snapshot = withContext(Dispatchers.IO) { store.snapshot() }; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = companionSpaceError(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(store, revision, manualRefresh) {
        try { snapshot = withContext(Dispatchers.IO) { store.snapshot() }; error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = companionSpaceError(failure) }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task { store.setCover(importCompanionSpaceImage(context, store, uri).id) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) task {
            var imported = 0; var failed = 0
            for (uri in uris.take(30)) {
                try { store.addPhoto(importCompanionSpaceImage(context, store, uri).id); imported++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++ }
            }
            notice = "已保存 $imported 张照片，原图片未改动。" + (if (failed > 0) "$failed 张未导入，请检查图片格式、大小和剩余空间。" else "") + if (uris.size > 30) "每批最多30张，剩余照片可再导入。" else ""
        }
    }
    BackHandler(onBack = onClose)
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("返回") }
                Column(Modifier.weight(1f)) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("$aiName · 只属于你们的空间", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                TextButton(enabled = !busy && snapshot != null && error == null, onClick = {
                    when (section) {
                        CompanionSpaceSection.SECRET -> storyEditor = StoryEditor(null, "prompt")
                        CompanionSpaceSection.SOCIAL -> composePost = true
                        CompanionSpaceSection.PHOTOS -> photoPicker.launch(arrayOf("image/*"))
                    }
                }) { Text(if (section == CompanionSpaceSection.PHOTOS) "＋ 照片" else "＋ 新建") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it, Modifier.padding(12.dp), color = colors.primary, style = MaterialTheme.typography.bodySmall) }
            if (error != null) {
                Text(error.orEmpty(), Modifier.padding(20.dp), color = colors.error)
                TextButton(onClick = { manualRefresh++ }) { Text("重试读取") }
            } else snapshot?.let { state ->
                when (section) {
                    CompanionSpaceSection.SECRET -> SecretBaseContent(state, busy, onEdit = { story, field -> storyEditor = StoryEditor(story, field) }, onDelete = { deleteStory = it })
                    CompanionSpaceSection.SOCIAL -> SharedSpaceContent(state, store, humanName, humanAvatar, aiName, aiAvatar, busy,
                        onCover = { coverPicker.launch(arrayOf("image/*")) }, onLike = { post -> task { store.likePost(post.id, "human", "human" !in post.likes) } },
                        onComment = { post, comment -> reply = SpaceReply(post, comment) }, onDelete = { deletePost = it }, onImage = { imagePreview = it })
                    CompanionSpaceSection.PHOTOS -> PhotoWallContent(state, store, busy,
                        onStyle = { task { store.setWallStyle(it) } }, onAdd = { photoPicker.launch(arrayOf("image/*")) },
                        onNote = { photo, note -> task { store.setPhotoNote(photo.id, note, photo.revision) } },
                        onMove = { photo, target -> task { store.movePhoto(photo.id, target) } }, onDelete = { deletePhoto = it }, onImage = { imagePreview = it })
                }
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    storyEditor?.let { edit -> SpaceStoryEditor(edit, busy, onClose = { storyEditor = null }, onSave = { title, prompt, body, paper ->
        task {
            if (edit.field == "body" && edit.story != null) store.writeStoryBody(edit.story.id, body, edit.story.revision)
            else store.saveHumanStory(title, prompt, paper, edit.story?.id, edit.story?.revision)
            storyEditor = null
        }
    }) }
    deleteStory?.let { story -> SpaceDeleteDialog("删除番外《${story.title}》？", busy, { deleteStory = null }) { task { store.deleteStory(story.id, story.revision); deleteStory = null } } }
    deletePhoto?.let { photo -> SpaceDeleteDialog("从照片墙删除这张照片？", busy, { deletePhoto = null }) { task { store.deletePhoto(photo.id); deletePhoto = null } } }
    deletePost?.let { post -> SpaceDeleteDialog("删除这条动态及其点赞、评论？", busy, { deletePost = null }) { task { store.deletePost(post.id, "human"); deletePost = null } } }
    if (composePost) SpacePostComposer(store, busy, { composePost = false }) { text, ids -> task { store.publishPost("human", text, ids); composePost = false } }
    reply?.let { target ->
        var text by remember(target) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) reply = null }, title = { Text(if (target.comment == null) "写评论" else "回复${if (target.comment.actor == "human") humanName else aiName}") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, placeholder = { Text("把想说的话留在这里……") }) },
            confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = { task { store.commentPost(target.post.id, "human", text, target.comment?.id); reply = null } }) { Text("发送") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { reply = null }) { Text("取消") } })
    }
    imagePreview?.let { id -> Dialog(onDismissRequest = { imagePreview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                AsyncImage(model = runCatching { store.mediaFile(id) }.getOrNull(), contentDescription = "照片原图", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                TextButton(onClick = { imagePreview = null }, modifier = Modifier.align(Alignment.TopEnd)) { Text("关闭", color = Color.White) }
            }
        }
    } }
}

@Composable
internal fun SecretBaseContent(state: CompanionSpaceSnapshot, busy: Boolean, onEdit: (SpaceStory?, String) -> Unit, onDelete: (SpaceStory) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Surface(onClick = { onEdit(null, "prompt") }, modifier = Modifier.height(244.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center) { Text("✧", fontSize = 36.sp); Spacer(Modifier.height(14.dp)); Text("写一封番外邀请", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("主线之外，给想象留一页空白。", style = MaterialTheme.typography.bodySmall) }
        } }
        items(state.stories.sortedByDescending { it.updatedAt }, key = { it.id }) { story ->
            var revealed by remember(story.id) { mutableStateOf(false) }
            var drag by remember(story.id) { mutableFloatStateOf(0f) }
            Box(Modifier.pointerInput(story.id) { detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                change.consume(); drag += amount; if (drag < -70) revealed = true; if (drag > 70) revealed = false
            }) }) {
                SpacePaper(story.paper, Modifier.fillMaxWidth().heightIn(min = 244.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✦", fontSize = 24.sp); Spacer(Modifier.width(6.dp)); Text(story.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(14.dp))
                        StoryCollapsedField("原番外指令", story.prompt) { onEdit(story, "prompt") }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LocalContentColor.current.copy(alpha = .15f))
                        StoryCollapsedField("番外正文", story.body.ifBlank { "等待故事落笔……" }) { onEdit(story, "body") }
                        Spacer(Modifier.height(12.dp))
                        if (revealed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { revealed = false }) { Text("收起") }
                            TextButton(enabled = !busy, onClick = { onDelete(story) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        } else Text("向左滑动可删除", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { revealed = true }, color = LocalContentColor.current.copy(alpha = .55f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryCollapsedField(title: String, content: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = .7f))
        Spacer(Modifier.height(5.dp))
        Text(content.substringBefore('\n').substringBefore('。').let { if (it.length < content.length) "$it…" else it }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpacePaper(paper: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val background = when (paper) { "kraft" -> Color(0xFFE8D6B6); "floral" -> Color(0xFFF9F0F2); "journal" -> Color(0xFFF0F2E8); "midnight" -> Color(0xFF252A43); else -> Color(0xFFFBF7EE) }
    val ink = if (paper == "midnight") Color(0xFFF5EBD9) else Color(0xFF493F43)
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = background, contentColor = ink, shadowElevation = 2.dp) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                when (paper) {
                    "letter" -> { var y = 52.dp.toPx(); while (y < size.height) { drawLine(ink.copy(alpha = .06f), Offset(0f, y), Offset(size.width, y), 1f); y += 26.dp.toPx() } }
                    "journal" -> { var x = 0f; while (x < size.width) { var y = 0f; while (y < size.height) { drawCircle(ink.copy(alpha = .10f), 1f, Offset(x, y)); y += 16.dp.toPx() }; x += 16.dp.toPx() } }
                    "floral" -> { for (i in 0..5) drawCircle(Color(0xFFD1A0AD).copy(alpha = .14f), 16.dp.toPx(), Offset(size.width - 18.dp.toPx() + (i % 2) * 15.dp.toPx(), (i * 43).dp.toPx())) }
                    "midnight" -> { for (i in 0..18) drawCircle(Color(0xFFEBD5A7).copy(alpha = .18f), (1 + i % 2).toFloat(), Offset(size.width * ((i * 37 % 97) / 97f), size.height * ((i * 23 % 89) / 89f))) }
                    else -> { drawLine(ink.copy(alpha = .13f), Offset(14.dp.toPx(), 0f), Offset(14.dp.toPx(), size.height), 1f) }
                }
            }
            content()
        }
    }
}

@Composable
internal fun SpaceStoryEditor(edit: StoryEditor, busy: Boolean, onClose: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember(edit) { mutableStateOf(edit.story?.title.orEmpty()) }
    var prompt by remember(edit) { mutableStateOf(edit.story?.prompt.orEmpty()) }
    var body by remember(edit) { mutableStateOf(edit.story?.body.orEmpty()) }
    var paper by remember(edit) { mutableStateOf(edit.story?.paper ?: "letter") }
    val isBody = edit.field == "body"
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = onClose) { Text("返回") }
                    Text(if (isBody) "番外正文" else "原番外指令", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !busy && (isBody || title.isNotBlank() && prompt.isNotBlank()), onClick = { onSave(title, prompt, body, paper) }) { Text("保存") }
                }
                if (!isBody) {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("space-story-title"), label = { Text("番外标题") }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        spacePapers.forEach { (key, name) -> FilterChip(selected = key == paper, onClick = { paper = key }, label = { Text(name) }) }
                    }
                }
                SpacePaper(paper, Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                    OutlinedTextField(value = if (isBody) body else prompt, onValueChange = { if (isBody) body = it else prompt = it },
                        modifier = Modifier.fillMaxSize().testTag("space-story-text"), placeholder = { Text(if (isBody) "AI 的番外故事会写在这里，你也可以编辑。" else "写下人物、世界与情节的起点……\n这部分只由你编辑，AI 可以阅读但不能改写。") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = LocalContentColor.current, unfocusedTextColor = LocalContentColor.current, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
                }
            }
        }
    }
}

@Composable
internal fun SharedSpaceContent(state: CompanionSpaceSnapshot, store: OrbisCompanionSpacesStore,
    humanName: String, humanAvatar: Avatar, aiName: String, aiAvatar: Avatar, busy: Boolean,
    onCover: () -> Unit, onLike: (SpacePost) -> Unit, onComment: (SpacePost, SpaceComment?) -> Unit, onDelete: (SpacePost) -> Unit, onImage: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    fun name(actor: String) = if (actor == "human") humanName else aiName
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(270.dp)) {
                Box(Modifier.fillMaxWidth().height(232.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer))).clickable(enabled = !busy, onClick = onCover)) {
                    state.coverMediaId?.let { AsyncImage(model = runCatching { store.mediaFile(it) }.getOrNull(), contentDescription = "共同空间封面，点击更换", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: Column(Modifier.align(Alignment.Center)) { Text("✦  OUR LITTLE UNIVERSE  ✦", letterSpacing = 2.sp, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(10.dp)); Text("把平凡的日子，留在一起。", style = MaterialTheme.typography.titleMedium) }
                    Text("轻触更换封面", Modifier.align(Alignment.TopEnd).padding(12.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .8f), RoundedCornerShape(12.dp)).padding(8.dp), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(humanName, fontWeight = FontWeight.Bold, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(10.dp)).padding(10.dp))
                    Spacer(Modifier.width(12.dp)); SpaceAvatar(humanName, humanAvatar, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)).border(3.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)))
                }
            }
        }
        if (state.posts.isEmpty()) item { Text("还没有动态。发布第一条，或邀请 $aiName 留下一段日常。", Modifier.padding(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.posts.sortedByDescending { it.createdAt }, key = { it.id }) { post ->
            var expanded by remember(post.id) { mutableStateOf(false) }
            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                SpaceAvatar(name(post.actor), if (post.actor == "human") humanAvatar else aiAvatar, Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name(post.actor), color = accent, fontWeight = FontWeight.SemiBold)
                    if (post.text.isNotEmpty()) SelectionContainer { Text(post.text, Modifier.padding(vertical = 7.dp).clickable { expanded = !expanded }, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis) }
                    if (post.text.length > 200) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "全文") }
                    SpacePostImages(post.imageIds, store, onImage)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(post.createdAt)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !busy, onClick = { onLike(post) }, contentPadding = PaddingValues(5.dp)) { Text(if ("human" in post.likes) "♥" else "♡", fontSize = 23.sp) }
                        TextButton(enabled = !busy, onClick = { onComment(post, null) }, contentPadding = PaddingValues(5.dp)) { Text("评论") }
                        TextButton(enabled = !busy, onClick = { onDelete(post) }, contentPadding = PaddingValues(5.dp)) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (post.likes.isNotEmpty() || post.comments.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            if (post.likes.isNotEmpty()) Text("♡  " + post.likes.joinToString("、", transform = ::name), color = accent, style = MaterialTheme.typography.bodySmall)
                            if (post.likes.isNotEmpty() && post.comments.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            post.comments.forEach { comment ->
                                val target = comment.replyTo?.let { id -> post.comments.firstOrNull { it.id == id } }
                                Text("${name(comment.actor)}${target?.let { " 回复 ${name(it.actor)}" }.orEmpty()}：${comment.text}", Modifier.fillMaxWidth().clickable(enabled = !busy) { onComment(post, comment) }.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        item { Spacer(Modifier.height(36.dp)) }
    }
}

@Composable
private fun SpacePostImages(ids: List<String>, store: OrbisCompanionSpacesStore, onImage: (String) -> Unit) {
    if (ids.size == 1) AsyncImage(model = runCatching { store.mediaFile(ids.first()) }.getOrNull(), contentDescription = "动态配图，点击放大", contentScale = ContentScale.Crop,
        modifier = Modifier.widthIn(max = 250.dp).heightIn(max = 310.dp).aspectRatio(.9f).clip(RoundedCornerShape(5.dp)).clickable { onImage(ids.first()) })
    else ids.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { id -> AsyncImage(model = runCatching { store.mediaFile(id) }.getOrNull(), contentDescription = "动态配图，点击放大", contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).clickable { onImage(id) }) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SpacePostComposer(store: OrbisCompanionSpacesStore, busy: Boolean, onClose: () -> Unit, onSave: (String, List<String>) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }; var ids by remember { mutableStateOf<List<String>>(emptyList()) }; var importing by remember { mutableStateOf(false) }; var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { importing = true; scope.launch {
            try {
                val result = mutableListOf<String>(); var failed = 0
                for (uri in uris.take(9 - ids.size)) {
                    try { result += importCompanionSpaceImage(context, store, uri).id }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed++ }
                }
                ids = (ids + result).distinct()
                if (failed > 0) notice = "$failed 张图片未加入，请检查格式、大小和剩余空间。已加入的图片保留。"
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = companionSpaceError(failure) }
            finally { importing = false }
        } }
    }
    Dialog(onDismissRequest = { if (!busy && !importing) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy && !importing, onClick = onClose) { Text("取消") }; Text("新动态", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Button(enabled = !busy && !importing && (text.isNotBlank() || ids.isNotEmpty()), onClick = { onSave(text, ids) }) { Text("发表") }
                }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(vertical = 18.dp), minLines = 6, placeholder = { Text("这一刻，想和 TA 分享什么？") })
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ids.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { id -> Box(Modifier.weight(1f).aspectRatio(1f)) {
                        AsyncImage(model = runCatching { store.mediaFile(id) }.getOrNull(), contentDescription = "待发表配图", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                        TextButton(enabled = !busy, onClick = { ids = ids - id }, modifier = Modifier.align(Alignment.TopEnd)) { Text("移除", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .5f))) }
                    } }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }; Spacer(Modifier.height(8.dp)) }
                OutlinedButton(enabled = !busy && !importing && ids.size < 9, onClick = { picker.launch(arrayOf("image/*")) }) { Text("＋ 添加图片（${ids.size}/9）") }
                Text("仅保存在当前助手的共同空间，双方可见。不发布到外部社交平台。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
            }
        }
    }
}

@Composable
internal fun PhotoWallContent(state: CompanionSpaceSnapshot, store: OrbisCompanionSpacesStore, busy: Boolean,
    onStyle: (String) -> Unit, onAdd: () -> Unit, onNote: (SpacePhoto, String) -> Unit, onMove: (SpacePhoto, Int) -> Unit, onDelete: (SpacePhoto) -> Unit, onImage: (String) -> Unit) {
    var editing by rememberSaveable(store) { mutableStateOf(false) }
    BackHandler(enabled = editing) { if (!busy) editing = false }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .18f))) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spaceWalls.forEach { (key, title) -> FilterChip(selected = state.wallStyle == key, enabled = !busy, onClick = { onStyle(key) }, label = { Text(title) }) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "编辑照片 · 备注单独保存，排序自动保存" else "${state.photos.size} 张照片 · 轻触查看完整原图",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = { editing = !editing }, modifier = Modifier.testTag("space-photo-edit-toggle")) {
                Text(if (editing) "完成" else "编辑")
            }
        }
        if (!editing) {
            OrbisPhotoWallDisplay(state.photos, state.wallStyle, store, !busy, onAdd, onImage)
            return@Column
        }
        LazyVerticalGrid(columns = GridCells.Fixed(if (state.wallStyle == "album") 2 else 2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(if (state.wallStyle == "hanging") 32.dp else 20.dp)) {
            items(state.photos, key = { it.id }) { photo ->
                val index = state.photos.indexOf(photo)
                var flipped by remember(photo.id) { mutableStateOf(false) }
                var note by remember(photo.id, photo.revision) { mutableStateOf(photo.note) }
                val angle by animateFloatAsState(if (flipped) 180f else 0f, label = "photoFlip")
                val isBack = angle >= 90f
                val tilt = if (state.wallStyle == "collage") (if (index % 2 == 0) -3f else 3f) else 0f
                Column(Modifier.rotate(tilt)) {
                    if (state.wallStyle == "hanging") Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                        HorizontalDivider(color = Color(0xFFB9A78A)); Box(Modifier.size(12.dp, 24.dp).background(Color(0xFFCBB38E), RoundedCornerShape(2.dp)))
                    }
                    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 235.dp).graphicsLayer { rotationY = angle; cameraDistance = 16 * density }, shape = RoundedCornerShape(if (state.wallStyle == "album") 14.dp else 3.dp), shadowElevation = 5.dp, color = Color(0xFFFFFCF5), contentColor = Color(0xFF493F43)) {
                        Column(Modifier.graphicsLayer { rotationY = if (isBack) 180f else 0f }.padding(if (state.wallStyle == "album") 6.dp else 10.dp)) {
                            if (isBack) {
                                TextButton(onClick = { flipped = false }, contentPadding = PaddingValues(0.dp)) { Text("↩ 照片正面") }
                                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("space-photo-note-${photo.id}"), placeholder = { Text("把这一刻写在背面……") }, minLines = 3, maxLines = 6,
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color(0xFF493F43), unfocusedTextColor = Color(0xFF493F43)))
                                TextButton(enabled = !busy && note != photo.note, onClick = { onNote(photo, note) }) { Text("保存备注") }
                            } else {
                                PhotoWallImage(photo, store, Modifier.fillMaxWidth().clickable { flipped = true }, "照片，点击翻到背面")
                                Text(photo.note.ifBlank { "轻触照片 · 写下这一刻" }, Modifier.fillMaxWidth().clickable { flipped = true }.padding(top = 10.dp, bottom = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(enabled = !busy && index > 0, onClick = { onMove(photo, index - 1) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("← 前移", style = MaterialTheme.typography.labelSmall) }
                        TextButton(enabled = !busy && index < state.photos.lastIndex, onClick = { onMove(photo, index + 1) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("后移 →", style = MaterialTheme.typography.labelSmall) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onImage(photo.mediaId) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("放大", style = MaterialTheme.typography.labelSmall) }
                        TextButton(enabled = !busy, onClick = { onDelete(photo) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item { Surface(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(220.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .65f), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Text("＋", fontSize = 38.sp); Text("留一个位置", style = MaterialTheme.typography.titleMedium); Text("从本地选择照片", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
}

@Composable
private fun SpaceDeleteDialog(title: String, busy: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(title) }, text = { Text("仅影响当前助手的这个空间，不删除手机原图或聊天。请确认后再继续。") },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("取消") } })
}

/** Read-only inherited avatars need neither the avatar editor nor its file-manager injection. */
@Composable
private fun SpaceAvatar(name: String, avatar: Avatar, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        when (avatar) {
            is Avatar.Image -> AsyncImage(model = avatar.url, contentDescription = "$name 的头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            is Avatar.Emoji -> Text(avatar.content, fontSize = 24.sp)
            Avatar.Dummy -> Text(name.take(1).ifBlank { "✦" }, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
