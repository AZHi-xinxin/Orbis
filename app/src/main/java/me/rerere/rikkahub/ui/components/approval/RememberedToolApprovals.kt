package me.rerere.rikkahub.ui.components.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.approval.ToolApprovalStore
import com.lover.connect.ui.components.StarSwitch

@Composable
fun RememberedToolApprovals(assistantId: String, approvalStore: ToolApprovalStore? = null) {
    val context = LocalContext.current
    val store = remember(context, approvalStore) { approvalStore ?: ToolApprovalStore.get(context) }
    val all by store.grants.collectAsState()
    val allAllowed by store.allowAllAssistants.collectAsState()
    val allowAll = assistantId in allAllowed && store.allowsAll(assistantId)
    val grants = all.filter { it.assistantId == assistantId }
    var error by remember(assistantId) { mutableStateOf<String?>(null) }
    fun revoke(stableId: String? = null) {
        error = runCatching { store.revoke(assistantId, stableId) }
            .exceptionOrNull()?.let { "撤销未能保存：本次运行已停止使用记住授权；请勿重启后继续自动调用，先排查存储并再次撤销。" }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已允许的工具", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("始终允许当前 AI 的所有已启用工具", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            StarSwitch(checked = allowAll, enabled = assistantId.isNotBlank(),
                modifier = Modifier.testTag("approval-allow-all"), onCheckedChange = { enabled ->
                    error = runCatching { store.setAllowAll(assistantId, enabled) }.exceptionOrNull()?.let {
                        if (enabled) "授权未能保存，本次没有开启全开。请检查本机存储后重试。"
                        else "关闭未能保存：本次运行已停止使用记住授权；请勿重启后继续自动调用，先排查存储并再次关闭。"
                    }
                })
        }
        Text("开启后，当前 AI 的本地、LC、Toy、云端、MCP 和工作区工具不再逐次确认，包括写入、锁应用等操作；之后你手动启用或更新的工具也适用。不会自动启用工具、授予手机系统权限或配置云端凭证，也不会代答需要你实际输入的提问。", style = MaterialTheme.typography.bodySmall)
        Text("只对当前 AI、本机安装生效；其他 AI 不受影响。工具目标、连接、参数旧批准仍会核验。打开开关不会自行执行之前等待中的调用；关闭不影响已经开始的操作。", style = MaterialTheme.typography.bodySmall)
        Text("关闭总开关后，单项「以后允许」仍保留；要一起取消请用下面的「撤销全部」。MCP／工作区原配置的免审批仍需在原设置关闭。", style = MaterialTheme.typography.bodySmall)
        if (allowAll) Text("全开已启用；下方单项记录暂不决定是否弹出审批，关闭总开关后可逐项管理。", style = MaterialTheme.typography.bodySmall)
        if (grants.isEmpty()) Text("暂无单项记住授权")
        grants.forEach { grant ->
            Row(Modifier.fillMaxWidth()) {
                Text(grant.approval.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !allowAll, onClick = { revoke(grant.approval.stableId) }) { Text("撤销") }
            }
        }
        Text("单项授权仅匹配当时的工具与连接；更换目标或定义后需重新允许。全开无需逐项重新允许，但不会让旧调用改投新目标。", style = MaterialTheme.typography.bodySmall)
        TextButton(modifier = Modifier.testTag("approval-revoke-all"), onClick = { revoke() }) { Text("撤销当前 AI 的全部授权（含全开）") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
