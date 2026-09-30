package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lover.connect.ui.components.StarSwitch
import me.rerere.rikkahub.data.model.OrbisActionMarker
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings

/** Stateless presentation editor. The owner persists values without changing message contents. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrbisChatFlowSettingsEditor(
    value: OrbisChatFlowSettings,
    onChange: (OrbisChatFlowSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChatFlowToggle(
            title = "分段聊天气泡",
            description = "按原有自然段显示为小气泡；关闭后恢复整条大气泡，其他偏好会保留。",
            checked = value.enabled,
            onCheckedChange = { onChange(value.copy(enabled = it)) },
            tag = "chat-flow-enabled",
        )
        ChatFlowToggle(
            title = "不设置动作区分",
            description = "所有文字正常分气泡，不淡显、不折叠。已选格式会保留，但暂不生效。",
            checked = !value.distinguishActions,
            onCheckedChange = { onChange(value.withNoActionDistinction(it)) },
            enabled = value.enabled,
            tag = "chat-flow-no-actions",
        )
        Text("将以下格式内的内容标记为动作（可多选）", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbisActionMarker.entries.forEach { marker ->
                FilterChip(
                    selected = marker in value.markers,
                    onClick = { onChange(value.withMarker(marker, marker !in value.markers)) },
                    enabled = value.enabled,
                    label = { Text(marker.label) },
                    modifier = Modifier.testTag("chat-flow-marker-${marker.name}"),
                )
            }
        }
        Text(
            when {
                !value.enabled -> "分段气泡已关闭，动作显示偏好暂不生效。"
                !value.distinguishActions -> "目前不区分动作；点击未选格式即可启用区分，默认淡显。"
                value.markers.isEmpty() -> "请选择至少一种格式；当前没有识别任何动作，文字仍正常显示。"
                value.collapseActions -> "选中格式内的内容默认折叠为“动作”，点击可展开或收起。"
                else -> "选中格式内的内容默认淡显，仍然可见；正文保持正常显示。"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("chat-flow-status"),
        )
        ChatFlowToggle(
            title = "将动作折叠",
            description = "仅折叠所选格式内的内容。关闭时只淡显，不隐藏。",
            checked = value.collapseActions,
            onCheckedChange = { onChange(value.copy(collapseActions = it)) },
            enabled = value.effectiveMarkers.isNotEmpty(),
            tag = "chat-flow-collapse-actions",
        )
        Text(
            "以上仅改变本机显示，不改原文、不改变 AI 请求，也不会减少上下文用量。格式识别不判断语义，括号内的台词或说明也可能被标记为动作。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ChatFlowToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        StarSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
