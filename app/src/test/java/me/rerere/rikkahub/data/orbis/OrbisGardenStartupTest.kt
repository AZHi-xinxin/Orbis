package me.rerere.rikkahub.data.orbis

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import org.junit.Assert.*
import org.junit.Test

/** No Android storage or real garden records: deterministic first-use/init ordering. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrbisGardenStartupTest {
    private class Storage(config: OrbisCloudHomeConfig) : OrbisCloudSettingsPersistence {
        var stored = Json.encodeToString(config)
        var reads = 0
        var writes = 0
        var bootstrapReads = 0
        var failRead = false
        var beforeRead: suspend () -> Unit = {}
        override suspend fun read(): String {
            reads++
            beforeRead()
            if (failRead) throw IOException("synthetic private path and credential")
            return stored
        }
        override suspend fun write(value: String) { writes++; stored = value }
        override suspend fun readBootstrap(): String? { bootstrapReads++; return null }
    }

    private val local = OrbisCloudHomeConfig(companionName = "合成伙伴")

    @Test fun firstToolBeforeInitCoroutineLoadsSavedLocalRouteExactlyOnce() = runTest {
        val persistence = Storage(local)
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        assertFalse(store.state.value.loaded)
        val author = store.withLocalGarden { it.companionName }
        assertEquals("合成伙伴", author)
        runCurrent() // Late scheduled initialization must not reread/reset an already loaded route.
        assertTrue(store.state.value.loaded)
        assertEquals(1, persistence.reads)
        assertEquals(0, persistence.writes)
    }

    @Test fun toolWaitsForExistingInitialReadAndNeverUsesPlaceholderNames() = runTest {
        val gate = CompletableDeferred<Unit>()
        val persistence = Storage(local).apply { beforeRead = { gate.await() } }
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        runCurrent()
        assertEquals(1, persistence.reads)
        var executions = 0
        val invocation = async(start = CoroutineStart.UNDISPATCHED) {
            store.withLocalGarden { executions++; it.companionName }
        }
        assertFalse(invocation.isCompleted)
        assertEquals(0, executions)
        gate.complete(Unit)
        assertEquals("合成伙伴", invocation.await())
        assertEquals(1, executions)
        assertEquals(1, persistence.reads)
        assertEquals(0, persistence.writes)
    }

    @Test fun savedRemoteRouteStillRejectsFirstToolWithoutSwitchingOrExecuting() = runTest {
        val persistence = Storage(local.copy(enabled = true, homeUrl = "https://example.com/garden"))
        val original = persistence.stored
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        var executions = 0
        try {
            store.withLocalGarden { executions++ }
            fail("remote route must reject")
        } catch (error: IllegalStateException) {
            assertEquals("garden_local_mode_required", error.message)
        }
        runCurrent()
        assertEquals(0, executions)
        assertTrue(store.state.value.config.enabled)
        assertEquals(original, persistence.stored)
        assertEquals(0, persistence.writes)
        assertEquals(1, persistence.reads)
    }

    @Test fun failedInitialReadHasDistinctReasonPreservesOriginalAndNeedsExplicitReload() = runTest {
        val persistence = Storage(local).apply { failRead = true }
        val original = persistence.stored
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        var executions = 0
        repeat(2) {
            try {
                store.withLocalGarden { executions++ }
                fail("unread configuration must reject")
            } catch (error: IllegalStateException) {
                assertEquals("garden_configuration_unavailable", error.message)
            }
        }
        runCurrent()
        assertEquals(0, executions)
        assertEquals(1, persistence.reads)
        assertEquals(0, persistence.bootstrapReads)
        assertEquals(0, persistence.writes)
        assertEquals(original, persistence.stored)
        assertFalse(store.state.value.canEdit)
        persistence.failRead = false
        store.reload()
        assertEquals("合成伙伴", store.withLocalGarden { it.companionName })
        assertEquals(2, persistence.reads)
        assertEquals(0, persistence.writes)
    }

    @Test fun cancelledWaiterCannotWriteAfterInitialReadCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val persistence = Storage(local).apply { beforeRead = { gate.await() } }
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        runCurrent()
        var executions = 0
        val invocation = async(start = CoroutineStart.UNDISPATCHED) {
            store.withLocalGarden { executions++ }
        }
        invocation.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, executions)
        assertTrue(store.state.value.loaded)
        assertEquals(0, persistence.writes)
    }

    @Test fun cancellationDuringFirstReadPropagatesWithoutInventingLocalSuccess() = runTest {
        val persistence = Storage(local).apply { beforeRead = { throw CancellationException("synthetic") } }
        val store = OrbisCloudSettingsStore(persistence, backgroundScope)
        var executions = 0
        try {
            store.withLocalGarden { executions++ }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(store.state.value.loaded)
        assertEquals(0, executions)
        assertEquals(0, persistence.writes)
    }
}
