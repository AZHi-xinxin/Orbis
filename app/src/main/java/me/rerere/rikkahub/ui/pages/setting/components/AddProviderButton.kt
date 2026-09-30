package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/** A shared, labelled entry from settings and the model picker. Drafts remain local until saved. */
@Composable
fun AddProviderButton(modifier: Modifier = Modifier, onAdded: () -> Unit = {}) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProviderSetting?>(null) }
    var saving by remember { mutableStateOf(false) }

    Button(
        onClick = { draft = ProviderSetting.OpenAI(name = "自定义连接", baseUrl = "") },
        enabled = !settings.init && !saving,
        modifier = modifier.testTag("add-model-connection"),
    ) { Text("添加自定义连接") }

    draft?.let { provider ->
        AddProviderDialog(
            provider = provider,
            saving = saving,
            onChange = { draft = it },
            onDismiss = { draft = null },
            onSave = {
                if (!saving && provider.isValidModelConnectionDraft()) {
                    saving = true
                    scope.launch {
                        try {
                            withContext(NonCancellable) {
                                settingsStore.update { current ->
                                    check(!current.init)
                                    // Preserve all existing connections and model selections.
                                    current.copy(providers = listOf(provider) + current.providers.filterNot { it.id == provider.id })
                                }
                            }
                            draft = null
                            onAdded()
                            navigator.navigate(Screen.SettingProviderDetail(provider.id.toString(), showModels = true))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            toaster.show("未能确认连接已保存，请重试。", type = ToastType.Error)
                        } finally {
                            saving = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun AddProviderDialog(
    provider: ProviderSetting,
    saving: Boolean,
    onChange: (ProviderSetting) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
    ) {
        Surface(
            modifier = modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .fillMaxHeight(.9f).imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("添加自定义连接", style = MaterialTheme.typography.titleLarge)
                // One bounded scroll viewport, with actions outside it. It stays scrollable
                // on a short display and when the software keyboard reduces the dialog height.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("model-connection-form"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("自行填写连接名称、服务地址和 API Key；协议不是提供商品牌。保存只保存配置，不代表接口已经可用。")
                    ProviderConfigure(provider = provider, onEdit = { if (!saving) onChange(it) })
                    Text("保存后可获取或手动添加模型，再选择当前聊天模型。兼容 OpenAI 的服务选择 OpenAI 协议。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("model-connection-form-end"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !saving,
                        modifier = Modifier.testTag("cancel-model-connection")) { Text("取消") }
                    Button(onClick = onSave, enabled = !saving && provider.isValidModelConnectionDraft(),
                        modifier = Modifier.weight(1f).testTag("save-model-connection")) {
                        Text(if (saving) "正在保存…" else "保存并添加模型")
                    }
                }
            }
        }
    }
}
