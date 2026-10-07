package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar as LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold as Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val folders = remember { listOf(FileFolders.UPLOAD) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var showCleanSheet by remember { mutableStateOf(false) }
    var selectedCleanRange by remember { mutableStateOf(CleanRange.DAYS_7) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())
    val protectionRevision by filesManager.protectionRevision.collectAsState()
    var locked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var protectionReady by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingBatch by remember { mutableStateOf<List<ManagedFileEntity>?>(null) }
    var pendingUnlock by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(files, protectionRevision) {
        protectionReady = false
        try { locked = filesManager.lockedPaths(); protectionReady = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { toaster.show("无法读取附件保护状态；已暂停删除，不会清空原文件。") }
        selected = selected.intersect(files.map { it.id }.toSet())
    }
    fun toggleLock(file: ManagedFileEntity) {
        if (busy || !protectionReady) return
        if (file.relativePath in locked) { pendingUnlock = file; return }
        scope.launch {
            busy = true
            try { filesManager.setLocked(file, true); toaster.show("已锁定，批量清理会跳过这个附件。") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toaster.show("未能保存锁定状态，原文件未删除。") }
            finally { busy = false }
        }
    }
    pendingUnlock?.let { file -> AlertDialog(onDismissRequest = { if (!busy) pendingUnlock = null },
        title = { Text("解锁这个附件？") },
        text = { Text("${file.displayName}\n解锁后可被单独删除或批量清理。若它仍作为头像或壁纸使用，删除后对应图片会不可用。解锁本身不会删除文件。") },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingUnlock = null }) { Text("保持锁定") } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { filesManager.setLocked(file, false); pendingUnlock = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toaster.show("解锁未保存，原文件未删除。") }
            finally { busy = false }
        } }) { Text("确认解锁") } }) }
    pendingBatch?.let { targets -> AlertDialog(onDismissRequest = { if (!busy) pendingBatch = null },
        title = { Text("删除选中的 ${targets.size} 个未锁定附件？") },
        text = { Text("已锁定的附件会跳过。删除可能使历史消息中的附件不可用，且无法撤销。") },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingBatch = null }) { Text("取消") } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            var removed = 0
            try {
                targets.forEach { if (filesManager.delete(it.id)) removed++ }
                toaster.show("已删除 $removed 个；其余锁定或未能删除的附件保持原样。")
                selected = emptySet(); pendingBatch = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toaster.show("已删除 $removed 个，清理已停止；剩余文件保留。") }
            finally { busy = false }
        } }) { Text("删除") } }) }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try { toaster.show(if (filesManager.delete(target.id, deleteFromDisk = true)) deletedToast else "附件已锁定或未能删除，文件保留。") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { toaster.show(deleteFailedToast) }
                            finally { pendingDelete = null; busy = false }
                        }
                    }, enabled = !busy && protectionReady
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanSheet) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showCleanSheet = false },
            sheetState = sheetState,
        ) {
            CleanFilesSheet(
                selectedRange = selectedCleanRange,
                onRangeSelected = { selectedCleanRange = it },
                onClean = {
                    showCleanSheet = false
                    scope.launch {
                        busy = true
                        try { val ok = selectedCleanRange.days?.let { days ->
                            filesManager.deleteOlderThan(
                                folder = selectedFolder,
                                cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong()),
                            )
                        } ?: filesManager.deleteAll(selectedFolder)
                        toaster.show(if (ok) "已清理未锁定附件，锁定项目保留。" else cleanFailedToast)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { toaster.show("清理已停止，未能处理的附件保留。") }
                        finally { busy = false }
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(enabled = !busy, onClick = { selecting = !selecting; selected = emptySet() }) {
                        Text(if (selecting) "完成" else "多选")
                    }
                    IconButton(
                        onClick = { showCleanSheet = true },
                        enabled = files.isNotEmpty() && protectionReady && !busy,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it }
            )
            Text("头像与已使用的壁纸默认锁定；长按附件可锁定或解锁。清空全部也会保留锁定项目。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = protectionReady && !busy, onClick = {
                    selected = files.filter { it.relativePath !in locked }.map { it.id }.toSet()
                }) { Text("全选未锁定") }
                TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text("取消选择") }
                TextButton(enabled = protectionReady && !busy && selected.isNotEmpty(), onClick = {
                    pendingBatch = files.filter { it.id in selected && it.relativePath !in locked }
                }) { Text("删除 ${selected.size}") }
            }

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            locked = file.relativePath in locked || !protectionReady,
                            selecting = selecting, selected = file.id in selected,
                            enabled = !busy && protectionReady,
                            onSelect = { selected = if (file.id in selected) selected - file.id else selected + file.id },
                            onLock = { toggleLock(file) },
                            onDelete = { pendingDelete = file }
                        )
                    }
                }
            }
        }
    }
}

private enum class CleanRange(val days: Int?) {
    DAYS_7(7),
    DAYS_14(14),
    DAYS_30(30),
    ALL(null),
}

@Composable
private fun CleanFilesSheet(
    selectedRange: CleanRange,
    onRangeSelected: (CleanRange) -> Unit,
    onClean: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_files_page_clean_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("只清理未锁定附件；锁定的头像、壁纸及你手动保护的文件不会被删除。",
            style = MaterialTheme.typography.bodySmall)
        Text(
            text = stringResource(R.string.setting_files_page_clean_range_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        CleanRange.entries.forEach { range ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRangeSelected(range) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                )
                Text(
                    text = range.days?.let {
                        stringResource(R.string.setting_files_page_clean_older_than_days, it)
                    } ?: stringResource(R.string.setting_files_page_clean_all),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        TextButton(
            onClick = onClean,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.setting_files_page_clean_action))
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    else -> folder
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    locked: Boolean,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onLock: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled,
            onClick = { if (selecting && !locked) onSelect() }, onLongClick = onLock, onLongClickLabel = "锁定或解锁附件"),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() },
                    enabled = enabled && !locked, modifier = Modifier.align(Alignment.TopStart))
                IconButton(
                    onClick = onDelete,
                    enabled = enabled && !locked,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                TextButton(enabled = enabled, onClick = onLock) { Text(if (locked) "已锁定 · 解锁" else "未锁定 · 锁定") }
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
