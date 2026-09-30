package me.rerere.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.pages.backup.tabs.ImportExportTab
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageHeader
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageSurface
import org.koin.androidx.compose.koinViewModel

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    var restart by remember { mutableStateOf(false) }
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, topBar = {
            OrbisPageHeader(title = "数据与本地备份", subtitle = "文件由你保管，不自动上传云端",
                avatar = { Text("↥") }, navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding())
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ImportExportTab(vm = vm, onShowRestartDialog = { restart = true })
            }
        }
    }
    if (restart) BackupDialog()
}
