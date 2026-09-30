package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lover.connect.ui.components.StarSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.withNativeToolSelection
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

internal enum class OrbisNativeToolSection { COMPANION, TOY, GARDEN }

/** One selection surface in each existing feature home. Never starts a service or connects a device. */
@Composable
internal fun OrbisNativeToolSelectionPanel(section: OrbisNativeToolSection) {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val options = when (section) {
        OrbisNativeToolSection.COMPANION -> listOf(LocalToolOption.CompanionDevice, LocalToolOption.CompanionMemory)
        OrbisNativeToolSection.TOY -> listOf(LocalToolOption.BluetoothToy)
        OrbisNativeToolSection.GARDEN -> listOf(LocalToolOption.LocalGarden, LocalToolOption.LocalReading, LocalToolOption.LocalSoup)
    }
    OrbisNativeToolSelectionCard(
        assistantName = assistant?.name?.ifBlank { "未命名 AI" } ?: "未选择 AI",
        options = options,
        selected = assistant?.localTools.orEmpty(),
        canEdit = !settings.init && assistant != null && !saving,
        error = error,
    ) { option, enabled ->
        val identity = assistant?.id ?: return@OrbisNativeToolSelectionCard
        saving = true
        error = null
        scope.launch {
            try {
                // Persist the explicit click even if this page is closed while DataStore writes.
                withContext(NonCancellable) {
                    store.update { current -> current.withNativeToolSelection(identity, option, enabled) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "工具选用未确认保存成功，请重试。没有执行任何工具。"
            } finally { saving = false }
        }
    }
}

@Composable
internal fun OrbisNativeToolSelectionCard(
    assistantName: String,
    options: List<LocalToolOption>,
    selected: List<LocalToolOption>,
    canEdit: Boolean,
    error: String? = null,
    onToggle: (LocalToolOption, Boolean) -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("给 $assistantName 的原生工具", style = MaterialTheme.typography.titleSmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option ->
                val (title, hint, tag) = when (option) {
                    LocalToolOption.CompanionDevice -> Triple("手机与陪伴 · 原生工具", "不用添加本机 MCP；闹钟台账查询与运行诊断在服务关闭时也可读，其它能力按需检查服务和系统权限。", "companion")
                    LocalToolOption.CompanionMemory -> Triple("离线记忆 · 读与保存", "只在本机保存；不需联网或启动陪伴服务，不自动迁移旧 App 的记忆。", "memory")
                    LocalToolOption.BluetoothToy -> Triple("蓝牙 Toy · 状态、控制、停止", "仅能控制你在此页选择并连接的设备；点亮不会连接或启动设备。", "toy")
                    LocalToolOption.LocalGarden -> Triple("本地后花园 · 查阅与新增", "授权这位 AI 查阅本机共用花园（包括你和其他已授权 AI 的记录），并经批准新增文字；查到的内容会进入该 AI 的模型上下文。不读远端、私聊或工作区，不自动删除或覆盖。", "garden")
                    LocalToolOption.LocalReading -> Triple("本地藏书阁 · 允许写批注", "默认可读取本机书目及导入书籍的章节片段；空书库返回空。点亮后还可经批准追加批注。读到的片段会发送给当前聊天模型，不读取 VPS，也不自动上传整本书。", "reading")
                    LocalToolOption.LocalSoup -> Triple("本地海龟汤 · 允许伙伴操作", "默认可读取本机公开进度；空对局返回空。点亮后可经批准保存提问、推理建议，使用提示或揭底。真正调用独立主持仍须逐次确认，可能计费；未揭底前不向伙伴提供汤底。", "soup")
                    else -> throw IllegalArgumentException("Unsupported native tool selection")
                }
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StarSwitch(checked = option in selected, enabled = canEdit,
                        onCheckedChange = { onToggle(option, it) },
                        modifier = Modifier.testTag("native-tool-toggle-$tag").semantics { contentDescription = title })
                    }
                }
                }
                }
                }
                }
            }
            Text("工具选用与执行批准分开；只读查询无需逐次确认，写入可选择「此次允许／以后允许」。系统授权仍由你确认。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
