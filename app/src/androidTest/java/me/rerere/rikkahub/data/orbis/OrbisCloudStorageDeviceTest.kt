package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android/ICU regression fixture. Uses only in-memory JSON; no file, WebView or network access. */
@RunWith(AndroidJUnit4::class)
class OrbisCloudStorageDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val home = "https://golden-example-6db696.example.org/"

    @Before fun requireIsolatedApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @After fun closeScope() { scope.cancel() }

    private class MemoryPersistence : OrbisCloudSettingsPersistence {
        var stored: String? = null
        var bootstrap: String? = null
        var writes = 0
        var bootstrapReads = 0
        var failWrites = false
        override suspend fun read(): String? = stored
        override suspend fun write(value: String) {
            if (failWrites) throw IOException("synthetic failure")
            stored = value
            writes++
        }
        override suspend fun readBootstrap(): String? {
            bootstrapReads++
            return bootstrap
        }
    }

    @Test fun recordOriginalPatternCompilationWithoutAssumingDeviceBehavior() {
        val result = runCatching {
            Regex("""\s*\{\s*"homeUrl"\s*:\s*"(?:[^"\\]|\\.)*"\s*}\s*""")
        }
        Log.i("OrbisCloudStorageTest", "oldPatternCompiles=${result.isSuccess}; " +
            "exceptionClass=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"}")
    }

    @Test fun bootstrapParsesOnAndroidAndPersistsOnlyDisabledSuggestion() {
        val persistence = MemoryPersistence().apply {
            bootstrap = """{"homeUrl":"https://GOLDEN-EXAMPLE-6db696.example.org:443/"}"""
        }
        val store = OrbisCloudSettingsStore(persistence, scope)
        assertTrue(store.state.value.loaded)
        assertTrue(store.state.value.canEdit)
        assertNull(store.state.value.error)
        assertEquals(OrbisCloudHomeConfig(false, home), store.state.value.config)
        assertEquals(store.state.value.config,
            Json.decodeFromString<OrbisCloudHomeConfig>(persistence.stored!!))
        assertEquals(1, persistence.bootstrapReads)
        assertEquals(1, persistence.writes)
    }

    @Test fun formattedBootstrapAndEscapedJsonSlashesStayDisabled() {
        val persistence = MemoryPersistence().apply {
            bootstrap = "\r\n {\n  \"homeUrl\" : \"https:\\/\\/golden-example-6db696.example.org\\/\"\n } \r\n"
        }
        val store = OrbisCloudSettingsStore(persistence, scope)
        assertNull(store.state.value.error)
        assertEquals(OrbisCloudHomeConfig(false, home), store.state.value.config)
        assertEquals(1, persistence.writes)
    }

    @Test fun manualSavePersistsAndOnlyExplicitEnabledSaveConnects() = runBlocking {
        val persistence = MemoryPersistence()
        val store = OrbisCloudSettingsStore(persistence, scope)
        assertEquals(0, persistence.writes)
        store.save(OrbisCloudHomeConfig(false, home))
        assertFalse(store.state.value.config.enabled)
        assertEquals(OrbisCloudHomeConfig(false, home),
            Json.decodeFromString<OrbisCloudHomeConfig>(persistence.stored!!))
        store.save(OrbisCloudHomeConfig(true, home))
        assertEquals(OrbisCloudHomeConfig(true, home), store.state.value.config)
        assertEquals(store.state.value.config,
            Json.decodeFromString<OrbisCloudHomeConfig>(persistence.stored!!))
        persistence.bootstrap = """{"homeUrl":"https://other.example.org/"}"""
        val readsBeforeReload = persistence.bootstrapReads
        val restored = OrbisCloudSettingsStore(persistence, scope)
        assertEquals(OrbisCloudHomeConfig(true, home), restored.state.value.config)
        assertEquals(readsBeforeReload, persistence.bootstrapReads)
        assertEquals(2, persistence.writes)
    }

    @Test fun invalidBootstrapKeepsStrictKeyAndAddressValidation() {
        listOf(
            """{"homeUrl":"$home","enabled":true}""",
            """{"homeUrl":"$home","token":"synthetic"}""",
            """{"homeUrl":"$home","homeUrl":"https://other.example.org/"}""",
            """{"homeUrl":"http://golden-example-6db696.example.org/"}""",
            """{"homeUrl":"https://127.0.0.1/"}""",
            "{\"homeUrl\":\"$home\"",
            """{"homeUrl":"$home"}}""",
            """{"homeUrl":"$home",}""",
            """{"homeUrl":"$home"} trailing""",
            """{"homeUrl":true}""",
        ).forEach { bootstrap ->
            val persistence = MemoryPersistence().apply { this.bootstrap = bootstrap }
            val store = OrbisCloudSettingsStore(persistence, scope)
            assertTrue(store.state.value.loaded)
            assertTrue(store.state.value.canEdit)
            assertEquals(OrbisCloudHomeConfig(), store.state.value.config)
            assertNotNull(store.state.value.error)
            assertEquals(0, persistence.writes)
        }
    }

    @Test fun failedBootstrapImportRemainsRecoverableByManualSave() = runBlocking {
        val persistence = MemoryPersistence().apply { bootstrap = "{" }
        val store = OrbisCloudSettingsStore(persistence, scope)
        assertNotNull(store.state.value.error)
        store.save(OrbisCloudHomeConfig(false, home))
        assertNull(store.state.value.error)
        assertEquals(OrbisCloudHomeConfig(false, home), store.state.value.config)
        assertEquals(1, persistence.writes)
    }

    @Test fun failedEnabledSaveKeepsPreviousDisabledStateAndStoredJson() = runBlocking {
        val persistence = MemoryPersistence().apply { bootstrap = """{"homeUrl":"$home"}""" }
        val store = OrbisCloudSettingsStore(persistence, scope)
        val previous = store.state.value
        val previousJson = persistence.stored
        persistence.failWrites = true
        try {
            store.save(OrbisCloudHomeConfig(true, home))
            fail("An unsuccessful write must not enable the homepage")
        } catch (_: IOException) { }
        assertEquals(previous, store.state.value)
        assertEquals(previousJson, persistence.stored)
        assertEquals(1, persistence.writes)
    }
}
