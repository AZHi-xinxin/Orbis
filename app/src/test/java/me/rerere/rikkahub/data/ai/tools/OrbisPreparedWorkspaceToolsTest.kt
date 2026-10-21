package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisPreparedWorkspaceToolsTest {
    @Test fun preRegisteredWriterKeepsOriginalApprovalAndRequiresLiveBindingAndWorkspace() = runBlocking {
        var bound = false
        var exists = false
        var executed = 0
        val approval = HostToolApproval("workspace:fixture:workspace_write_file", "existing", "Native write")
        val original = Tool("workspace_write_file", "original", needsApproval = { true }, hostApproval = approval,
            execute = { executed++; listOf(UIMessagePart.Text("""{"sizeBytes":10}""")) })
        val tools = narrowPreparedWorkspaceTools(listOf(original,
            original.copy(name = "workspace_read_file"), original.copy(name = "workspace_shell"),
            original.copy(name = "workspace_edit_file")), { bound }, { exists })
        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals(approval, tool.hostApproval)
        val input = buildJsonObject { put("path", "/workspace/backup.json"); put("text", "synthetic-secret") }
        assertTrue(tool.needsApproval(input))
        assertFalse(tool.isApprovalCurrent())
        assertTrue(tool.execute(input).toString().contains("workspace_not_bound"))
        bound = true
        assertTrue(tool.execute(input).toString().contains("workspace_not_bound"))
        exists = true
        for (path in listOf("/upload/secret", "/workspace/../secret", "/workspace/a\\b", "/workspace/", "/workspace/a//b")) {
            assertTrue(tool.execute(buildJsonObject { put("path", path); put("text", "") }).toString().contains("workspace_path_denied"))
        }
        assertEquals(0, executed)
        val receipt = tool.execute(input).toString()
        assertEquals(1, executed)
        assertFalse(receipt.contains("synthetic-secret"))
        bound = false
        tool.execute(input)
        assertEquals(1, executed)
    }
}
