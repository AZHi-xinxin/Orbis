package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.model.normalizeOrbisCloudHomeUrl
import me.rerere.rikkahub.data.model.validOrbisGardenName
import me.rerere.rikkahub.data.orbis.OrbisCloudSettingsStore
import org.koin.compose.koinInject

/** Local configuration only. Reading this entry never opens a website or tests a connection. */
@Composable
fun OrbisCloudSettingsEntry() {
    val store = koinInject<OrbisCloudSettingsStore>()
    val state by store.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = OrbisTheme.colors
    Surface(color = colors.panel, contentColor = colors.ink,
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.border.copy(alpha = .65f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp)) {
            OrbisSettingsLink("后花园 · 存储与称呼",
                if (!state.loaded) "正在读取本机设置"
                else if (state.error != null) "设置读取失败 · 点击查看与重试"
                else if (state.config.enabled) "已授权连接 · 管理地址与连接"
                else "本地存储 · 无需 VPS，可选连接自建原站",
                if (state.loaded) ({ open = true }) else null)
        }
    }
    if (open) OrbisCloudConnectionSheet(onDismiss = { open = false })
}

/** Public entry for the native home header and settings page. No credentials cross into this editor. */
@Composable
fun OrbisCloudConnectionSheet(onDismiss: () -> Unit) {
    val store = koinInject<OrbisCloudSettingsStore>()
    val state by store.state.collectAsStateWithLifecycle()
    OrbisCloudConnectionEditor(
        loaded = state.loaded,
        config = state.config,
        onSave = store::save,
        onDismiss = onDismiss,
        loadError = state.error,
        canEdit = state.canEdit,
        onReload = store::reload,
    )
}

/** Pure UI seam for synthetic tests; the draft is independent from subsequent store emissions. */
@Composable
internal fun OrbisCloudConnectionEditor(
    loaded: Boolean,
    config: OrbisCloudHomeConfig,
    onSave: suspend (OrbisCloudHomeConfig) -> Unit,
    onDismiss: () -> Unit,
    loadError: String? = null,
    canEdit: Boolean = true,
    onReload: suspend () -> Unit = {},
) {
    var saving by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var retryError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(
            onDismissRequest = { if (!saving) onDismiss() },
            containerColor = colors.panel,
            contentColor = colors.ink,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            if (!loaded || !canEdit) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("后花园 · 存储与称呼", style = MaterialTheme.typography.titleLarge)
                    Text(retryError ?: loadError ?: "正在读取本机设置…")
                    if (loadError != null) {
                        Button(onClick = {
                            retrying = true
                            retryError = null
                            scope.launch {
                                try {
                                    onReload()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    retryError = "本机设置暂未读取成功，请稍后重试。"
                                } finally {
                                    retrying = false
                                }
                            }
                        }, enabled = !retrying, modifier = Modifier.testTag("orbis-cloud-reload")) {
                            Text(if (retrying) "读取中…" else "重新读取设置")
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            } else {
                var draft by rememberSaveable { mutableStateOf(config.homeUrl) }
                var gardenName by rememberSaveable { mutableStateOf(config.gardenName) }
                var humanName by rememberSaveable { mutableStateOf(config.humanName) }
                var companionName by rememberSaveable { mutableStateOf(config.companionName) }
                var confirmationUrl by rememberSaveable { mutableStateOf<String?>(null) }
                var error by rememberSaveable { mutableStateOf<String?>(null) }
                val normalized = normalizeOrbisCloudHomeUrl(draft)
                val sameConnection = config.enabled && normalized == config.homeUrl

                fun persist(next: OrbisCloudHomeConfig) {
                    if (saving) return
                    saving = true
                    confirmationUrl = null
                    error = null
                    scope.launch {
                        try {
                            onSave(next)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "设置暂未保存成功，填写的地址还在这里，可以重试。"
                        } finally {
                            saving = false
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("后花园 · 存储与称呼", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("两条路线任选：本地无需账号或 VPS；自建云端可连接由你自己的 Supabase 支撑的 Orbis 网站。", style = MaterialTheme.typography.bodyMedium)
                    OrbisSettingsNote(if (config.enabled) "当前：自建远端 · 原站数据仍在原站" else "当前：本地新空间 · SQLite 保存在这部手机")
                    Text("切换不搬运、复制或删除任何一边的数据。本地日记、锚点、信件、心愿、歌曲和书库记录保存在手机。AI 共读与主持需要你自己的模型连接，但不需要 VPS。卸载、清除数据或换手机前，请分别导出花园与书库备份。", style = MaterialTheme.typography.bodySmall)
                    loadError?.let { OrbisSettingsNote(it) }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; error = null; confirmationUrl = null },
                        enabled = !saving,
                        label = { Text("原主页 HTTPS 地址") },
                        placeholder = { Text("https://example.netlify.app") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = false,
                        maxLines = 3,
                        isError = draft.isNotBlank() && normalized == null,
                        supportingText = {
                            Text(if (draft.isNotBlank() && normalized == null)
                                "请填写有效的 HTTPS 主页地址。"
                            else "填写自建网站主页，不是 Supabase 项目 API 地址；原站登录在网页内完成，不在这里填写数据库管理密钥。")
                        },
                        modifier = Modifier.fillMaxWidth().testTag("orbis-cloud-home-url"),
                    )
                    Text("保存地址会使用本地空间；连接原主页时会再次向你确认。",
                        style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                    if (config.enabled) {
                        Text("保存新地址会关闭当前云端连接，新地址需要重新授权。",
                            style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(
                        onClick = { normalized?.let { persist(config.copy(enabled = false, homeUrl = it)) } },
                        enabled = !saving && normalized != null,
                        modifier = Modifier.fillMaxWidth().testTag("orbis-cloud-save-address"),
                    ) { Text("保存地址（暂不连接）") }
                    Button(
                        onClick = { confirmationUrl = normalized },
                        enabled = !saving && normalized != null && !sameConnection,
                        modifier = Modifier.fillMaxWidth().testTag("orbis-cloud-connect"),
                    ) { Text(if (sameConnection) "已连接原主页" else "连接原主页") }
                    if (config.enabled) {
                        TextButton(
                            onClick = { persist(config.copy(enabled = false)) },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().testTag("orbis-cloud-disconnect"),
                        ) { Text("使用本地新空间，保留远端地址") }
                    }
                    Text("本地花园称呼（不改助手名、人格或聊天）", style = MaterialTheme.typography.titleSmall)
                    listOf(Triple("花园名称", gardenName, "orbis-garden-name"),
                        Triple("我的称呼", humanName, "orbis-garden-human"),
                        Triple("伙伴称呼", companionName, "orbis-garden-companion")).forEachIndexed { index, (label, value, tag) ->
                        OutlinedTextField(value = value, onValueChange = {
                            when (index) { 0 -> gardenName = it; 1 -> humanName = it; else -> companionName = it }
                        }, enabled = !saving, label = { Text(label) }, singleLine = true,
                            isError = !validOrbisGardenName(value), modifier = Modifier.fillMaxWidth().testTag(tag))
                    }
                    Text("每项 1–32 字。称呼只影响本地花园的新记录署名与展示；原记录、远端网页和助手配置保持不变。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { persist(config.copy(gardenName = gardenName, humanName = humanName, companionName = companionName)) },
                        enabled = !saving && listOf(gardenName, humanName, companionName).all(::validOrbisGardenName),
                        modifier = Modifier.fillMaxWidth().testTag("orbis-garden-save-names")) { Text("保存本地称呼（不切换存储）") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (saving) Text("保存中…", modifier = Modifier.padding(12.dp), color = colors.mutedInk)
                        TextButton(onClick = onDismiss, enabled = !saving,
                            modifier = Modifier.testTag("orbis-cloud-cancel")) { Text("取消") }
                    }
                }

                confirmationUrl?.let { target ->
                    AlertDialog(
                        onDismissRequest = { if (!saving) confirmationUrl = null },
                        title = { Text("在本机连接这个原主页？") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SelectionContainer {
                                    Text(target, modifier = Modifier.testTag("orbis-cloud-confirm-url"),
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Text("确认后，此站会联网读取和保存云端数据；原站的编辑、游戏和自动同步按原站规则运行。")
                                Text("请在原站登录界面亲自输入登录信息。当前聊天继续保留，可随时通过原生按钮返回。")
                                Text("切换到聊天、连接设置或后台前，请先保存网页中的编辑。")
                                Text("你可以在本机关闭云端连接；已保存到云端的内容仍由原站管理。")
                                Text("本地花园不会自动上传或同步到此站；本站功能和费用由你自行部署的服务决定。原有 MCP / 云工具配置不会被替换。")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { persist(config.copy(enabled = true, homeUrl = target)) },
                                enabled = !saving, modifier = Modifier.testTag("orbis-cloud-confirm-connect")) {
                                Text("确认连接")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmationUrl = null }, enabled = !saving,
                                modifier = Modifier.testTag("orbis-cloud-confirm-cancel")) { Text("暂不连接") }
                        },
                        containerColor = colors.panel,
                        titleContentColor = colors.ink,
                        textContentColor = colors.ink,
                    )
                }
            }
        }
    }
}
