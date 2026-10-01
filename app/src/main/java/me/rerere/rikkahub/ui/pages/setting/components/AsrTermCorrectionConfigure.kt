package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRTermCorrectionRule
import me.rerere.asr.ASRTermCorrectionSettings
import me.rerere.asr.correctDeviceAsrTranscript
import me.rerere.asr.asrDevicePronunciation
import me.rerere.asr.isAsrPhoneticCorrectionAvailable
import me.rerere.asr.validateAsrCorrectionRules
import me.rerere.rikkahub.ui.components.ui.Switch

@Composable
fun AsrTermCorrectionConfigure(
    value: ASRTermCorrectionSettings,
    onSave: (ASRTermCorrectionSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("语音称呼纠正", style = MaterialTheme.typography.titleMedium)
            Text(if (value.enabled) "已开启 · ${value.rules.size} 组称呼，仅处理语音识别结果" else "未开启 · 手打文字与旧聊天不会改动")
            TextButton(onClick = { editing = true }) { Text("设置称呼与误识别别名") }
        }
    }
    if (editing) {
        val phoneticAvailable = remember { isAsrPhoneticCorrectionAvailable() }
        var enabled by remember { mutableStateOf(value.enabled) }
        var rules by remember { mutableStateOf(value.rules) }
        var error by remember { mutableStateOf<String?>(null) }
        var preview by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("语音称呼纠正") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("启用语音称呼纠正", Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Text("默认只替换明确填写的误识别别名；英文区分大小写且不匹配其他单词内部。可逐组开启完整中文称呼的同音匹配，不猜未登记名字。只处理新的听写、语音条和通话；手打正文、旧聊天不改。通话显示并保存纠正后文字，原识别仍可查看。本次通话沿用接通时的规则，修改设置从下次通话生效。")
                    if (!phoneticAvailable) Text("此系统暂不支持离线读音匹配（需 Android 10 或更新及系统读音库）；精确别名仍可使用，已保存的同音设置不会被自动改动。")
                    rules.forEachIndexed { index, rule ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = rule.target,
                                onValueChange = { text -> rules = rules.mapIndexed { i, old -> if (i == index) old.copy(target = text.take(100)) else old }; error = null },
                                label = { Text("正确称呼 ${index + 1}") },
                                placeholder = { Text("填写完整称呼") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("完整称呼同音匹配（2～6 字中文）", Modifier.weight(1f))
                                Switch(checked = rule.phoneticMatch, enabled = phoneticAvailable || rule.phoneticMatch,
                                    onCheckedChange = { checked ->
                                        rules = rules.mapIndexed { i, old -> if (i == index) old.copy(phoneticMatch = checked) else old }
                                        error = null
                                    })
                            }
                            if (rule.phoneticMatch) Text("忽略声调，但不使用近音、缩写或模糊猜测。同音普通词也可能误改；完整称呼首字的沈按姓氏 shen 匹配，其他多音字仍依系统读音。请先预览，不合适就只填精确别名。多个已登记称呼同音或匹配有歧义时拒绝自动选择。")
                            OutlinedTextField(
                                value = rule.aliases.joinToString("\n"),
                                onValueChange = { text -> rules = rules.mapIndexed { i, old -> if (i == index) old.copy(aliases = text.take(3_000).split('\n')) else old }; error = null },
                                label = { Text(if (rule.phoneticMatch) "精确别名（可选），每行一个" else "误识别别名，每行一个") },
                                placeholder = { Text("填写实际出现的误识别文字") },
                                minLines = 2,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(onClick = { rules = rules.filterIndexed { i, _ -> i != index }; error = null }) { Text("移除此组") }
                        }
                    }
                    TextButton(enabled = rules.size < 100, onClick = { rules = rules + ASRTermCorrectionRule() }) { Text("添加称呼") }
                    OutlinedTextField(value = preview, onValueChange = { preview = it.take(1_000) }, label = { Text("本地预览（不会发送）") }, modifier = Modifier.fillMaxWidth())
                    if (preview.isNotEmpty()) Text("纠正后：" + correctDeviceAsrTranscript(preview, ASRTermCorrectionSettings(enabled, rules)).corrected)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = rules.map { it.copy(target = it.target.trim(), aliases = it.aliases.map(String::trim).filter(String::isNotEmpty).distinct()) }
                    error = validateAsrCorrectionRules(cleaned, if (phoneticAvailable) ::asrDevicePronunciation else null)
                    if (error == null) { onSave(ASRTermCorrectionSettings(enabled, cleaned)); editing = false }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}
