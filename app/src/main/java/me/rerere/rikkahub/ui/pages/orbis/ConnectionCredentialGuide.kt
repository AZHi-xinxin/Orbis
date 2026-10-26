package me.rerere.rikkahub.ui.pages.orbis

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

/** Human-only credential viewing/export. No tool callback, saved state, chat attachment or logging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionCredentialGuide(owner: String, entries: List<ConnectionProposal>,
    onClose: () -> Unit, onContinue: () -> Unit,
    store: SettingsStore = koinInject(), repository: WorkspaceRepository = koinInject()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = entries.map { it.draft.credentialFile }.filter(String::isNotBlank).distinct()
    val workspace = store.settingsFlow.value.assistants.firstOrNull { it.id.toString() == owner }?.workspaceId?.toString()
    var credentials by remember(owner, workspace, files) { mutableStateOf<DeploymentCredentials?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val upstream = runCatching { deploymentUpstream(store.settingsFlow.value, owner) }.getOrNull()
    fun stillCurrent(): Boolean = workspace != null && store.settingsFlow.value.assistants
        .any { it.id.toString() == owner && it.workspaceId?.toString() == workspace } &&
        store.settingsFlow.value.connectionProposals.filter { it.owner == owner }.any { it.draft.credentialFile in files }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val value = credentials
        if (uri != null && value != null && stillCurrent()) scope.launch {
            notice = try {
                withContext(Dispatchers.IO) {
                    check(stillCurrent())
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(value.humanExport()) }
                }
                "已保存到你选择的位置。请妥善保管，不要发进聊天。"
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { "没有确认保存成功，请重新选择保存位置。" }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OrbisTheme.colors.page, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp).testTag("connection-credential-guide")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (files.isEmpty()) "准备 ST 连接" else "先保存你的连接密钥", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("收起") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (upstream != null) {
                    Text("部署前 · 使用当前模型", style = MaterialTheme.typography.titleMedium)
                    Text("${upstream.model}\n${upstream.baseUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("如果 ST 要使用这个模型，点下方按钮，把该连接的凭据交给当前助手工作区中的部署程序。只用于你指定的部署目标；不会填进聊天。若这里已是旧 ST，请先切回原模型。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy, modifier = Modifier.testTag("connection-prepare-upstream"), onClick = {
                        busy = true
                        scope.launch {
                            notice = try {
                                withContext(Dispatchers.IO) {
                                    val current = deploymentUpstream(store.settingsFlow.value, owner)
                                    check(current.workspace == upstream.workspace && current.privateJson() == upstream.privateJson())
                                    repository.writeNativeText(current.workspace, ST_UPSTREAM_FILE, current.privateJson(), overwrite = true)
                                }
                                "已准备好。回到聊天告诉 AI「当前模型资料已准备好」，由部署脚本读取 $ST_UPSTREAM_FILE；不要让 AI 打印文件正文。"
                            } catch (c: CancellationException) { throw c }
                            catch (_: Exception) { "没有完成准备，请先确认当前模型和工作区，再重试。" }
                            finally { busy = false }
                        }
                    }) { Text("将此模型凭据准备到当前工作区") }
                    HorizontalDivider()
                }
                Text("这是新 ST 的两把钥匙，由部署时自动生成，不是模型服务商的 API Key。")
                Text("① 点下方查看。② 另存到你自己保管的位置。③ 已妥善保存后去填写：网关对应网关密钥，MCP 对应 MCP 密钥。")
                if (files.size == 1 && workspace != null) {
                    Text("位置：当前助手工作区 → 文件 → ${files.single().removePrefix("/workspace/")}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true; credentials = null
                        scope.launch {
                            try {
                                val value = withContext(Dispatchers.IO) {
                                    check(stillCurrent())
                                    val chunk = repository.readNativeChunk(workspace, files.single(), 0, 16384, null)
                                    check(chunk.eof && chunk.totalBytes <= 16384)
                                    parseDeploymentCredentials(chunk.text).also { check(stillCurrent()) }
                                }
                                credentials = value; notice = "仅在此页显示，不回传给 AI。"
                            } catch (c: CancellationException) { throw c }
                            catch (_: Exception) { notice = "没有读到完整的两把 ST 密钥。请让 AI 核对是否已把部署凭据文件保存到当前工作区；不要把文件正文发进聊天。" }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "正在读取…" else "查看部署密钥") }
                } else {
                    Text("还没有可查看的部署凭据文件。告诉 AI：“请把部署生成的两把 ST 密钥留在我的工作区凭据文件中，再为连接建议提供 credential_file 路径；不要把密钥发给我。”")
                    Text("如果你已从服务提供方保存了密钥，可以直接去填写。")
                }
                credentials?.let { value ->
                    NativeCredentialRow("ST 网关密钥", value.gateway)
                    NativeCredentialRow("ST MCP 密钥", value.mcp)
                    Button(onClick = { export.launch("ST-连接密钥.txt") }) { Text("另存两把密钥") }
                }
                notice?.let { Text(it, color = OrbisTheme.colors.accent) }
                Text("收起后可继续看聊天；输入框上方的「连接设置」可以随时拉起。", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("已妥善保存，去填写") }
        }
    }
}

@Composable private fun NativeCredentialRow(label: String, value: String) {
    val context = LocalContext.current
    var visible by remember(value) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = OrbisTheme.colors.panel) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(if (visible) value else "••••••••••••••••", modifier = Modifier.padding(vertical = 8.dp))
            Row {
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText(label, value)
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }) { Text("复制") }
            }
        }
    }
}
