package me.rerere.rikkahub.data.orbis

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrbisCloudSettingsStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val previous = OrbisCloudHomeConfig(false, "https://example.com/old")
    @After fun close() { scope.cancel() }

    private class Storage : OrbisCloudSettingsPersistence {
        var stored: String? = null
        var bootstrap: String? = null
        var readFailure = false
        var writeFailure = false
        var bootstrapReads = 0
        var writes = 0
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun read(): String? {
            if (readFailure) throw IOException("synthetic private detail")
            return stored
        }
        override suspend fun write(value: String) {
            beforeWrite()
            if (writeFailure) throw IOException("synthetic private detail")
            stored = value
            writes++
        }
        override suspend fun readBootstrap(): String? { bootstrapReads++; return bootstrap }
    }

    @Test fun absentConfigurationLoadsOfflineWithoutWriting() {
        val storage = Storage()
        val store = OrbisCloudSettingsStore(storage, scope)
        assertTrue(store.state.value.loaded)
        assertEquals(OrbisCloudHomeConfig(), store.state.value.config)
        assertTrue(store.state.value.canEdit)
        assertNull(store.state.value.error)
        assertEquals(0, storage.writes)
    }

    @Test fun bootstrapImportsOnlyDisabledSuggestionAndPersistsIt() {
        val storage = Storage().apply { bootstrap = """{"homeUrl":"https://EXAMPLE.com:443/orbis"}""" }
        val store = OrbisCloudSettingsStore(storage, scope)
        assertEquals(OrbisCloudHomeConfig(false, "https://example.com/orbis"), store.state.value.config)
        assertEquals(store.state.value.config, Json.decodeFromString<OrbisCloudHomeConfig>(storage.stored!!))
        assertEquals(1, storage.writes)
    }

    @Test fun savedConfigurationWinsAndBootstrapIsNeverRead() {
        val storage = Storage().apply {
            stored = Json.encodeToString(previous.copy(enabled = true))
            bootstrap = """{"homeUrl":"https://other.example.com/"}"""
        }
        val store = OrbisCloudSettingsStore(storage, scope)
        assertEquals(previous.copy(enabled = true), store.state.value.config)
        assertEquals(0, storage.bootstrapReads)
        assertEquals(0, storage.writes)
    }

    @Test fun explicitlySavedEmptyOfflineConfigurationAlsoSuppressesBootstrap() {
        val storage = Storage().apply { stored = "{}"; bootstrap = """{"homeUrl":"https://example.com/"}""" }
        val store = OrbisCloudSettingsStore(storage, scope)
        assertEquals(OrbisCloudHomeConfig(), store.state.value.config)
        assertEquals(0, storage.bootstrapReads)
    }

    @Test fun bootstrapRejectsExtraCredentialEnabledAndDuplicateFields() {
        listOf("""{"homeUrl":"https://example.com/","enabled":true}""",
            """{"homeUrl":"https://example.com/","token":"synthetic"}""",
            """{"homeUrl":"https://example.com/","homeUrl":"https://other.example.com/"}""",
            """{"homeUrl":"https://localhost/"}""", "[]", "{", " ".repeat(4097)).forEach { invalid ->
            val storage = Storage().apply { bootstrap = invalid }
            val store = OrbisCloudSettingsStore(storage, scope)
            assertTrue(store.state.value.loaded)
            assertFalse(store.state.value.config.enabled)
            assertNotNull(store.state.value.error)
            assertTrue(store.state.value.canEdit)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun malformedBootstrapStillAllowsExplicitManualSetup() = runBlocking {
        val storage = Storage().apply { bootstrap = "{" }
        val store = OrbisCloudSettingsStore(storage, scope)
        store.save(previous)
        assertEquals(previous, store.state.value.config)
        assertNull(store.state.value.error)
    }

    @Test fun failedReadIsOfflineAndCannotOverwriteUnknownExistingConfiguration() = runBlocking {
        val original = Json.encodeToString(previous.copy(enabled = true))
        val storage = Storage().apply { stored = original; readFailure = true }
        val store = OrbisCloudSettingsStore(storage, scope)
        assertTrue(store.state.value.loaded)
        assertFalse(store.state.value.config.enabled)
        assertFalse(store.state.value.canEdit)
        assertNotNull(store.state.value.error)
        assertFalse(store.state.value.error!!.contains("synthetic"))
        try { store.save(previous); fail("must block") } catch (_: IllegalStateException) { }
        assertEquals(original, storage.stored)
        assertEquals(0, storage.bootstrapReads)
        assertEquals(0, storage.writes)
    }

    @Test fun invalidSavedConfigurationIsPreservedAndNeverReplacedByBootstrap() = runBlocking {
        val storage = Storage().apply {
            stored = """{"enabled":true,"homeUrl":"http://localhost/"}"""
            bootstrap = """{"homeUrl":"https://example.com/"}"""
        }
        val original = storage.stored
        val store = OrbisCloudSettingsStore(storage, scope)
        assertFalse(store.state.value.config.enabled)
        assertFalse(store.state.value.canEdit)
        try { store.save(previous); fail("must block") } catch (_: IllegalStateException) { }
        assertEquals(original, storage.stored)
        assertEquals(0, storage.bootstrapReads)
    }

    @Test fun reloadCanRecoverAfterReadFailureWithoutWriting() = runBlocking {
        val storage = Storage().apply { stored = Json.encodeToString(previous); readFailure = true }
        val store = OrbisCloudSettingsStore(storage, scope)
        storage.readFailure = false
        store.reload()
        assertEquals(previous, store.state.value.config)
        assertNull(store.state.value.error)
        assertEquals(0, storage.writes)
    }

    @Test fun failedSaveNeverPublishesUncommittedEnabledConfiguration() = runBlocking {
        val storage = Storage().apply { stored = Json.encodeToString(previous); writeFailure = true }
        val store = OrbisCloudSettingsStore(storage, scope)
        val before = store.state.value
        try { store.save(previous.copy(enabled = true)); fail("must fail") } catch (error: IOException) {
            assertFalse(error.message!!.contains("synthetic"))
        }
        assertEquals(before, store.state.value)
        assertEquals(previous, Json.decodeFromString<OrbisCloudHomeConfig>(storage.stored!!))
    }

    @Test fun pendingSaveIsInvisibleUntilDurableCommitAndEditsAreSerialized() = runBlocking {
        val storage = Storage().apply { stored = Json.encodeToString(previous) }
        val store = OrbisCloudSettingsStore(storage, scope)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        storage.beforeWrite = { started.complete(Unit); finish.await() }
        val save = async(start = CoroutineStart.UNDISPATCHED) { store.save(previous.copy(enabled = true)) }
        started.await()
        assertEquals(previous, store.state.value.config)
        val disable = async(start = CoroutineStart.UNDISPATCHED) { store.save(previous) }
        assertFalse(disable.isCompleted)
        finish.complete(Unit)
        save.await()
        disable.await()
        assertEquals(previous, store.state.value.config)
        assertEquals(previous, Json.decodeFromString<OrbisCloudHomeConfig>(storage.stored!!))
        assertEquals(2, storage.writes)
    }

    @Test fun invalidUserInputIsRejectedWithoutStorageOrStateChange() = runBlocking {
        val storage = Storage().apply { stored = Json.encodeToString(previous) }
        val store = OrbisCloudSettingsStore(storage, scope)
        try { store.save(OrbisCloudHomeConfig(true, "")); fail("must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(previous, store.state.value.config)
        assertEquals(0, storage.writes)
    }

    @Test fun disablingKeepsAddressAndNoOtherStorageKeysAreInvolved() = runBlocking {
        val storage = Storage().apply { stored = Json.encodeToString(previous.copy(enabled = true)) }
        val store = OrbisCloudSettingsStore(storage, scope)
        store.save(previous)
        assertEquals(previous, store.state.value.config)
        val saved = storage.stored!!
        assertFalse(saved.contains("providers"))
        assertFalse(saved.contains("assistants"))
        assertFalse(saved.contains("apiKey"))
    }

    @Test fun separateStoreInstancesDoNotShareConfiguration() = runBlocking {
        val first = OrbisCloudSettingsStore(Storage(), scope)
        val second = OrbisCloudSettingsStore(Storage(), scope)
        first.save(previous)
        assertEquals(OrbisCloudHomeConfig(), second.state.value.config)
    }

    @Test fun oldEnabledConfigRemainsRemoteAndNewNamesDoNotTriggerAMigrationWrite() {
        val persistence = Storage().apply { stored = """{"enabled":true,"homeUrl":"https://example.com/"}""" }
        val store = OrbisCloudSettingsStore(persistence, scope)
        assertTrue(store.state.value.config.enabled)
        assertEquals("我", store.state.value.config.humanName)
        assertEquals(0, persistence.writes)
    }

    @Test fun savingNamesKeepsExistingRouteAndAddressWithoutTouchingAssistants() = runBlocking {
        val persistence = Storage().apply { stored = Json.encodeToString(previous.copy(enabled = true)) }
        val store = OrbisCloudSettingsStore(persistence, scope)
        val next = store.state.value.config.copy(gardenName = "自己的花园", humanName = "A", companionName = "B")
        store.save(next)
        assertTrue(store.state.value.config.enabled); assertEquals(previous.homeUrl, store.state.value.config.homeUrl)
        assertEquals(next, OrbisCloudSettingsStore(persistence, scope).state.value.config)
        assertFalse(persistence.stored!!.contains("assistants"))
    }

    @Test fun localToolGuardRejectsRemoteAndUnreadConfigWithoutExecuting() = runBlocking {
        var calls = 0
        listOf(Storage().apply { stored = Json.encodeToString(previous.copy(enabled = true)) },
            Storage().apply { readFailure = true }).forEach { persistence ->
            val store = OrbisCloudSettingsStore(persistence, scope)
            try { store.withLocalGarden { calls++ }; fail("must reject") } catch (_: IllegalStateException) { }
        }
        assertEquals(0, calls)
        OrbisCloudSettingsStore(Storage(), scope).withLocalGarden { calls++ }
        assertEquals(1, calls)
    }

    @Test fun modeSwitchWaitsForLocalInvocationAndInvalidNamesNeverWrite() = runBlocking {
        val persistence = Storage()
        val store = OrbisCloudSettingsStore(persistence, scope)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val local = async(start = CoroutineStart.UNDISPATCHED) { store.withLocalGarden { started.complete(Unit); finish.await() } }
        started.await()
        val remote = async(start = CoroutineStart.UNDISPATCHED) { store.save(previous.copy(enabled = true)) }
        assertFalse(remote.isCompleted); finish.complete(Unit); local.await(); remote.await()
        assertTrue(store.state.value.config.enabled)
        val writes = persistence.writes
        try { store.save(previous.copy(humanName = "x\ny")); fail("invalid name") } catch (_: IllegalArgumentException) { }
        assertEquals(writes, persistence.writes)
    }
}
