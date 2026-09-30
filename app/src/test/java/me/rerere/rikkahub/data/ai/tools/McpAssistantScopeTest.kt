package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class McpAssistantScopeTest {
    private fun server(name: String) = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = name, tools = listOf(McpTool(name = "read"))),
        url = "https://example.invalid/$name",
    )

    @Test fun catalogueUsesConversationAssistantNotGloballySelectedAssistant() {
        val first = server("First")
        val second = server("Second")
        val a = Assistant(name = "A", mcpServers = setOf(first.id))
        val b = Assistant(name = "B", mcpServers = setOf(second.id))
        val settings = Settings(assistants = listOf(a, b), assistantId = b.id, mcpServers = listOf(first, second))
        assertEquals(listOf(first.id), mcpToolsForAssistant(settings, a).map { it.first })
        assertEquals(listOf(second.id), mcpToolsForAssistant(settings, b).map { it.first })
        assertTrue(mcpToolsForAssistant(settings, a.copy(mcpServers = emptySet())).isEmpty())
    }

    @Test fun disabledServerAndDisabledToolsAreNeverProvided() {
        val server = server("First")
        val assistant = Assistant(mcpServers = setOf(server.id))
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id,
            mcpServers = listOf(server.copy(commonOptions = server.commonOptions.copy(enable = false))))
        assertTrue(mcpToolsForAssistant(settings, assistant).isEmpty())
        val disabledTool = server.copy(commonOptions = server.commonOptions.copy(tools = listOf(McpTool(name = "read", enable = false))))
        assertTrue(mcpToolsForAssistant(settings.copy(mcpServers = listOf(disabledTool)), assistant).isEmpty())
    }
}
