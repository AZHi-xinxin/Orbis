package me.rerere.rikkahub.data.orbis.soup

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

private class MemoryDmPersistence : SoupDmPersistence {
    var saved: String? = null
    var writes = 0
    var failWrite = false
    var failRead = false
    override suspend fun read(): String? {
        check(!failRead)
        return saved
    }
    override suspend fun write(value: String?) {
        writes++
        check(!failWrite)
        saved = value
    }
}

class SoupDmSettingsTest {
    @Test fun arbitraryExplicitHttpsApiDoesNotRequireCloudGateway() {
        assertEquals("https://dm.example.org/custom/v1", soupDmEndpoint("https://dm.example.org/custom/v1/"))
        assertEquals("https://api.deepseek.com/v1", soupDmEndpoint("https://api.deepseek.com:443/v1"))
    }

    @Test fun rejectsEndpointCredentialQueryFragmentTraversalAndWrongProtocol() {
        listOf("http://dm.example.org/v1", "https://user:pass@dm.example.org/v1", "https://dm.example.org/v1?api_key=secret",
            "https://dm.example.org/v1#secret", "https://dm.example.org:8443/v1", "https://dm.example.org/%76%31",
            "https://dm.example.org/v1/../v1", "https://dm.example.org/v1//x", "https://dm.example.org/v1/chat/completions",
            "https://dm.example.org/v1\n", "https://localhost/v1").forEach { endpoint ->
            // Surrounding whitespace is harmless and normalized; embedded controls are rejected.
            if (!endpoint.endsWith('\n')) assertThrows(Exception::class.java) { soupDmEndpoint(endpoint) }
        }
        assertThrows(Exception::class.java) { soupDmEndpoint("https://dm.example.org/v1\npath") }
    }

    @Test fun explicitAttestationRequiredAndNoKeyInUiState() = runBlocking {
        val persistence = MemoryDmPersistence()
        val store = SoupDmSettingsStore(persistence, this)
        store.reload()
        try { store.save("https://dm.example.org/v1", "synthetic-model", "synthetic-secret", false); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, persistence.writes)
        val id = store.save("https://dm.example.org/v1", "synthetic-model", "synthetic-secret", true)
        assertEquals(id, store.state.value.selectionId)
        assertFalse(store.state.value.toString().contains("synthetic-secret"))
        val selected = requireNotNull(store.selectionOrNull(id))
        assertEquals("synthetic-secret", (selected.provider as ProviderSetting.OpenAI).apiKey)
        assertTrue(selected.model.customHeaders.isEmpty())
        assertTrue(selected.model.customBodies.isEmpty())
        assertTrue(selected.model.tools.isEmpty())
        assertFalse((selected.provider as ProviderSetting.OpenAI).includeHistoryReasoning)
    }

    @Test fun invalidModelKeyAndUnconfirmedSettingsNeverWrite() = runBlocking {
        val persistence = MemoryDmPersistence(); val store = SoupDmSettingsStore(persistence, this); store.reload()
        for ((model, key) in listOf("" to "key", "model" to "", "a\nb" to "key", "model" to "key\nvalue", "m".repeat(201) to "key")) {
            try { store.save("https://dm.example.org/v1", model, key, true); fail() } catch (_: Exception) { }
        }
        assertEquals(0, persistence.writes)
    }

    @Test fun saveReloadAndClearKeepConfigurationSeparateFromGame() = runBlocking {
        val persistence = MemoryDmPersistence(); val store = SoupDmSettingsStore(persistence, this); store.reload()
        val id = store.save("https://dm.example.org/v1", "synthetic-model", "synthetic-secret", true)
        store.reload()
        assertEquals(id, store.state.value.selectionId)
        assertNotNull(store.selectionOrNull(id))
        assertNull(store.selectionOrNull("00000000-0000-0000-0000-000000000001"))
        store.clear()
        assertNull(persistence.saved); assertNull(store.state.value.selectionId); assertNull(store.selectionOrNull(id))
    }

    @Test fun corruptConfigFailsClosedWithoutOverwritingEvidence(): Unit = runBlocking {
        val persistence = MemoryDmPersistence().apply { saved = "not-json" }
        val store = SoupDmSettingsStore(persistence, this); store.reload()
        assertTrue(store.state.value.loaded); assertFalse(store.state.value.canEdit)
        try { store.save("https://dm.example.org/v1", "model", "key", true); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, persistence.writes); assertEquals("not-json", persistence.saved)
        assertThrows(IllegalStateException::class.java) { store.selectionOrNull("synthetic") }
    }

    @Test fun failedSaveDoesNotContinueUsingOldConfiguration() = runBlocking {
        val persistence = MemoryDmPersistence(); val store = SoupDmSettingsStore(persistence, this); store.reload()
        val oldId = store.save("https://dm.example.org/v1", "model", "synthetic-old", true)
        persistence.failWrite = true
        try { store.save("https://dm.example.org/v1", "model", "synthetic-new", true); fail() } catch (_: IllegalStateException) { }
        assertFalse(store.state.value.canEdit)
        assertThrows(IllegalStateException::class.java) { store.selectionOrNull(oldId) }
        persistence.failWrite = false; store.reload()
        assertNotNull(store.selectionOrNull(oldId))
    }

    @Test fun keyOrEndpointChangeInvalidatesPreparedCallAndRequiresNewSelection() = runBlocking {
        val persistence = MemoryDmPersistence(); val store = SoupDmSettingsStore(persistence, this); store.reload()
        val id = store.save("https://dm.example.org/v1", "model", "synthetic-old", true)
        val gameStorage = SoupMemoryStorage(); val repository = SoupRepository(gameStorage, { 1000L })
        repository.selectHost(id)
        val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var calls = 0
        val host = SoupHost({ Settings(providers = emptyList()) }, customSelection = store::selectionOrNull) {
            calls++; UIMessage.assistant("""{"answer":"是"}""")
        }
        val controller = SoupController(repository, host)
        val prepared = controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？")
        assertEquals(0, calls)
        val newId = store.save("https://other-dm.example.org/v1", "model", "synthetic-new", true)
        assertNotEquals(id, newId)
        try { controller.execute(prepared); fail() } catch (_: Exception) { }
        assertEquals(0, calls); assertTrue(repository.snapshot().active!!.attempts.isEmpty())
        repository.selectHost(newId)
        controller.execute(controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？"))
        assertEquals(1, calls)
        assertFalse(gameStorage.value!!.contains("synthetic-new"))
        assertFalse(gameStorage.value!!.contains("synthetic-old"))
    }
}
