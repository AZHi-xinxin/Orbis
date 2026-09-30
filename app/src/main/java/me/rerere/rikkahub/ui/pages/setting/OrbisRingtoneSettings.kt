package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lover.connect.OrbisRingtoneKind
import com.lover.connect.OrbisRingtonePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OrbisRingtoneSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { OrbisRingtonePreferences(context) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本地铃声", style = MaterialTheme.typography.titleMedium)
        Text("来电和闹钟分别选择。文件失效时使用系统默认铃声；选择文件不会试听，也不会改变系统音量。",
            style = MaterialTheme.typography.bodySmall)
        OrbisRingtoneChoice(OrbisRingtoneKind.INCOMING, "来电铃声", preferences)
        OrbisRingtoneChoice(OrbisRingtoneKind.ALARM, "闹钟铃声", preferences)
    }
}

@Composable
private fun OrbisRingtoneChoice(kind: OrbisRingtoneKind, title: String, preferences: OrbisRingtonePreferences) {
    val scope = rememberCoroutineScope()
    var selection by remember(preferences, kind) { mutableStateOf(preferences.get(kind)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                selection = withContext(Dispatchers.IO) { preferences.select(kind, uri) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "无法保存此音频的长期读取权限，请重新选择本机音频。原设置保留。" }
            finally { busy = false }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(selection?.label ?: "系统默认", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = {
                    error = null
                    runCatching { picker.launch(arrayOf("audio/*")) }
                        .onFailure { error = "系统文件选择器暂不可用，请稍后重试。" }
                }) { Text(if (busy) "保存中…" else "选择音频") }
                TextButton(enabled = !busy && selection != null, onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            withContext(Dispatchers.IO) { preferences.reset(kind) }
                            selection = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "设置未保存，请重试。" }
                        finally { busy = false }
                    }
                }) { Text("恢复系统默认") }
            }
        }
    }
}
