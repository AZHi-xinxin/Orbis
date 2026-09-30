package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.orbis.integration.OrbisIntegration
import me.rerere.rikkahub.data.orbis.integration.OrbisIntegrationConnections
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

/** TechHub agents and multi-provider AI rooms are deliberately separate products. */
@Composable
fun OrbisGroupLandingPage() {
    val navigator = LocalNavController.current
    val hub by koinInject<OrbisIntegrationConnections>()[OrbisIntegration.TECH_HUB].state.collectAsStateWithLifecycle()
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink,
            topBar = { OrbisPageHeader(title = "群聊", subtitle = "各自的声音，在同一个窗口相遇",
                avatar = { Icon(HugeIcons.UserGroup, null) }, navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding()) },
            bottomBar = { OrbisChatDock("当前 群聊") }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hub.available) OrbisNavigationCard(OrbisNavigationItem("tech-hub", "TechHub", "协作消息 · 独立服务", HugeIcons.UserGroup,
                    { navigator.navigate(Screen.OrbisTechHub) }), Modifier.fillMaxWidth())
                OrbisNavigationCard(OrbisNavigationItem("ai-group", "AI 群聊", "添加成员 · 各自的连接和模型", HugeIcons.UserGroup,
                    { navigator.navigate(Screen.OrbisAiGroups) }), Modifier.fillMaxWidth())
                Text("AI 群聊不需要部署 TechHub。群内记录与私人聊天分开；下方星星随时回到当前私人聊天。",
                    style = MaterialTheme.typography.bodyMedium, color = OrbisTheme.colors.mutedInk)
            }
        }
    }
}
