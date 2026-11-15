package me.rerere.rikkahub.data.orbis.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.approval.ToolApprovalStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.boundary.BoundaryHost
import me.rerere.rikkahub.data.orbis.integration.AiConfigurationRules
import me.rerere.rikkahub.data.orbis.theme.openThemes
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/** No network, tool execution, setting mutation or chat reads. Callable from UI without an LLM. */
internal class SelfCheckHost(private val context: Context, private val settings: SettingsStore,
    private val mcp: McpManager, private val workspace: WorkspaceRepository) {
    suspend fun check(owner: String, focus: String, conversationId: String? = null): SelfCheckReport = withContext(Dispatchers.IO) {
        require(focus in selfCheckFocus)
        val snapshot = settings.settingsFlow.value
        val a = AiConfigurationRules.requireOwner(snapshot, owner)
        val boundary = BoundaryHost.get(context).state(owner)
        val items = selfCheckConfiguration(snapshot, owner, focus, boundary, conversationId).toMutableList()
        items.add(SelfCheckItem("fault_report_entry","闪退与按钮故障报告","info",
            "配置自检与系统退出记录是两类证据；未分类故障不能据此确定闪退原因。",
            "人类打开自检→故障记录与导出，开启30分钟记录，复现一次，重开后导出并自行分享；也可从设置→关于进入。不会收集聊天或密钥，不自动上传。","human"))
        fun add(code: String, title: String, status: String, evidence: String, next: String, by: String = "human") {
            items.add(SelfCheckItem(code, title, status, evidence, next, by))
        }
        suspend fun section(name: String, read: suspend () -> Unit) {
            try { read() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { add("${name}_unreadable", "${name}状态暂时读不到", "unknown", "本机读取没有完成，不能据此判断已关闭或数据不存在。", "保留原数据，复制本报告和对应错误卡反馈。") }
        }
        if (focus in setOf("overview", "permissions", "tools")) {
            section("approval") {
                val approvals = ToolApprovalStore.get(context)
                add("execution_approval", "工具执行确认", "info",
                    if (approvals.allowsAll(owner)) "人类已为当前AI保存执行授权；仍受工具开启和系统权限限制。" else "当前AI未开启全部执行自动允许；单项已记住授权仍有效，其余执行时显示确认卡。",
                    "工具调用出现确认卡时选择本次允许或按需记住；不是把工具开关打开就自动允许所有操作。")
            }
            if (focus == "permissions") {
                val permissions = linkedMapOf("麦克风" to Manifest.permission.RECORD_AUDIO, "读取日历" to Manifest.permission.READ_CALENDAR,
                    "写入日历" to Manifest.permission.WRITE_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) permissions["通知"] = Manifest.permission.POST_NOTIFICATIONS
                permissions.forEach { (label, permission) ->
                    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                    add("permission_${permission.substringAfterLast('.').lowercase()}", label, if (granted) "ok" else "info",
                        if (granted) "系统权限已允许。" else "系统权限未允许；只有使用相关功能时才需要。",
                        "手机系统设置→应用→Orbis→权限；通知在通知管理。不同手机菜单名称可能略有不同。")
                }
            }
        }
        if (focus in setOf("overview", "tools") &&
            me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.CompanionDevice in a.localTools) section("weather") {
            val configured = !context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
                .getString("city", "").isNullOrBlank()
            items.add(weatherConfigurationItem(configured))
        }
        if (focus == "theme") section("theme") {
            val store = openThemes(context, owner)
            val state = store.snapshot()
            add("theme_store", "主题库", "ok", "${state.entries.size}套主题，${state.entries.count { it.draft != null }}份草稿；已发布${state.entries.sumOf { it.versions.size }}个版本。",
                "发布失败先orbis_theme read读取同一id，publish传expected_revision；apply才传revision。", "ai_with_existing_tools")
            state.entries.take(30).forEach { entry ->
                val body = entry.draft ?: entry.versions.lastOrNull()?.body
                val missing = body?.let(store::missingAssetFields).orEmpty()
                if (missing.isNotEmpty()) add("theme_asset_missing", "主题图片缺失", "blocked",
                    "主题id=${entry.id}；字段=${missing.joinToString()}。",
                    "用orbis_theme list查看已有图片，重新导入后patch对应字段；纯色背景设type=solid、asset=null。旧发布版本保留。", "ai_with_existing_tools")
            }
            add("theme_apply_grant", "自动应用外观", "info",
                if (state.allowAiApply) "交给AI自由装扮已开启。" else "全局自动应用未开启；已单独允许${state.autoApply.size}套主题，其余需要人类预览确认。",
                "侧栏→自定义美化→预览并应用；需要自动切换时打开交给AI自由装扮。此开关不影响写草稿和发布版本。")
            add("theme_pending", "待预览主题", "info", if (state.pending == null) "没有等待人类确认的已发布主题。" else "有已发布主题正在等待人类预览确认。",
                if (state.pending == null) "人类可在自定义美化点草稿查看示例，再发布并预览；AI用publish再apply请求应用。" else "侧栏→自定义美化→打开待确认预览→应用。")
        }
        if (focus == "mcp") section("mcp") {
            val selected = snapshot.mcpServers.filter { it.id in a.mcpServers }
            add("mcp_selection", "当前助手MCP选择", "info", "已选择${selected.size}个；应用中另有${snapshot.mcpServers.size - selected.size}个未选用连接。下方最多列20个。",
                "侧栏→当前助手设置→MCP选择连接；设置→MCP管理地址和认证。其他助手的选择独立。")
            selected.take(20).forEachIndexed { i, server ->
                val status = mcp.syncingStatus.value[server.id]
                val blocked = !server.commonOptions.enable || !boundary.permits("mcp", server.id.toString())
                val text = when {
                    !server.commonOptions.enable -> "应用连接开关关闭。"
                    !boundary.permits("mcp", server.id.toString()) -> "当前AI自行停用了此MCP。"
                    status is McpStatus.Connected -> "最近连接状态为已连接；不等于全部工具已验收。"
                    status is McpStatus.NeedsAuthorization -> "需要人类完成连接认证。"
                    status is McpStatus.Error -> "最近连接报错；未读取或转发远端错误正文。"
                    status is McpStatus.Connecting || status is McpStatus.Reconnecting || status is McpStatus.Authorizing -> "正在连接或认证。"
                    else -> "尚无已连接状态。"
                }
                add("mcp_${i + 1}", "MCP ${i + 1}", if (blocked || status is McpStatus.Error) "blocked" else "info",
                    "id=${server.id}；$text 已开启${server.commonOptions.tools.count { it.enable }}项工具。",
                    "用orbis_configure inspect把id对应到名称；check(kind=mcp,id=${server.id})可经批准握手检查。认证由人类在设置→MCP完成。", "ai_with_existing_tools")
            }
        }
        if (focus in setOf("tools", "model", "mcp")) section("tool_schema") {
            val schemas = snapshot.mcpServers.filter { it.id in a.mcpServers && it.commonOptions.enable && boundary.permits("mcp", it.id.toString()) }
                .flatMap { server -> server.commonOptions.tools.filter { it.enable }.map { server.id.toString() to it } }
            var failures = 0
            schemas.take(1000).forEachIndexed { index, (serverId, tool) ->
                tool.inputSchema?.let { schema ->
                    val codes = me.rerere.ai.provider.providers.google.googleToolSchemaIssueCodes(Json.encodeToJsonElement(schema))
                    if (codes.isNotEmpty() && failures++ < 10) add("schema_$index", "工具参数兼容性线索", "info",
                        "MCP id=$serverId；已选目录序号=${index + 1}；Google原生协议转换副本的结构代码=${codes.joinToString()}。此检查不禁用工具，也不证明当前模型请求被拒绝。",
                        "先核对实际请求协议及错误卡。当前若是OpenAI兼容或Anthropic，不能把Google转换副本的提示直接当成该协议故障；需要时由对应MCP服务维护者核对定义，不猜填items类型。", "developer")
                }
            }
            add("schema_summary", "MCP参数兼容性检查", "info",
                "检查${schemas.size.coerceAtMost(1000)}个已选工具的Google原生协议转换副本，发现${failures}项线索，最多展示10项；不增删或禁用工具，不证明工具目录被截断，也不是完整远端兼容认证。",
                "本次只检查数组items与类型转换；需结合当前实际协议和错误卡核对。工具名不在目录与参数结构拒绝是不同问题，不根据本报告合并定因。")
        }
        if (focus in setOf("tools", "permissions")) section("phone_control") {
            val selected = me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.PhoneControl in a.localTools
            val connected = me.rerere.rikkahub.data.orbis.agentwork.PhoneControlService.instance != null
            val count = me.rerere.rikkahub.data.orbis.agentwork.PhoneGrants(context,owner).read().size
            add("phone_control_status", "通用手机操作", if (selected && (!connected || count == 0)) "blocked" else "info",
                "当前助手开关=$selected；系统服务已连接=$connected；已选应用数=$count。未读取屏幕或执行操作。",
                "工具与娱乐→任务与能力→手机操作。开启助手开关、选择允许应用，再由人类启用Orbis手机操作无障碍服务。")
        }
        if (focus == "tools") section("prepared_tools") {
            val receipt = me.rerere.rikkahub.data.orbis.agentwork.catalogPrefs(context).getString(owner,null)?.let {
                Json.decodeFromString<me.rerere.rikkahub.data.orbis.agentwork.AgentCatalogReceipt>(it)
            }
            add("prepared_tool_catalog", "最近工具目录记录", "info",
                receipt?.let { "本机准备${it.names.size}项函数工具，记录时间=${it.at}；未核对服务商实际接收数量，数量本身不能证明目录被截断。" }
                    ?: "没有近期目录记录。开启工具发现后发起新一轮生成可记录。",
                "工具与娱乐→任务与能力→工具与诊断查看名称；AI可调用orbis_tool_discover按用途查找。本项不会自动收起工具；只有核实服务商报数量超限后，才按需由人类手动收起暂不用的工具。")
        }
        if (focus == "workspace") section("workspace") {
            val ready = workspace.readiness(a.workspaceId?.toString())
            add("workspace_readiness", "当前助手工作区", "info", JsonObject(ready.filterKeys { it in setOf("shell_status", "rootfs_installed", "shell_ready", "permissions") }).toString(), ready["next_steps"]?.jsonPrimitive?.content ?: "先查询orbis_configure workspace_status。", "ai_with_existing_tools")
            val bound = a.workspaceId?.toString()?.let { workspace.getById(it) }
            if (bound != null) {
                val host = me.rerere.rikkahub.data.orbis.st.StRuntimeHost.get(context)
                add("st_runtime", "ST托管状态", "info", "已配置=${host.saved(bound.root).spec != null}；允许托管=${host.enabled(bound.root)}；阶段=${host.current(bound.root).phase}。",
                    "北斗导航→工具与娱乐→工作区→当前助手工作区→ST持续运行；AI有orbis_st_runtime时可按已有授权启动或查状态。", "ai_with_existing_tools")
            }
        }
        if (focus == "stickers") {
            add("sticker_import", "人类保存表情包", "info", "保存表情包不需要开启AI工具。图片支持PNG/JPEG/GIF/WebP，单张最多8MiB、边长最多4096像素。",
                "重新选择原图或截图；具体失败看最近故障代码。没有失败记录时不能确认是格式、空间还是文件读取问题。")
            add("storage_space", "本机剩余空间", "info", "应用所在分区可用${context.filesDir.usableSpace / (1024 * 1024)}MiB。", "空间不足时先在系统存储管理释放空间，再重开表情库核对保存结果。")
        }
        val recent = try { DiagnosticJournal.read(context, owner) } catch (_: Exception) {
            add("diagnostics_unreadable", "故障摘要无法读取", "unknown", "未读取到可靠摘要。", "保留对应错误卡反馈。")
            emptyList()
        }
        // Recheck ownership after suspension; UI tab changes never retarget this request.
        AiConfigurationRules.requireOwner(settings.settingsFlow.value, owner)
        SelfCheckReport(BuildConfig.VERSION_NAME, System.currentTimeMillis(), focus, items, recent)
    }
}
