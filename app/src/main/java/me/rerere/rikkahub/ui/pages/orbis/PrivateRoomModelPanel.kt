package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Identity explanation only. No model selector, route store, OAuth, network client or
 * private content is read here. Existing legacy route files are left untouched and unused.
 */
@Composable
internal fun PrivateRoomModelPanel(assistantName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("房间所属助手：$assistantName", style = MaterialTheme.typography.titleSmall)
        Text("沿用这位助手当前配置，无需另设模型或 ST 连接。", style = MaterialTheme.typography.bodySmall)
        Text("由同一位助手在他的普通聊天中使用隐私室工具；身份、记忆和上下文沿用该次聊天配置。更换模型不会另建房间，也不会转移房间主人。",
            style = MaterialTheme.typography.bodySmall)
        Text("这里不会另起独立模型请求，也不会自动发送聊天消息。", style = MaterialTheme.typography.bodySmall)
    }
}
