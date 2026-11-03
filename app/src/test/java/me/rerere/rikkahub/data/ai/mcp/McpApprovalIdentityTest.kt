package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.approval.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class McpApprovalIdentityTest {
    private val tool = McpTool(name = "read", description = "Read synthetic", needsApproval = true)
    private val other = McpTool(name = "other")
    private val server = McpServerConfig.StreamableHTTPServer(url = "https://synthetic.invalid/mcp",
        commonOptions = McpCommonOptions(name = "test", tools = listOf(tool, other)))

    @Test fun `unrelated catalog updates and display titles do not invalidate current tool`() {
        val changed = server.copy(commonOptions = server.commonOptions.copy(displayName = "new title",
            tools = listOf(other.copy(description = "changed"), McpTool(name = "new"), tool)))
        assertTrue(mcpApprovalCurrent(server, tool, changed))
        assertEquals(mcpApprovalRevision(server, tool), mcpApprovalRevision(changed, tool))
    }

    @Test fun `target auth schema approval flag and enable changes still invalidate`() {
        val variants = listOf(
            server.copy(url = "https://different.invalid/mcp"),
            server.copy(commonOptions = server.commonOptions.copy(headers = listOf("Authorization" to "synthetic"))),
            server.copy(commonOptions = server.commonOptions.copy(enable = false)),
            server.copy(commonOptions = server.commonOptions.copy(tools = listOf(tool.copy(description = "Write now")))),
            server.copy(commonOptions = server.commonOptions.copy(tools = listOf(tool.copy(needsApproval = false)))),
            server.copy(commonOptions = server.commonOptions.copy(tools = listOf(tool.copy(enable = false)))),
            server.copy(commonOptions = server.commonOptions.copy(tools = emptyList())),
        )
        variants.forEach { assertFalse(mcpApprovalCurrent(server, tool, it)) }
    }

    @Test fun `global grant is live across catalog refresh and scoped by assistant`() = runTest {
        var stored: String? = null
        val grants = ToolApprovalStore({ stored }, { stored = it })
        var live = server
        val definition = Tool(name = "mcp__test__read", description = "read",
            needsApproval = { true }, isApprovalCurrent = { mcpApprovalCurrent(server, tool, live) },
            hostApproval = HostToolApproval("mcp:${server.id}:read", mcpApprovalRevision(server, tool), "read"),
            execute = { emptyList() })
        val bound = bindToolApproval(definition, "one", grants)
        val args = buildJsonObject {}
        assertTrue(bound.needsApproval(args))
        grants.setAllowAll("one", true)
        live = server.copy(commonOptions = server.commonOptions.copy(tools = listOf(tool, other.copy(description = "refresh"))))
        assertFalse(bound.needsApproval(args))
        assertTrue(bindToolApproval(definition, "two", grants).needsApproval(args))
        live = live.copy(url = "https://different.invalid/mcp")
        assertTrue(bound.needsApproval(args))
        grants.setAllowAll("one", false)
        live = server
        assertTrue(bound.needsApproval(args))
    }

    @Test fun `single remembered grant survives unrelated refresh and process reload`() = runTest {
        var data: String? = null
        val store = ToolApprovalStore({ data }, { data = it })
        fun bound(s: McpServerConfig, grants: ToolApprovalStore) = bindToolApproval(Tool(
            name = "mcp__test__read", description = "read", needsApproval = { true },
            hostApproval = HostToolApproval("mcp:${s.id}:read", mcpApprovalRevision(s, tool), "read"),
            execute = { emptyList() }), "one", grants)
        store.allow("one", checkNotNull(bound(server, store).hostApproval))
        val changed = server.copy(commonOptions = server.commonOptions.copy(tools = listOf(other, tool)))
        val reload = ToolApprovalStore({ data }, { data = it })
        assertFalse(bound(changed, reload).needsApproval(buildJsonObject {}))
    }
}
