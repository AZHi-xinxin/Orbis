package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController

/** Opening this chooser reads only local settings. The final navigation is a deliberate user action. */
@Composable
internal fun OrbisStModelSettingsEntry(
    providers: List<ProviderSetting>, preferredProviderId: String?, enabled: Boolean,
    onLeaveSettings: () -> Unit,
) {
    val navigator = LocalNavController.current
    val choices = remember(providers) { orbisStModelEntryChoices(providers) }
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        selected = orbisStModelEntryTarget(choices, preferredProviderId)
        show = true
    }, enabled = enabled) { Text("ST 模型目录与切换") }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("ST 模型目录与切换") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选已有的 ST 网关连接。连接名称不代表服务已验证；这里不会新建连接、修改密钥或更换 ST 身份。")
            Text(ORBIS_ST_MODEL_DIRECTORY_NOTE, style = MaterialTheme.typography.bodySmall)
            if (choices.isEmpty()) Text("没有可选的已启用 OpenAI 兼容连接，请先去系统设置 → 模型与连接添加。")
            choices.forEach { choice ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected == choice.providerId, onClick = { selected = choice.providerId })
                    Column(Modifier.weight(1f)) {
                        Text(choice.name)
                        Text("手机已添加 ${choice.configuredModels} 个型号", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(ORBIS_ST_MODEL_ROUTE_NOTE, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = {
            // Use fresh composition choices; deleted/ambiguous providers cannot be silently substituted.
            val providerId = orbisStModelEntryTarget(choices, selected) ?: return@TextButton
            show = false
            onLeaveSettings()
            navigator.navigate(Screen.SettingProviderDetail(providerId, showModels = true))
        }, enabled = enabled && orbisStModelEntryTarget(choices, selected) != null) { Text("打开此连接的模型页") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("返回") } },
    )
}
