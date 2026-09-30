package me.rerere.rikkahub.ui.pages.orbis.toy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.toy.OrbisToyController
import me.rerere.rikkahub.data.orbis.toy.ToyNearbyDevice
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageHeader
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageSurface
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** Single device-management entry. Opening the page never requests a permission or scans. */
@Composable
fun OrbisToyDevicePage(onBack: () -> Unit, toolSettings: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val controller = remember(context) { OrbisToyController.get(context) }
    val state by controller.state.collectAsState()
    val discovery by controller.discovery.collectAsState()
    val scope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<ToyNearbyDevice?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (controller.hasScanPermissions()) { permissionMessage = null; controller.startScan() }
        else permissionMessage = "未获得扫描所需权限，不能扫描新设备；仅缺少扫描权限不会阻止已连接设备的控制或停止。"
    }
    DisposableEffect(controller) { onDispose { controller.stopScan() } }
    OrbisPageSurface {
        Scaffold(containerColor = Color.Transparent, contentColor = OrbisTheme.colors.ink, topBar = {
            OrbisPageHeader(title = "蓝牙 Toy", subtitle = "手机直连 · 手动选设备 · 原版五档",
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                modifier = Modifier.statusBarsPadding())
        }) { insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { toolSettings() }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(state.deviceName ?: "尚未选择设备", style = MaterialTheme.typography.titleMedium)
                            Text(state.message)
                            if (state.requestedIntensity != null) Text("已请求强度 ${state.requestedIntensity}（${state.level} 档）· 持续到停止")
                            if (state.connected) Text("已连接 ${state.deviceNames.size} 台设备")
                            if (state.physicalStopUncertain) Text("无法确认设备已物理停止，请检查实体开关。", color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { scope.launch { controller.stop() } }) { Text("停止") }
                                OutlinedButton(onClick = { controller.stopAndDisconnect() }) { Text("停止并断开") }
                            }
                        }
                    }
                }
                item {
                    Text("沿用原版 FFE0 协议及五档映射，支持手动添加多台设备；不能保证所有蓝牙设备兼容。AI 只能控制你选定且已连接的设备，连接名单变化后旧授权不再适用。")
                    Text("非零强度每 1.4 秒重发，直到你或 AI 明确停止；没有额外时长上限，切到后台不会被主动断开。持续运行中加入的新设备也会跟随当前强度。系统休眠、杀进程或蓝牙断线仍可能中断控制，无法保证后台持续运行或物理停机；请保留实体开关的操作条件。",
                        Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Button(enabled = !discovery.connecting && !discovery.scanning,
                        onClick = {
                            if (controller.hasScanPermissions()) controller.startScan()
                            else permissions.launch(OrbisToyController.requiredScanPermissions())
                        }) { Text(if (discovery.connecting) "正在连接…" else if (discovery.scanning) "扫描中…" else "授权并扫描我的设备") }
                    if (discovery.scanning) TextButton(onClick = controller::stopScan) { Text("停止扫描") }
                    permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (discovery.message.isNotBlank()) Text(discovery.message)
                }
                items(discovery.devices, key = { it.selectionId }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(device.name); Text("编号尾号 ${device.suffix}") }
                            TextButton(enabled = !discovery.connecting, onClick = { selection = device }) { Text("选择") }
                        }
                    }
                }
                item { Text("ADB Toy 仍使用原 MCP，不属于这个蓝牙入口。此处不访问旧 HTML 的公网轮询服务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    selection?.let { selected ->
        AlertDialog(onDismissRequest = { selection = null }, title = { Text("连接这台设备？") },
            text = { Text("${selected.name} · 尾号 ${selected.suffix}\n请确认这是你自己的设备。" +
                if (state.requestedIntensity != null) "当前有持续强度 ${state.requestedIntensity}；新设备连接后也会跟随当前强度。" else "当前未运行；连接后等待设置强度。") },
            confirmButton = { TextButton(onClick = { selection = null; controller.connectSelected(selected.selectionId) }) { Text("确认连接") } },
            dismissButton = { TextButton(onClick = { selection = null }) { Text("取消") } })
    }
}
