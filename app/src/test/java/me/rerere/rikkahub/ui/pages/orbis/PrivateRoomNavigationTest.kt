package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PrivateRoomNavigationTest {
    @Test fun onlyNewestForegroundReadCanPublishEvenIfOlderReadFinishesLast() = runBlocking {
        val gate = PrivateRoomRefreshGate(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var snapshot: String? = null
        val older = launch {
            privateRoomRefreshLatest(gate, { started.complete(Unit); release.await(); "ABSENT" },
                { snapshot = it }, { snapshot = null })
        }
        started.await()
        assertTrue(privateRoomRefreshLatest(gate, { "READY" }, { snapshot = it }, { snapshot = null }))
        release.complete(Unit); older.join()
        assertEquals("READY", snapshot)
    }

    @Test fun oldLifetimeFailureCannotClearNewForegroundSnapshot() = runBlocking {
        val gate = PrivateRoomRefreshGate(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var snapshot: String? = "ABSENT"
        var failures = 0
        val older = launch {
            privateRoomRefreshLatest<String>(gate, {
                started.complete(Unit); release.await(); error("synthetic old read failure")
            }, { snapshot = it }, { failures++; snapshot = null })
        }
        started.await()
        gate.invalidate(false)
        assertNull(gate.begin())
        snapshot = null // The UI invalidates the displayed status on ON_STOP.
        gate.invalidate(true)
        assertTrue(privateRoomRefreshLatest(gate, { "READY" }, { snapshot = it }, { snapshot = null }))
        release.complete(Unit); older.join()
        assertEquals("READY", snapshot)
        assertEquals(0, failures)
    }

    @Test fun failedForegroundReadCannotKeepPreviousAbsentAsAConfirmedState() = runBlocking {
        val gate = PrivateRoomRefreshGate(true)
        var snapshot: String? = "ABSENT"
        val published = privateRoomRefreshLatest<String>(gate,
            { error("/synthetic/private/read-failure") }, { snapshot = it }, { snapshot = null })
        assertFalse(published)
        assertNull(snapshot)
    }

    @Test fun cancelledReadDoesNotPublishOrConvertCancellationToOrdinaryFailure() = runBlocking {
        val gate = PrivateRoomRefreshGate(true)
        var calls = 0
        try {
            privateRoomRefreshLatest<String>(gate, { throw CancellationException("synthetic cancellation") },
                { calls++ }, { calls++ })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, calls)
        gate.invalidate(false)
        assertNull(gate.begin())
    }

    @Test fun sameNamedAssistantsRemainDifferentOwnersWithoutMutatingInput() {
        val first = Assistant(name = "同名")
        val second = Assistant(name = "同名")
        val input = listOf(first, second)
        val options = privateRoomOwners(input, first.id.toString(), "旧名字")
        assertEquals(listOf(first.id.toString(), second.id.toString()), options.map { it.id })
        assertEquals(listOf("同名", "同名"), options.map { it.name })
        assertEquals(listOf(first, second), input)
    }

    @Test fun entryOwnerIsKeptWithoutDuplicatingOrChangingItsIdentity() {
        val id = Uuid.random().toString()
        val options = privateRoomOwners(emptyList(), id, "")
        assertEquals(listOf(PrivateRoomOwner(id, "AI")), options)
        assertTrue(privateRoomUsesInitialRepository(id, id))
        assertFalse(privateRoomUsesInitialRepository(Uuid.random().toString(), id))
    }

    @Test fun choosingNewOwnerUsesShortCreationFlowNotConnectionSettings() {
        assertEquals(PrivateRoomScreen.CREATE, privateRoomOwnerDestination(PrivateRoomScreen.NEW_OWNER))
        assertEquals(PrivateRoomScreen.SETTINGS, privateRoomOwnerDestination(PrivateRoomScreen.OWNERS))
        assertFalse(PrivateRoomScreen.entries.any { it.name == "MODEL" })
        assertTrue(privateRoomBusyLabel(PrivateRoomScreen.CREATE).contains("没有调用模型"))
    }

    @Test fun onlyAllowlistedReasonsAppearAndNoExceptionMessageOrCauseIsExposed() {
        listOf("device_key_unavailable", "storage_failed", "unsafe_path", "already_exists",
            "recovery_required", "recovery_not_confirmed", "authentication_failed", "invalid_format", "size_limit")
            .forEach { code ->
                val notice = privateRoomFailureNotice(PrivateVaultException(code))
                assertTrue(notice.contains("（$code）"))
                assertTrue(notice.contains("未自动重试"))
            }
        listOf(
            IllegalStateException("/synthetic/private/code-body", RuntimeException("SYNTHETIC_SECRET")),
            PrivateVaultException("/synthetic/private/recovery-code"),
        ).forEach { error ->
            val notice = privateRoomFailureNotice(error)
            assertTrue(notice.contains("operation_unconfirmed"))
            assertFalse(notice.contains("synthetic"))
            assertFalse(notice.contains("SYNTHETIC_SECRET"))
            assertFalse(notice.contains("recovery-code"))
        }
    }
}
