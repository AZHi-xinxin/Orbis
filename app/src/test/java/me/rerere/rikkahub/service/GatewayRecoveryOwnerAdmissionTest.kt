package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class GatewayRecoveryOwnerAdmissionTest {
    // Equal session values must not substitute for the captured live session instance.
    private data class Session(val id: String)
    private data class Model(val id: String)
    private data class Provider(val endpoint: String, val credentialProfile: String)

    private val session = Session("conversation")
    private val assistant = "assistant"
    private val model = Model("vision")
    private val provider = Provider("https://gateway.invalid", "synthetic-profile-a")

    private fun owner(
        session: Session = this.session,
        assistant: String = this.assistant,
        model: Model = this.model,
        provider: Provider? = this.provider,
        settled: Boolean = false,
    ) = GatewayRecoveryCapturedOwner(session, assistant, model, provider, settled)

    private fun admits(
        owners: List<GatewayRecoveryCapturedOwner<Session, String, Model, Provider>>,
        currentSession: Session = session,
        currentAssistant: String = assistant,
        expectedModel: Model? = model,
        expectedProvider: Provider? = provider,
        currentModel: Model? = model,
        currentProvider: Provider? = provider,
    ) = gatewayRecoveryOwnersMatch(currentSession, currentAssistant, expectedModel,
        expectedProvider, currentModel, currentProvider, owners)

    @Test fun `same live owner and equal copied route are admitted`() {
        assertTrue(admits(listOf(owner(model = model.copy(), provider = provider.copy())),
            currentModel = model.copy(), currentProvider = provider.copy()))
    }

    @Test fun `equal session value cannot replace captured session identity`() {
        val replacement = session.copy()
        assertEquals(session, replacement)
        assertNotSame(session, replacement)
        assertFalse(admits(listOf(owner()), currentSession = replacement))
        assertFalse(admits(listOf(owner(session = replacement))))
    }

    @Test fun `assistant mismatch is rejected even when model and provider match`() {
        assertFalse(admits(listOf(owner(assistant = "another-assistant"))))
        assertFalse(admits(listOf(owner()), currentAssistant = "another-assistant"))
    }

    @Test fun `changed current model or provider is rejected with no captured requests`() {
        assertFalse(admits(emptyList(), currentModel = Model("different-model")))
        assertFalse(admits(emptyList(), currentProvider = provider.copy(endpoint = "https://other.invalid")))
        assertFalse(admits(emptyList(), currentProvider = provider.copy(credentialProfile = "synthetic-profile-b")))
    }

    @Test fun `unsettled captured model and provider must both match current route`() {
        assertFalse(admits(listOf(owner(model = Model("old-model")))))
        assertFalse(admits(listOf(owner(provider = provider.copy(endpoint = "https://old.invalid")))))
        assertFalse(admits(listOf(owner(provider = provider.copy(credentialProfile = "synthetic-profile-b")))))
        assertFalse(admits(listOf(owner(provider = null))))
    }

    @Test fun `settled owner may retain a different old model and provider`() {
        assertTrue(admits(listOf(owner(model = Model("old-model"),
            provider = provider.copy(endpoint = "https://old.invalid"), settled = true))))
        assertTrue(admits(listOf(owner(model = Model("old-model"), provider = null, settled = true))))
    }

    @Test fun `settled owner still requires captured session and assistant`() {
        assertFalse(admits(listOf(owner(session = session.copy(), settled = true))))
        assertFalse(admits(listOf(owner(assistant = "another-assistant", settled = true))))
    }

    @Test fun `settled owner does not bypass current route snapshot`() {
        val owners = listOf(owner(settled = true))
        assertFalse(admits(owners, currentModel = Model("changed-model")))
        assertFalse(admits(owners, currentProvider = provider.copy(endpoint = "https://changed.invalid")))
        assertFalse(admits(owners, currentProvider = provider.copy(credentialProfile = "synthetic-profile-b")))
    }

    @Test fun `multiple owners all require admission regardless of ordering`() {
        val unresolved = owner()
        val settledOld = owner(model = Model("old-model"), provider = null, settled = true)
        val valid = listOf(unresolved, settledOld, owner(provider = provider.copy()))
        assertTrue(admits(valid))
        for (invalid in listOf(
            owner(model = Model("wrong-model")),
            owner(provider = provider.copy(endpoint = "https://wrong.invalid")),
            owner(session = session.copy(), settled = true),
            owner(assistant = "wrong-assistant", settled = true),
        )) {
            assertFalse(admits(valid + invalid))
            assertFalse(admits(listOf(invalid) + valid))
        }
    }

    @Test fun `empty captured collection keeps current route check and does not require historical nonce`() {
        assertTrue(admits(emptyList()))
        assertFalse(admits(emptyList(), expectedModel = Model("previous-model")))
        assertFalse(admits(emptyList(), expectedProvider = provider.copy(endpoint = "https://previous.invalid")))
    }

    @Test fun `nullable route retains comparison semantics without inventing remote idle proof`() {
        assertTrue(admits(emptyList(), expectedModel = null, expectedProvider = null,
            currentModel = null, currentProvider = null))
        assertFalse(admits(emptyList(), currentModel = null))
        assertFalse(admits(emptyList(), currentProvider = null))
        assertTrue(admits(listOf(owner(provider = null)), expectedProvider = null, currentProvider = null))
        assertFalse(admits(listOf(owner(provider = null)), expectedModel = null, expectedProvider = null,
            currentModel = null, currentProvider = null))
    }

    @Test fun `ownership diagnostic excludes model and provider details`() {
        assertEquals("GatewayRecoveryCapturedOwner(redacted)", owner().toString())
    }
}
