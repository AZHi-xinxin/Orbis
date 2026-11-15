package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal val pluginJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal const val PLUGIN_CODE_BYTES = 96 * 1024
internal const val PLUGIN_OUTPUT_BYTES = 32 * 1024
internal const val PLUGIN_TIMEOUT_MS = 5_000L
internal val pluginPermissions = linkedMapOf(
    "filesystem.read" to "读取此插件自己的文件",
    "filesystem.write" to "写入此插件自己的文件",
    "network" to "访问声明的 HTTPS 网站",
    "clipboard.read" to "读取剪贴板文字",
    "clipboard.write" to "写入剪贴板文字",
    "shell" to "使用当前助手已授权的工作区终端",
    "schedule.read" to "查看本机日程",
    "schedule.write" to "创建、修改和删除本机日程（不自动设闹钟）",
    "alarm" to "设置或取消 Orbis 的一次性闹钟",
    "notify" to "向这台手机发送通知",
    "wake" to "预约唤醒调用插件的原对话（仍受哨兵总开关控制）",
    "sentinel" to "管理此插件创建的哨兵（仍需人类开启总开关）",
    "memory.read" to "查找当前助手的本机记忆",
    "memory.write" to "向当前助手的本机记忆新增笔记",
    "device.status" to "读取电量、充电、亮屏与锁屏状态",
    "device.steps" to "读取已授权的今日步数",
    "device.weather" to "读取已设置城市的天气",
    "device.foreground" to "读取已开启设备情境中的前台应用观察（保留时间和过期标识）",
    "device.observe" to "让此插件的哨兵使用已授权的低电量、常亮、夜间使用和围栏观察",
    "background.run" to "按计划在后台运行此版本插件（可能使用已批准的联网、通知等能力）",
    "response.check" to "自动检查此助手各对话的回复正文；未通过时标记为待修改草稿",
)

/** Descriptive fields belong to the author; execution facts and grants never come from this body. */
@Serializable internal data class PluginManifest(
    val name: String,
    val title: String,
    val summary: String,
    val introduction: String,
    val code: String,
    val parameters: JsonObject = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) },
    val icon: String = "moon",
    val palette: String = "lavender",
    val permissions: List<String> = emptyList(),
    val networkHosts: List<String> = emptyList(),
    // Omit the default to preserve every pre-existing manifest hash/grant.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val beforeResponse: Boolean = false,
) {
    fun validate() {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,39}"))) { "plugin_name_invalid" }
        require(title.isNotBlank() && title.length <= 80 && summary.isNotBlank() && summary.length <= 240 &&
            introduction.isNotBlank() && introduction.length <= 12_000) { "plugin_description_invalid" }
        require(code.isNotBlank() && code.toByteArray().size <= PLUGIN_CODE_BYTES) { "plugin_code_size" }
        require(icon in setOf("moon", "book", "letter", "leaf", "star", "music", "sparkles")) { "plugin_icon_invalid" }
        require(palette in setOf("lavender", "blue", "rose", "mint", "gold")) { "plugin_palette_invalid" }
        require(permissions.distinct() == permissions && permissions.all { it in pluginPermissions }) { "plugin_permission_unknown" }
        require(networkHosts.size <= 8 && networkHosts.distinct() == networkHosts && networkHosts.all {
            it.length <= 253 && it == it.lowercase() && it.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) &&
                '.' in it && !it.contains("..") && !it.endsWith(".local") && !it.endsWith(".localhost") &&
                it != "localhost" && !it.matches(Regex("[0-9.]+"))
        }) { "plugin_network_hosts_invalid" }
        require(("network" in permissions) == networkHosts.isNotEmpty()) { "plugin_network_hosts_required" }
        require(!beforeResponse || "response.check" in permissions) { "plugin_hook_permission_required" }
        PluginSchema.validateSchema(parameters)
    }
    fun hash(): String = MessageDigest.getInstance("SHA-256").digest(pluginJson.encodeToString(this).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

@Serializable internal data class PluginVersion(val number: Int, val body: PluginManifest, val hash: String, val createdAt: Long)
@Serializable internal data class PluginRun(
    val id: String, val version: Int, val startedAt: Long, val status: String = "running",
    val finishedAt: Long? = null, val elapsedMs: Long = 0, val error: String? = null,
    val calls: List<PluginHostCall> = emptyList(),
)
@Serializable internal data class PluginHostCall(val index: Int, val method: String, val startedAt: Long,
    val outcome: String = "unknown", val finishedAt: Long? = null)
@Serializable internal data class PluginEntry(
    val id: String,
    val revision: Int = 1,
    val versions: List<PluginVersion>,
    val activeVersion: Int? = null,
    val previousVersion: Int? = null,
    val enabled: Boolean = false,
    val updatedAt: Long,
    val totalUses: Long = 0,
    val usesByDay: Map<Long, Long> = emptyMap(),
    val runs: List<PluginRun> = emptyList(),
) {
    fun active() = versions.firstOrNull { it.number == activeVersion }
    fun latest() = versions.maxBy { it.number }
    fun recentUses(now: Long) = usesByDay.filterKeys { it in (now / 86_400_000 - 29)..(now / 86_400_000) }.values.sum()
}
@Serializable internal data class PluginState(val schema: Int = 1, val entries: List<PluginEntry> = emptyList())
@Serializable internal data class PluginGrant(val hash: String, val permissions: Set<String>, val workspaceId: String? = null)
internal enum class PluginOrder { FREQUENCY, UPDATED }
internal fun sortedPlugins(entries: List<PluginEntry>, order: PluginOrder, now: Long): List<PluginEntry> =
    entries.sortedWith(if (order == PluginOrder.FREQUENCY) compareByDescending<PluginEntry> { it.recentUses(now) }
        .thenByDescending { it.updatedAt }.thenBy { it.id } else compareByDescending<PluginEntry> { it.updatedAt }.thenBy { it.id })

internal fun pluginError(error: Throwable): String = error.message?.takeIf {
    it.matches(Regex("plugin_[a-z0-9_]{1,80}"))
} ?: "plugin_execution_failed"

internal fun pluginErrorText(code: String): String = when (code) {
    "plugin_timeout" -> "运行超过 5 秒，已停止这次执行。"
    "plugin_disabled" -> "插件或个人插件功能已关闭。"
    "plugin_not_approved" -> "这个版本还没有获准使用，请在插件详情查看。"
    "plugin_revision_conflict" -> "插件刚刚有更新，请刷新后再操作。"
    "plugin_permission_denied" -> "调用的能力未声明或未获准使用。"
    "plugin_network_host_denied" -> "这个网站不在已批准的网站列表中。"
    "plugin_network_private_address" -> "插件的网络能力仅允许公开 HTTPS 地址。"
    "plugin_workspace_approval_required" -> "请先在当前助手工作区授权终端工具，再启用插件的终端权限。"
    "plugin_workspace_changed" -> "助手工作区已改变，请重新确认插件权限。"
    "plugin_output_limit" -> "插件输出过长，已停止这次执行。"
    "plugin_arguments_invalid" -> "传入的参数不符合插件的说明。"
    "plugin_interrupted" -> "上次运行被中断，没有自动重试。"
    "plugin_script_error" -> "插件脚本运行出错，可查看记录并请 AI 修改。"
    "plugin_store_invalid" -> "插件记录暂时无法读取，原文件已保留。"
    "plugin_conversation_required" -> "此能力需要原对话的有效绑定，请从原聊天调用。"
    "plugin_host_scope_denied" -> "只能操作此插件在原对话创建的哨兵。"
    "plugin_response_check_failed" -> "交稿前检查尚未通过，请查看插件记录并修改后再试。"
    "plugin_response_check_limit" -> "最多同时启用 4 个交稿检查插件，请先停用一个。"
    "plugin_job_interval_invalid" -> "后台周期任务的间隔需在 15 分钟至 30 天之间。"
    "plugin_job_limit" -> "此插件最多保留 10 个后台任务，请先取消不再需要的任务。"
    "plugin_busy" -> "已有插件正在运行，请稍后再试。"
    else -> "操作未完成（$code），原记录已保留。"
}

internal fun pluginCallLabel(method: String): String = when(method.substringBefore('.')) {
    "files"->"插件文件"; "network"->"访问网站"; "clipboard"->"剪贴板"; "shell"->"工作区终端"
    "schedule"->"本机日程"; "alarm"->"闹钟"; "notify"->"手机通知"; "wake"->"预约原窗口"
    "sentinel"->"插件哨兵"; "memory"->"本机记忆"; "system"->"手机状态"; "jobs"->"后台任务"; else->"宿主调用"
}
