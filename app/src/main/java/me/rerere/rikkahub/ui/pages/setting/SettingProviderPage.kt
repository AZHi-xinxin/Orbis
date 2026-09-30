package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar as LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold as Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.AddProviderButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val store = org.koin.compose.koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val visibleProviders = remember(settings.providers) {
        me.rerere.rikkahub.ui.pages.setting.components.orbisVisibleModelConnections(settings.providers)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("模型连接") },
                subtitle = { Text("已有连接与自定义接口") },
                navigationIcon = { BackButton() },
                actions = {
                    // Import remains explicit; hidden presets are never removed from storage.
                    ImportProviderButton { imported ->
                        scope.launch {
                            try {
                                withContext(NonCancellable) {
                                    store.update { current ->
                                        check(!current.init)
                                        current.copy(providers = listOf(imported.copyProvider(Uuid.random())) + current.providers)
                                    }
                                }
                                toaster.show("连接已导入；尚未测试接口。", type = ToastType.Success)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                toaster.show("未能确认导入连接已保存，请检查后重试。", type = ToastType.Error)
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .consumeWindowInsets(innerPadding).imePadding().testTag("orbis-model-connections"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("add") {
                AddProviderButton(Modifier.fillMaxWidth())
            }
            item("heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已有连接 · ${visibleProviders.size}", style = MaterialTheme.typography.titleMedium)
                    Text("只显示你添加或调整过的连接。未配置的品牌预置已收起，原有数据没有删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("保存连接后，再获取或手动添加模型；启用开关不表示接口已经验证可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visibleProviders.isEmpty()) item("empty") {
                Text("还没有自定义连接。点上方按钮，填写自己的服务地址和 API Key。")
            }
            items(visibleProviders, key = { it.id }) { provider ->
                ProviderItem(provider = provider, modifier = Modifier.fillMaxWidth(),
                    dragHandle = {},
                    onClick = { navController.navigate(Screen.SettingProviderDetail(provider.id.toString())) })
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        handleQRResult(result, onAdd, toaster, context)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(HugeIcons.FileImport, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 主要操作：扫描二维码
                        Button(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Camera01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 次要操作：从相册选择
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleQRResult(
    result: QRResult,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        when (result) {
            is QRResult.QRError -> {
                toaster.show(
                    context.getString(
                        R.string.setting_provider_page_scan_error,
                        result
                    ), type = ToastType.Error
                )
            }

            QRResult.QRMissingPermission -> {
                toaster.show(
                    context.getString(R.string.setting_provider_page_no_permission),
                    type = ToastType.Error
                )
            }

            is QRResult.QRSuccess -> {
                val setting = decodeProviderSetting(result.content.rawValue ?: "")
                onAdd(setting)
            }

            QRResult.QRUserCanceled -> {}
        }
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        // 使用ImageUtils解析二维码
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}


@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
        onClick = {
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("${provider::class.simpleName} 接口", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                }
            }
            dragHandle()
        }
    }
}
