package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.backup.tabs.ConversationRescueEntry

/** Explicit, non-exported recovery entry: no RouteActivity, chat render, last-window selection,
 * incoming-call activation or automatic repair. The existing rescue lock and confirmations apply. */
class ConversationRescueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("会话检查与恢复", style = MaterialTheme.typography.headlineSmall)
                        Text("此入口不打开聊天窗口。界面崩溃不等于聊天已删除；先保留原数据，不要卸载、清数据或重复导入。")
                        Text("检查仅在本机进行，不会自动重试模型或工具。副本含私密聊天，不要公开上传。",
                            style = MaterialTheme.typography.bodySmall)
                        ConversationRescueEntry(enabled = true)
                        TextButton(onClick = { finish() }) { Text("返回安全模式") }
                    }
                }
            }
        }
    }
}
