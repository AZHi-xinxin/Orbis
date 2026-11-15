package me.rerere.rikkahub.data.orbis.integration

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ConnectionCredentialStateTest {
    private val owner = Assistant()
    private fun saved(kind: String, auth: String = "bearer"): Settings {
        val draft = ConnectionDraft(kind, "合成连接", "https://example.invalid/api", model = "test", voice = "test", auth = auth)
        val pending = ConnectionProposalRules.propose(Settings(assistants = listOf(owner)), owner.id.toString(), draft)
        assertFalse(ConnectionProposalRules.hasSavedCredential(pending, pending.connectionProposals.single()))
        return ConnectionProposalRules.approve(pending, draft.id(owner.id.toString()), "synthetic-key", true)
    }
    @Test fun savedPresenceWorksForAllFourKindsAndSurvivesClosingWithoutExposingKey() {
        listOf("mcp_http", "mcp_sse", "model_openai", "tts_openai").forEach { kind ->
            val before = saved(kind); val p = before.connectionProposals.single()
            assertTrue(kind, ConnectionProposalRules.hasSavedCredential(before, p))
            val closed = ConnectionProposalRules.dismissSetup(before, p.owner, p.id)
            assertTrue(ConnectionProposalRules.hasSavedCredential(closed, closed.connectionProposals.single()))
            assertFalse(closed.connectionProposals.single().setupCompleted)
            assertEquals(before, closed.copy(connectionProposals = before.connectionProposals))
            assertFalse(closed.connectionProposals.toString().contains("synthetic-key"))
            val kept = ConnectionProposalRules.approve(closed, p.id, null, true)
            assertEquals(closed, kept)
        }
    }
    @Test fun missingNativeRecordOrEmptyCredentialNeverClaimsSaved() {
        listOf("mcp_http", "mcp_sse", "model_openai", "tts_openai").forEach { kind ->
            val s = saved(kind); val p = s.connectionProposals.single()
            assertFalse(ConnectionProposalRules.hasSavedCredential(s.copy(mcpServers = emptyList(), providers = emptyList(), ttsProviders = emptyList()), p))
            val empty = when (kind) {
                "model_openai" -> s.copy(providers = s.providers.map { if (it.id.toString() == p.id) (it as ProviderSetting.OpenAI).copy(apiKey = " ") else it })
                "tts_openai" -> s.copy(ttsProviders = s.ttsProviders.map { if (it.id.toString() == p.id) (it as TTSProviderSetting.OpenAI).copy(apiKey = " ") else it })
                else -> s.copy(mcpServers = s.mcpServers.map { it.clone(commonOptions = it.commonOptions.copy(headers = listOf("Authorization" to "Bearer "))) })
            }
            assertFalse(kind, ConnectionProposalRules.hasSavedCredential(empty, p))
        }
    }
    @Test fun unrelatedCredentialAndKeylessConfigurationDoNotProduceSavedBadge() {
        val s = saved("mcp_http"); val p = s.connectionProposals.single()
        val changed = s.copy(mcpServers = s.mcpServers.map { it.clone(commonOptions = it.commonOptions.copy(displayName = "手动修改")) })
        assertFalse(ConnectionProposalRules.hasSavedCredential(changed, p))
        val keyless = saved("mcp_http", "none")
        assertFalse(ConnectionProposalRules.hasSavedCredential(keyless, keyless.connectionProposals.single()))
        val other = ConnectionProposal(Assistant().id.toString(), p.draft)
        assertFalse(ConnectionProposalRules.hasSavedCredential(s, other))
    }
}
