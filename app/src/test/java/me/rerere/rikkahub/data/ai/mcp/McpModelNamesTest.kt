package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.dispatchProvidedTool
import org.junit.Assert.*
import org.junit.Test

class McpModelNamesTest {
    private val raw = "mcp__TencentDocs__sheet.get_cell_data"
    private val legal = Regex("^[a-zA-Z0-9_-]{1,64}$")

    @Test fun `Tencent names punctuation unicode and overlong names all fit model contracts without collisions`() {
        val names = listOf("manage.search_file", "manage.query_file_info", "manage.get_privilege",
            "sheet.get_sheet_info", "sheet.get_cell_data", "sheet.set_cell_value", "sheet.set_range_value",
            "sheet_get_cell_data", "sheet/get_cell_data", "sheet:读取", "sheet get cell data", "x".repeat(180))
            .map { "mcp__TencentDocs__$it" }
        val aliases = names.map(::mcpModelName)
        assertTrue(aliases.all { legal.matches(it) })
        assertEquals(names.size, aliases.distinct().size)
        assertEquals(aliases, names.reversed().map(::mcpModelName).reversed())
        assertEquals(aliases, aliases.map(::mcpModelName))
        assertNotEquals(mcpModelName(raw), mcpModelName(raw.replace("TencentDocs", "OtherDocs")))
    }

    @Test fun `valid MCP names and non MCP names are unchanged`() {
        listOf("mcp__Stiller__stbrain_manage", "mcp__Server__valid-123", "mcp__" + "a".repeat(59),
            "orbis_community", "provider.native").forEach { assertEquals(it, mcpModelName(it)) }
    }

    @Test fun `crafted alias shadow and duplicate server names fail closed`() {
        listOf(listOf(raw, mcpModelName(raw)), listOf(raw, raw)).forEach { names ->
            assertTrue(runCatching { requireUniqueMcpModelNames(names) }.isFailure)
        }
        requireUniqueMcpModelNames(listOf(raw, raw.replace('.', '_')))
    }

    @Suppress("DEPRECATION")
    @Test fun `history projection preserves original data and handles legacy call and result`() {
        val approval = HostToolApproval("remote-target", "revision", "label")
        val call = UIMessagePart.Tool("call", raw, "{\"original\":1}",
            listOf(UIMessagePart.Text("original result")), hostApproval = approval)
        val legacy = UIMessagePart.ToolCall("old", raw, "{}")
        val legacyResult = UIMessagePart.ToolResult("old", raw, JsonObject(emptyMap()), JsonObject(emptyMap()))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call, legacy, legacyResult))
        val projected = listOf(message).withMcpModelNames().single()
        assertEquals(message.copy(parts = listOf(call.copy(toolName = mcpModelName(raw)),
            legacy.copy(toolName = mcpModelName(raw)), legacyResult.copy(toolName = mcpModelName(raw)))), projected)
        assertEquals(raw, (message.parts.first() as UIMessagePart.Tool).toolName)
        assertSame(projected, listOf(projected).withMcpModelNames().single())
    }

    @Test fun `aliases and old pending names dispatch the same original target without changing guards`() = runBlocking {
        val seen = mutableListOf<String>()
        var enabled = true
        val original = Tool(raw, "synthetic", execute = { seen += "sheet.get_cell_data"; listOf(UIMessagePart.Text("ok")) },
            hostApproval = HostToolApproval("mcp:server:sheet.get_cell_data", "revision", "label"),
            isSelected = { enabled }, isApprovalCurrent = { enabled }, needsApproval = { true })
        val alias = original.withMcpModelName()
        assertSame(original.execute, alias.execute)
        assertSame(original.hostApproval, alias.hostApproval)
        assertTrue(alias.needsApproval(JsonObject(emptyMap())))
        for (name in listOf(raw, alias.name)) {
            val call = UIMessagePart.Tool("synthetic", name, "{}")
            val result = call.dispatchProvidedTool(listOf(alias)) { call.copy(output = it.execute(JsonObject(emptyMap()))) }
            assertTrue(result.isExecuted)
        }
        assertEquals(listOf("sheet.get_cell_data", "sheet.get_cell_data"), seen)
        enabled = false
        assertFalse(alias.isSelected()); assertFalse(alias.isApprovalCurrent())
    }

    @Test fun `legacy name cannot dispatch a different remote tool deliberately named as its alias`() = runBlocking {
        val shadow = Tool(mcpModelName(raw), "different canonical remote tool", execute = { error("must not execute") })
        val legacy = UIMessagePart.Tool("old", raw, "{}")
        var entered = false
        legacy.dispatchProvidedTool(listOf(shadow)) { entered = true; error("must not dispatch") }
        assertFalse(entered)
        assertFalse(shadow.matchesMcpModelName(raw))
    }
}
