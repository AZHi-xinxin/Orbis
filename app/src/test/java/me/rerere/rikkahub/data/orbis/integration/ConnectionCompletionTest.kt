package me.rerere.rikkahub.data.orbis.integration

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.mcp.connectionKey
import org.junit.Assert.*
import org.junit.Test

class ConnectionCompletionTest {
    private val owner = Assistant()
    private val other = Assistant()
    private val draft = ConnectionDraft("mcp_http", "我的 ST", "http://127.0.0.1:18901/mcp")
    private fun saved(): Settings {
        val initial = Settings(assistants = listOf(owner, other), mcpServers = emptyList())
        val pending = ConnectionProposalRules.propose(initial, owner.id.toString(), draft)
        return ConnectionProposalRules.approve(pending, draft.id(owner.id.toString()), "synthetic-private-key", true)
    }
    @Test fun renameChangesRealDisplayTitleWithoutChangingAnyConnectionOrToolIdentity() {
        val old = saved(); val id = old.connectionProposals.single().id; val before = old.mcpServers.single()
        assertEquals("我的 ST", before.commonOptions.displayTitle)
        val changed = ConnectionProposalRules.renameMcp(old, owner.id.toString(), id, "记忆空间")
        val after = changed.mcpServers.single()
        assertEquals("记忆空间", after.commonOptions.displayTitle)
        assertEquals(before.connectionKey(), after.connectionKey())
        assertEquals(before, after.clone(commonOptions = after.commonOptions.copy(displayName = before.commonOptions.displayName)))
        assertTrue(ConnectionProposalRules.matchesInstalled(changed, changed.connectionProposals.single()))
        assertTrue(runCatching { ConnectionProposalRules.renameMcp(old, other.id.toString(), id, "no") }.isFailure)
        assertEquals(changed, ConnectionProposalRules.renameMcp(changed, owner.id.toString(), id, "记忆空间"))
    }
    @Test fun completionNeedsCurrentVerificationAndPreservesNativeConnection() {
        val old = saved(); val id = old.connectionProposals.single().id
        assertTrue(runCatching { ConnectionProposalRules.completeSetup(old, owner.id.toString(), id) { false } }.isFailure)
        val complete = ConnectionProposalRules.completeSetup(old, owner.id.toString(), id) { true }
        assertTrue(complete.connectionProposals.single().setupCompleted)
        assertEquals(old.mcpServers, complete.mcpServers); assertEquals(old.assistants, complete.assistants)
        val off = ConnectionProposalRules.setMcpEnabled(old, owner.id.toString(), id, false)
        assertTrue(runCatching { ConnectionProposalRules.completeSetup(off, owner.id.toString(), id) { true } }.isFailure)
        val edited = ConnectionProposalRules.propose(complete, owner.id.toString(), draft.copy(name = "new"))
        assertFalse(edited.connectionProposals.single().setupCompleted)
    }
}
