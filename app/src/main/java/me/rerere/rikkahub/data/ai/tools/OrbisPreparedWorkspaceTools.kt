package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.validWorkspaceComponent

/** A fixed default workspace may be bound later in this same generation. No shell or credential reads. */
internal suspend fun createPreparedWorkspaceTools(
    workspaceId: String,
    repository: WorkspaceRepository,
    stillBound: () -> Boolean,
): List<Tool> = narrowPreparedWorkspaceTools(
    createWorkspaceTools(workspaceId, repository), stillBound,
    exists = { repository.getById(workspaceId) != null },
)

internal fun narrowPreparedWorkspaceTools(
    tools: List<Tool>,
    stillBound: () -> Boolean,
    exists: suspend () -> Boolean,
): List<Tool> = tools.filter { it.name == "workspace_write_file" }.map { tool ->
    tool.copy(
        description = "After orbis_configure workspace_prepare succeeds, write a UTF-8 configuration file inside /workspace/ using native IO. Original file approval applies. No shell or Linux installation; receipt contains metadata only. " + tool.description,
        isApprovalCurrent = { stillBound() && tool.isApprovalCurrent() },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.takeIf { it.isString }?.content
            if (!stillBound() || !exists()) {
                listOf(UIMessagePart.Text("""{"ok":false,"status":"workspace_not_bound","help":"先执行orbis_configure workspace_prepare。"}"""))
            } else if (path == null || !path.startsWith("/workspace/") || path.length > 4096 ||
                !path.removePrefix("/workspace/").split('/').all(::validWorkspaceComponent)) {
                listOf(UIMessagePart.Text("""{"ok":false,"status":"workspace_path_denied"}"""))
            } else tool.execute(args)
        },
    )
}
