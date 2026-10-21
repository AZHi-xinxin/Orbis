package me.rerere.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import me.rerere.rikkahub.ui.pages.backup.tabs.AutomaticBackupTab
import me.rerere.rikkahub.ui.pages.backup.tabs.WebDavTab
import me.rerere.rikkahub.ui.pages.backup.tabs.S3Tab
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
    var tab by remember { mutableStateOf(0) }
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, topBar = {
            OrbisPageHeader(title = "数据与存档", subtitle = "本地先保存，服务器由你绑定",
                avatar = { Text("↥") }, navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding())
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableTabRow(selectedTabIndex = tab) {
                    listOf("自动存档", "文件导入导出", "WebDAV 连接", "S3 连接").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                when(tab) {
                    0 -> AutomaticBackupTab(onRestart = { restart = true }, onConnection = { tab = if(it == "webdav") 2 else 3 })
                    1 -> ImportExportTab(vm = vm, onShowRestartDialog = { restart = true })
                    2 -> WebDavTab(vm = vm, onShowRestartDialog = { restart = true })
                    3 -> S3Tab(vm = vm, onShowRestartDialog = { restart = true })
                }
            }
        }
    }
    if (restart) BackupDialog()
}
