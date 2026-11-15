package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ApprovalToolCatalogTest {
    @Test fun `legacy MCP approval catalog resolves legal alias but keeps target and revocation checks`() {
        val raw = tool.copy(name = "mcp__TencentDocs__sheet.get_cell_data")
        val alias = raw.copy(name = me.rerere.rikkahub.data.ai.mcp.mcpModelName(raw.name), originalMcpName = raw.name)
        val restored = scope.restore(saved(listOf(raw)), listOf(alias)).single()
        assertEquals(alias.name, restored.name)
        assertEquals(raw.hostApproval, restored.hostApproval)
        assertTrue(restored.isSelected())
        val revoked = scope.restore(saved(listOf(raw)), listOf(alias.copy(isSelected = { false }))).single()
        assertFalse(revoked.isSelected())
        val replaced = scope.restore(saved(listOf(raw)), listOf(alias.copy(hostApproval = raw.hostApproval!!.copy(stableId = "different")))).single()
        assertFalse(replaced.isSelected())
    }
    private val scope = ApprovalCatalogScope("assistant", "conversation", "provider", "model", "native")
    private val messages = listOf(UIMessage.user("synthetic user"), UIMessage.assistant("synthetic tool request"))
    private val schema = InputSchema.Obj(JsonObject(mapOf("value" to JsonObject(mapOf("type" to JsonPrimitive("string"))))))
    private val tool = Tool("original", "original description", parameters = { schema },
        hostApproval = HostToolApproval("target", "revision", "label"), execute = { listOf(UIMessagePart.Text("done")) })
    private fun saved(tools: List<Tool> = listOf(tool)): List<UIMessage> = messages.dropLast(1) +
        messages.last().copy(orbisApprovalToolCatalog = scope.capture(messages, tools))

    @Test fun `serialized approval preserves wire definitions and binds fresh executors`() = runBlocking {
        val restored = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(saved()))
        var count = 0
        val live = tool.copy(execute = { count++; listOf(UIMessagePart.Text("fresh executor")) })
        val result = scope.restore(restored, listOf(live, tool.copy(name = "new")))
        assertEquals(listOf("original"), result.map { it.name })
        assertEquals(schema, result.single().parameters())
        assertEquals("fresh executor", (result.single().execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text)
        assertEquals(1, count)
    }

    @Test fun `removed tools retain schema but cannot dispatch`() {
        val result = scope.restore(saved(), emptyList()).single()
        assertEquals(schema, result.parameters())
        assertFalse(result.isSelected())
        assertFalse(result.isApprovalCurrent())
    }

    @Test fun `changed schema description identity and revision never acquire an old approval`() {
        val changes = listOf(tool.copy(description = "changed"), tool.copy(parameters = { null }),
            tool.copy(hostApproval = tool.hostApproval!!.copy(stableId = "other target")),
            tool.copy(hostApproval = tool.hostApproval!!.copy(revision = "new revision")))
        changes.forEach { changed ->
            val result = scope.restore(saved(), listOf(changed)).single()
            assertEquals(tool.description, result.description)
            assertEquals(schema, result.parameters())
            assertFalse(result.isSelected())
        }
    }

    @Test fun `remembered grant and live revocation are checked without changing advertised catalog`() {
        var allowed = false
        var selected = true
        var current = true
        val live = tool.copy(needsApproval = { !allowed }, isSelected = { selected }, isApprovalCurrent = { current })
        val restored = scope.restore(saved(), listOf(live)).single()
        assertTrue(restored.needsApproval(JsonObject(emptyMap())))
        allowed = true
        assertFalse(restored.needsApproval(JsonObject(emptyMap())))
        selected = false; current = false
        assertFalse(restored.isSelected())
        assertFalse(restored.isApprovalCurrent())
        assertEquals(schema, restored.parameters())
    }

    @Test fun `fresh user input and legacy cards take current catalog`() {
        val current = listOf(tool, tool.copy(name = "new"))
        assertSame(current, scope.restore(saved() + UIMessage.user("new input"), current))
        assertSame(current, scope.restore(messages, current))
    }

    @Test fun `scope or source changes fail before executing or sending`() {
        listOf(ApprovalCatalogScope("other", "conversation", "provider", "model", "native"),
            ApprovalCatalogScope("assistant", "other", "provider", "model", "native"),
            ApprovalCatalogScope("assistant", "conversation", "other", "model", "native"),
            ApprovalCatalogScope("assistant", "conversation", "provider", "other", "native"),
            ApprovalCatalogScope("assistant", "conversation", "provider", "model", "other")).forEach { other ->
            assertThrows(IllegalStateException::class.java) { other.restore(saved(), listOf(tool)) }
        }
        assertThrows(IllegalStateException::class.java) {
            scope.restore(listOf(UIMessage.user("replacement"), saved().last()), listOf(tool))
        }
        assertThrows(IllegalStateException::class.java) {
            scope.restore(saved().dropLast(1) + saved().last().copy(id = kotlin.uuid.Uuid.random()), listOf(tool))
        }
    }

    @Test fun `a second pause captures the original catalog again`() {
        val resumed = scope.restore(saved(), listOf(tool, tool.copy(name = "late")))
        val again = scope.capture(saved(), resumed)
        assertEquals(saved().last().orbisApprovalToolCatalog!!.tools, again.tools)
    }
}
