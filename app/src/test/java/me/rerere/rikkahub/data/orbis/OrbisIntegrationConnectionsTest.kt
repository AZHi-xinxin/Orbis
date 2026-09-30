package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.integration.*
import org.junit.Assert.*
import org.junit.Test

class OrbisIntegrationConnectionsTest {
    private class Memory : OrbisConnectionPersistence {
        var saved: String? = null
        var failRead = false
        var failWrite = false
        override suspend fun read(): String? { if (failRead) error("secret path"); return saved }
        override suspend fun write(value: String?) { if (failWrite) error("secret token"); saved = value }
    }
    private val token = "a".repeat(40)
    @Test fun addressesRejectCredentialLeakAndUnencryptedPublicHosts() {
        listOf("http://example.com", "https://u:p@example.com", "https://example.com/?token=x", "https://example.com/#x",
            "http://169.254.169.254", "https://example.com/a%2fb", "https://example.com/\n").forEach {
            assertTrue(it, runCatching { normalizeOrbisIntegrationUrl(it) }.isFailure)
        }
        listOf("https://example.com", "http://127.0.0.1:1234", "http://100.71.245.14:8791", "http://192.168.1.2").forEach {
            assertTrue(runCatching { normalizeOrbisIntegrationUrl(it) }.isSuccess)
        }
    }
    @Test fun tokensRejectWhitespaceAndHeaders() {
        listOf("x", "a".repeat(4100), token + "\r\nX: foo", "a".repeat(20) + " " + "b".repeat(20)).forEach {
            assertTrue(runCatching { validateOrbisIntegrationToken(it) }.isFailure)
        }
    }
    @Test fun defaultClosedAndOnlyExplicitSaveEnables() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = Memory(); val store = OrbisConnectionStore(persistence, scope)
            assertFalse(store.state.value.available)
            assertNull(store.readCredential())
            store.save("https://example.com/", token, true)
            assertTrue(store.state.value.available)
            assertEquals("https://example.com", store.state.value.baseUrl)
            assertFalse(store.state.value.toString().contains(token))
            assertFalse(store.readCredential().toString().contains(token))
            store.save("https://example.com", "", false)
            assertFalse(store.state.value.available)
            assertNull(store.readCredential())
            assertTrue(store.state.value.configured)
        } finally { scope.cancel() }
    }
    @Test fun changedEndpointNeedsTokenAndFailedSavePreservesCredential() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = Memory(); val store = OrbisConnectionStore(persistence, scope)
            store.save("https://example.com", token, true)
            val saved = persistence.saved; val revision = store.state.value.revision
            assertTrue(runCatching { store.save("https://other.example", "", true) }.isFailure)
            persistence.failWrite = true
            assertTrue(runCatching { store.save("https://other.example", "b".repeat(40), true) }.isFailure)
            assertEquals(saved, persistence.saved)
            assertEquals(revision, store.state.value.revision)
            assertEquals(token, store.readCredential()?.token)
        } finally { scope.cancel() }
    }
    @Test fun loadFailureKeepsUnreadFileAndDisablesWritingUntilClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = Memory().apply { saved = "broken" }
            val store = OrbisConnectionStore(persistence, scope)
            assertFalse(store.state.value.available); assertFalse(store.state.value.canEdit)
            assertTrue(runCatching { store.save("https://example.com", token, true) }.isFailure)
            assertEquals("broken", persistence.saved)
            store.clear()
            assertNull(persistence.saved); assertTrue(store.state.value.canEdit)
            store.save("https://example.com", token, true)
            persistence.failRead = true; store.reload()
            assertNull(store.readCredential())
            assertFalse(store.state.value.available)
            assertFalse(store.state.value.error!!.contains("secret"))
        } finally { scope.cancel() }
    }
}
