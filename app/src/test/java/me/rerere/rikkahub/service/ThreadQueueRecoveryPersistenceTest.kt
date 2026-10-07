package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Real stores/queues through the actual ChatService durable commit boundary. No model/tool calls. */
class ThreadQueueRecoveryPersistenceTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val assistant = "22222222-2222-4222-8222-222222222222"
    private val fingerprint = "a".repeat(64)
    private class Disk {
        var bytes: String? = null
        var fail = false
        val pause = OrbisQueuePauseStore({ bytes }, { if (fail) error("disk full"); bytes = it })
    }
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun `restarted owner idle release removes fresh restriction and holds every old input`() {
        val gateway = Disk()
        gateway.pause.pause(id, "gateway_terminal_unconfirmed")
        val freshDisk = Disk()
        FreshHumanInputRecoveryStore(freshDisk.pause).authorize(id, assistant)
        val fresh = FreshHumanInputRecoveryStore(freshDisk.pause) // Reconstructed without RAM permit.
        val scopes = GatewayRecoveryScopeStore(Disk().pause)
        scopes.record(id, fingerprint)
        val automatic = Disk()
        automatic.pause.pause(id, "gateway_terminal_unconfirmed")
        val human = MessageQueue(initiallyPaused = true)
        human.enqueue(text("old normal text"))
        human.enqueue(text("old call"), voiceCallId = id, voiceCallKind = "turn")
        var memoryBlocked = true
        assertTrue(commitRecoveredGatewayLane({ true },
            { gateway.pause.pause(id, "gateway_terminal_unconfirmed") },
            { human.holdAllInputsForFreshRecovery() },
            { automatic.pause.resumeIfReason(id, "gateway_terminal_unconfirmed") },
            { scopes.clearAfterConfirmedIdle(id, fingerprint) },
            { fresh.clearAfterConfirmedIdle(id, assistant) },
            { human.resume(); check(!human.state.value.paused) },
            { gateway.pause.resume(id) }, { memoryBlocked = false }))
        assertFalse(memoryBlocked)
        assertEquals(FreshHumanRecoveryStatus.NONE, fresh.status(id, assistant))
        assertEquals(QueuePauseStatus.UNPAUSED, gateway.pause.status(id))
        assertEquals(QueuePauseStatus.UNPAUSED, automatic.pause.status(id))
        assertEquals(2, human.state.value.messages.size)
        assertTrue(human.state.value.messages.all { it.recoveryHeldReason != null })
        assertNull(human.takeNext())
        human.enqueue(text("explicit new input after full recovery"))
        assertEquals(text("explicit new input after full recovery"), human.takeNext()!!.parts)
    }

    @Test fun `final CAS failure leaves every durable record untouched`() {
        var writes = 0
        assertFalse(commitRecoveredGatewayLane({ false }, { writes++ }, { writes++ }, { writes++ },
            { writes++ }, { writes++ }, { writes++ }, { writes++ }, { writes++ }))
        assertEquals(0, writes)
    }

    @Test fun `every partial durable failure keeps transport guard and never releases RAM`() {
        // Failure at each intermediate boundary, including fresh removal and queue resume.
        for (failingStep in 0..5) {
            val gateway = Disk()
            var released = false
            var step = 0
            fun boundary() { if (step++ == failingStep) error("write failed") }
            assertTrue(runCatching {
                commitRecoveredGatewayLane({ true },
                    { gateway.pause.pause(id, "gateway_terminal_unconfirmed") },
                    { boundary() }, { boundary() }, { boundary() }, { boundary() }, { boundary() },
                    { boundary(); gateway.pause.resume(id) }, { released = true })
            }.isFailure)
            assertFalse(released)
            assertEquals(QueuePauseStatus.PAUSED, gateway.pause.status(id))
        }
    }

    @Test fun `fresh restriction clear is durable owner bound and repeatable`() {
        val disk = Disk()
        val store = FreshHumanInputRecoveryStore(disk.pause)
        store.authorize(id, assistant)
        assertTrue(runCatching { store.clearAfterConfirmedIdle(id, Uuid.random().toString()) }.isFailure)
        assertEquals(FreshHumanRecoveryStatus.ACTIVE, store.status(id, assistant))
        disk.fail = true
        assertTrue(runCatching { store.clearAfterConfirmedIdle(id, assistant) }.isFailure)
        assertNotEquals(FreshHumanRecoveryStatus.NONE, store.status(id, assistant))
        val afterRestart = FreshHumanInputRecoveryStore(OrbisQueuePauseStore({ disk.bytes }, { disk.bytes = it }))
        afterRestart.clearAfterConfirmedIdle(id, assistant)
        afterRestart.clearAfterConfirmedIdle(id, assistant)
        assertEquals(FreshHumanRecoveryStatus.NONE, afterRestart.status(id, assistant))
    }

    @Test fun `new origin fingerprint refuses changed and multiple destinations but legacy stays explicit`() {
        val disk = Disk()
        val store = GatewayRecoveryScopeStore(disk.pause)
        assertEquals(GatewayRecoveryScopeStatus.LEGACY, store.status(id, fingerprint))
        store.record(id, fingerprint)
        assertEquals(GatewayRecoveryScopeStatus.MATCH, store.status(id, fingerprint))
        assertEquals(GatewayRecoveryScopeStatus.MISMATCH, store.status(id, "b".repeat(64)))
        assertTrue(runCatching { store.clearAfterConfirmedIdle(id, "b".repeat(64)) }.isFailure)
        store.record(id, "b".repeat(64))
        assertEquals(GatewayRecoveryScopeStatus.MISMATCH, store.status(id, fingerprint))
        assertTrue(runCatching { store.clearAfterConfirmedIdle(id, fingerprint) }.isFailure)
        assertFalse(disk.bytes!!.contains("https://"))
    }

    @Test fun `scope corruption and failed clear fail closed`() {
        val disk = Disk()
        val store = GatewayRecoveryScopeStore(disk.pause)
        store.record(id, fingerprint)
        disk.fail = true
        assertTrue(runCatching { store.clearAfterConfirmedIdle(id, fingerprint) }.isFailure)
        assertEquals(GatewayRecoveryScopeStatus.UNAVAILABLE, store.status(id, fingerprint))
        disk.bytes = "corrupt"
        assertEquals(GatewayRecoveryScopeStatus.UNAVAILABLE, store.status(id, fingerprint))
    }

    @Test fun `unknown tool protection is not cleared by restoring transport`() {
        val automatic = Disk()
        automatic.pause.pause(id, "unknown_tool_result")
        var toolCalls = 0
        assertTrue(commitRecoveredGatewayLane({ true }, {}, {},
            { automatic.pause.resumeIfReason(id, "gateway_terminal_unconfirmed") }, {}, {}, {}, {}, {}))
        assertEquals("unknown_tool_result", automatic.pause.pauseReason(id))
        assertEquals(0, toolCalls)
    }

    @Test fun `old automatic input stays held while a later new event can run`() {
        val automatic = AutomaticWakeQueue()
        val old = Uuid.random()
        automatic.enqueue(text("old wake"), true, old, old.toString())
        val newer = Uuid.random()
        automatic.enqueue(text("new wake after confirmed recovery"), true, newer, newer.toString())
        automatic.holdAllForRecovery(setOf(old.toString()))
        assertEquals(newer, automatic.takeNext()!!.id)
        assertEquals(old, automatic.pending.single().id)
        assertNotNull(automatic.pending.single().recoveryHeldReason)
    }
}
