package me.rerere.ai.provider.chatgpt

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptPlanProtocolTest {
    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptPlanProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun initialRegistrationUsesOwnIdentityAndExactLoopback() {
        val url = ChatGptPlanProtocol.authorize("urn:uuid:host", "http://127.0.0.1:44321/auth/callback", "state", "nonce", "verifier").toHttpUrl()
        assertEquals("auth.openai.com", url.host)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("Orbis", url.queryParameter("agent_name_hint"))
        assertEquals("http://127.0.0.1:44321/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals(ChatGptPlanProtocol.RESOURCE, url.queryParameter("resource"))
        assertNull(url.queryParameter("client_secret"))
    }
    @Test fun returningUsesOnlySelectedRegistrationHints() {
        val url = ChatGptPlanProtocol.authorize("host", "http://127.0.0.1:55443/auth/callback", "state", "nonce", "verifier", "oaiapp_saved", "a@example.com", "hint").toHttpUrl()
        assertEquals("oaiapp_saved", url.queryParameter("client_id")); assertNull(url.queryParameter("agent_name_hint"))
        assertEquals("hint", url.queryParameter("id_token_hint"))
    }
    @Test fun callbackCannotReplaceSelectedClientOrSkipState() {
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.callbackClient("a", "b", null, "oaiapp_new", null) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.callbackClient("a", "a", "oaiapp_old", "oaiapp_new", null) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.callbackClient("a", "a", null, "dynamic_agent_client", null) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.callbackClient("a", "a", null, null, "access_denied") }
        assertEquals("oaiapp_old", ChatGptPlanProtocol.callbackClient("a", "a", "oaiapp_old", null, null))
    }
    @Test fun redirectCannotBeNonLoopbackOrPathChanged() {
        listOf("http://localhost:1234/auth/callback", "https://example.org/auth/callback", "http://127.0.0.1:1234/callback").forEach {
            assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.authorize("host", it, "a", "b", "c") }
        }
    }
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val jwks: JsonObject get() = (pair.public as RSAPublicKey).let { public -> buildJsonObject {
        put("keys", buildJsonArray { add(buildJsonObject { put("kid", "test"); put("kty", "RSA"); put("alg", "RS256")
            put("n", b64(public.modulus.toByteArray())); put("e", b64(public.publicExponent.toByteArray())) }) })
    } }
    private fun claims() = buildJsonObject { put("iss", ChatGptPlanProtocol.ISSUER); put("aud", "oaiapp_test"); put("sub", "test-subject"); put("nonce", "test-nonce"); put("exp", 2000) }
    private fun jwt(claims: JsonObject, alg: String = "RS256"): String {
        val body = b64("""{"alg":"$alg","kid":"test"}""".toByteArray()) + "." + b64(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(body.toByteArray()); sign() }
        return body + "." + b64(signature)
    }
    @Test fun signatureAndClaimsAreVerified() {
        assertEquals("test-subject", ChatGptPlanProtocol.verifyIdToken(jwt(claims()), jwks, "oaiapp_test", "test-nonce", 1000)["sub"]?.jsonPrimitive?.content)
        listOf("iss" to JsonPrimitive("https://attacker.invalid"), "aud" to JsonPrimitive("oaiapp_other"), "exp" to JsonPrimitive(999), "nonce" to JsonPrimitive("wrong")).forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.verifyIdToken(jwt(JsonObject(claims() + (key to value))), jwks, "oaiapp_test", "test-nonce", 1000) }
        }
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.verifyIdToken(jwt(claims(), "none"), jwks, "oaiapp_test", "test-nonce", 1000) }
    }
    @Test fun unsignedMutationIsRejected() {
        val signed = jwt(claims()).split('.')
        val mutation = signed[0] + "." + b64(JsonObject(claims() + ("sub" to JsonPrimitive("attacker"))).toString().toByteArray()) + "." + signed[2]
        assertThrows(IllegalArgumentException::class.java) { ChatGptPlanProtocol.verifyIdToken(mutation, jwks, "oaiapp_test", "test-nonce", 1000) }
    }
    @Test fun safeErrorsNeverEchoTokensOrNetworkDetails() {
        val output = ChatGptPlanProtocol.safeMessage(IllegalArgumentException("token=private-secret https://private.invalid"))
        assertFalse(output.contains("private")); assertTrue(output.contains("ChatGPT"))
    }
}
