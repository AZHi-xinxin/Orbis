package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRCorrectionResult

@Composable
fun AsrCorrectionReview(
    review: ASRCorrectionResult?,
    modifier: Modifier = Modifier,
    onUseOriginal: (() -> Unit)? = null,
    eventId: Long = 0,
    dismissed: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    contentColor: Color = LocalContentColor.current,
) {
    var showing by rememberSaveable(eventId) { mutableStateOf(false) }
    var consumed by rememberSaveable(eventId) { mutableStateOf(false) }
    if (review?.changed != true || dismissed || consumed) return
    val close = { showing = false; consumed = true; onDismiss?.invoke(); Unit }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { showing = true }, modifier = Modifier.weight(1f, fill = false)) {
            Text("已纠正称呼 · 核对原识别", color = contentColor)
        }
        IconButton(onClick = close, modifier = Modifier.testTag("asr-correction-dismiss")
            .semantics { contentDescription = "关闭本条称呼纠正提示" }) {
            Text("×", color = contentColor)
        }
    }
    if (showing) AlertDialog(
        onDismissRequest = close,
        title = { Text("语音识别核对") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("原识别：\n${review.original}\n\n纠正后：\n${review.corrected}")
                    Text("只记录本次识别的原文，不含手打前缀；需要时可复制原识别自行修改。")
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("知道了") } },
        dismissButton = { if (onUseOriginal != null) TextButton(onClick = { onUseOriginal(); close() }) { Text("使用原识别") } },
    )
}
