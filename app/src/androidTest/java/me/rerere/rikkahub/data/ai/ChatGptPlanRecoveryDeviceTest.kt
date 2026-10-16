package me.rerere.rikkahub.data.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.chatgpt.ChatGptPlanAccounts
import me.rerere.ai.provider.chatgpt.ChatGptPlanProtocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID

/** Real Android encrypted storage; synthetic identity/tokens; interceptor never opens a socket. */
class ChatGptPlanRecoveryDeviceTest {
    private class Fixture {
        private val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "synthetic-plan-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var keyId = "synthetic-old"
        var keyFailure = false
        var tokenCount = 0
        var modelCount = 0
        var expireFirstAccess = false
        var rotateOnNextToken = false
        var claimSubject = "synthetic-subject"
        val accounts: ChatGptPlanAccounts

        private fun b64(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        private fun jwt(clientId: String, nonce: String?): String {
            val header = buildJsonObject { put("alg", "RS256"); put("kid", keyId) }
            val claims = buildJsonObject {
                put("iss", ChatGptPlanProtocol.ISSUER); put("aud", clientId); put("sub", claimSubject)
                put("email", "synthetic@example.invalid"); put("exp", System.currentTimeMillis() / 1000 + 3600)
                nonce?.let { put("nonce", it) }
            }
            val payload = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private); update(payload.toByteArray()); sign()
            }
            return payload + "." + b64(signature)
        }

        init {
            clearKeys()
            accounts = ChatGptPlanAccounts(context)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                var code = 200
                val text = when (request.url.toString()) {
                    "${ChatGptPlanProtocol.ISSUER}/.well-known/jwks.json" -> if (keyFailure) {
                        code = 403; "<html>synthetic temporary unavailable</html>"
                    } else (pair.public as RSAPublicKey).let { key -> buildJsonObject {
                        put("keys", buildJsonArray { add(buildJsonObject {
                            put("kid", keyId); put("kty", "RSA"); put("alg", "RS256")
                            put("n", b64(key.modulus.toByteArray())); put("e", b64(key.publicExponent.toByteArray()))
                        }) })
                    }.toString() }
                    ChatGptPlanProtocol.TOKEN -> {
                        val form = request.body as okhttp3.FormBody
                        fun field(name: String) = (0 until form.size).firstOrNull { form.name(it) == name }?.let { form.value(it) }
                        val refresh = field("grant_type") == "refresh_token"
                        if (refresh) {
                            assertEquals("synthetic-refresh-1", field("refresh_token"))
                            assertNull(field("scope"))
                        }
                        tokenCount++
                        if (rotateOnNextToken) { keyId = "synthetic-rotated"; keyFailure = true; rotateOnNextToken = false }
                        buildJsonObject {
                            put("token_type", "Bearer"); put("access_token", "synthetic-access-$tokenCount")
                            put("refresh_token", "synthetic-refresh-$tokenCount")
                            put("id_token", jwt(checkNotNull(field("client_id")), if (refresh) null else "synthetic-nonce"))
                            put("expires_in", if (expireFirstAccess && tokenCount == 1) 1 else 3600)
                            put("scope", ChatGptPlanProtocol.SCOPE)
                        }.toString()
                    }
                    "${ChatGptPlanProtocol.RESOURCE}/models" -> {
                        modelCount++
                        assertEquals("Bearer synthetic-access-$tokenCount", request.header("Authorization"))
                        """{"models":[{"slug":"synthetic-model","display_name":"Synthetic","visibility":"list"}]}"""
                    }
                    else -> error("Unexpected synthetic endpoint")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                    .body(text.toResponseBody((if (code == 200) "application/json" else "text/html").toMediaType())).build()
            }.build()
            ChatGptPlanAccounts::class.java.getDeclaredField("client").apply { isAccessible = true }.set(accounts, client)
        }
        suspend fun signIn(id: String? = null, client: String = "oaiapp_synthetic") = accounts.finishSignIn(id,
            "http://127.0.0.1:44123/auth/callback", "synthetic-state", "synthetic-state", "synthetic-nonce",
            "synthetic-verifier", client, "synthetic-code", null)
        fun clearKeys() { ChatGptPlanAccounts::class.java.getDeclaredField("cachedKeys").apply { isAccessible = true }.set(null, null) }
        fun close() {
            clearKeys()
            // Only this fixture's unique vault directory, never the application's real vault.
            directory.listFiles()?.forEach { check(it.isFile); it.delete() }
            directory.delete()
        }
    }

    @Test fun signingKeyOutageAfterCodeExchangeResumesWithoutSpendingCodeTwice() = runBlocking {
        val f = Fixture()
        try {
            f.rotateOnNextToken = true
            assertTrue(runCatching { f.signIn() }.isFailure)
            val pending = f.accounts.accounts().single()
            assertTrue(pending.verificationPending); assertFalse(pending.signedIn)
            assertEquals(1, f.tokenCount); assertEquals(0, f.modelCount)
            f.keyFailure = false
            assertEquals("synthetic-model", f.accounts.models(pending.id).single().modelId)
            assertEquals(1, f.tokenCount)
            assertTrue(f.accounts.accounts().single().signedIn)
        } finally { f.close() }
    }

    @Test fun rotatedRefreshIsRetainedBeforeIdentityKeyRetry() = runBlocking {
        val f = Fixture()
        try {
            f.expireFirstAccess = true
            val signedIn = f.signIn()
            f.rotateOnNextToken = true
            assertTrue(runCatching { f.accounts.models(signedIn.id) }.isFailure)
            assertEquals(2, f.tokenCount); assertTrue(f.accounts.accounts().single().verificationPending)
            f.keyFailure = false
            assertEquals("synthetic-access-2", f.accounts.accessToken(signedIn.id))
            assertEquals(2, f.tokenCount) // Never retry the already rotated old refresh token.
        } finally { f.close() }
    }

    @Test fun sameEmailRegistrationsStaySeparateAndReauthorizationReusesSelection() = runBlocking {
        val f = Fixture()
        try {
            val first = f.signIn(client = "oaiapp_first")
            val second = f.signIn(client = "oaiapp_second")
            assertNotEquals(first.id, second.id)
            assertEquals(2, f.accounts.accounts().size)
            assertEquals(first.id, f.signIn(first.id, "oaiapp_first").id)
            assertEquals(2, f.accounts.accounts().size)
        } finally { f.close() }
    }

    @Test fun changedSubjectCannotBecomeUsableEvenAfterSuccessfulTokenExchange() = runBlocking {
        val f = Fixture()
        try {
            val original = f.signIn()
            f.claimSubject = "synthetic-other-person"
            assertTrue(runCatching { f.signIn(original.id) }.isFailure)
            assertFalse(f.accounts.accounts().single().signedIn)
            assertTrue(runCatching { f.accounts.models(original.id) }.isFailure)
            assertEquals(0, f.modelCount)
        } finally { f.close() }
    }
}
