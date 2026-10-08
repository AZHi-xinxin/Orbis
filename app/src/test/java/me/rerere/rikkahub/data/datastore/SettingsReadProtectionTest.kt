package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

class SettingsReadProtectionTest {
    @get:Rule val files = TemporaryFolder()

    private fun savedSettings() = Settings(readRevision = 7, launchCount = 37,
        titlePrompt = "saved title instruction", providers = emptyList(), assistants = emptyList())

    private class Fixture(val saved: Settings) {
        val protection = SettingsReadProtection()
        var durable = saved
        var published = Settings.dummy()
        var writes = 0
        suspend fun read() = protection.protect(flowOf(durable)).first()
        suspend fun transaction(transform: (Settings) -> Settings): Settings {
            val updated = transform(durable).copy(readRevision = durable.readRevision + 1)
            writes++
            durable = updated
            return updated
        }
        suspend fun update(transform: (Settings) -> Settings) = protection.update(
            transform, ::transaction, { published = it })
    }

    @Test fun firstIoFailureCompletesOnlyWithUninitializedSnapshot() = runTest {
        val protection = SettingsReadProtection()
        val values = protection.protect(flow { throw IOException("read unavailable") }).toList()
        assertEquals(1, values.size)
        assertTrue(values.single().init)
        assertRejected(protection, values.single().copy(init = false))
    }

    @Test fun readFailureRetainsExactLastSuccessWithoutWritableDefaults() = runTest {
        val fixture = Fixture(savedSettings())
        val values = fixture.protection.protect(flow {
            emit(fixture.saved)
            throw IOException("read unavailable")
        }).toList()
        assertEquals(listOf(fixture.saved, fixture.saved.copy(init = true)), values)
        assertRejected(fixture.protection, fixture.saved)
    }

    @Test fun sharedStateKeepsReadOnlyMarkerAfterUpstreamCompletes() = runTest {
        val fixture = Fixture(savedSettings())
        val state = fixture.protection.protect(flow {
            emit(fixture.saved)
            throw IOException("read unavailable")
        }).stateIn(backgroundScope, SharingStarted.Eagerly, Settings.dummy())
        runCurrent()
        assertEquals(fixture.saved.copy(init = true), state.value)
        assertRejected(fixture.protection, state.value)
    }

    @Test fun genuineEmptyReadIsWritableAndPersistencePrecedesPublication() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        val events = mutableListOf<String>()
        fixture.protection.update(
            transform = { it.copy(launchCount = it.launchCount + 1) },
            transaction = {
                assertTrue(fixture.published.init)
                fixture.transaction(it).also { events += "persist" }
            },
            publish = { fixture.published = it; events += "publish" },
        )
        assertEquals(listOf("persist", "publish"), events)
        assertEquals(fixture.saved.copy(launchCount = 38, readRevision = 8), fixture.published)
    }

    @Test fun updatesAreRejectedBeforeAnySuccessfulRead() = runTest {
        assertRejected(SettingsReadProtection(), savedSettings())
    }

    @Test fun rawFirstBeforeUiPublicationCanSaveLaunchCount() = runTest {
        val fixture = Fixture(savedSettings())
        val first = fixture.read()
        assertTrue(fixture.published.init)
        assertTrue(fixture.update {
            SettingsStore.replaceFreshSnapshot(it, first.copy(launchCount = first.launchCount + 1))
        })
        assertEquals(fixture.saved.copy(launchCount = 38, readRevision = 8), fixture.published)
    }

    @Test fun fallbackCandidateIsRejectedInsideTransactionWithoutWriting() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        try {
            fixture.update { it.copy(init = true) }
            fail("fallback candidate must not persist")
        } catch (_: IllegalStateException) { }
        assertEquals(0, fixture.writes)
        assertEquals(fixture.saved, fixture.durable)
    }

    @Test fun laterCollectorCannotUnlockFailedInstanceEvenWithNewerVersion() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        fixture.protection.protect(flow<Settings> { throw IOException("read unavailable") }).first()
        val later = fixture.protection.protect(flowOf(fixture.saved.copy(readRevision = 100, launchCount = 999))).first()
        assertEquals(fixture.saved.copy(init = true), later)
        assertRejected(fixture.protection, later.copy(init = false))
        assertEquals(fixture.saved, SettingsReadProtection().protect(flowOf(fixture.saved)).first())
    }

    @Test fun launchCountCopyOfFailedReadCannotPersist() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        val failed = fixture.protection.protect(flow<Settings> { throw IOException("read unavailable") }).first()
        assertRejected(fixture.protection, failed.copy(launchCount = failed.launchCount + 1))
    }

    @Test fun writeIoFailurePublishesOnlyLastSuccessAndLatchesReadOnly() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        try {
            fixture.protection.update(
                transform = { it.copy(launchCount = 999) },
                transaction = { it(fixture.durable); throw IOException("write unavailable") },
                publish = { fixture.published = it },
            )
            fail("failed write must be reported")
        } catch (error: IOException) { assertEquals("write unavailable", error.message) }
        assertEquals(fixture.saved.copy(init = true), fixture.published)
        assertEquals(fixture.saved, fixture.durable)
        assertRejected(fixture.protection, fixture.saved)
    }

    @Test fun queuedUpdateRechecksFailureGateAfterPreviousWrite() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writing = async {
            fixture.protection.update(
                transform = { it.copy(launchCount = it.launchCount + 1) },
                transaction = { entered.complete(Unit); release.await(); fixture.transaction(it) },
                publish = { fixture.published = it },
            )
        }
        entered.await()
        val failing = async {
            fixture.protection.protect(flow<Settings> { throw IOException("read unavailable") }).first()
        }
        runCurrent()
        val queued = async { assertRejected(fixture.protection, fixture.saved) }
        runCurrent()
        assertFalse(failing.isCompleted)
        assertFalse(queued.isCompleted)
        release.complete(Unit)
        writing.await()
        assertEquals(fixture.durable.copy(init = true), failing.await())
        queued.await()
        assertEquals(1, fixture.writes)
    }

    @Test fun concurrentTransformsUseLatestPersistedValue() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            fixture.protection.update(
                transform = { it.copy(launchCount = it.launchCount + 1) },
                transaction = { entered.complete(Unit); release.await(); fixture.transaction(it) },
                publish = { fixture.published = it },
            )
        }
        entered.await()
        val second = async { fixture.update { it.copy(launchCount = it.launchCount + 1) } }
        runCurrent()
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(39, fixture.durable.launchCount)
        assertEquals(9L, fixture.durable.readRevision)
    }

    @Test fun nonIoFailuresAndCancellationAreNotConvertedToDefaults() = runTest {
        val protection = SettingsReadProtection()
        try {
            protection.protect(flow<Settings> { throw IllegalArgumentException("invalid source") }).toList()
            fail("must propagate")
        } catch (error: IllegalArgumentException) { assertEquals("invalid source", error.message) }
        try {
            protection.protect(flow<Settings> { throw CancellationException("cancel collection") }).toList()
            fail("must cancel")
        } catch (error: CancellationException) { assertEquals("cancel collection", error.message) }
    }

    @Test fun oldReadVersionCannotReplaceNewerCacheOrFailureFallback() = runTest {
        val fixture = Fixture(savedSettings())
        fixture.read()
        fixture.update { it.copy(titlePrompt = "latest") }
        val delayed = fixture.protection.protect(flowOf(fixture.saved)).first()
        assertEquals(fixture.durable, delayed)
        val failed = fixture.protection.protect(flow<Settings> { throw IOException("read unavailable") }).first()
        assertEquals(fixture.durable.copy(init = true), failed)
    }

    @Test fun localReadVersionIsNotSerializedIntoExportedSettings() {
        val encoded = JsonInstant.encodeToString(Settings.serializer(), savedSettings())
        assertFalse(encoded.contains("readRevision"))
        assertEquals(0L, JsonInstant.decodeFromString(Settings.serializer(), encoded).readRevision)
    }

    @Test fun realDataStoreOldFeedbackBehindSecondWriteCannotRollBackThirdWrite() = runTest(timeout = 30.seconds) {
        withDataStore { store ->
            val protection = SettingsReadProtection()
            val initial = SettingsStore.readSettings(store.data.first())
            protection.protect(flowOf(initial)).first()
            val ui = MutableStateFlow(initial)
            val firstSaved = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val oldEmissionCaptured = CompletableDeferred<Unit>()
            val releaseOldEmission = CompletableDeferred<Unit>()
            val initialFeedback = CompletableDeferred<Unit>()
            val feedback = async {
                protection.protect(store.data.map { SettingsStore.readSettings(it) }.onEach {
                    if (it.readRevision == initial.readRevision + 1) {
                        oldEmissionCaptured.complete(Unit)
                        releaseOldEmission.await()
                    }
                }).first {
                    if (it.readRevision == initial.readRevision) initialFeedback.complete(Unit)
                    it.readRevision >= initial.readRevision + 2
                }
            }
            initialFeedback.await()
            val first = async {
                protection.update(
                    transform = { it.copy(launchCount = it.launchCount + 1) },
                    transaction = {
                        SettingsStore.updatePersistedSettings(store, it).also {
                            firstSaved.complete(Unit)
                            releaseFirstWrite.await()
                        }
                    }, publish = { ui.value = it },
                )
            }
            firstSaved.await()
            oldEmissionCaptured.await()
            val second = async {
                protection.update({ it.copy(titlePrompt = "second write") },
                    { SettingsStore.updatePersistedSettings(store, it) }, { ui.value = it })
            }
            runCurrent() // write 2 waits first; the old source emission then waits behind it.
            releaseOldEmission.complete(Unit)
            runCurrent()
            releaseFirstWrite.complete(Unit)
            first.await(); second.await()
            val observed = feedback.await()
            assertEquals(initial.readRevision + 2, observed.readRevision)
            assertEquals("second write", observed.titlePrompt)
            protection.update({ it.copy(launchCount = it.launchCount + 1) },
                { SettingsStore.updatePersistedSettings(store, it) }, { ui.value = it })
            val persisted = SettingsStore.readSettings(store.data.first())
            assertEquals(initial.launchCount + 2, persisted.launchCount)
            assertEquals("second write", persisted.titlePrompt)
            assertEquals(initial.readRevision + 3, persisted.readRevision)
            assertSamePersistedSettings(persisted, ui.value)
        }
    }

    @Test fun realNarrowEditIsPreservedByFullTransformBeforeItsEmissionArrives() = runTest(timeout = 30.seconds) {
        withDataStore { store ->
            val protection = SettingsReadProtection()
            val stale = protection.protect(store.data.map { SettingsStore.readSettings(it) }).first()
            SettingsStore.editPersistedSettings(store) { it[SettingsStore.TITLE_PROMPT] = "narrow changed" }
            var latest: Settings? = null
            protection.update({ it.copy(launchCount = it.launchCount + 1) },
                { SettingsStore.updatePersistedSettings(store, it) }, { latest = it })
            assertEquals("narrow changed", latest!!.titlePrompt)
            assertEquals(stale.launchCount + 1, latest!!.launchCount)
            assertEquals(stale.readRevision + 2, latest!!.readRevision)
            assertSamePersistedSettings(checkNotNull(latest), SettingsStore.readSettings(store.data.first()))
        }
    }

    @Test fun realStaleWholeSnapshotIsQuietlyRejectedWithoutOverwritingNarrowEdit() = runTest(timeout = 30.seconds) {
        withDataStore { store ->
            val protection = SettingsReadProtection()
            val stale = protection.protect(store.data.map { SettingsStore.readSettings(it) }).first()
            SettingsStore.editPersistedSettings(store) { it[SettingsStore.TITLE_PROMPT] = "narrow changed" }
            val accepted = protection.update(
                { SettingsStore.replaceFreshSnapshot(it, stale.copy(launchCount = 999)) },
                { SettingsStore.updatePersistedSettings(store, it) }, { fail("must not publish") })
            assertFalse(accepted)
            val persisted = SettingsStore.readSettings(store.data.first())
            assertEquals("narrow changed", persisted.titlePrompt)
            assertEquals(stale.launchCount, persisted.launchCount)
            assertEquals(stale.readRevision + 1, persisted.readRevision)
            // A stale page is not an IO error: a later explicit transform remains usable.
            assertTrue(protection.update({ it.copy(launchCount = it.launchCount + 1) },
                { SettingsStore.updatePersistedSettings(store, it) }, {}))
        }
    }

    @Test fun realNarrowEditQueuedDuringFullTransactionKeepsBothChanges() = runTest(timeout = 30.seconds) {
        withDataStore { store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initial = SettingsStore.readSettings(store.data.first())
            val writing = async {
                SettingsStore.updatePersistedSettings(store, { it.copy(launchCount = it.launchCount + 1) }) { _, _ ->
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val narrow = async {
                SettingsStore.editPersistedSettings(store) { it[SettingsStore.TITLE_PROMPT] = "narrow changed" }
            }
            runCurrent()
            release.complete(Unit)
            writing.await(); narrow.await()
            val persisted = SettingsStore.readSettings(store.data.first())
            assertEquals(initial.launchCount + 1, persisted.launchCount)
            assertEquals("narrow changed", persisted.titlePrompt)
            assertEquals(initial.readRevision + 2, persisted.readRevision)
        }
    }

    private fun assertSamePersistedSettings(expected: Settings, actual: Settings) {
        // BingLocalOptions is not a data class: separate decodes have distinct object identity.
        // Compare every serialized field and the two transient source-state fields separately.
        assertEquals(expected.init, actual.init)
        assertEquals(expected.readRevision, actual.readRevision)
        assertEquals(JsonInstant.encodeToString(Settings.serializer(), expected),
            JsonInstant.encodeToString(Settings.serializer(), actual))
    }

    private suspend fun withDataStore(block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val file = files.newFolder().resolve("test.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file })
        try {
            SettingsStore.editPersistedSettings(store) {
                it[SettingsStore.LAUNCH_COUNT] = 37
                it[SettingsStore.TITLE_PROMPT] = "saved title instruction"
            }
            block(store)
        } finally { job.cancelAndJoin() }
    }

    private suspend fun assertRejected(protection: SettingsReadProtection, candidate: Settings) {
        var entered = false
        try {
            protection.update(transform = { candidate }, transaction = {
                entered = true
                it(candidate)
            }, publish = { fail("untrusted value must not publish") })
            fail("untrusted source must be rejected")
        } catch (error: IllegalStateException) {
            assertEquals("settings_not_ready_or_read_failed", error.message)
        }
        assertFalse(entered)
    }
}
