package me.rerere.rikkahub.data.orbis.diagnostics

import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.orbis.boundary.AssistantBoundary
import me.rerere.rikkahub.data.orbis.integration.AiConfigurationRules

internal val diagnosticToolOptions = linkedMapOf(
    LocalToolOption.JavascriptEngine to ("eval_javascript" to "JavaScript计算"),
    LocalToolOption.Clipboard to ("clipboard" to "剪贴板"),
    LocalToolOption.Tts to ("text_to_speech" to "文字转语音"),
    LocalToolOption.ScreenTime to ("screen_time" to "屏幕时间"),
    LocalToolOption.CompanionDevice to ("companion_device" to "设备原生能力"),
    LocalToolOption.CompanionMemory to ("companion_memory" to "设备记忆"),
    LocalToolOption.BluetoothToy to ("toy_bluetooth" to "蓝牙设备"),
    LocalToolOption.LocalReading to ("orbis_reading" to "本地共读批注"),
    LocalToolOption.LocalSoup to ("orbis_soup" to "海龟汤"),
    LocalToolOption.MessageRecall to ("orbis_recall_message" to "允许AI撤回自己的消息"),
    LocalToolOption.ContextPruning to ("orbis_context_pruning" to "允许AI清理旧思考与工具记录"),
    LocalToolOption.ThemeDesign to ("orbis_theme" to "AI自定义美化"),
    LocalToolOption.SelfCheck to ("orbis_self_check" to "AI自检"),
    LocalToolOption.TimeInfo to ("get_time_info" to "时间"),
    LocalToolOption.Calendar to ("calendar_query" to "日历"),
    LocalToolOption.AskUser to ("ask_user" to "提问"),
    LocalToolOption.LocalGarden to ("orbis_garden" to "本地花园"),
    LocalToolOption.LocalGallery to ("orbis_gallery" to "格子"),
    LocalToolOption.HumanHealth to ("orbis_health" to "人类健康"),
    LocalToolOption.LocalDesire to ("orbis_desire" to "愿望"),
)

internal fun selfCheckConfiguration(settings: Settings, owner: String, focus: String,
    boundary: AssistantBoundary, conversationId: String? = null, now: Long = System.currentTimeMillis()): List<SelfCheckItem> {
    require(focus in selfCheckFocus)
    val a = AiConfigurationRules.requireOwner(settings, owner)
    val items = mutableListOf<SelfCheckItem>()
    fun add(code: String, title: String, status: String, evidence: String, next: String, by: String = "human") {
        items.add(SelfCheckItem(code, title, status, evidence, next, by))
    }
    if (focus in setOf("overview", "model", "tools")) {
        val id = boundary.modelOverride?.takeIf { "model" in boundary.grants }?.let { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() } ?: a.chatModelId ?: settings.chatModelId
        val parent = settings.providers.firstOrNull { p -> p.models.any { it.id == id } }
        val model = parent?.models?.firstOrNull { it.id == id }
        val provider = model?.providerOverwrite ?: parent
        when {
            model == null || provider == null -> add("model_missing", "尚未选择可用聊天模型", "blocked", "当前选择没有对应型号。", "聊天顶部点模型名→选择已配置模型；设置→模型服务可添加连接。")
            !provider.enabled || !parent.enabled -> add("provider_disabled", "模型服务已关闭", "blocked", "当前型号所属服务或覆盖服务未启用。", "设置→模型服务→当前服务→启用。")
            !boundary.permits("model", model.id.toString()) -> add("model_declined", "当前AI已停用该模型", "blocked", "拒绝权中的模型选择处于停用状态。", "查询orbis_boundary state(kind=model)，由AI选择恢复或换用另一已授权型号。", "ai_with_existing_tools")
            else -> add("model_configured", "当前模型配置可读取", "info", "协议=${when(provider) { is ProviderSetting.Google -> "Google原生"; is ProviderSetting.OpenAI -> "OpenAI兼容"; is ProviderSetting.Claude -> "Anthropic" }}；本机未测试远端。", "需验证连接时可用orbis_configure check(kind=model,id=${model.id})；这是一次真实短请求，沿用审批。", "ai_with_existing_tools")
        }
        if (provider != null) {
            val endpoint = when (provider) { is ProviderSetting.OpenAI -> provider.baseUrl; is ProviderSetting.Google -> provider.baseUrl; is ProviderSetting.Claude -> provider.baseUrl }
            val parsed = runCatching { java.net.URI(endpoint) }.getOrNull()
            val valid = parsed?.scheme in setOf("http", "https") && !parsed?.host.isNullOrBlank() && parsed?.userInfo == null && parsed?.fragment == null
            add("model_endpoint", "模型服务地址格式", if (valid) "ok" else "blocked", if (valid) "地址格式可识别；未测试是否可达。" else "地址不是完整HTTP(S)地址，或含不支持的用户信息/片段。",
                "设置→模型服务→当前服务→API地址，按服务商给出的完整地址填写。自检不展示地址内容。")
            val hasKey = when(provider) { is ProviderSetting.OpenAI -> provider.apiKey.isNotBlank() || provider.chatGptAccountId != null; is ProviderSetting.Google -> provider.apiKey.isNotBlank(); is ProviderSetting.Claude -> provider.apiKey.isNotBlank() }
            add("model_credential", "认证配置", "info", if (hasKey) "已配置凭据或账号；未验证有效性。" else "没有填写API密钥；免密服务可以正常使用，需要认证的服务需人类补填。",
                "设置→模型服务→当前服务→密钥/账号。密钥只在设置页填写，不发到聊天。")
        }
        if (model != null) {
            add("model_tools", "模型工具调用", if (ModelAbility.TOOL in model.abilities) "ok" else "blocked",
                if (ModelAbility.TOOL in model.abilities) "本机型号设置已打开工具能力。" else "本机型号设置未打开工具能力，AI无法调用自检等工具。",
                "设置→模型服务→当前型号→工具调用；开启前确认服务商支持该能力。")
            add("model_images", "模型图片输入", if (Modality.IMAGE in model.inputModalities) "ok" else "info",
                if (Modality.IMAGE in model.inputModalities) "本机已标记图片输入能力。" else "当前型号未标记图片输入能力。",
                "需要识图时在型号设置核对图片输入，或选支持识图的型号；本机开关不等于上游实际支持。")
        }
        boundary.pause(conversationId, now)?.let {
            add("conversation_paused", "AI已暂停对话", "info", "当前处于约定暂停，截止时间=${it.until}。", "聊天底部查看倒计时；允许申请时可申请继续，否则等待到期。")
        }
    }
    if (focus in setOf("tools", "theme")) {
        diagnosticToolOptions.filter { focus == "tools" || it.key == LocalToolOption.ThemeDesign }.forEach { (option, label) ->
            val enabled = option in a.localTools
            // Only exact tool IDs can prove a boundary choice; do not invent family-level IDs.
            val declined = option in setOf(LocalToolOption.ThemeDesign, LocalToolOption.SelfCheck, LocalToolOption.TimeInfo) && !boundary.permits("tool", label.first)
            add("tool_${label.first}", label.second, if (!enabled || declined) "info" else "ok",
                when { !enabled -> "人类尚未为当前助手打开此工具。"; declined -> "人类已开启，当前AI自行停用了此工具。"; else -> "当前助手已开启此工具；执行仍按当前授权。" },
                if (declined) "orbis_boundary state(kind=tool)查看真实ID，再由AI选择恢复。" else "侧栏→当前助手设置→本地工具→${label.second}。未使用的功能可以保持关闭。",
                if (declined) "ai_with_existing_tools" else "human")
        }
    }
    if (focus == "overview") add("self_check_entry", "按需查看具体功能", "info", "详细配置仅在选择检查项目或AI调用时读取。", "可选工具、主题、模型、MCP、工作区、表情包和系统权限；AI自检在本地工具开启。")
    return items
}
