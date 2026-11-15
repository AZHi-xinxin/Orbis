package me.rerere.rikkahub.data.ai.mcp

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/** A model-facing alias only. MCP execution and approval identities keep the remote name. */
internal fun mcpModelName(name: String): String {
    if (!name.startsWith("mcp__") || (name.length <= 64 && name.all(::modelNameChar))) return name
    // Replacing punctuation alone would merge sheet.get_cell_data and sheet_get_cell_data.
    // Hash the complete UTF-8 name, including server, before truncating the readable prefix.
    val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return name.map { if (modelNameChar(it)) it else '_' }.joinToString("").take(30) + "__" + digest
}

private fun modelNameChar(char: Char) = char in 'a'..'z' || char in 'A'..'Z' ||
    char in '0'..'9' || char == '_' || char == '-'

internal fun Tool.withMcpModelName(): Tool = mcpModelName(name).let {
    if (it == name) this else copy(name = it, originalMcpName = name)
}

/** Legacy calls may use the old name only when this exact host definition owns that name. */
internal fun Tool.matchesMcpModelName(requested: String): Boolean = name == requested ||
    (originalMcpName == requested && name == mcpModelName(requested))

/** Even a deliberately named remote tool must not shadow an alias or another server's tool. */
internal fun requireUniqueMcpModelNames(names: List<String>) {
    check(names.map(::mcpModelName).distinct().size == names.size) {
        "MCP 工具转换后的名称发生冲突，请检查重复的服务器或工具名称；未调用任何工具。"
    }
}

/** Request-only projection: never migrate stored chats, IDs, arguments, outputs or approvals. */
@Suppress("DEPRECATION")
internal fun List<UIMessage>.withMcpModelNames(): List<UIMessage> = map { message ->
    var changed = false
    val parts = message.parts.map { part ->
        val updated = when (part) {
            is UIMessagePart.Tool -> mcpModelName(part.toolName).let { if (it == part.toolName) part else part.copy(toolName = it) }
            is UIMessagePart.ToolCall -> mcpModelName(part.toolName).let { if (it == part.toolName) part else part.copy(toolName = it) }
            is UIMessagePart.ToolResult -> mcpModelName(part.toolName).let { if (it == part.toolName) part else part.copy(toolName = it) }
            else -> part // Server-native tools are not client MCP functions.
        }
        if (updated !== part) changed = true
        updated
    }
    if (changed) message.copy(parts = parts) else message
}
