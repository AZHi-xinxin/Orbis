package me.rerere.ai.util

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayPeerEvidenceTest {
    private fun handle(model: String = "model", endpoint: String = "https://synthetic.example/v1/chat/completions",
        authorization: String = "Bearer synthetic", conversation: String = "conversation") =
        OrbisGatewayRequest(conversation, "a".repeat(32), model, endpoint.toHttpUrl(), authorization, "orbis:$conversation")

    @Test fun `absent response advertisement is not known gateway evidence`() {
        assertFalse(handle().hasObservedControlPeerOf(handle()))
    }

    @Test fun `prior peer evidence classifies but does not authorize changed model scope`() {
        val earlier = handle().also { it.acknowledgeControlProtocol() }
        val newer = handle(model = "another-model")
        assertTrue(newer.hasObservedControlPeerOf(earlier))
        assertFalse(newer.hasAutomaticControlScopeOf(earlier))
        assertFalse(newer.supportsAutomaticFinish)
    }

    @Test fun `classification cannot cross endpoint credential or conversation identity`() {
        val earlier = handle().also { it.acknowledgeControlProtocol() }
        assertFalse(handle(endpoint = "https://other.example/v1/chat/completions").hasObservedControlPeerOf(earlier))
        assertFalse(handle(endpoint = "https://synthetic.example/another/chat/completions").hasObservedControlPeerOf(earlier))
        assertFalse(handle(authorization = "Bearer different-synthetic").hasObservedControlPeerOf(earlier))
        assertFalse(handle(conversation = "another-conversation").hasObservedControlPeerOf(earlier))
    }
}
