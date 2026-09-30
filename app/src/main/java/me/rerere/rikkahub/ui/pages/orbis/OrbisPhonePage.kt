package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lover.connect.BridgeDashboard
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.rikkahub.ui.components.nav.BackButton

/** One native settings entry. Rendering never starts LC or requests permissions. */
@Composable
fun OrbisPhonePage(hostVisible: Boolean = true) {
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink, topBar = {
            OrbisPageHeader(title = "手机与陪伴", subtitle = "内嵌陪伴功能 · 独立授权与配置",
                avatar = { Icon(HugeIcons.SecurityCheck, contentDescription = null) },
                navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding())
        }) { padding ->
            val colors = OrbisTheme.colors
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                primary = colors.accent, onPrimary = colors.onAccent,
                surface = colors.panel, surfaceContainer = colors.panel,
                onSurface = colors.ink, onSurfaceVariant = colors.mutedInk,
                outlineVariant = colors.border,
            )) {
                if (hostVisible) BridgeDashboard(Modifier.padding(padding), legacyAutomationReadOnly = true, headerContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OrbisIntegrationSettingsEntry(me.rerere.rikkahub.data.orbis.integration.OrbisIntegration.TECH_HUB)
                        OrbisNativeToolSelectionPanel(OrbisNativeToolSection.COMPANION)
                    }
                }, sentinelContent = { OrbisSentinelSettingsPanel() })
            }
        }
    }
}
