package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.activities.*
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun OrbisPetAi(owner: String, repo: PetRepository, onClose: () -> Unit, conversationId: String? = null) {
    val context = LocalContext.current.applicationContext
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val statuses by PetAiRuntime.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun update(block: () -> Unit) {
        if (saving) return
        scope.launch {
            saving = true
            try { withContext(NonCancellable + Dispatchers.IO) { block() }; notice = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "这次未保存，原档案保留。" }
            finally { saving = false }
        }
    }
    CompanionWindow(onClose) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            TextButton(onClick = onClose) { Text("返回小窝") }
            Text("宠物的声音", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("为${state.pet?.name ?: "宠物"}选择独立模型", style = MaterialTheme.typography.titleMedium)
            Text("只发送宠物身份、近期照料和你在这里说的话，不发送主聊天与私人记忆。模型选择行为，不直接改档案。选择模型不会发请求。", style = MaterialTheme.typography.bodyMedium)
            ModelSelector(modelId = state.aiModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                providers = settings.providers.filter { it.enabled }, type = ModelType.CHAT, onSelect = { model ->
                    update { repo.configureAi(model.id.toString(), false); PetAiRuntime.schedule(context, owner, false) }
                })
            Row {
                Column(Modifier.weight(1f)) {
                    Text("启用宠物 AI")
                    Text("开启即允许照料互动及约每小时一次的自主反应；可能产生模型费用。系统休眠时不保证准点，恢复后不补发。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.aiConsent, { enabled -> update {
                    repo.configureAi(state.aiModelId, enabled); PetAiRuntime.schedule(context, owner, enabled)
                } }, enabled = !saving && state.aiModelId != null)
            }
            Text("服务端是否另加记忆取决于所选服务；如使用 ST，优先选择其独立无记忆模型。切换模型需重新开启，不会重置宠物。", style = MaterialTheme.typography.bodySmall)
            var budget by remember(state.aiDailyLimit) { mutableStateOf(state.aiDailyLimit.toString()) }
            OutlinedTextField(budget, { budget = it.filter(Char::isDigit).take(4) }, singleLine = true,
                label = { Text("每天最多请求几次 · 0 为不设上限") }, supportingText = {
                    Text("达到次数后照料仍可用，次日恢复。最近计数：${state.aiUsageDay.ifBlank { "尚未请求" }} · ${state.aiUsageCount} 次。失败也计入尝试。")
                }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !saving && budget.toIntOrNull()?.let { it in 0..1000 } == true,
                onClick = { update { repo.configureAiBudget(budget.toInt()) } }) { Text("保存次数设置") }
            HorizontalDivider()
            Text("小窝动态", style = MaterialTheme.typography.titleMedium)
            Text("自主捣乱或吵闹时，每次只抽一次50%机会，最多每小时一条。只发到绑定聊天的通知栏，不自动唤醒助手、不打断通话；下次聊天可查看。", style = MaterialTheme.typography.bodySmall)
            Row {
                Text(if(state.noticesEnabled) "已绑定聊天提醒" else "聊天提醒关闭", Modifier.weight(1f))
                Switch(state.noticesEnabled, { enabled -> update {
                    repo.configureNotices(if(enabled) conversationId ?: state.noticeConversationId else state.noticeConversationId, enabled)
                } }, enabled = !saving && (conversationId != null || state.noticeConversationId != null))
            }
            if(conversationId == null && state.noticeConversationId == null) Text("从希望接收动态的聊天窗口打开小窝，即可绑定。", style = MaterialTheme.typography.bodySmall)
            if(conversationId != null && state.noticeConversationId != null && conversationId != state.noticeConversationId)
                TextButton(onClick = { update { repo.configureNotices(conversationId, true) } }) { Text("改为提醒这个聊天") }
            HorizontalDivider()
            Text("说给它听", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(words, { words = it.take(1000) }, modifier = Modifier.fillMaxWidth(), label = { Text("你和助手都可以跟它说话") })
            Button(enabled = state.aiConsent && words.isNotBlank(), onClick = {
                PetAiRuntime.enqueue(context, owner, "talk", words); words = ""
            }) { Text("和它说话") }
            statuses[owner]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("翻译机 ${state.translationCredits} 次 · 累计健康照料 ${state.careDays.size} 天", style = MaterialTheme.typography.titleMedium)
            Text("每累计 10 个状态良好的照料日奖励 1 次，漏一天不清零；同一句翻译后可反复查看，不重复扣次数。", style = MaterialTheme.typography.bodySmall)
            state.sayings.asReversed().forEach { saying ->
                Text(saying.animal, style = MaterialTheme.typography.bodyLarge)
                if (saying.translated) Text(saying.original, style = MaterialTheme.typography.bodyMedium)
                else TextButton(enabled = !saving && state.translationCredits > 0, onClick = { update { repo.translate(saying.id) } }) { Text("翻译这句话 · 1 次") }
                HorizontalDivider()
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
