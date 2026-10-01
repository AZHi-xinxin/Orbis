package me.rerere.rikkahub.ui.pages.orbis

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.update.ORBIS_RELEASES_PAGE
import me.rerere.rikkahub.data.update.OrbisPreparedUpdate
import me.rerere.rikkahub.data.update.OrbisPublishedRelease
import me.rerere.rikkahub.data.update.OrbisUpdateService
import me.rerere.rikkahub.data.update.orbisReleaseIsNewer

/** Release notes are plain text, never executable HTML. Every download/install is an explicit user action. */
@Composable
fun OrbisUpdateDialog(onDismiss: () -> Unit, initialRelease: OrbisPublishedRelease? = null) {
    val context = LocalContext.current
    val service = remember(context) { OrbisUpdateService(context) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initialRelease) }
    var history by remember { mutableStateOf<List<OrbisPublishedRelease>?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var backedUp by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<OrbisPreparedUpdate?>(null) }
    var downloaded by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                message = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message?.take(260) ?: "更新操作未完成，原应用和数据没有改变。"
                else "更新服务或系统安装入口暂时不可用，请稍后重试或打开官方发布页；原数据未改动。"
            } finally { busy = false }
        }
    }

    fun openPage(url: String = ORBIS_RELEASES_PAGE) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { message = "没有可打开网页的应用。官方发布页：$ORBIS_RELEASES_PAGE" }
    }

    fun close() { job?.cancel(); onDismiss() }

    LaunchedEffect(Unit) {
        if (initialRelease == null) action {
            selected = service.check(force = true).latest
            prepared = null
        }
    }

    AlertDialog(onDismissRequest = ::close, title = { Text("Orbis 更新与历史版本") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall)
            Text("只检查 AZHi-xinxin/Orbis 的正式发布。自动检查最多每天一次，不会自动下载或安装，也不发送聊天或模型密钥。", style = MaterialTheme.typography.bodySmall)
            if (!service.canReplaceCurrentApp) Text("你当前使用的是 Dev／其他包名。正式版与它的数据独立，这里只提供正式发布页，不把正式 APK 当作 Dev 的覆盖更新。")
            if (busy) {
                if (total > 0) {
                    LinearProgressIndicator(progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("下载与校验：${downloaded / 1024 / 1024} / ${total / 1024 / 1024} MiB")
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            selected?.let { release ->
                Text(release.name ?: release.tag, style = MaterialTheme.typography.titleMedium)
                // Rendering external release notes as text avoids loading remote images or executing embedded markup.
                Text(release.body?.ifBlank { null } ?: "此版本未填写更新说明。")
                TextButton(onClick = { openPage(release.pageUrl) }) { Text("查看该版本的官方发布页") }
                val newer = orbisReleaseIsNewer(release.tag, BuildConfig.VERSION_NAME)
                val recovery = release.tag.contains("recovery", true) || release.tag.contains("rollback", true)
                val asset = runCatching { service.matchingAsset(release) }.getOrNull()
                if (!newer && !recovery) Text("该标签不是比当前版本更新的普通版本。低版本代码不能正常覆盖安装；请看下方回退说明。", style = MaterialTheme.typography.bodySmall)
                if (service.canReplaceCurrentApp && (newer || recovery) && asset != null) {
                    Text("本机安装包：${asset.name}（${asset.size / 1024 / 1024} MiB）。仅核对通过 SHA-256、包名、签名和更高版本代码后，才交给系统确认安装。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Checkbox(checked = backedUp, onCheckedChange = { backedUp = it }, enabled = !busy)
                        Text("我已备份重要聊天，并分别备份后花园与书库。普通聊天 ZIP 不包含所有独立数据。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (prepared == null) Button(enabled = backedUp && !busy, onClick = {
                        downloaded = 0; total = asset.size
                        action {
                            prepared = service.downloadAndVerify(release) { done, length -> downloaded = done; total = length }
                            total = 0
                            message = "校验完成。点击下方按钮，由 Android 再确认安装；尚未安装。"
                        }
                    }) { Text(if (recovery) "下载并核对兼容恢复包" else "下载并核对更新包") }
                    else Button(enabled = backedUp && !busy, onClick = {
                        action {
                            if (!service.installationPermissionGranted()) {
                                context.startActivity(service.permissionSettingsIntent())
                                message = "请在系统中允许 Orbis 安装更新，返回后再点此按钮；不会自动安装。"
                            } else {
                                context.startActivity(service.installerIntent(requireNotNull(prepared)))
                                message = "已交给系统安装界面。以系统结果为准，本页面不宣称安装成功。"
                            }
                        }
                    }) { Text("安装／允许安装来源") }
                } else if (service.canReplaceCurrentApp && asset == null) {
                    Text("没有找到适合本机的唯一 APK，可到发布页查看。32 位手机不能安装当前的 64 位通用包。")
                }
            }
            TextButton(enabled = !busy, onClick = { action { selected = service.check(force = true).latest; prepared = null; backedUp = false } }) { Text("重新检查最新版本") }
            TextButton(enabled = !busy, onClick = {
                historyOpen = !historyOpen
                if (historyOpen && history == null) action { history = service.history() }
            }) { Text("历史版本与回退说明") }
            if (historyOpen) {
                Text("回退不是一键撤销：Android 通常禁止低 versionCode 覆盖。不要为回退直接卸载或清除数据；旧程序也可能不兼容新数据。这里不自动降级、不卸载、不恢复旧备份。")
                Text("可在历史发布页查看说明和下载。只有官方重新签发、同包名同签名且版本代码更高的 recovery／rollback 兼容恢复包，才能通过本页检查后交给系统安装；这也不保证旧数据结构兼容。", style = MaterialTheme.typography.bodySmall)
                history.orEmpty().forEach { release ->
                    TextButton(enabled = !busy, onClick = {
                        selected = release; prepared = null; backedUp = false; message = null
                    }) { Text("查看 ${release.name ?: release.tag}") }
                }
            }
            TextButton(onClick = { openPage() }) { Text("打开全部正式发布与下载") }
        } },
        confirmButton = { TextButton(onClick = ::close) { Text(if (busy) "取消并关闭" else "关闭") } },
    )
}
