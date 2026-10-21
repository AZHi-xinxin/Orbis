package me.rerere.rikkahub.data.orbis.integration

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ConnectionProposalTest {
    private val owner = Assistant(name = "Owner")
    private val other = Assistant(name = "Other")
    private val draft = ConnectionDraft("tts_openai", "Voice", "https://voice.example/v1", "tts", "alloy")
    private val initial = Settings(assistants = listOf(owner, other), ttsProviders = emptyList(), mcpServers = emptyList())
    private fun propose(d: ConnectionDraft = draft, settings: Settings = initial) = ConnectionProposalRules.propose(settings, owner.id.toString(), d)
    @Test fun proposalsDoNotConnectWaitOrChangeMainModel() {
        val next = propose()
        assertTrue(next.ttsProviders.isEmpty()); assertTrue(next.mcpServers.isEmpty())
        assertEquals(initial.assistants, next.assistants); assertEquals("pending", next.connectionProposals.single().status)
        assertEquals(next, propose(settings = next))
    }
    @Test fun sameScopeRememberedWithoutLeakingNativeKeyOrChangingSelection() {
        val pending = propose(); val id = pending.connectionProposals.single().id
        val saved = ConnectionProposalRules.approve(pending, id, "synthetic-key", true)
        val changed = propose(draft.copy(voice = "nova"), saved)
        val native = changed.ttsProviders.single() as TTSProviderSetting.OpenAI
        assertEquals("nova", native.voice); assertEquals("synthetic-key", native.apiKey)
        assertEquals("saved", changed.connectionProposals.single().status)
        assertFalse(changed.connectionProposals.toString().contains("synthetic-key"))
        assertEquals(initial.assistants, changed.assistants)
    }
    @Test fun newEndpointOwnerKindAndAuthNeverInheritGrant() {
        val pending = propose(); val saved = ConnectionProposalRules.approve(pending, pending.connectionProposals.single().id, "synthetic", true)
        listOf(draft.copy(endpoint = "https://other.example/v1"), draft.copy(endpoint = "https://voice.example/other"),
            draft.copy(auth = "none"), ConnectionDraft("mcp_http", "MCP", draft.endpoint)).forEach {
            assertEquals("pending", propose(it, saved).connectionProposals.last().status)
        }
        assertEquals("pending", ConnectionProposalRules.propose(saved, other.id.toString(), draft).connectionProposals.last().status)
    }
    @Test fun humanEditsAndRevocationWinOverModelUpdates() {
        val pending = propose(); val saved = ConnectionProposalRules.approve(pending, pending.connectionProposals.single().id, "synthetic", true)
        val human = saved.copy(ttsProviders = listOf((saved.ttsProviders.single() as TTSProviderSetting.OpenAI).copy(voice = "human")))
        val result = propose(draft.copy(voice = "ai"), human)
        assertEquals("human", (result.ttsProviders.single() as TTSProviderSetting.OpenAI).voice)
        assertEquals("changed_in_settings", result.connectionProposals.single().status)
        val revoked = ConnectionProposalRules.replace(saved, saved.connectionProposals.single().copy(alwaysAllow = false))
        assertEquals("pending", propose(draft.copy(voice = "ai"), revoked).connectionProposals.single().status)
    }
    @Test fun noSecretUrlsScriptsUnknownKindsOrWrongOwners() {
        listOf("https://user:pass@host/v1", "https://host/v1?key=secret", "https://host/v1#key", "file:///system/test", "https://host/a/../b").forEach {
            assertTrue(runCatching { propose(draft.copy(endpoint = it)) }.isFailure)
        }
        assertTrue(runCatching { propose(draft.copy(kind = "shell")) }.isFailure)
        assertTrue(runCatching { ConnectionProposalRules.propose(initial, "missing", draft) }.isFailure)
        val pending = propose()
        assertTrue(runCatching { ConnectionProposalRules.approve(pending, pending.connectionProposals.single().id, null, true) }.isFailure)
        assertTrue(pending.ttsProviders.isEmpty())
    }
    @Test fun mcpIsDedicatedToOneOwnerAndIdempotent() {
        val d = ConnectionDraft("mcp_http", "Friendly name", "http://127.0.0.1:8080/mcp", auth = "none")
        val pending = propose(d); val id = pending.connectionProposals.single().id
        val saved = ConnectionProposalRules.approve(pending, id, null, true)
        assertEquals(1, saved.mcpServers.size); assertTrue(saved.mcpServers.single().commonOptions.strictEndpoint)
        assertTrue(saved.assistants.first().mcpServers.contains(saved.mcpServers.single().id))
        assertEquals(other.mcpServers, saved.assistants.last().mcpServers)
        assertEquals(saved, propose(d, saved))
    }
    @Test fun keylessMcpStagesWithoutConnectingAndOnlyHumanEnableChangesIt() {
        val d = ConnectionDraft("mcp_http", "ST MCP", "http://127.0.0.1:8080/mcp", auth = "none")
        val staged = ConnectionProposalRules.stageKeyless(initial, owner.id.toString(), d)
        val id = staged.connectionProposals.single().id
        assertEquals("saved", staged.connectionProposals.single().status)
        assertFalse(staged.mcpServers.single().commonOptions.enable)
        assertEquals(staged, ConnectionProposalRules.stageKeyless(staged, owner.id.toString(), d))
        val revised = ConnectionProposalRules.stageKeyless(staged, owner.id.toString(), d.copy(name = "My ST"))
        assertFalse(revised.mcpServers.single().commonOptions.enable)
        val enabled = ConnectionProposalRules.enableMcp(revised, owner.id.toString(), id)
        assertTrue(enabled.mcpServers.single().commonOptions.enable)
        assertTrue(runCatching { ConnectionProposalRules.enableMcp(revised, other.id.toString(), id) }.isFailure)
    }
    @Test fun keylessVoiceSavesButNeverSelectsOrConnects() {
        val d = draft.copy(auth = "none")
        val staged = ConnectionProposalRules.stageKeyless(initial, owner.id.toString(), d)
        assertEquals("saved", staged.connectionProposals.single().status)
        assertEquals(initial.selectedTTSProviderId, staged.selectedTTSProviderId)
        assertEquals("", (staged.ttsProviders.single() as TTSProviderSetting.OpenAI).apiKey)
    }
    @Test fun newModelProviderNeedsOnlyNativeKeyAndRemainsSeparateFromSelections() {
        val d = ConnectionDraft("model_openai", "Fast", "https://models.example/v1", model = "fast-v2")
        val pending = propose(d)
        val saved = ConnectionProposalRules.approve(pending, d.normalized().id(owner.id.toString()), "synthetic-model-key", true)
        val provider = saved.providers.single { it.id.toString() == saved.connectionProposals.single().id } as me.rerere.ai.provider.ProviderSetting.OpenAI
        assertEquals("synthetic-model-key", provider.apiKey)
        assertEquals("fast-v2", provider.models.single().modelId)
        assertEquals(initial.fastModelId, saved.fastModelId)
        assertEquals(initial.chatModelId, saved.chatModelId)
        assertFalse(saved.connectionProposals.toString().contains("synthetic-model-key"))
        assertTrue(ConnectionProposalRules.matchesInstalled(saved, saved.connectionProposals.single()))
        val changed = propose(d.copy(model = "fast-v3"), saved)
        assertEquals("fast-v3", changed.providers.single { it.id == provider.id }.models.single().modelId)
        assertEquals(provider.models.single().id, changed.providers.single { it.id == provider.id }.models.single().id)
    }
    @Test fun sseAndAssistantUnbindingAreNotMistakenForHttpApproval() {
        val d = ConnectionDraft("mcp_sse", "Legacy", "https://mcp.example/sse", auth = "none")
        val saved = ConnectionProposalRules.stageKeyless(initial, owner.id.toString(), d)
        assertTrue(saved.mcpServers.single() is me.rerere.rikkahub.data.ai.mcp.McpServerConfig.SseTransportServer)
        val unbound = saved.copy(assistants = saved.assistants.map { it.copy(mcpServers = emptySet()) })
        val revised = ConnectionProposalRules.stageKeyless(unbound, owner.id.toString(), d.copy(name = "Updated"))
        assertEquals("changed_in_settings", revised.connectionProposals.single().status)
        assertTrue(revised.assistants.first().mcpServers.isEmpty())
    }
}
