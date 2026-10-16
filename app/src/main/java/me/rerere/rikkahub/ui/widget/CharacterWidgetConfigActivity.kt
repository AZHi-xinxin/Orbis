package me.rerere.rikkahub.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

/** Exported only for the launcher configure contract. Always verifies a hosted instance first. */
class CharacterWidgetConfigActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second launcher widget is a different configuration transaction.
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!CharacterWidgetBindings.hosted(this, id)) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val settings by koinInject<SettingsStore>().settingsFlow.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                Text("选择小部件的伙伴", style = MaterialTheme.typography.headlineSmall)
                Text("每个小部件只绑定一位助手。桌面会显示助手名字、Q版图片和预备问候；AI 经授权也可修改问候，请勿放敏感内容。")
                if (failed) Text("没有完成绑定，请重试。", color = MaterialTheme.colorScheme.error)
                if (settings.init || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!settings.init && settings.assistants.isEmpty()) Text("请先在 Orbis 创建一位助手。")
                LazyColumn(Modifier.weight(1f)) { if (!settings.init) items(settings.assistants, key = { it.id.toString() }) { assistant ->
                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CharacterWidgetBindings.bind(this@CharacterWidgetConfigActivity, id, assistant.id.toString()); OrbisCharacterWidget.update(this@CharacterWidgetConfigActivity, id) }
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { busy = false }
                        }
                    }) { Text(assistant.name.ifBlank { "未命名助手" }) }
                } }
                TextButton(enabled = !busy, onClick = { finish() }) { Text("取消") }
            } } }
        }
    }
}
