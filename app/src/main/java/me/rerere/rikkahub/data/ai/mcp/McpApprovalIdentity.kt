package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.approval.approvalFingerprint

/** Bind one tool to its destination/auth/schema, not to unrelated tools or its display title. */
internal fun mcpApprovalRevision(server: McpServerConfig, tool: McpTool, suffix: String = ""): String {
    val scoped = server.clone(commonOptions = server.commonOptions.copy(tools = listOf(tool), displayName = ""))
    return approvalFingerprint("mcp-tool-v2\n${Json.encodeToString<McpServerConfig>(scoped)}$suffix")
}

internal fun mcpApprovalCurrent(server: McpServerConfig, tool: McpTool, live: McpServerConfig?): Boolean {
    if (live == null || !live.commonOptions.enable) return false
    val currentTool = live.commonOptions.tools.singleOrNull { it.name == tool.name && it.enable } ?: return false
    return mcpApprovalRevision(server, tool) == mcpApprovalRevision(live, currentTool)
}
