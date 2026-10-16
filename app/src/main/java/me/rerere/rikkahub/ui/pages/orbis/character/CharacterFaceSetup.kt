package me.rerere.rikkahub.ui.pages.orbis.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.character.*

/** Human-only compatible-face calibration. Original assets remain untouched. */
@Composable
internal fun CharacterFaceSetup(store: CharacterStore, profile: CharacterProfile, onDismiss: () -> Unit, onSave: (CharacterProfile) -> Unit) {
    val assets = profile.presentation.assets.filter { it.kind in setOf(CharacterAssetKind.POSE, CharacterAssetKind.EXPRESSION) }
    var selected by remember { mutableStateOf(assets.firstOrNull()?.id) }
    val asset = assets.firstOrNull { it.id == selected }
    var anchor by remember(selected) { mutableStateOf(asset?.faceAnchor ?: CharacterFaceAnchor("my-character", .20f, .35f, .65f, .24f)) }
    var bitmap by remember(selected) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(selected) { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (asset != null) runCatching { withContext(Dispatchers.IO) { val bytes = store.assetBytes(asset); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }
            .onSuccess { bitmap = it; failure = it == null }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failure = true }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Q版面部配准", style = MaterialTheme.typography.titleLarge)
                Text("仅用于同一人物、同一画风。给动作与表情设置相同配准组，分别框住眼眉、嘴和面颊，不要包含手或肩膀。点头只替换面部。", style = MaterialTheme.typography.bodySmall)
                if (assets.isEmpty()) Text("请先导入动作和表情图。")
                FlowRow { assets.forEach { a -> TextButton(onClick = { selected = a.id }) { Text((if (selected == a.id) "● " else "") + a.id) } } }
                if (asset != null) {
                    val region = CharacterRegion("face", "面部", anchor.x, anchor.y, anchor.width, anchor.height)
                    bitmap?.let { image -> CharacterPortrait(image, listOf(region), true, selected = "face", onMove = { dx, dy ->
                        anchor = anchor.copy(x = (anchor.x + dx).coerceIn(0f, 1 - anchor.width), y = (anchor.y + dy).coerceIn(0f, 1 - anchor.height))
                    }, modifier = Modifier.fillMaxWidth().height(260.dp)) }
                    if (failure) Text("图片无法读取，未更改配置。")
                    OutlinedTextField(anchor.group, { if (it.length <= 48) anchor = anchor.copy(group = it) }, label = { Text("配准组（小写字母、数字、短横线）") })
                    Text("面部宽度"); Slider(anchor.width, { anchor = anchor.copy(width = it, x = anchor.x.coerceAtMost(1 - it)) }, valueRange = .03f..1f)
                    Text("面部高度"); Slider(anchor.height, { anchor = anchor.copy(height = it, y = anchor.y.coerceAtMost(1 - it)) }, valueRange = .03f..1f)
                    fun save(face: CharacterFaceAnchor?) = onSave(profile.copy(presentation = profile.presentation.copy(assets = profile.presentation.assets.map { if (it.id == asset.id) it.copy(faceAnchor = face) else it })))
                    Button(enabled = !failure && bitmap != null && anchor.group.matches(Regex("[a-z][a-z0-9_-]{0,47}")), onClick = { save(anchor) }) { Text("保存此图配准") }
                    TextButton(onClick = { save(null) }) { Text("移除此图配准") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
