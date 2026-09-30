package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.OrbisStickerTextSegment
import me.rerere.rikkahub.data.model.splitOrbisStickerReferences
import me.rerere.rikkahub.data.orbis.OrbisStickers
import me.rerere.rikkahub.data.orbis.AndroidStickerRepository
import java.io.File

/** Visual projection only. Neither the persisted message nor the provider's parts are modified. */
@Composable
internal fun OrbisStickerMessageText(
    content: String,
    modifier: Modifier = Modifier,
    allowReference: (String) -> Boolean = { true },
    renderText: @Composable (String) -> Unit,
) {
    val parsed = remember(content) { splitOrbisStickerReferences(content) }
    if (parsed.none { it is OrbisStickerTextSegment.Reference }) {
        Column(modifier) { renderText(content) }
        return
    }
    // Preserve an existing user-configured visual mask. A regex may suppress/replace an
    // existing reference, but cannot manufacture a new local-file authorization.
    val segments = parsed.map { segment ->
        if (segment is OrbisStickerTextSegment.Reference && !allowReference("(表情包:${segment.id})"))
            OrbisStickerTextSegment.Text("(表情包:${segment.id})") else segment
    }
    if (segments.none { it is OrbisStickerTextSegment.Reference }) {
        Column(modifier) { segments.forEachIndexed { index, segment ->
            key(index) { renderText((segment as OrbisStickerTextSegment.Text).value) }
        } }
        return
    }
    val context = LocalContext.current
    var repository by remember(context.applicationContext) { mutableStateOf<AndroidStickerRepository?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(context.applicationContext) {
        try { repository = withContext(Dispatchers.IO) { OrbisStickers.open(context.applicationContext) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    val readyRepository = repository
    if (readyRepository == null) {
        Column(modifier) {
            Text(if (loadFailed) "本机表情库暂时无法读取；原始消息仍保留。" else "正在读取本机表情…", fontSize = 11.sp)
            // Never give a valid tag snapshot to Markdown, including the first loading frame.
            segments.forEachIndexed { index, segment -> key(index) {
                when (segment) {
                    is OrbisStickerTextSegment.Text -> if (segment.value.isNotBlank()) renderText(segment.value)
                    is OrbisStickerTextSegment.Reference -> {
                        Text("表情 ${segment.id}", fontSize = 12.sp)
                        segment.tags?.let { Text(it.joinToString(" · "), fontSize = 11.sp, lineHeight = 16.sp) }
                    }
                }
            } }
        }
        return
    }
    val state by readyRepository.state.collectAsState()
    val blocked by readyRepository.writeBlocked.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        segments.forEachIndexed { index, segment -> key(index) {
            when (segment) {
                is OrbisStickerTextSegment.Text -> if (segment.value.isNotBlank()) renderText(segment.value)
                is OrbisStickerTextSegment.Reference -> {
                    val sticker = state.stickers.firstOrNull { it.id == segment.id }
                    val file by produceState<File?>(null, readyRepository, sticker, blocked) {
                        value = null
                        if (!blocked) value = withContext(Dispatchers.IO) { readyRepository.fileForId(segment.id) }
                    }
                    if (sticker == null || file == null) {
                        Text("表情 ${segment.id} 暂不在本机表情库中。", fontSize = 12.sp,
                            lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        segment.tags?.let { Text(it.joinToString(" · "), fontSize = 11.sp, lineHeight = 16.sp) }
                    } else {
                        val tags = segment.tags ?: sticker.tags
                        var imageFailed by remember(file) { mutableStateOf(false) }
                        Column(Modifier.widthIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // File is resolved only through the validated local ID allowlist.
                            // It is never derived from a URL, model-produced path or tag text.
                            AsyncImage(model = file,
                                contentDescription = "共享表情 ${segment.id}：${tags.joinToString("，")}",
                                onError = { imageFailed = true }, onSuccess = { imageFailed = false },
                                modifier = Modifier.sizeIn(minWidth = 96.dp, maxWidth = 180.dp).height(144.dp))
                            if (imageFailed) Text("表情图片暂时无法显示。", fontSize = 11.sp, lineHeight = 16.sp)
                            Text(tags.joinToString(" · "), fontSize = 10.sp, lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } }
    }
}
