package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.sentinel.OrbisSentinels
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Opening this host reads state only. Only an explicit human master click can change configuration. */
@Composable
internal fun OrbisSentinelSettingsPanel() {
    val context = LocalContext.current.applicationContext
    val opened = remember(context) { runCatching { OrbisSentinels.open(context) } }
    val sentinels = opened.getOrNull()
    if (sentinels == null) {
        Text("哨兵状态读取失败；未重置规则，也未更改总开关。请重新打开此页或检查本地存储。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val settingsStore = koinInject<SettingsStore>()
    val repository = koinInject<ConversationRepository>()
    val chat = koinInject<ChatService>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val state by sentinels.rules.state.collectAsStateWithLifecycle()
    val runtime by sentinels.runtimeState.collectAsStateWithLifecycle()
    val inbox by chat.orbisEvents.inbox.state.collectAsStateWithLifecycle()
    val targetIds = remember(state.rules, inbox.bindings) {
        (state.rules.map { it.conversationId } + inbox.bindings.values.map { it.conversationId }).distinct()
    }
    var conversationNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(targetIds) {
        conversationNames = withContext(Dispatchers.IO) {
            targetIds.mapNotNull { id ->
                try {
                    repository.getConversationById(Uuid.parse(id))?.let { id to it.title.ifBlank { "未命名会话" } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }.toMap()
        }
    }
    OrbisSentinelPanel(
        state = state,
        assistantNames = settings.assistants.associate { it.id.toString() to it.name.ifBlank { "未命名 AI" } },
        conversationNames = conversationNames,
        inbox = inbox,
        runtimeRunning = runtime.running,
        runtimeError = runtime.error,
        // No inferred ownership: receiving an old sender's event is not proof of migration.
        legacyOwned = false,
        saving = saving,
        notice = notice,
        onHumanMasterChange = { enabled ->
            if (!saving) {
                saving = true
                notice = null
                scope.launch {
                    try {
                        withContext(NonCancellable + Dispatchers.IO) { sentinels.setHumanEnabled(enabled) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        notice = "总开关操作未确认完成，请核对实际开关与运行状态；没有创建或修改任何规则。"
                    } finally { saving = false }
                }
            }
        },
    )
}
