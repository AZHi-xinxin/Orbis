package com.lover.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Notification shortcuts in the embedded host must use the same read-only policy as its page. */
fun isOrbisLegacyAutomationHost(packageName: String): Boolean =
    packageName == "org.orbis.agent" || packageName == "org.orbis.agent.dev"

/** Pure read-only transport view. The API deliberately accepts no URL or token text. */
@Composable
fun BridgeLegacySentinelReadOnlyCard(
    sendingEnabled: Boolean,
    endpointConfigured: Boolean,
    credentialConfigured: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("旧哨兵连接 · 只读", style = MaterialTheme.typography.titleMedium)
        Text("旧链路迁移中：此处仅显示既有配置，不修改分项开关、目标地址或凭证。AI 管理的本机规则及人类总开关在 Orbis 本地哨兵面板。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("已保存的事件发送配置：${if (sendingEnabled) "启用" else "关闭"}（只读）", style = MaterialTheme.typography.bodyMedium)
        Text("旧目标地址：${if (endpointConfigured) "已配置" else "未配置"}", style = MaterialTheme.typography.bodySmall)
        Text("旧连接凭证：${if (credentialConfigured) "已配置" else "未配置"}（不显示原文）", style = MaterialTheme.typography.bodySmall)
        Text("配置存在不等于已经运行或送达，也不代表迁移完成；不会自动改投目标或发送测试事件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Pure legacy rule inspection. The only action stops previously enabled collection for privacy;
 * it cannot enable observation, edit intervals/prompts, or alter the new human master switch.
 */
@Composable
fun BridgeLegacyAutomationReadOnlyCard(
    showVision: Boolean,
    collectionEnabled: Boolean,
    aiName: String,
    userName: String,
    relationship: String,
    personality: String,
    intervalMinutes: String,
    restThresholdMinutes: String,
    onStopCollection: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("旧版内置观察配置 · 只读", style = MaterialTheme.typography.titleMedium)
        Text("旧链路迁移中：人格、提醒门槛和周期暂不可由此修改，也不能在这里重新开启旧自动规则。新的本机哨兵由 AI 管理。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("旧自动采集设置：${if (collectionEnabled) "已开启" else "已关闭"}（不代表当前服务正在运行）",
            style = MaterialTheme.typography.bodyMedium)
        if (showVision) {
            LegacyConfigurationValue("AI 名字", aiName)
            LegacyConfigurationValue("你的昵称", userName)
            LegacyConfigurationValue("关系", relationship)
            LegacyConfigurationValue("观察人格原文", personality)
            LegacyConfigurationValue("截屏间隔", "$intervalMinutes 分钟")
        } else {
            LegacyConfigurationValue("同一非聊天应用连续使用门槛", "$restThresholdMinutes 分钟")
            Text("旧引擎仍按原聊天豁免与冷却策略处理；这里只读，不会重写配置。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = collectionEnabled, onClick = onStopCollection,
            modifier = Modifier.fillMaxWidth().testTag("legacy-stop-collection")) {
            Text("停止旧版自动采集")
        }
        Text("这是隐私停止：只停止旧版自动采集与连续使用监测，不撤销系统权限，也不改写本地哨兵规则。截屏授权、无障碍和其他隐私选项仍由你控制。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegacyConfigurationValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "未填写" }, style = MaterialTheme.typography.bodySmall)
    }
}

/** Only the alert threshold is a rule setting; reporting facts and location privacy remain separate. */
@Composable
fun BridgeLegacyReminderDistanceReadOnlyCard(reminderKmText: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("第二次提醒距离 · 只读", style = MaterialTheme.typography.titleSmall)
        Text("$reminderKmText 公里", style = MaterialTheme.typography.bodyMedium)
        Text("旧链路迁移中，此提醒条件暂不可由此修改。主动报备、定位授权、暂停追踪和清除位置数据仍由你控制。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
