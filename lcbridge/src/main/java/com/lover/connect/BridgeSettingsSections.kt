package com.lover.connect

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun BridgeRuntimeSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        while (true) {
            val runtime = JSONObject(McpServiceController.runtimeStatusJson(context))
            val running = runtime.optBoolean("mcp_service_alive")
            val ready = runtime.optBoolean("native_runtime_ready")
            val listening = runtime.optBoolean("mcp_server_listening")
            val lastError = runtime.optString("native_runtime_last_error").ifBlank {
                runtime.optString("native_runtime_recovery_last_error")
            }
            val phaseLabel = when (runtime.optString("native_runtime_phase")) {
                "created" -> "创建中"
                "initializing" -> "准备中"
                "ready" -> "就绪"
                "failed" -> "启动失败"
                "disabled" -> "已关闭"
                "stopped", "not_running" -> "未运行"
                else -> "未知"
            }
            status = "运行意图：${if (runtime.optBoolean("mcp_desired_enabled")) "已启用" else "关闭"}" +
                " · 进程服务：${if (running) "运行中" else "未运行"}" +
                "\n原生工具：${if (ready) "已就绪" else "未就绪"}" +
                " · 阶段：$phaseLabel" +
                "\n兼容 MCP 端口：${if (listening) "监听中" else "未监听"}（原生工具不依赖此端口）" +
                (if (lastError.isBlank()) "" else "\n最近运行错误类型：$lastError")
            delay(2_000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, fontSize = 13.sp)
            Text("启动陪伴、开启屏幕观察、位置追踪和通知内容是不同授权。首次使用保持关闭；已经启用的陪伴会在返回 Orbis 时尝试恢复，不会自动授予权限。请先停止旧独立应用的相应能力，避免两套同时观察或通知。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("这里使用 Orbis 的独立沙箱和 Android Keystore；旧独立应用的权限、坐标、API 密钥与运行状态不会继承。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BridgePermissionRequests() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) "系统已允许本次申请的权限" else "未授予权限；对应能力保持不可用"
    }
    if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
        request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }, modifier = Modifier.fillMaxWidth()) { Text("申请系统通知权限") }
    if (Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = {
        request.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }, modifier = Modifier.fillMaxWidth()) { Text("申请系统体力活动权限") }
    if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        notice = if (alarm.canScheduleExactAlarms()) "系统已允许精确闹钟" else {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")))
            }.fold({ "请在系统页面决定是否允许" }, { "系统未提供入口，请在本应用系统设置中查找闹钟权限" })
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("精确闹钟授权状态 / 系统设置") }
    if (notice.isNotEmpty()) Text(notice, fontSize = 12.sp)
}

@Composable
internal fun BridgeSentinelSection(legacyAutomationReadOnly: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lc_config", Context.MODE_PRIVATE) }
    if (legacyAutomationReadOnly) {
        BridgeLegacySentinelReadOnlyCard(
            sendingEnabled = prefs.getBoolean("sentinel_enabled", false),
            endpointConfigured = !prefs.getString("sentinel_url", "").isNullOrBlank(),
            credentialConfigured = !prefs.getString("sentinel_token", "").isNullOrBlank(),
        )
        return
    }
    var url by remember { mutableStateOf(prefs.getString("sentinel_url", "").orEmpty()) }
    var token by remember { mutableStateOf(prefs.getString("sentinel_token", "").orEmpty()) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("sentinel_enabled", false)) }
    var notice by remember { mutableStateOf("") }
    Text("哨兵事件与外部自我唤醒", fontSize = 18.sp)
    Text("手机原生引擎发送围栏、视觉和连续使用事件；需要兼容 ingress 服务。保存不会发送测试、不改变服务器会话、不复制旧独立应用的凭证。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("启用事件发送（保存后生效）")
        Switch(enabled, { enabled = it })
    }
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Ingress URL（/loverconnect/alert）") })
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(), label = { Text("Ingress token（仅本机保存）") })
    Button(onClick = {
        val target = url.trim()
        val secret = token.trim()
        notice = if (enabled && (!SentinelEndpointPolicy.isAllowed(target) ||
                SentinelEndpointPolicy.locationEventsUrl(target) == null || secret.length < 16)) {
            "未保存：请填写有效的 HTTPS 或本机/私有网络 alert 地址及至少 16 字符 token。"
        } else {
            val saved = prefs.edit().putString("sentinel_url", target).putString("sentinel_token", secret)
                .putBoolean("sentinel_enabled", enabled).commit()
            if (saved) "配置已保存；尚未发送测试。关闭发送不会删除已有待发围栏事件。"
            else "保存失败，原配置未确认更改"
        }
    }) { Text("保存哨兵配置") }
    if (notice.isNotBlank()) Text(notice, fontSize = 12.sp)
    Text("验证入口：连接本机 MCP 后，明确授权一次 test_sentinel（会真实发送消息）。get_runtime_status 可只读检查本机运行。返回 accepted 不等于下游已显示、回复或手机已通知。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("模型自我唤醒是另一项 MCP 服务：schedule_wakeup / list_wakeups / cancel_wakeup。请在 Orbis MCP 管理中手动添加并选用，先用 list_wakeups 只读验证。这里不部署该服务，也不能修改其固定投递会话；仍指向 RikkaHub 的服务器不会自动改投 Orbis。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("老哨兵的晨晚窗口、静默门槛、概率和冷却由服务器管理，不是此页的本地开关。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun BridgeInterventionSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lc_config", Context.MODE_PRIVATE) }
    var packages by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("30") }
    var overlay by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var focusPackages by remember { mutableStateOf(prefs.getStringSet("focus_rikka_packages", emptySet()).orEmpty().joinToString("\n")) }
    var focusEnabled by remember { mutableStateOf(prefs.getBoolean("focus_rikka_enabled", false)) }
    var redirectPackages by remember { mutableStateOf(prefs.getStringSet("redirect_rikka_packages", emptySet()).orEmpty().joinToString("\n")) }
    var window by remember { mutableStateOf(prefs.getString("redirect_rikka_window", "").orEmpty()) }
    var locked by remember { mutableStateOf(AppLockManager.getLockedApps(context)) }
    var notice by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val interventionsSupported = remember { DeviceCompatibility.activeAppInterventionsSupported() }
    fun parse(raw: String) = raw.split(',', '\n', ' ').map(String::trim).filter(String::isNotEmpty).toSet()
    fun applyResult(result: Result<*>) {
        notice = if (result.isSuccess) "已保存；执行仍取决于无障碍授权和设备兼容性" else "未保存：目标或参数无效"
        locked = AppLockManager.getLockedApps(context)
    }
    Text("应用限制与回到 Orbis", fontSize = 18.sp)
    Text("只作用于你填写的娱乐应用包名；系统、安全、支付、导航、聊天及 Orbis 自身受保护。需手动授予无障碍，部分品牌仅支持被动观察。启用操作需再次确认。",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(packages, { packages = it }, Modifier.fillMaxWidth(), label = { Text("锁定包名（一次一个）") })
    OutlinedTextField(duration, { duration = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("锁定分钟（0 为手动解除，最多 10080）") })
    OutlinedTextField(message, { message = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("可选提醒（最多 80 字符）") })
    Row(verticalAlignment = Alignment.CenterVertically) { Text("显示拦截提示"); Switch(overlay, { overlay = it }) }
    Button(onClick = {
        confirm = {
            val minutes = duration.toIntOrNull()
            if (!interventionsSupported) notice = "当前设备为被动兼容模式，未写入任何限制"
            else if (minutes == null || minutes !in 0..10080 || parse(packages).size != 1) notice = "请输入一个包名和有效分钟数"
            else applyResult(runCatching {
                AppLockManager.lock(context, packages.trim(), minutes, message, overlay).getOrThrow()
            })
        }
    }) { Text("确认添加应用限制…") }
    Text("当前限制：${locked.joinToString().ifBlank { "无" }}", fontSize = 12.sp)
    locked.forEach { pkg -> TextButton(onClick = {
        locked = AppLockManager.unlock(context, pkg)
        LCAccessibilityService.instance?.dismissLockOverlay()
        notice = "已解除所选应用限制"
    }) { Text("解除 $pkg") } }
    OutlinedTextField(focusPackages, { focusPackages = it }, Modifier.fillMaxWidth(), label = { Text("专注目标包名（换行或逗号分隔）") })
    Row(verticalAlignment = Alignment.CenterVertically) { Text("启用专注回聊"); Switch(focusEnabled, { focusEnabled = it }) }
    Button(onClick = { confirm = {
        if (focusEnabled && !interventionsSupported) notice = "当前设备为被动兼容模式，未启用规则"
        else applyResult(AppLockManager.configureFocus(context, focusEnabled, parse(focusPackages)))
    } }) { Text("确认保存专注规则…") }
    OutlinedTextField(redirectPackages, { redirectPackages = it }, Modifier.fillMaxWidth(), label = { Text("定时回聊包名（留空关闭）") })
    OutlinedTextField(window, { window = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("时段 HH:mm-HH:mm（可跨午夜）") })
    Button(onClick = { confirm = {
        if (parse(redirectPackages).isNotEmpty() && !interventionsSupported) notice = "当前设备为被动兼容模式，未启用规则"
        else applyResult(AppLockManager.configureRedirect(context, parse(redirectPackages), window))
    } }) { Text("确认保存时段规则…") }
    OutlinedButton(onClick = {
        AppLockManager.clearAll(context)
        LCAccessibilityService.instance?.dismissLockOverlay()
        AppLockManager.configureFocus(context, false, emptySet())
        AppLockManager.configureRedirect(context, emptySet(), null)
        focusEnabled = false; focusPackages = ""; redirectPackages = ""; window = ""; locked = emptySet()
        notice = "全部应用限制与回聊规则已解除"
    }) { Text("立即解除所有应用限制与回聊规则") }
    if (notice.isNotEmpty()) Text(notice, fontSize = 12.sp)
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text("应用这些手机操作规则？") },
        text = { Text("已授予无障碍权限时，这些规则会拦截所选应用，并可能返回 Orbis。你可以随时使用“立即解除”停止。") },
        confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
