package me.rerere.rikkahub.data.orbis.integration

import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Authenticator
import okhttp3.CookieJar
import org.junit.Assert.*
import org.junit.Test

/** Loopback and wholly synthetic credentials only; never resolves or contacts a public host. */
class OrbisAtlasBootstrapClientTest {
    private val key = "synthetic-gateway-token-" + "g".repeat(40)
    private val token = "orb_atlas_" + "a".repeat(43)
    private val requestId = "b".repeat(32)
    private val deviceId = "c".repeat(32)
    private val grantId = "d".repeat(32)
    private val capability = """{"schema":"orbis.st.atlas-bootstrap/1","service":"stillerbrain","enabled":true,"scope":"atlas.metadata.read","atlasSchema":"orbis.st.atlas/1"}"""
    private fun result(status: String = "active") = """{"schema":"orbis.st.atlas-register-result/1","requestId":"$requestId","grantId":"$grantId","status":"$status","scope":"atlas.metadata.read","expiresAt":null}"""
    private fun provider(url: String) = ProviderSetting.OpenAI(apiKey = key, baseUrl = url)

    @Test fun onlyRootOrFinalV1IsAcceptedAndProxyPrefixIsKept() {
        listOf("https://example.test" to "https://example.test", "https://example.test/v1/" to "https://example.test",
            "https://example.test/proxy/v1" to "https://example.test/proxy",
            "http://100.64.1.2:8791/v1" to "http://100.64.1.2:8791").forEach { (input, expected) ->
            assertEquals(expected, atlasBootstrapSource(provider(input)).root)
        }
    }

    @Test fun rejectsTraversalNormalizationEncodedSeparatorsCredentialsQueriesAndPublicCleartext() {
        listOf("https://example.test/a/../v1", "https://example.test/a/./v1", "https://example.test/a%2fv1",
            "https://example.test/a/%2e%2e/v1", "https://example.test//v1", "https://u:p@example.test/v1",
            "https://example.test/v1?", "https://example.test/v1#", "http://public.example/v1",
            "http://169.254.169.254/v1", "https://example.test/custom", "https://example.test/a\\v1").forEach {
            val error = runCatching { atlasBootstrapSource(provider(it)) }.exceptionOrNull()
            assertTrue(it, error is AtlasBootstrapException)
            assertEquals("source_invalid", (error as AtlasBootstrapException).code)
            assertFalse(error.toString().contains(key))
        }
    }

    @Test fun rejectsMultipleKeysDisabledProvidersAndDoesNotExposeSingleKey() {
        listOf(provider("https://example.test/v1").copy(apiKey = "$key,$key"),
            provider("https://example.test/v1").copy(apiKey = "$key\n$key"),
            provider("https://example.test/v1").copy(enabled = false),
            provider("https://example.test/v1").copy(apiKey = "")).forEach {
            assertTrue(runCatching { atlasBootstrapSource(it) }.isFailure)
        }
        val source = atlasBootstrapSource(provider("https://example.test/v1"))
        assertFalse(source.toString().contains(key))
        assertTrue(source.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun clientHasNoRedirectRetryProxyCookiesAuthenticatorsOrLoggers() {
        val client = OrbisAtlasBootstrapClient().client
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.callTimeoutMillis in 1..20_000)
    }

    @Test fun capabilityUsesExactUnauthenticatedSameOriginPrefixPath() = runBlocking {
        Fixture(capability).use { server ->
            OrbisAtlasBootstrapClient().capability(server.root + "/proxy")
            val request = server.request.get()!!
            assertEquals("GET /proxy/v1/st/atlas/capabilities HTTP/1.1", request.first())
            assertFalse(request.any { it.startsWith("Authorization:", true) || it.startsWith("Cookie:", true) })
            assertEquals("", server.body.get())
        }
    }

    @Test fun capabilityRejectsForeignProductExtraMissingDuplicateNestedAndIncorrectTypes() = runBlocking {
        val invalid = listOf(
            capability.replace("stillerbrain", "openai"), capability.replace("true", "\"true\""),
            capability.replace("\"enabled\":true,", ""), capability.replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false"),
            capability.replace("\"enabled\":true", "\"enabled\":true,\"\\u0065nabled\":true"),
            capability.dropLast(1) + ",\"url\":\"https://other.invalid\"}",
            "{\"nested\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}",
        )
        invalid.forEach { value -> Fixture(value).use { server ->
            failure("invalid_response") { OrbisAtlasBootstrapClient().capability(server.root) }
        } }
    }

    @Test fun disabledCapabilityIsNotSuccessfulEnrollment() = runBlocking {
        Fixture(capability.replace("true", "false")).use { server ->
            failure("disabled") { OrbisAtlasBootstrapClient().capability(server.root) }
        }
    }

    @Test fun registerUsesOnlyGatewayBearerAndExactVerifierRequest() = runBlocking {
        Fixture(result()).use { server ->
            val source = atlasBootstrapSource(provider(server.root + "/proxy/v1"))
            val result = OrbisAtlasBootstrapClient().register(source, requestId, deviceId, atlasBootstrapDigest(token))
            assertEquals(AtlasBootstrapRegistration(grantId, true), result)
            val headers = server.request.get()!!
            assertEquals("POST /proxy/v1/st/atlas/grants HTTP/1.1", headers.first())
            assertEquals(1, headers.count { it.startsWith("Authorization:", true) })
            assertTrue(headers.any { it == "Authorization: Bearer $key" })
            val body = Json.parseToJsonElement(server.body.get()!!).jsonObject
            assertEquals(setOf("schema", "requestId", "deviceId", "verifier"), body.keys)
            assertEquals(atlasBootstrapDigest(token), body.getValue("verifier").jsonPrimitive.content)
            assertFalse(server.body.get()!!.contains(token)); assertFalse(server.body.get()!!.contains(key))
        }
    }

    @Test fun revokedResponseIsDistinguishedAndNeverTreatedAsActive() = runBlocking {
        Fixture(result("revoked")).use { server ->
            val result = OrbisAtlasBootstrapClient().register(atlasBootstrapSource(provider(server.root)), requestId, deviceId, atlasBootstrapDigest(token))
            assertFalse(result.active)
        }
    }

    @Test fun registerRejectsWrongIdsScopeExpiryAndSecretBearingAdditionalFields() = runBlocking {
        listOf(result().replace(requestId, "e".repeat(32)), result().replace(grantId, "bad"),
            result().replace("atlas.metadata.read", "all"), result().replace("null", "\"forever\""),
            result("unknown"), result().dropLast(1) + ",\"token\":\"$key\"}"
        ).forEach { response -> Fixture(response).use { server ->
            failure("invalid_response") {
                OrbisAtlasBootstrapClient().register(atlasBootstrapSource(provider(server.root)), requestId, deviceId, atlasBootstrapDigest(token))
            }
        } }
    }

    @Test fun revokeUsesIndependentTokenAndFixedSchemaWithoutGatewayKey() = runBlocking {
        Fixture("""{"schema":"orbis.st.atlas-revoke-result/1","status":"revoked"}""").use { server ->
            OrbisAtlasBootstrapClient().revoke(server.root, token, requestId)
            val headers = server.request.get()!!
            assertEquals("POST /v1/st/atlas/grants/revoke HTTP/1.1", headers.first())
            assertTrue(headers.any { it == "Authorization: Bearer $token" })
            assertFalse(headers.any { it.contains(key) })
            assertEquals(setOf("schema", "requestId"), Json.parseToJsonElement(server.body.get()!!).jsonObject.keys)
        }
    }

    @Test fun redirectsAndErrorBodiesNeverForwardOrExposeCredentials() = runBlocking {
        listOf(302 to "unknown", 401 to "unauthorized", 403 to "disabled", 404 to "unsupported", 503 to "unknown").forEach { (status, reason) ->
            Fixture(key, status, extra = "Location: http://127.0.0.1:1/forbidden\r\n").use { server ->
                failure(reason) { OrbisAtlasBootstrapClient().revoke(server.root, token, requestId) }
            }
        }
    }

    @Test fun nonJsonAndOversizeResponsesFailClosed() = runBlocking {
        Fixture(capability, contentType = "text/html").use { server ->
            failure("invalid_response") { OrbisAtlasBootstrapClient().capability(server.root) }
        }
        Fixture(" ".repeat(4097)).use { server ->
            failure("invalid_response") { OrbisAtlasBootstrapClient().capability(server.root) }
        }
        Fixture(capability, length = 4097).use { server ->
            failure("invalid_response") { OrbisAtlasBootstrapClient().capability(server.root) }
        }
    }

    private suspend fun failure(code: String, action: suspend () -> Unit) {
        try { withTimeout(5_000) { action() }; fail("Expected protocol failure") }
        catch (error: AtlasBootstrapException) {
            assertEquals(code, error.code); assertNull(error.cause)
            assertFalse(error.toString().contains(key)); assertFalse(error.toString().contains(token))
        }
    }

    private class Fixture(responseBody: String, status: Int = 200, contentType: String = "application/json",
        length: Int = responseBody.toByteArray().size, extra: String = "") : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val peer = AtomicReference<Socket?>()
        val root = "http://127.0.0.1:${server.localPort}"
        val request = AtomicReference<List<String>?>()
        val body = AtomicReference<String?>()
        private val worker = thread(isDaemon = true, name = "atlas-bootstrap-loopback") {
            try { server.accept().use { socket ->
                peer.set(socket); socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val headers = buildList {
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; add(line) }
                }
                val count = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                check(count in 0..2048)
                val chars = CharArray(count)
                var read = 0
                while (read < count) { val next = input.read(chars, read, count - read); check(next > 0); read += next }
                request.set(headers); body.set(String(chars))
                socket.getOutputStream().use { output ->
                    output.write(("HTTP/1.1 $status Fixture\r\nContent-Type: $contentType\r\nContent-Length: $length\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(responseBody.toByteArray()); output.flush()
                }
            } } catch (_: Exception) { /* Intentional peer close/oversize rejection. */ }
            finally { peer.set(null) }
        }
        override fun close() { peer.get()?.runCatching { close() }; server.close(); worker.join(1000) }
    }
}
