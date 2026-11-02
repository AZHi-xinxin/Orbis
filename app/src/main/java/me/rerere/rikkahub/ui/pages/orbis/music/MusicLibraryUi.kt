package me.rerere.rikkahub.ui.pages.orbis.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.orbis.music.*

@Composable internal fun MusicLibraryLauncher(name: String, close: () -> Unit) {
    val runtime = rememberMusicRuntime() ?: return
    val library by runtime.store.state.collectAsState()
    CompositionLocalProvider(LocalMusicLight provides (library.appearance.style == "mist")) { MusicLibraryPage(runtime, name, close) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MusicLibraryPage(runtime: MusicRuntime, ownerName: String, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by runtime.store.state.collectAsState()
    val playback by runtime.playback.collectAsState()
    val importer = remember { MusicImporter(context.applicationContext, runtime.store) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("歌单") }
    var categories by remember { mutableStateOf(emptySet<String>()) }
    var creator by remember { mutableStateOf("全部") }
    var opened by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<MusicPlaylist?>(null) }
    var creating by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<MusicImportFile>?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var importTask by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var lyricsFor by remember { mutableStateOf<String?>(null) }
    var metadataFor by remember { mutableStateOf<MusicTrack?>(null) }
    var coverFor by remember { mutableStateOf<String?>(null) }
    var songMenu by remember { mutableStateOf<MusicTrack?>(null) }
    var delete by remember { mutableStateOf<MusicPlaylist?>(null) }
    fun prepare(block: suspend () -> List<MusicImportFile>) { scope.launch {
        preparing = true
        try { pending = block() } catch(e: CancellationException) { throw e } catch(e: Exception) { result = friendlyMusicError(e) }
        finally { preparing = false }
    } }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) prepare { importer.files(it) } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri != null) prepare { importer.folder(uri) } }
    val pickLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = lyricsFor; lyricsFor = null
        if(uri != null && id != null) scope.launch { try { val text = importer.lyrics(uri); withContext(Dispatchers.IO) { runtime.store.lyrics(id, text) }; result = "歌词已保存" }
            catch(e: CancellationException) { throw e } catch(e: Exception) { result = friendlyMusicError(e) } }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverFor; coverFor = null
        if(uri != null && id != null) scope.launch { try { importer.playlistCover(id, uri) }
            catch(e: CancellationException) { throw e } catch(e: Exception) { result = friendlyMusicError(e) } }
    }
    val playlist = library.playlists.firstOrNull { it.id == opened }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val light = LocalMusicLight.current
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view, light) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
                }
            }
            onDispose { }
        }
        val colors = if(light) lightColorScheme(primary = MusicMint, onPrimary = androidx.compose.ui.graphics.Color.White,
            secondary = MusicGold, secondaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE9EE), onSecondaryContainer = MusicInk,
            surfaceContainerHigh = Color(0xFFE7EFF4), surfaceContainerHighest = Color(0xFFDCE8EE), outline = MusicMuted.copy(alpha = .45f), outlineVariant = MusicMuted.copy(alpha = .22f),
            background = MusicNight, surface = MusicNight, onSurface = MusicInk, onBackground = MusicInk, onSurfaceVariant = MusicMuted)
        else darkColorScheme(primary = MusicMint, onPrimary = MusicNight, secondary = MusicGold,
            secondaryContainer = androidx.compose.ui.graphics.Color(0xFF2C4453), onSecondaryContainer = MusicMint,
            surfaceContainerHigh = Color(0xFF142737), surfaceContainerHighest = Color(0xFF203747), outline = MusicMuted.copy(alpha = .45f), outlineVariant = MusicMuted.copy(alpha = .22f),
            background = MusicNight, surface = MusicNight, onSurface = MusicInk, onBackground = MusicInk, onSurfaceVariant = MusicMuted)
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize(), color = MusicNight) {
                MusicLibraryBackdrop(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MusicButton("‹", "返回") { if(opened != null) { opened = null; query = "" } else close() }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if(playlist == null) "星河音乐" else "我的歌单", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                                Text(if(playlist == null) "把喜欢的歌，留在星河里" else "${playlist.tracks.size} 首 · ${playlist.creatorName}创建", color = MusicMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if(playlist == null) MusicButton("+", "创建歌单") { creating = true }
                            else Box {
                                var menu by remember { mutableStateOf(false) }
                                MusicButton("⋮", "歌单操作") { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("编辑歌单") }, onClick = { edit = playlist; menu = false })
                                    DropdownMenuItem(text = { Text("更换封面") }, onClick = { coverFor = playlist.id; pickCover.launch(arrayOf("image/*")); menu = false })
                                    DropdownMenuItem(text = { Text("删除歌单") }, onClick = { delete = playlist; menu = false })
                                }
                            }
                        }
                        val songs = (if(playlist != null) playlist.tracks.mapNotNull { id -> library.tracks.firstOrNull { it.id == id } } else library.tracks)
                            .filter { (tab != "收藏" || playlist != null || it.favorite) && (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query, true)) }
                        val playlists = library.playlists.filter { (categories.isEmpty() || it.categories.any { c -> c in categories }) &&
                            (creator == "全部" || if(creator == "我创建") it.creatorId == "human" else it.creatorId.startsWith("ai:")) && it.name.contains(query, true) }
                        LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)) {
                            if(runtime.store.loadError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                Text("曲库记录暂时无法读取，原文件已保留，请反馈后再继续。", color = MusicGold)
                            }
                            if(playlist != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    MusicPlaylistCover(playlist, runtime, Modifier.padding(top = 8.dp).size(170.dp))
                                    Text(playlist.name, Modifier.padding(top = 16.dp), fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text("${playlist.creatorName} 创建 · ${playlist.tracks.size} 首", Modifier.padding(top = 6.dp), color = MusicMuted, fontSize = 12.sp)
                                    if(playlist.description.isNotBlank()) Text(playlist.description, Modifier.padding(top = 10.dp, bottom = 4.dp), color = MusicInk, fontSize = 13.sp, textAlign = TextAlign.Center)
                                    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { runtime.human { runtime.command("play", playlist.tracks) } }, enabled = playlist.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("▶  播放全部") }
                                        OutlinedButton(onClick = { runtime.human { runtime.command("queue", (playback.queue + playlist.tracks).distinct()) } }, enabled = playlist.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("加入队列") }
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        playlist.categories.forEach { value -> SuggestionChip(onClick = { categories = setOf(value); opened = null; query = "" }, label = { Text(value) }) }
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                MusicSearch(query, { query = it }, if(playlist == null) "搜索歌曲、歌手、歌单" else "搜索这张歌单")
                            }
                            if(playlist == null) item(span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { pickFiles.launch(arrayOf("audio/*", "application/ogg")) }, enabled = importTask?.isActive != true && !preparing, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("↓  导入本地音乐") }
                                        OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = importTask?.isActive != true && !preparing, modifier = Modifier.heightIn(min = 48.dp)) { Text("文件夹") }
                                    }
                                }
                            }
                            if(preparing || importTask?.isActive == true) item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if(preparing) "正在读取文件信息…" else progress, Modifier.weight(1f), fontSize = 12.sp, color = MusicMuted)
                                    if(importTask?.isActive == true) TextButton(onClick = { importTask?.cancel(); progress = "已取消，已导入的歌曲保留"; importTask = null }) { Text("取消导入") }
                                }
                            }
                            if(playlist == null) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        listOf("歌单", "歌曲", "收藏").forEach { value -> MusicLibraryTab(value, tab == value) { tab = value } }
                                    }
                                }
                                if(tab == "歌单") {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            (listOf("全部") + library.categories).distinct().forEach { value -> FilterChip(if(value == "全部") categories.isEmpty() else value in categories, { categories = if(value == "全部") emptySet() else if(value in categories) categories - value else categories + value }, label = { Text(value, fontSize = 12.sp) }, shape = CircleShape) }
                                            AssistChip({ categoryDialog = true }, label = { Text("+ 分类", fontSize = 12.sp) }, shape = CircleShape)
                                        }
                                    }
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            listOf("全部", "我创建", "AI 创建").forEach { value -> MusicLibraryTab(if(value == "全部") "全部歌单" else value, creator == value, small = true) { creator = value } }
                                        }
                                    }
                                    if(playlists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMusic("还没有这样的歌单", "导入几首喜欢的歌，再为它们起个名字。\n开启音乐工具后，$ownerName 也可以创建专属歌单。") }
                                    items(playlists, key = { "playlist-${it.id}" }) { p ->
                                        Column(Modifier.fillMaxWidth().clickable { opened = p.id; query = "" }) {
                                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                                MusicPlaylistCover(p, runtime, Modifier.fillMaxSize())
                                                CompositionLocalProvider(LocalMusicLight provides false) {
                                                    MusicButton("▶", "播放${p.name}", Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC102638), CircleShape)) { if(p.tracks.isNotEmpty()) runtime.human { runtime.command("play", p.tracks) } }
                                                }
                                            }
                                            Text(p.name, Modifier.padding(top = 8.dp), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                            Text("${p.creatorName} · ${p.tracks.size} 首", Modifier.padding(top = 3.dp), fontSize = 11.sp, color = MusicMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(p.categories.joinToString(" · "), Modifier.padding(top = 2.dp), fontSize = 10.sp, color = MusicGold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            if(playlist != null || tab != "歌单") {
                                if(songs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMusic("这里还没有歌曲", "支持 MP3、FLAC、M4A 等本地音频。\n文件夹里的同名 LRC 会一起导入。") }
                                items(songs, key = { "track-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { t ->
                                    Row(Modifier.fillMaxWidth().clickable { runtime.human { runtime.command("play", songs.map { it.id }, songs.indexOf(t)) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        MusicCover(t, runtime, 44)
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text((if(playback.trackId == t.id) "♫  " else "") + t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if(playback.trackId == t.id) MusicMint else MusicInk)
                                            Text("${t.artist} · ${musicTime(t.durationMs)}", color = MusicMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        MusicButton("⋮", "${t.title}的操作") { songMenu = t }
                                    }
                                }
                            }
                        }
                        GalaxySurface(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val current = library.tracks.firstOrNull { it.id == playback.trackId }
                                MusicCover(current, runtime, 40)
                                Column(Modifier.weight(1f).clickable { runtime.appearance { it.copy(mode = "expanded") }; close() }.padding(horizontal = 10.dp)) {
                                    Text(current?.title ?: "一起听歌", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if(playback.loading) "准备中" else current?.artist ?: "点开星河播放器", fontSize = 10.sp, color = MusicMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                MusicButton(if(playback.playing) "Ⅱ" else "▶", "播放或暂停") { runtime.human { runtime.command(if(playback.playing) "pause" else "resume") } }
                                MusicButton("❯", "下一首") { runtime.human { runtime.command("next") } }
                            }
                        }
                    }
                }
            }
        if(creating || edit != null) PlaylistEditor(runtime, library, edit, { creating = false; edit = null }) { name, cat, ids, description ->
            val old = edit
            scope.launch { try { withContext(Dispatchers.IO) { runtime.store.playlist(name, cat, "human", "我", ids, old?.id, old?.revision, description) }; creating = false; edit = null }
                catch(e: CancellationException) { throw e } catch(e: Exception) { result = friendlyMusicError(e) } }
        }
        if(categoryDialog) CategoryManager(runtime, library) { categoryDialog = false }
        pending?.let { files -> ImportPreview(files, library, opened, runtime, { pending = null }) { chosen, target ->
            pending = null
            importTask = scope.launch {
                progress = "正在导入…"
                try {
                    val report = importer.import(chosen) { done, total -> scope.launch { progress = "已处理 $done / $total 首" } }
                    if(target != null) withContext(Dispatchers.IO) {
                        val p = runtime.store.state.value.playlists.firstOrNull { it.id == target } ?: error("music_playlist_missing")
                        runtime.store.playlist(p.name, p.categories.joinToString(","), "human", "我", p.tracks + report.ids, p.id, p.revision)
                    }
                    tab = "歌曲"; result = "导入 ${report.added} 首，已有 ${report.duplicate} 首" +
                        if(report.failed.isEmpty()) "。" else "，失败 ${report.failed.size} 首：\n" + report.failed.take(10).joinToString("\n")
                } catch(e: CancellationException) { throw e } catch(e: Exception) { result = friendlyMusicError(e) }
                finally { importTask = null }
            }
        } }
        songMenu?.let { t -> AlertDialog(onDismissRequest = { songMenu = null }, title = { Text(t.title) }, text = { Column {
            TextButton(onClick = { runtime.human { withContext(Dispatchers.IO) { runtime.store.favorite(t.id) } }; songMenu = null }) { Text(if(t.favorite) "取消收藏" else "收藏歌曲") }
            TextButton(onClick = { metadataFor = t; songMenu = null }) { Text("编辑歌曲信息") }
            TextButton(onClick = { lyricsFor = t.id; songMenu = null; pickLyrics.launch(arrayOf("*/*")) }) { Text("导入 LRC 歌词") }
            Text("加入或移出歌单：打开歌单 → 编辑歌单。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { songMenu = null }) { Text("完成") } }) }
        metadataFor?.let { t -> SongMetadataDialog(t, { metadataFor = null }) { title, artist, album ->
            runtime.human { withContext(Dispatchers.IO) { runtime.store.metadata(t.id, title, artist, album) } }; metadataFor = null
        } }
        delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除歌单？") }, text = { Text("删除“${p.name}”，歌曲仍保留在曲库。") }, confirmButton = { TextButton(onClick = {
            runtime.human { withContext(Dispatchers.IO) { runtime.store.deletePlaylist(p.id, "human", p.revision) } }; delete = null; opened = null
        }) { Text("删除歌单") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
        result?.let { text -> AlertDialog(onDismissRequest = { result = null }, title = { Text("星河音乐") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { result = null }) { Text("完成") } }) }
        val error by runtime.error.collectAsState()
        error?.let { text -> AlertDialog(onDismissRequest = runtime::dismissError, title = { Text("星河音乐") }, text = { Text(text) }, confirmButton = { TextButton(onClick = runtime::dismissError) { Text("知道了") } }) }
    }
    }
}

@Composable private fun MusicSearch(query: String, change: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
        .background(MusicMuted.copy(alpha = .07f)).border(1.dp, MusicMuted.copy(alpha = .25f), CircleShape)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕", Modifier.padding(end = 10.dp), color = MusicMuted, fontSize = 22.sp)
        BasicTextField(query, change, Modifier.weight(1f), singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MusicInk), cursorBrush = SolidColor(MusicMint),
            decorationBox = { field -> Box { if(query.isEmpty()) Text(hint, color = MusicMuted, fontSize = 13.sp); field() } })
    }
}

@Composable private fun MusicLibraryTab(label: String, selected: Boolean, small: Boolean = false, select: () -> Unit) {
    Column(Modifier.heightIn(min = 48.dp).clickable(onClick = select).padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(label, color = if(selected) MusicMint else MusicMuted, fontSize = if(small) 12.sp else 15.sp, fontWeight = if(selected) FontWeight.Medium else FontWeight.Normal)
        Box(Modifier.padding(top = 7.dp).width(if(small) 22.dp else 28.dp).height(2.dp).background(if(selected) MusicMint else Color.Transparent, CircleShape))
    }
}

@Composable private fun EmptyMusic(title: String, detail: String) {
    GalaxySurface(Modifier.fillMaxWidth()) { Column(Modifier.padding(28.dp)) { Text("✦", fontSize = 38.sp, color = MusicGold); Spacer(Modifier.height(20.dp)); Text(title, fontSize = 20.sp); Spacer(Modifier.height(12.dp)); Text(detail, color = MusicMuted, fontSize = 13.sp) } }
}
@Composable private fun NameDialog(title: String, label: String, close: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { OutlinedTextField(value, { if(it.length <= 120) value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value.trim()) }, enabled = value.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
@Composable private fun PlaylistEditor(runtime: MusicRuntime, library: MusicLibrary, old: MusicPlaylist?, close: () -> Unit, save: (String, String, List<String>, String) -> Unit) {
    var name by remember { mutableStateOf(old?.name ?: "") }; var category by remember { mutableStateOf(old?.categories?.joinToString(", ") ?: "未分类") }
    var description by remember { mutableStateOf(old?.description ?: "") }
    var selected by remember { mutableStateOf(old?.tracks ?: emptyList()) }; var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(if(old == null) "创建歌单" else "编辑歌单") }, text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicPlaylistCover(old, runtime, Modifier.size(104.dp).align(Alignment.CenterHorizontally))
        OutlinedTextField(name, { if(it.length <= 120) name = it }, label = { Text("歌单名称") }, singleLine = true)
        OutlinedTextField(category, { if(it.length <= 120) category = it }, label = { Text("分类 · 多个用逗号隔开") }, singleLine = true)
        OutlinedTextField(description, { if(it.length <= 2000) description = it }, label = { Text("歌单介绍") }, maxLines = 3)
        Row(Modifier.horizontalScroll(rememberScrollState())) { library.categories.forEach { value -> TextButton(onClick = { category = value }) { Text(value) } } }
        OutlinedTextField(query, { query = it }, label = { Text("选择曲目 · 已选 ${selected.size}") }, singleLine = true)
        LazyColumn(Modifier.height(190.dp)) {
            item { Row(Modifier.horizontalScroll(rememberScrollState())) { selected.forEachIndexed { index, id -> TextButton(onClick = { if(index > 0) selected = selected.toMutableList().also { java.util.Collections.swap(it, index, index - 1) } }, enabled = index > 0) { Text("↑ " + (library.tracks.firstOrNull { it.id == id }?.title ?: ""), maxLines = 1) } } } }
            items(library.tracks.filter { "${it.title} ${it.artist}".contains(query, true) }, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().clickable { selected = if(t.id in selected) selected - t.id else selected + t.id }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(t.id in selected, null); Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } }
    } }, confirmButton = { TextButton(onClick = { save(name, category, selected, description) }, enabled = name.isNotBlank() && category.isNotBlank()) { Text("保存歌单") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
@Composable private fun ImportPreview(files: List<MusicImportFile>, library: MusicLibrary, current: String?, runtime: MusicRuntime, close: () -> Unit, start: (List<MusicImportFile>, String?) -> Unit) {
    var selected by remember { mutableStateOf(files.toSet()) }; var target by remember { mutableStateOf(current) }; var create by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("导入到星河") }, text = { Column {
        Text("复制到 Orbis 本地曲库；相同文件自动去重。预计占用 %.1f MB".format(selected.sumOf { it.bytes } / 1048576.0), style = MaterialTheme.typography.bodySmall)
        Text("音乐副本和歌单保存在本机，当前聊天备份暂不包含音乐库。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(target == null, { target = null }, label = { Text("仅加入曲库") })
            library.playlists.forEach { p -> FilterChip(target == p.id, { target = p.id }, label = { Text(p.name) }) }
        }
        TextButton(onClick = { create = true }) { Text("+ 先建一张歌单") }
        Row { TextButton(onClick = { selected = files.toSet() }) { Text("全选") }; TextButton(onClick = { selected = emptySet() }) { Text("清空选择") } }
        LazyColumn(Modifier.heightIn(max = 280.dp)) { items(files) { f -> Row(Modifier.fillMaxWidth().clickable { selected = if(f in selected) selected - f else selected + f }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(f in selected, null); Column { Text(f.title.ifBlank { f.name }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp); Text(listOf(f.artist, musicTime(f.durationMs), f.name.substringAfterLast('.')).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 10.sp) }
        } } }
        if(files.isEmpty()) Text("这个文件夹没有找到音频。")
    } }, confirmButton = { TextButton(onClick = { start(files.filter { it in selected }, target) }, enabled = selected.any { !it.name.endsWith(".lrc", true) }) { Text("导入所选") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
    if(create) NameDialog("新建歌单", "歌单名称", { create = false }) { name -> runtime.human {
        val p = withContext(Dispatchers.IO) { runtime.store.playlist(name, "未分类", "human", "我", emptyList()) }; target = p.id; create = false
    } }
}

@Composable private fun SongMetadataDialog(track: MusicTrack, close: () -> Unit, save: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf(track.title) }; var artist by remember { mutableStateOf(track.artist) }; var album by remember { mutableStateOf(track.album) }
    AlertDialog(onDismissRequest = close, title = { Text("歌曲信息") }, text = { Column {
        OutlinedTextField(title, { if(it.length <= 120) title = it }, label = { Text("歌曲名") }, singleLine = true)
        OutlinedTextField(artist, { if(it.length <= 120) artist = it }, label = { Text("歌手") }, singleLine = true)
        OutlinedTextField(album, { if(it.length <= 300) album = it }, label = { Text("专辑") }, singleLine = true)
    } }, confirmButton = { TextButton(onClick = { save(title, artist, album) }, enabled = title.isNotBlank() && artist.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable private fun CategoryManager(runtime: MusicRuntime, library: MusicLibrary, close: () -> Unit) {
    var name by remember { mutableStateOf("") }; var original by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("歌单分类") }, text = { Column {
        OutlinedTextField(name, { if(it.length <= 120) name = it }, label = { Text(if(original == null) "新分类" else "重命名分类") }, singleLine = true)
        TextButton(onClick = { runtime.human { withContext(Dispatchers.IO) { if(original == null) runtime.store.category(name) else runtime.store.category(original!!, name) }; name = ""; original = null } }, enabled = name.isNotBlank()) { Text(if(original == null) "创建" else "保存名称") }
        LazyColumn(Modifier.heightIn(max = 280.dp)) { items(library.categories) { category -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(category, Modifier.weight(1f)); TextButton(onClick = { original = category; name = category }) { Text("改名") }
            TextButton(onClick = { val idx = library.categories.indexOf(category); if(idx > 0) runtime.human { withContext(Dispatchers.IO) { runtime.store.reorderCategories(library.categories.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }) } } }, enabled = library.categories.indexOf(category) > 0) { Text("↑") }
        } } }
    } }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}
