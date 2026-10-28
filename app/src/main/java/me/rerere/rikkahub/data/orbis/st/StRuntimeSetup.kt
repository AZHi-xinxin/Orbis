package me.rerere.rikkahub.data.orbis.st

import kotlinx.serialization.json.*

/** Query-only diagnostics: the current permission is different from a frozen run's catalogue. */
internal fun stRuntimeReadiness(
    bound: Boolean,
    enabled: Boolean,
    shellReady: Boolean,
    registeredForRun: Boolean,
    configured: Boolean,
    phase: String,
): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("registered_for_this_run", registeredForRun)
    put("instance_registered", configured)
    put("phase", phase)
    put("execution_performed", false)
    val reason = when {
        !bound -> "workspace_unbound"
        !enabled -> "hosting_disabled"
        !shellReady -> "shell_not_ready"
        !registeredForRun -> "new_turn_required"
        !configured -> "first_start_required"
        else -> "instance_registered"
    }
    put("reason", reason)
    put("next_steps", when (reason) {
        "workspace_unbound" -> "先绑定当前助手的工作区；不要开启别的助手的托管开关。"
        "hosting_disabled" -> "只有用户选择在本手机Orbis托管时，才请人类在上方workspace_name对应工作区开启「允许此工作区托管 ST」。"
        "shell_not_ready" -> "先按上方Rootfs与Shell检查结果处理，保留已安装实例，不为试按钮反复重装。"
        "new_turn_required" -> "本工作区已允许托管，但本轮开始时尚未注册对应工具。结束本轮，请人类回原聊天另发一条新消息，再查orbis_help(topic=tools)。不要反复调用未注册工具、创建新聊天或重装。"
        "first_start_required" -> "AI先调用orbis_st_runtime(action=start,target=this_phone_orbis,source=实际源码目录,python=实际Python路径,config=实际私有配置路径)登记并启动现有实例；不传密钥内容。页面「启动已配置实例」在首次登记前是灰色，不能让人类靠点击它完成首次启动。"
        else -> "已有托管登记。调用orbis_st_runtime(action=status)确认状态；停止时可用start重新启动同一实例。ready后仍需验证模型回复和记忆存取。"
    })
}
