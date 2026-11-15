package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.setting.OrbisCommunityCard

/** Human-requested invitation instructions; opening this card never issues or sends a code. */
@Composable
internal fun OrbisInvitationGuide() {
    var showGroup by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("orbis-invitation-guide"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("还没有邀请码？", style = MaterialTheme.typography.titleSmall)
        Text("一个邀请码注册的 Orbis 账号，通用社交、剧本杀、桌游和市场。已有账号直接登录，无需分别申请。",
            style = MaterialTheme.typography.bodySmall)
        Text("在 Orbis 设置 → 关于与开源许可中加入 QQ 交流群，向群管理员登记收件邮箱。我们会通过邮件发放邀请码，无需添加开发者微信。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showGroup = true }, modifier = Modifier.testTag("invitation-open-group")) { Text("查看 QQ 群入口") }
    }
    if (showGroup) OrbisInterfaceTheme {
        ModalBottomSheet(onDismissRequest = { showGroup = false },
            containerColor = OrbisTheme.colors.page, contentColor = OrbisTheme.colors.ink) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                OrbisCommunityCard()
                TextButton(onClick = { showGroup = false }) { Text("返回申请指引") }
            }
        }
    }
}
