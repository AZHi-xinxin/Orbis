package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.orbis.FreshHumanRecoveryStatus
import me.rerere.rikkahub.data.orbis.GatewayRecoveryScopeStore
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisEventInbox
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import org.junit.Assert.*
import org.junit.Test

class FutureAutomaticWakeRecoveryPolicyTest {
    private val conversation = "11111111-1111-4111-8111-111111111111"
    private val assistant = "22222222-2222-4222-8222-222222222222"

    @Test fun `future only reset refuses a live controller without cancelling its work`() = runTest {
        val control = InterruptibleQueueControl<String>()
        val finish = CompletableDeferred<Unit>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            control.run(conversation) { finish.await() }
        }
        assertFalse(control.beginLocalResetIfIdle(conversation))
        assertTrue(worker.isActive)
        assertFalse(worker.isCancelled)
        finish.complete(Unit)
        worker.join()
        assertTrue(control.beginLocalResetIfIdle(conversation))
        assertFalse(control.beginLocalResetIfIdle(conversation))
        control.endLocalReset(conversation)
        assertTrue(control.beginLocalResetIfIdle(conversation))
        control.endLocalReset(conversation)
    }

    @Test fun `only explicit acknowledgement of detached historical tool uncertainty is allowed`() {
        for (fresh in FreshHumanRecoveryStatus.entries) {
            for (hold in listOf(null, "unknown_tool_result", "legacy_unknown_tool_result",
                    "gateway_terminal_unconfirmed", "event_receipt_not_saved", "other_failure")) {
                assertEquals("$fresh $hold",
                    fresh in setOf(FreshHumanRecoveryStatus.NONE, FreshHumanRecoveryStatus.DETACHED) &&
                        hold in setOf(null, "unknown_tool_result", "legacy_unknown_tool_result"),
                    mayRecoverFutureAutomaticWakes(fresh, hold, true, true, false))
            }
        }
    }

    @Test fun `jobs checkpoint pending tools calls and transport uncertainty remain blocking`() {
        for (ready in listOf(false, true)) for (transportClear in listOf(false, true))
            for (call in listOf(false, true)) {
                assertEquals(ready && transportClear && !call,
                    mayRecoverFutureAutomaticWakes(FreshHumanRecoveryStatus.DETACHED,
                        "unknown_tool_result", ready, transportClear, call))
            }
    }

    private class Transaction {
        var owner = true
        var guard = false
        var held = false
        var suppressed = false
        var durable = true
        var fresh = true
        var automatic = true
        var failAt: String? = null
        var failAfterAt: String? = null
        var changeOwnerAtVerification = false
        val calls = mutableListOf<String>()
        private fun step(name: String, action: () -> Unit) {
            calls += name
            if (failAt == name) error("synthetic_write_failure")
            action()
            if (failAfterAt == name) error("synthetic_read_back_failure")
        }
        fun commit(): Boolean = commitFutureAutomaticWakeRecovery(
            stillOwner = { owner },
            establishGuard = { step("guard") { guard = true } },
            preservePreviousInputs = { step("hold") { held = true } },
            suppressPreviousEvents = { step("suppress") { suppressed = true } },
            verifyPreviousEvents = {
                calls += "verify"
                if (changeOwnerAtVerification) owner = false
                suppressed && durable
            },
            clearFreshRestriction = { step("fresh") { fresh = false } },
            acknowledgeAutomaticHold = { step("automatic") { automatic = false } },
            releaseGuard = { step("release") { guard = false } },
        )
    }

    @Test fun `success suppresses previous events before any release with guard last`() {
        val tx = Transaction()
        assertTrue(tx.commit())
        assertEquals(listOf("guard", "hold", "suppress", "verify", "fresh", "automatic", "release"), tx.calls)
        assertTrue(tx.held)
        assertTrue(tx.suppressed)
        assertFalse(tx.fresh)
        assertFalse(tx.automatic)
        assertFalse(tx.guard)
        // There is deliberately no model, tool, stop, resume-human-queue or dispatch callback.
    }

    @Test fun `every failure after guard establishment keeps future wakes blocked`() {
        for (step in listOf("hold", "suppress", "fresh", "automatic", "release")) {
            val tx = Transaction().also { it.failAt = step }
            assertNotNull(runCatching { tx.commit() }.exceptionOrNull())
            assertTrue(step, tx.guard)
            if (step in setOf("hold", "suppress", "fresh")) assertTrue(step, tx.fresh)
        }
    }

    @Test fun `failed guard never starts suppression or acknowledgement`() {
        val tx = Transaction().also { it.failAt = "guard" }
        assertNotNull(runCatching { tx.commit() }.exceptionOrNull())
        assertEquals(listOf("guard"), tx.calls)
        assertTrue(tx.fresh)
        assertTrue(tx.automatic)
    }

    @Test fun `final release committed before lost acknowledgement never resurrects previous input`() {
        val tx = Transaction().also { it.failAfterAt = "release" }
        assertNotNull(runCatching { tx.commit() }.exceptionOrNull())
        assertFalse(tx.guard)
        assertFalse(tx.fresh)
        assertFalse(tx.automatic)
        assertTrue(tx.held)
        assertTrue(tx.suppressed)
        assertEquals(listOf("guard", "hold", "suppress", "verify", "fresh", "automatic", "release"), tx.calls)
    }

    @Test fun `silent suppression write failure retains guard and both old restrictions`() {
        val tx = Transaction().also { it.durable = false }
        assertNotNull(runCatching { tx.commit() }.exceptionOrNull())
        assertTrue(tx.guard)
        assertTrue(tx.fresh)
        assertTrue(tx.automatic)
        assertFalse(tx.calls.contains("release"))
    }

    @Test fun `owner changes before or after suppression cannot authorize release`() {
        val before = Transaction().also { it.owner = false }
        assertFalse(before.commit())
        assertTrue(before.calls.isEmpty())
        val during = Transaction().also { it.changeOwnerAtVerification = true }
        assertFalse(during.commit())
        assertTrue(during.guard)
        assertTrue(during.suppressed)
        assertTrue(during.fresh)
    }

    @Test fun `a guarded partially acknowledged transaction can be retried without replay`() {
        val tx = Transaction().also { it.failAt = "automatic" }
        assertNotNull(runCatching { tx.commit() }.exceptionOrNull())
        assertFalse(tx.fresh)
        assertTrue(tx.guard)
        tx.failAt = null
        assertTrue(tx.commit())
        assertFalse(tx.guard)
        assertTrue(tx.suppressed)
        assertTrue(tx.held)
    }

    @Test fun `inbox verifies durable suppression rather than its published memory`() {
        var disk: String? = null
        var dropWrites = false
        val inbox = OrbisEventInbox({ disk }, { if (!dropWrites) disk = it })
        val binding = OrbisEventBinding(assistant, conversation)
        inbox.bind(setOf("self_reminder"), binding)
        val old = inbox.accept(OrbisIncomingEvent("old", "self_reminder", "synthetic"), binding, 1).first
        dropWrites = true
        inbox.mark(old.id, "suppressed", "held_by_future_wake_recovery")
        assertEquals("suppressed", inbox.get(old.id)?.state)
        assertFalse(inbox.verifySuppressed(conversation, setOf(old.id)))
        val reopened = OrbisEventInbox({ disk }, { disk = it })
        assertEquals("accepted", reopened.get(old.id)?.state)
        reopened.mark(old.id, "suppressed", "held_by_future_wake_recovery")
        assertTrue(reopened.verifySuppressed(conversation, setOf(old.id)))
        assertFalse(reopened.verifySuppressed(assistant, setOf(old.id)))
        assertFalse(reopened.verifySuppressed(conversation, setOf("missing")))
        disk = null
        assertFalse(reopened.verifySuppressed(conversation, setOf(old.id)))
    }

    @Test fun `scope absence check distinguishes missing active conflicting and unreadable evidence`() {
        var disk: String? = null
        val store = GatewayRecoveryScopeStore(OrbisQueuePauseStore({ disk }, { disk = it }))
        assertEquals(false, store.hasUnresolvedScope(conversation))
        store.record(conversation, "a".repeat(64))
        assertEquals(true, store.hasUnresolvedScope(conversation))
        store.record(conversation, "b".repeat(64))
        assertEquals(true, store.hasUnresolvedScope(conversation))
        disk = "corrupt"
        assertNull(store.hasUnresolvedScope(conversation))
    }
}
