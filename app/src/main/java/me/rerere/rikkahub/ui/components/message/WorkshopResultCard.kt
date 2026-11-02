package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.isImageWorkshopSource
import me.rerere.rikkahub.data.orbis.workshop.ImageWorkshop
import me.rerere.rikkahub.data.orbis.workshop.WorkshopJob
import me.rerere.rikkahub.data.orbis.workshop.WorkshopStatus
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage

/** Generated originals stay visible independently of the model reply and event-details fold. */
@Composable
internal fun WorkshopResultCard(id: String, images: List<String>, source: String, eventId: String,
    assistantId: String?,
    modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // The host result notification is model-facing receipt data, not the image prompt.
    // Resolve the original submission from its local job; never infer it from the AI's reply
    // or re-send an image request just to populate this display.
    val job by produceState<WorkshopJob?>(null, source, eventId, assistantId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!isImageWorkshopSource(source) || assistantId == null) return@runCatching null
                val jobId = source.removePrefix("image_workshop.")
                if (eventId != "result-$jobId") return@runCatching null
                ImageWorkshop.open(context).ledger.get(jobId)?.takeIf { it.assistantId == assistantId && !it.deleted }
            }.getOrNull()
        }
    }
    Column(modifier.fillMaxWidth().testTag("workshop-result-$id"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (images.isEmpty()) "生图工坊 · 制作结果" else "生图工坊 · 图片已送达",
            style = MaterialTheme.typography.labelMedium)
        images.forEachIndexed { index, uri ->
            ZoomableAsyncImage(model = uri, contentDescription = "工坊原图 ${index + 1}，点按查看与保存",
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 160.dp, max = 560.dp)
                    .clip(MaterialTheme.shapes.large).testTag("workshop-result-image-$index"))
        }
        if (images.isNotEmpty()) Text("点按查看 / 保存原图 · 已保存在生图工坊", style = MaterialTheme.typography.bodySmall)
        if (images.isEmpty()) Text(when (job?.status) {
            WorkshopStatus.COMPLETE -> "原图已保存在生图工坊，当前未能加载。"
            WorkshopStatus.FAILED, WorkshopStatus.INTERRUPTED, WorkshopStatus.CANCELLED ->
                "本次制作未完成，可到生图工坊查看原因；不会自动重新制作。"
            else -> "可到生图工坊查看制作状态。"
        }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起生成提示词" else "查看生成提示词")
        }
        if (expanded) {
            val prompt = job?.prompt
            if (prompt != null) {
                SelectionContainer { Text(prompt, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(prompt)) }) { Text("复制提示词") }
            } else Text("未找到这张图的原始提示词，可能尚未载入或制作记录已被移除。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
