package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.integration.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionDismissalTest {
    private val owner = Assistant()
    private val other = Assistant()
    private val ownerId = owner.id.toString()
    private val draft = ConnectionDraft("mcp_http", "ST test", "https://example.invalid/mcp")
    private fun fixtures(): Settings {
        var state = Settings(assistants = listOf(owner, other), mcpServers = emptyList())
        state = ConnectionProposalRules.propose(state, ownerId, draft)
        state = ConnectionProposalRules.approve(state, draft.id(ownerId), "synthetic-key", true, enableMcp = false)
        state = ConnectionProposalRules.propose(state, ownerId,
            ConnectionDraft("model_openai", "ST gateway", "https://example.invalid/v1", model = "model"))
        return ConnectionProposalRules.propose(state, other.id.toString(), draft)
    }
    private fun response(parts: List<UIMessagePart>) = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

    @Test fun closeAllOfflineAndPendingNoticesPreservesEverythingExceptOwnedUiFlag() {
        val before = fixtures()
        val after = ConnectionProposalRules.dismissSetup(before, ownerId)
        assertEquals(before, after.copy(connectionProposals = before.connectionProposals))
        before.connectionProposals.zip(after.connectionProposals).forEach { (old, now) ->
            assertEquals(old, now.copy(setupDismissed = false))
            assertEquals(now.owner == ownerId, now.setupDismissed)
            assertFalse(now.setupCompleted)
            assertEquals(now.owner != ownerId, now.setupPromptPending)
        }
        assertEquals(after, ConnectionProposalRules.dismissSetup(after, ownerId))
        val restored = Json.decodeFromString<ConnectionProposal>(Json.encodeToString(after.connectionProposals.first()))
        assertTrue(restored.setupDismissed); assertFalse(restored.setupPromptPending)
        val legacy = Json.decodeFromString<ConnectionProposal>(Json.encodeToString(before.connectionProposals.first()))
        assertFalse(legacy.setupDismissed); assertTrue(legacy.setupPromptPending)
    }

    @Test fun oneIdCannotTouchAnotherAssistantOrBroadenToAllAndNewDraftCanAskAgain() {
        val before = fixtures(); val id = draft.id(ownerId)
        val hidden = ConnectionProposalRules.dismissSetup(before, ownerId, id)
        assertEquals(listOf(id), hidden.connectionProposals.filter { it.setupDismissed }.map { it.id })
        assertTrue(runCatching { ConnectionProposalRules.dismissSetup(before, ownerId, draft.id(other.id.toString())) }.isFailure)
        assertTrue(runCatching { ConnectionProposalRules.dismissSetup(before, ownerId, "") }.isFailure)
        assertTrue(runCatching { ConnectionProposalRules.dismissSetup(before, Assistant().id.toString()) }.isFailure)
        assertEquals(hidden, ConnectionProposalRules.propose(hidden, ownerId, draft))
        val edited = ConnectionProposalRules.propose(hidden, ownerId, draft.copy(name = "Changed by AI"))
        assertTrue(edited.connectionProposals.single { it.id == id }.setupPromptPending)
    }

    @Test fun actualToolCanCloseUnverifiedThenExplicitlyReopenWithoutApprovingOrSendingSecrets() = runBlocking {
        var state = fixtures(); val before = state
        val closed = mutableListOf<String?>(); val opened = mutableListOf<ConnectionProposal?>()
        val tool = createConnectionProposalTool(ownerId, { state }, { f -> state = f(state); true },
            onSetupDismissed = { closed += it }, onSetupNeeded = { p, _ -> opened += p })
        val close = buildJsonObject { put("action", "dismiss_setup") }
        assertFalse(tool.needsApproval(close))
        val receipt = response(tool.execute(close))
        assertTrue(receipt.getValue("ok").jsonPrimitive.boolean)
        assertTrue(receipt.getValue("ui_dismiss_requested").jsonPrimitive.boolean)
        assertEquals(listOf<String?>(null), closed)
        assertFalse(receipt.toString().contains("synthetic-key"))
        receipt.getValue("proposals").jsonArray.forEach {
            assertFalse(it.jsonObject.getValue("verified").jsonPrimitive.boolean)
            assertFalse(it.jsonObject.getValue("setup_completed").jsonPrimitive.boolean)
            assertTrue(it.jsonObject.getValue("setup_dismissed").jsonPrimitive.boolean)
        }
        assertEquals(before, state.copy(connectionProposals = before.connectionProposals))
        val id = draft.id(ownerId)
        assertFalse(response(tool.execute(buildJsonObject { put("action", "complete"); put("id", id) })).getValue("ok").jsonPrimitive.boolean)
        val reopen = response(tool.execute(buildJsonObject { put("action", "open_setup"); put("id", id) }))
        assertTrue(reopen.getValue("ok").jsonPrimitive.boolean)
        assertTrue(opened.single()!!.setupPromptPending)
        assertTrue(state.connectionProposals.single { it.id == id }.setupPromptPending)
        assertTrue(state.connectionProposals.single { it.owner == ownerId && it.id != id }.setupDismissed)
        assertFalse(state.mcpServers.single().commonOptions.enable)
    }

    @Test fun failedSaveOrForeignIdDoesNotCloseUiAndEmptyGuideCanBeClosed() = runBlocking {
        val state = fixtures(); var closed = 0
        val failed = createConnectionProposalTool(ownerId, { state }, { false }, onSetupDismissed = { closed++ })
        val args = buildJsonObject { put("action", "dismiss_setup") }
        assertFalse(response(failed.execute(args)).getValue("ok").jsonPrimitive.boolean)
        val tool = createConnectionProposalTool(ownerId, { state }, { f -> f(state); true }, onSetupDismissed = { closed++ })
        for (id in listOf("", draft.id(other.id.toString()))) {
            assertFalse(response(tool.execute(buildJsonObject { put("action", "dismiss_setup"); put("id", id) })).getValue("ok").jsonPrimitive.boolean)
        }
        assertEquals(0, closed)
        val empty = Settings(assistants = listOf(owner))
        val guide = createConnectionProposalTool(ownerId, { empty }, { f -> f(empty); true }, onSetupDismissed = { closed++ })
        assertTrue(response(guide.execute(args)).getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, closed)
        assertTrue(tool.description.contains("dismiss_setup"))
        assertTrue(tool.parameters().toString().contains("dismiss_setup"))
        assertTrue(orbisManualReadChapter("st_install", emptySet(), offset = 6, limit = 10).toString().contains("dismiss_setup"))
    }
}
