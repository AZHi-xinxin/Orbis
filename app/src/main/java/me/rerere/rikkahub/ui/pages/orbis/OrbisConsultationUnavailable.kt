package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton

/** A static public placeholder: no stores, credentials, jobs or clients are constructed. */
@Composable
internal fun OrbisConsultationUnavailablePage() {
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink,
            topBar = { OrbisPageHeader("咨询室", subtitle = "正在开发，暂未开放", compact = true,
                navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding()) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("正在开发，暂未开放", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun OrbisConsultationUnavailableDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("咨询室") },
        text = { Text("正在开发，暂未开放") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
