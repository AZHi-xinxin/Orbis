package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus

/** Requested diagnostics only; never installs Linux, grants approval or executes a command. */
internal fun workspaceReadiness(workspace: WorkspaceEntity?, rootfsInstalled: Boolean): JsonObject = buildJsonObject {
    val ready = workspace?.shellStatus == WorkspaceShellStatus.READY.name && rootfsInstalled
    put("ok", true)
    put("workspace_id", workspace?.id?.let(::JsonPrimitive) ?: JsonNull)
    put("workspace_name", workspace?.name?.take(160)?.let(::JsonPrimitive) ?: JsonNull)
    put("shell_status", workspace?.shellStatus ?: "unbound")
    put("rootfs_installed", rootfsInstalled)
    put("shell_ready", ready)
    put("execution_performed", false)
    put("guide", "orbis_help(topic=guide, chapter=workspace_setup)")
    put("next_steps", when {
        workspace == null -> "先调用 orbis_configure(action=workspace_prepare) 创建并绑定自己的工作区，再查询 workspace_status。"
        ready -> "Linux 已就绪。运行命令仍按本工作区的审批设置；出现确认框时说明本次要做什么，由人类选择允许一次或记住授权。无需重新安装。"
        workspace.shellStatus == WorkspaceShellStatus.INSTALLING.name -> "正在安装，请等待完成；不要重复点击或重新安装。完成后再查询 workspace_status。"
        else -> "请人类点右下角北斗导航 → 工具娱乐 → 工作区 → 名称与 workspace_name 相同的工作区 → 启用 Shell → 安装 Rootfs。等 Shell 状态显示「就绪」、按钮变为「重新安装 Rootfs」后回来告诉你，再查询 workspace_status。已有文件时不要为了排障直接重新安装。"
    })
    put("permissions", buildJsonObject {
        put("shell_requires_approval", workspace?.toolApprovalOverrides()?.get("workspace_shell") ?: true)
        put("note", "需要审批不等于功能关闭；不要让用户一律打开所有权限。")
    })
}
