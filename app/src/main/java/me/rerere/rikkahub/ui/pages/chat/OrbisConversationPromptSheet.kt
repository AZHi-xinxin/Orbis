package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.lover.connect.ui.components.StarSwitch as Switch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.OrbisGenerationParameterEdit
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme

/** Edits stay in this sheet until Save succeeds. The conversation id owns the draft. */
@Composable
internal fun OrbisConversationPromptSheet(
    conversationId: String,
    prompt: OrbisConversationPrompt,
    generating: Boolean,
    composerOpacity: Float,
    onSave: suspend (OrbisConversationPrompt) -> Unit,
    onDismiss: () -> Unit,
    assistant: Assistant? = null,
    onSaveGenerationParameters: (suspend (OrbisGenerationParameterEdit) -> OrbisGenerationParameters)? = null,
) {
    var text by rememberSaveable(conversationId) { mutableStateOf(prompt.text) }
    var enabled by rememberSaveable(conversationId) { mutableStateOf(prompt.enabled) }
    var worldBookText by rememberSaveable(conversationId) { mutableStateOf(prompt.worldBookText) }
    var worldBookEnabled by rememberSaveable(conversationId) { mutableStateOf(prompt.worldBookEnabled) }
    var promptSaving by remember(conversationId) { mutableStateOf(false) }
    var parametersSaving by remember(conversationId) { mutableStateOf(false) }
    val saving = promptSaving || parametersSaving
    var lastSavedPromptJson by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var anythingSaved by rememberSaveable(conversationId) { mutableStateOf(false) }
    var error by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val editable = !saving && !generating
    val draftPrompt = OrbisConversationPrompt(text, enabled, worldBookText, worldBookEnabled)
    val promptAlreadySaved = lastSavedPromptJson == Json.encodeToString(draftPrompt)
    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(
            onDismissRequest = { if (!saving) onDismiss() },
            containerColor = colors.panel.copy(alpha = composerOpacity),
            contentColor = colors.ink,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("对话设置", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text("本会话内容与当前 AI 参数分别保存。关闭会放弃尚未保存的改动。",
                    style = MaterialTheme.typography.bodySmall)
                Text("世界书 · 当前会话", fontWeight = FontWeight.SemiBold)
                Text("记录人物、关系、地点或故事背景。启用后整段随本会话发送；不是关键词触发，也不会改动其他会话。",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("在本会话启用世界书", Modifier.weight(1f))
                    Switch(checked = worldBookEnabled,
                        onCheckedChange = { worldBookEnabled = it; error = null }, enabled = editable,
                        modifier = Modifier.semantics { contentDescription = "在本会话启用世界书" })
                }
                OutlinedTextField(value = worldBookText,
                    onValueChange = { worldBookText = it; error = null }, enabled = editable,
                    label = { Text("世界书正文（可留空）") }, minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("orbis-conversation-worldbook-input"))
                Text("关闭会保留正文；只有点保存后才生效。内容占用当前模型上下文。",
                    style = MaterialTheme.typography.bodySmall)
                Text("给 AI 的提示词", fontWeight = FontWeight.SemiBold)
                Text("为当前会话补充偏好或要求，保存后从下一次回复生效。",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("在本会话启用", fontWeight = FontWeight.SemiBold)
                        Text("关闭后保留你填写的文字", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it; error = null },
                        modifier = Modifier.semantics { contentDescription = "在本会话启用提示词" },
                        enabled = editable)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    enabled = editable,
                    label = { Text("附加提示（可留空）") },
                    placeholder = { Text("例如：这段聊天请用简洁的中文，先给结论，再给步骤。") },
                    minLines = 4,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth().testTag("orbis-conversation-prompt-input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = colors.ink,
                        unfocusedTextColor = colors.ink,
                    ),
                )
                Text("原有助手设定继续保留。这里的内容随本会话保存，并在启用时发送给当前模型。",
                    style = MaterialTheme.typography.bodySmall)
                if (enabled && text.isBlank()) {
                    Text("填写内容后，启用开关即可生效。", style = MaterialTheme.typography.bodySmall)
                }
                if (generating) {
                    Text("等这次回复完成后，就可以修改并保存。", color = colors.onSand,
                        style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                if (promptAlreadySaved) {
                    Text("本会话内容已保存。AI 参数需使用下方按钮单独保存。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(modifier = Modifier.fillMaxWidth().testTag("orbis-conversation-save"), onClick = {
                    if (!promptSaving && !parametersSaving && !generating && !promptAlreadySaved) {
                        promptSaving = true
                        error = null
                        scope.launch {
                            try {
                                onSave(draftPrompt)
                                lastSavedPromptJson = Json.encodeToString(draftPrompt)
                                anythingSaved = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "暂未保存成功，请在回复完成后重试。填写的内容还在这里。"
                            } finally {
                                promptSaving = false
                            }
                        }
                    }
                }, enabled = editable && !promptAlreadySaved) {
                    Text(if (promptSaving) "保存中…" else "保存本会话内容")
                }
                if (assistant != null && onSaveGenerationParameters != null) {
                    HorizontalDivider()
                    OrbisGenerationParametersSection(
                        conversationId = conversationId,
                        assistant = assistant,
                        editable = editable,
                        onSavingChange = { parametersSaving = it },
                        onSaved = { anythingSaved = true },
                        onSave = onSaveGenerationParameters,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving,
                        modifier = Modifier.testTag("orbis-conversation-dismiss")) {
                        Text(if (anythingSaved) "关闭（放弃未保存改动）" else "取消")
                    }
                }
            }
        }
    }
}
