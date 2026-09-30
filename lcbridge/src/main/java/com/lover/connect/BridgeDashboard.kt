package com.lover.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared module home for the Orbis destination and notification shortcut. */
@Composable
fun BridgeDashboard(
    modifier: Modifier = Modifier,
    legacyAutomationReadOnly: Boolean = false,
    headerContent: @Composable () -> Unit = {},
    sentinelContent: (@Composable () -> Unit)? = null,
) {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = BridgeSection.entries.firstOrNull { it.name == selectedName }
    BackHandler(selected != null) { selectedName = null }
    if (selected != null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { selectedName = null }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("‹ 返回手机与陪伴")
            }
            key(selected) {
                if (selected == BridgeSection.SENTINEL && sentinelContent != null) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        sentinelContent()
                    }
                } else MainScreen(Modifier.weight(1f), selected, legacyAutomationReadOnly = legacyAutomationReadOnly)
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxSize()) {
        val compactTiles = maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.15f
        LazyVerticalGrid(columns = GridCells.Fixed(if (compactTiles) 3 else 2), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    headerContent()
                    Text("手机与陪伴", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text("内嵌陪伴功能已分组收纳。按需配置、逐项授权；进入页面不会开始观察。请先停止旧独立应用的相应功能，避免重复运行。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(BridgeSection.entries, key = { it.name }) { section ->
                Card(onClick = { selectedName = section.name }, modifier = Modifier.fillMaxWidth().heightIn(min = if (compactTiles) 128.dp else 150.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(if (compactTiles) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compactTiles) 7.dp else 11.dp)) {
                        Text(when (section) {
                            BridgeSection.CONNECTION -> "⌘"; BridgeSection.VISION -> "◉"
                            BridgeSection.REST -> "◷"; BridgeSection.LOCATION -> "⌖"
                            BridgeSection.CONTEXT -> "≋"; BridgeSection.CONTROLS -> "⌁"
                            BridgeSection.SENTINEL -> "✦"; BridgeSection.LOCAL -> "▤"
                        }, fontSize = if (compactTiles) 23.sp else 25.sp, color = MaterialTheme.colorScheme.primary)
                        Text(section.title, fontSize = if (compactTiles) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (compactTiles) section.description.substringBefore(" · ") else section.description,
                            fontSize = if (compactTiles) 11.sp else 12.sp, lineHeight = if (compactTiles) 16.sp else 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (legacyAutomationReadOnly)
                    "设备操作仍需陪伴服务及系统授权。旧哨兵链路迁移中；这里的旧行为配置仅供查看，不可修改。请在 Orbis 的本地哨兵面板查看 AI 管理的规则与人类总开关；系统权限、隐私选择与停止采集仍由你控制。"
                    else "本地引擎与设置已内嵌；可为当前 AI 启用原生陪伴工具，不需要再连接本机 MCP。设备操作仍需运行陪伴服务及系统权限；离线记忆不依赖服务。哨兵和自我唤醒仍是外部服务，不因安装 Orbis 自动改目标。",
                    Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}
