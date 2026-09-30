package me.rerere.rikkahub.data.orbis.cloudtools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake persistence only. Synthetic credentials never leave process memory. */
class CloudCredentialStoreTest {
    private val url = "https://synthetic.example.ts.net:18910"
    private val token = "A".repeat(64)
    private class Memory : CloudCredentialPersistence {
        var value: String? = null
        var failRead = false
        var failWrite = false
        override suspend fun read(): String? { if (failRead) error("synthetic sensitive detail"); return value }
        override suspend fun write(value: String?) { if (failWrite) error("synthetic sensitive detail"); this.value = value }
    }

    @Test fun saveReadClearKeepSecretsOutOfPublicState() = runTest {
        val memory = Memory(); val store = CloudToolCredentialStore(memory, backgroundScope)
        store.reload(); assertFalse(store.state.value.configured)
        store.save(url, token)
        assertTrue(store.state.value.configured); assertEquals(url, store.state.value.baseUrl)
        assertFalse(store.state.value.toString().contains(token))
        assertFalse(store.readCredential().toString().contains(token))
        store.reload(); assertTrue(store.state.value.configured)
        store.clear(); assertNull(memory.value); assertNull(store.readCredential())
    }

    @Test fun corruptLoadFailsClosedWithoutOverwritingAndExplicitClearCanRecover() = runTest {
        val memory = Memory().apply { value = "not a synthetic json object" }
        val store = CloudToolCredentialStore(memory, backgroundScope)
        store.reload(); assertFalse(store.state.value.configured); assertFalse(store.state.value.canEdit)
        try { store.save(url, token); fail("Expected refusal") } catch (_: CloudToolsException) { }
        assertEquals("not a synthetic json object", memory.value)
        store.clear(); assertTrue(store.state.value.canEdit)
    }

    @Test fun failedSavePreservesPreviousStateAndErrorNeverContainsCredentials() = runTest {
        val memory = Memory(); val store = CloudToolCredentialStore(memory, backgroundScope)
        store.reload(); store.save(url, token); val old = memory.value
        memory.failWrite = true
        try { store.save(url, "B".repeat(64)); fail("Expected failure") }
        catch (error: CloudToolsException) {
            assertFalse(error.toString().contains("synthetic sensitive detail")); assertFalse(error.toString().contains(token))
        }
        assertEquals(old, memory.value); assertTrue(store.state.value.configured)
    }

    @Test fun rejectsHttpForeignHostsCredentialsInUrlAndQueryOrFragment() {
        val bad = listOf("http://synthetic.example.ts.net:18910", "https://public.example:18910",
            "https://synthetic.example.ts.net", "$url/path", "$url?token=no", "$url#fragment",
            "https://user:pass@synthetic.example.ts.net:18910", "$url/../", " $url", "$url/%2f")
        bad.forEach { candidate ->
            try { normalizeCloudGatewayUrl(candidate); fail("Expected URL rejection") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(url, normalizeCloudGatewayUrl("$url/"))
    }

    @Test fun tokenFormatMatchesDedicatedServerGrantAndHttpClientCannotRedirectOrRetry() {
        listOf("short", "Bearer $token", "A".repeat(129), "a".repeat(42), "a".repeat(43) + "\n")
            .forEach { invalid ->
                try { validateCloudGatewayCredential(url, invalid); fail("Expected token rejection") }
                catch (_: IllegalArgumentException) { }
            }
        val client = OkHttpCloudToolHttp().client
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure); assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty()); assertNull(client.cache)
    }
}
