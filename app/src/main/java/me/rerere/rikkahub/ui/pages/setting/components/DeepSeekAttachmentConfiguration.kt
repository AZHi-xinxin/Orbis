package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.transformers.DeepSeekAttachmentSettings
import me.rerere.rikkahub.data.ai.transformers.DeepSeekAttachmentCache

@Composable
internal fun DeepSeekAttachmentConfiguration(value: DeepSeekAttachmentSettings, update: (DeepSeekAttachmentSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retry by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    Column {
        Text("DeepSeek 附件解析", style = MaterialTheme.typography.titleMedium)
        Text("默认在本地解析。选用 DeepSeek 后，所发送的受支持附件会上传官方处理，文字识别按页计费；当前 1.5 元/千页，以官方价格为准。图片聊天仍优先由支持视觉的当前模型直接看图。")
        listOf("local" to "本地解析（默认）", "ocr" to "DeepSeek 文字识别 · 任意聊天模型",
            "files_text" to "DeepSeek 文件输入 · 文字", "files_rich" to "DeepSeek 文件输入 · 图文").forEach { (mode, label) ->
            TextButton(onClick = { update(value.copy(mode = mode)) }) { Text((if (value.mode == mode) "✓ " else "") + label) }
        }
        if (value.mode == "ocr") {
            OutlinedTextField(value.apiKey, { update(value.copy(apiKey = it)) }, label = { Text("DeepSeek 官方 API Key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("识别 PDF、Word、PPT 和非视觉模型收到的图片文字。成功结果保存在本机，同一文件和 Key 复用，不会每轮重新识别。")
        } else if (value.mode.startsWith("files_")) {
            Text("需当前聊天直连官方 Flash，使用该聊天配置的单个官方 Key。文件在官方保存 7 天；图文模式会把识别出的插图交给 Flash 本身。再次使用过期文件需重新解析，正文和插图另计输入 Token。ST 或其他模型请选择文字识别。")
        }
        if (value.mode != "local") TextButton(onClick = { retry = true }) { Text("允许重试上次未完成的附件") }
        if (notice.isNotEmpty()) Text(notice)
    }
    if (retry) AlertDialog(onDismissRequest = { retry = false }, title = { Text("允许重新处理附件？") },
        text = { Text("连接中断后，官方可能仍完成处理并计费。继续后，下次发送时可重新上传未确认完成的附件，可能再次计费；成功缓存会保留。") },
        confirmButton = { TextButton(onClick = {
            retry = false
            scope.launch { val count = withContext(Dispatchers.IO) { DeepSeekAttachmentCache(context.filesDir).allowRetry() }; notice = "已允许重试 $count 份未完成附件，下次发送时生效。" }
        }) { Text("允许重试") } }, dismissButton = { TextButton(onClick = { retry = false }) { Text("取消") } })
}
