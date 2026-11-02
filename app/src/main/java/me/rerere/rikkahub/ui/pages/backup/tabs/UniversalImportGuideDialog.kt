package me.rerere.rikkahub.ui.pages.backup.tabs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.sync.importer.UniversalChatGuide

@Composable
internal fun UniversalImportGuideDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    UniversalImportGuideContent(copied, onClose) { label, text ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        copied = label
    }
}

@Composable
internal fun UniversalImportGuideContent(copied: String?, onClose: () -> Unit, onCopy: (String, String) -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("通用聊天记录 · 格式说明") }, text = {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("把转换提示词和聊天导出文件交给你选择的AI，得到通用JSON后回到这里导入。")
                Text(UniversalChatGuide.instructions)
                Text("最小示例", style = MaterialTheme.typography.titleSmall)
                Text(UniversalChatGuide.example, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        Column {
            TextButton(modifier = Modifier.testTag("universal-copy-prompt"), onClick = { onCopy("AI转换提示词", UniversalChatGuide.conversionPrompt) }) { Text("复制AI转换提示词") }
            TextButton(modifier = Modifier.testTag("universal-copy-example"), onClick = { onCopy("JSON示例", UniversalChatGuide.example) }) { Text("复制JSON示例") }
            copied?.let { Text("已复制：$it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("universal-copy-result")) }
        }
    }, dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
