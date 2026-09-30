package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisGenerationParameterConflict
import me.rerere.rikkahub.data.model.OrbisGenerationParameterDraft
import me.rerere.rikkahub.data.model.OrbisGenerationParameterEdit
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import me.rerere.rikkahub.ui.components.ai.ReasoningButton

/** Uses the same Assistant fields and ReasoningButton as AssistantBasicPage, but edits a draft. */
@Composable
internal fun OrbisGenerationParametersSection(
    conversationId: String,
    assistant: Assistant,
    editable: Boolean,
    onSavingChange: (Boolean) -> Unit,
    onSaved: () -> Unit,
    onSave: suspend (OrbisGenerationParameterEdit) -> OrbisGenerationParameters,
) {
    val key = "$conversationId:${assistant.id}"
    var baselineJson by rememberSaveable(key) {
        mutableStateOf(Json.encodeToString(OrbisGenerationParameters.from(assistant)))
    }
    val baseline = Json.decodeFromString<OrbisGenerationParameters>(baselineJson)
    var draftJson by rememberSaveable(key) {
        mutableStateOf(Json.encodeToString(OrbisGenerationParameterDraft.from(baseline)))
    }
    val draft = Json.decodeFromString<OrbisGenerationParameterDraft>(draftJson)
    var saving by remember(key) { mutableStateOf(false) }
    var notice by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = runCatching { draft.parameters() }
    val dirty = parsed.getOrNull() != baseline
    fun change(value: OrbisGenerationParameterDraft) {
        draftJson = Json.encodeToString(value)
        notice = null
        failed = false
    }

    Text("当前 AI 的生成参数", fontWeight = FontWeight.SemiBold)
    Text("当前 AI，影响使用该 AI 的会话", style = MaterialTheme.typography.bodyMedium)
    Text("这些不是本会话专属参数；单独保存后供下一次请求使用。其他会话已开始的回复不会中途改参。模型或服务商可能不支持部分参数。",
        style = MaterialTheme.typography.bodySmall)
    ParameterToggle("自定义温度", draft.temperatureEnabled, editable) { change(draft.copy(temperatureEnabled = it)) }
    if (draft.temperatureEnabled) {
        ParameterNumber("温度（0–2）", "orbis-generation-temperature", draft.temperature,
            editable, decimal = true) { change(draft.copy(temperature = it)) }
    }
    ParameterToggle("自定义 top-p", draft.topPEnabled, editable) { change(draft.copy(topPEnabled = it)) }
    if (draft.topPEnabled) {
        ParameterNumber("top-p（0–1）", "orbis-generation-top-p", draft.topP,
            editable, decimal = true) { change(draft.copy(topP = it)) }
    }
    Text("关闭自定义温度或 top-p 时使用模型默认值。", style = MaterialTheme.typography.bodySmall)
    ParameterNumber("上下文消息数量上限", "orbis-generation-context", draft.contextMessageLimit,
        editable) { change(draft.copy(contextMessageLimit = it)) }
    Text("0 表示不限制；非零至少 20。限制的是发送给模型的消息条数，不会删除聊天记录。",
        style = MaterialTheme.typography.bodySmall)
    ParameterToggle("流式输出", draft.streamOutput, editable) { change(draft.copy(streamOutput = it)) }
    ParameterNumber("最大输出 token（留空使用默认）", "orbis-generation-max-tokens", draft.maxTokens,
        editable) { change(draft.copy(maxTokens = it)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("推理预算", fontWeight = FontWeight.SemiBold)
            Text("沿用模型的推理等级映射，并非所有服务商都按固定 token 数执行。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (editable) {
            ReasoningButton(modifier = Modifier.heightIn(min = 48.dp), compact = true, reasoningLevel = draft.reasoningLevel,
                onUpdateReasoningLevel = { change(draft.copy(reasoningLevel = it)) })
        } else {
            Text(draft.reasoningLevel.name)
        }
    }
    if (dirty && parsed.isFailure) {
        Text(parsed.exceptionOrNull()?.message ?: "请检查参数。", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    notice?.let {
        Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
    }
    Button(
        modifier = Modifier.fillMaxWidth().testTag("orbis-generation-save"),
        enabled = editable && dirty && parsed.isSuccess,
        onClick = {
            // Set synchronously, before launching, to reject a second tap.
            if (!saving && editable) {
                val edit = OrbisGenerationParameterEdit(assistant.id, baseline, parsed.getOrThrow())
                saving = true
                onSavingChange(true)
                notice = null
                scope.launch {
                    try {
                        val saved = onSave(edit)
                        baselineJson = Json.encodeToString(saved)
                        draftJson = Json.encodeToString(OrbisGenerationParameterDraft.from(saved))
                        failed = false
                        notice = "当前 AI 参数已保存。本会话内容需使用上方按钮单独保存。"
                        onSaved()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (conflict: OrbisGenerationParameterConflict) {
                        failed = true
                        notice = conflict.message
                    } catch (_: Exception) {
                        failed = true
                        notice = "AI 参数保存未确认，请重试；草稿仍保留。本会话内容不会随此操作保存。"
                    } finally {
                        saving = false
                        onSavingChange(false)
                    }
                }
            }
        },
    ) { Text(if (saving) "参数保存中…" else "保存当前 AI 参数") }
}

@Composable
private fun ParameterToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        StarSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ParameterNumber(
    label: String, tag: String, value: String, enabled: Boolean,
    decimal: Boolean = false, onChange: (String) -> Unit,
) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}
