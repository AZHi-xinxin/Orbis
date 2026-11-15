package me.rerere.rikkahub.web.routes

import org.junit.Assert.*
import org.junit.Test

class OrbisEventCredentialPolicyTest {
    @Test fun touchKeyCannotAuthorizeOrdinaryEventsOrConversationContext() {
        assertTrue(acceptsOrbisEventCredential("device-key", true, { false }, { it == "device-key" }))
        assertFalse(acceptsOrbisEventCredential("device-key", false, { false }, { error("must not inspect scoped credential") }))
    }
    @Test fun existingDeviceIntegrationRemainsAcceptedAndWebJwtDoesNot() {
        for (touch in listOf(false, true)) {
            assertTrue(acceptsOrbisEventCredential("legacy-key", touch, { it == "legacy-key" }, { false }))
            assertFalse(acceptsOrbisEventCredential("web-jwt", touch, { it == "legacy-key" }, { it == "touch-key" }))
        }
    }
    @Test fun missingCredentialCannotInitializeOrReadSecretFiles() {
        for (token in listOf(null, "", " ")) assertFalse(acceptsOrbisEventCredential(token, true,
            { error("no read") }, { error("no read") }))
    }
}
