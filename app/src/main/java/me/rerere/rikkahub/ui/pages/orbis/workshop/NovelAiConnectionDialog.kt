package me.rerere.rikkahub.ui.pages.orbis.workshop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.NovelAiImageApi

@Composable
internal fun NovelAiConnectionDialog(existing: ProviderSetting.OpenAI?, busy: Boolean,
    onDismiss: () -> Unit, onSave: (ProviderSetting.OpenAI, String) -> Unit) {
    // Keys are transient form state, never in saveable UI state or log output.
    var key by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var selected by remember { mutableStateOf(NovelAiImageApi.V5_FULL) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("连接 NovelAI") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("单张文生图，原图保存在工坊，也能送到当前聊天。")
            NovelAiImageApi.supportedModels.forEach { (id, label) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected == id, onClick = { selected = id }, enabled = !busy)
                    TextButton(onClick = { selected = id }, enabled = !busy) { Text(label) }
                }
            }
            Text("使用同一账户额度。V5 的 Opus 免费用量与 4.5 分开计算，免费用量耗尽后可能消耗 Anlas。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, label = { Text("Persistent API Token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("novelai-token"))
            Text("密钥在 NovelAI 的 Account → Get Persistent API Token 中生成。",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = !busy && key.trim().startsWith("pst-"),
            modifier = Modifier.testTag("novelai-save"), onClick = {
                val models = NovelAiImageApi.supportedModels.keys.map { id ->
                    val defaults = NovelAiImageApi.model(id)
                    existing?.models?.firstOrNull { it.modelId == id }?.copy(
                        type = defaults.type, inputModalities = defaults.inputModalities,
                        outputModalities = defaults.outputModalities, abilities = defaults.abilities) ?: defaults
                }
                onSave((existing ?: ProviderSetting.OpenAI(name = "NovelAI", baseUrl = NovelAiImageApi.BASE_URL))
                    .copy(apiKey = key.trim(), enabled = true,
                        models = existing?.models.orEmpty().filterNot { NovelAiImageApi.supportsModel(it.modelId) } + models), selected)
            }) { Text(if (busy) "保存中…" else "保存并选用") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
