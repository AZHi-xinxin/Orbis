package me.rerere.rikkahub.ui.pages.orbis

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.chatgpt.ChatGptPlanAccount
import me.rerere.ai.provider.chatgpt.ChatGptPlanAccounts
import me.rerere.ai.provider.chatgpt.ChatGptPlanProtocol
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.minutes

/** Local account management only; sign-in never silently changes an assistant's chosen model. */
@Composable
internal fun OrbisChatGptPlanEntry() {
    val context = LocalContext.current
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val vault = remember(context.applicationContext) { ChatGptPlanAccounts(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(emptyList<ChatGptPlanAccount>()) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var welcome by remember { mutableStateOf<String?>(null) }
    var signOut by remember { mutableStateOf<ChatGptPlanAccount?>(null) }
    var addRegistration by remember { mutableStateOf(false) }
    suspend fun refresh() { accounts = withContext(Dispatchers.IO) { vault.accounts() } }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        operation = scope.launch {
            try { block(); refresh() }
            catch (cancelled: CancellationException) { notice = "已取消本次操作；没有自动重发。"; throw cancelled }
            catch (error: Exception) { notice = ChatGptPlanProtocol.safeMessage(error); runCatching { refresh() } }
            finally { busy = false; operation = null }
        }
    }
    fun login(account: ChatGptPlanAccount?) = work {
        val state = ChatGptPlanProtocol.randomSecret()
        val nonce = ChatGptPlanProtocol.randomSecret()
        val verifier = ChatGptPlanProtocol.randomSecret()
        val listener = OAuthLoopbackCallbackServer(callbackPath = "/auth/callback", appName = "Orbis", callbackReceiptOnly = true)
        val session = listener.openSession(context, state)
        try {
            val url = withContext(Dispatchers.IO) { vault.authorizationUrl(account?.id, session.redirectUri, state, nonce, verifier) }
            CustomTabsOAuthAuthorizationLauncher.launch(context, url)
            val callback = session.awaitCallback(5.minutes) ?: error("siwc_timeout")
            val connected = vault.finishSignIn(account?.id, session.redirectUri, state, callback.state,
                nonce, verifier, callback.clientId, callback.code, callback.error)
            notice = if (connected.planEnabled) "授权已保存。请读取可用模型，再从模型选择器选择；没有自动切换现有聊天。"
                else "账号已登录，但未获得 ChatGPT 套餐使用权限。"
            if (connected.planEnabled && !connected.welcomeSeen) welcome = connected.id
        } finally { withContext(NonCancellable) { session.close() } }
    }
    OrbisSettingsLink("ChatGPT 套餐", "官方授权 · 账号与用量管理 · 不需要填 API Key",
        { open = true; work { refresh() } })
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("使用 ChatGPT 套餐") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 510.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("适用于开源、本机运行的 Orbis。是否可用、可选模型及用量以账号实际授权为准；这不是无限额度，也不会读取 ChatGPT 的历史聊天。")
            Text("角色、记忆及工具权限仍由 Orbis 管理。生图、朗读和语音识别仍用各自的独立配置。登录凭证不进入普通备份或分享。", style = MaterialTheme.typography.bodySmall)
            val activeModel = settings.getCurrentChatModel()
            val activeProvider = settings.providers.filterIsInstance<ProviderSetting.OpenAI>().firstOrNull { p ->
                p.chatGptAccountId != null && p.models.any { it.id == activeModel?.id }
            }
            Text(activeProvider?.let { "当前聊天：${it.name} / ${activeModel?.displayName}" }
                ?: "当前聊天未选择 ChatGPT 套餐连接。", style = MaterialTheme.typography.bodySmall)
            if (accounts.isNotEmpty()) Text("已有账号请在对应卡片重新授权，不会新建登记。同一邮箱也可能有不同工作区或授权登记，编号不同的连接相互独立。",
                style = MaterialTheme.typography.bodySmall)
            accounts.forEach { account -> OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account.label, style = MaterialTheme.typography.titleSmall)
                    Text(when { account.verificationPending -> "凭证已加密暂存，身份验证待完成；尚不可用于聊天"
                        !account.signedIn -> "未完成授权或已退出，可重新授权"
                        account.planEnabled -> "授权已验证；读取模型不等于生成测试通过"
                        else -> "已登录，套餐权限未开启" })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { login(account) }) { Text("重新授权") }
                        TextButton(enabled = !busy && (account.signedIn || account.verificationPending), onClick = { signOut = account }) { Text("退出") }
                    }
                    Button(enabled = !busy && (account.planEnabled || account.verificationPending) && !settings.init, onClick = { work {
                        val models = vault.models(account.id)
                        require(models.isNotEmpty()) { "siwc_models_unavailable" }
                        check(store.update { current ->
                            val existing = current.providers.filterIsInstance<ProviderSetting.OpenAI>().singleOrNull { it.chatGptAccountId == account.id }
                            val provider = (existing ?: ProviderSetting.OpenAI(name = "ChatGPT 套餐 · ${account.label}", chatGptAccountId = account.id)).copy(
                                apiKey = "", baseUrl = ChatGptPlanProtocol.RESOURCE, responsesPath = "/responses", useResponseApi = true,
                                models = models.map { item -> existing?.models?.firstOrNull { it.modelId == item.modelId }?.let { item.copy(id = it.id) } ?: item })
                            current.copy(providers = current.providers.filterNot { it.id == provider.id } + provider)
                        })
                        notice = "${account.label}：已读取 ${models.size} 个模型选项，尚未证明能生成回复。请打开此编号的连接，选择模型并测试；现有角色和聊天没有改变。"
                    } }) { Text(if (account.verificationPending) "继续验证并读取模型" else "读取模型并加入连接") }
                }
            } }
            Button(enabled = !busy, onClick = { if (accounts.isEmpty()) login(null) else addRegistration = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Black, contentColor = androidx.compose.ui.graphics.Color.White)) {
                Text("Continue with ChatGPT")
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://chatgpt.com/settings/usage".toUri())) }) { Text("Manage usage · 管理套餐用量") }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("授权时请在系统浏览器操作，完成后返回此页。", style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(onClick = { if (busy) operation?.cancel() else open = false }) { Text(if (busy) "取消当前操作" else "完成") } },
    )
    if (addRegistration) AlertDialog(onDismissRequest = { addRegistration = false }, title = { Text("新增独立授权登记？") },
        text = { Text("如果只是重新登录已有账号，请取消并点击对应卡片的「重新授权」。继续将创建独立登记，即使邮箱相同也不会合并或覆盖已有连接。") },
        confirmButton = { TextButton(onClick = { addRegistration = false; login(null) }) { Text("新增登记") } },
        dismissButton = { TextButton(onClick = { addRegistration = false }) { Text("使用已有登记") } })
    welcome?.let { id -> AlertDialog(onDismissRequest = { }, title = { Text("ChatGPT 授权已验证") },
        text = { Text("你主动选择这个连接后，符合条件的 AI 请求将使用 ChatGPT 套餐。可随时到 ChatGPT 设置管理用量和授权；Orbis 不会在额度耗尽时自动改走付费 API。") },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { vault.acknowledgeWelcome(id) }; welcome = null; refresh() } }) { Text("知道了") } }) }
    signOut?.let { account -> AlertDialog(onDismissRequest = { signOut = null }, title = { Text("退出 ${account.label}？") },
        text = { Text("将停止该账号正在进行的请求，清除本机令牌并尝试撤销远端会话。聊天记录与角色不删除，其他账号不受影响。") },
        confirmButton = { TextButton(onClick = { signOut = null; work {
            val revoked = vault.signOut(account.id)
            notice = if (revoked) "已退出并撤销会话。" else "本机已退出；远端撤销未确认，请在 ChatGPT 设置中断开 Orbis 授权。"
        } }) { Text("退出") } }, dismissButton = { TextButton(onClick = { signOut = null }) { Text("取消") } }) }
}
