package me.rerere.rikkahub.ui.pages.orbis.workshop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.workshop.*
import java.io.File

@Composable
internal fun WorkshopReferenceSection(
    references: List<WorkshopReference>, selected: List<String>, enabled: Boolean,
    assetFile: (String) -> File, onSelect: (String) -> Unit,
    onCreate: () -> Unit, onEdit: (WorkshopReference) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("人物参考", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCreate, enabled = enabled) { Text("添加人物") }
        }
        Text("存好名字与照片，下次直接选用。允许伙伴使用后，当前伙伴换聊天也能按名字调用。", style = MaterialTheme.typography.bodySmall)
        if (references.isEmpty()) Text("例如：我、阿止。每个人物保存一张清晰参考图。", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(references, key = { it.id }) { reference ->
                OutlinedCard(Modifier.width(136.dp)) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = assetFile(reference.asset.file), contentDescription = reference.name,
                            modifier = Modifier.size(104.dp), contentScale = ContentScale.Fit)
                        FilterChip(selected = reference.id in selected, onClick = { onSelect(reference.id) },
                            enabled = enabled, label = { Text(reference.name) })
                        Text(if (reference.allowAi) "伙伴可用" else "仅我使用", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { onEdit(reference) }, enabled = enabled) { Text("编辑 ${reference.name}") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkshopReferenceEditor(
    workshop: ImageWorkshop, owner: String, initial: WorkshopReference?, seed: WorkshopAsset?, busy: Boolean, saveError: String?,
    onDismiss: () -> Unit, onSave: (WorkshopReference, Long?) -> Unit, onDelete: (String) -> Unit,
) {
    var name by rememberSaveable(initial?.id, seed?.file) { mutableStateOf(initial?.name ?: "") }
    var allowAi by rememberSaveable(initial?.id, seed?.file) { mutableStateOf(initial?.allowAi ?: true) }
    var asset by remember(initial?.id, seed?.file) { mutableStateOf(initial?.asset ?: seed) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { asset = withContext(Dispatchers.IO) { workshop.importReference(uri) }; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "图片未能导入，请使用 PNG、JPEG 或 WebP，每张不超过 24MB。" }
            finally { importing = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy && !importing) onDismiss() },
        title = { Text(if (initial == null) "添加人物参考" else "编辑人物参考") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            asset?.let { AsyncImage(model = workshop.assetFile(it.file), contentDescription = "人物参考预览",
                modifier = Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) }
            OutlinedButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !busy && !importing) {
                Text(if (asset == null) "选择照片" else "更换照片")
            }
            OutlinedTextField(value = name, onValueChange = { name = it.take(32) }, singleLine = true,
                label = { Text("人物名字，例如：我、阿止") }, enabled = !busy && !importing)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("允许当前伙伴跨聊天使用", Modifier.weight(1f))
                Switch(checked = allowAi, onCheckedChange = { allowAi = it }, enabled = !busy && !importing)
            }
            Text("选用时会把原图传给你所选的生图服务。其他伙伴看不到这份参考；相似度取决于模型。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (initial != null) TextButton(onClick = { confirmDelete = true }, enabled = !busy && !importing) { Text("删除这份参考") }
        } },
        confirmButton = { TextButton(enabled = asset != null && name.isNotBlank() && !busy && !importing,
            onClick = { onSave(initial?.copy(name = name, asset = asset!!, allowAi = allowAi)
                ?: WorkshopReference(assistantId = owner, name = name, asset = asset!!, allowAi = allowAi), initial?.revision) }) { Text("保存参考") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy && !importing) { Text("取消") } })
    if (confirmDelete && initial != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除“${initial.name}”的参考？") },
        text = { Text("以后不能再按这个名字选用。已经生成的作品保留，尚未发出的关联制作会停止。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial.id) }, enabled = !busy) { Text("删除参考") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
