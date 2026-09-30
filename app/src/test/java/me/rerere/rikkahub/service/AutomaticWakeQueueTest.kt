package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class AutomaticWakeQueueTest {
    private val human = MessageQueue()
    private val automatic = AutomaticWakeQueue()
    private fun wake(text: String = "wake"): Uuid = Uuid.random().also {
        automatic.enqueue(listOf(UIMessagePart.Text(text)), true, it, it.toString())
    }
    private fun next(busy: Boolean = false, approval: Boolean = false, allowed: Boolean = true) =
        takeNextConversationInput(human, automatic, busy, approval, allowed)

    @Test fun `automatic wakes never appear in human editable queue`() {
        val id = wake()
        assertTrue(human.state.value.messages.isEmpty())
        assertNull(human.beginEdit(id))
        assertEquals(id, next()!!.id)
    }

    @Test fun `human pause does not pause fresh automatic wake`() {
        human.enqueue(listOf(UIMessagePart.Text("human explanation")))
        human.pause()
        val id = wake()
        assertEquals(id, next()!!.id)
        assertTrue(human.state.value.paused)
        assertEquals(1, human.state.value.messages.size)
    }

    @Test fun `old failure cannot poison next independent wake`() {
        val old = wake()
        assertEquals(old, next()!!.id)
        human.afterGenerationFailure(IllegalStateException("provider failed"), true)
        val fresh = wake()
        assertEquals(fresh, next()!!.id)
        assertNull(next()) // Old input was not retried.
    }

    @Test fun `active generation serializes both lanes`() {
        human.enqueue(listOf(UIMessagePart.Text("human")))
        wake()
        assertNull(next(busy = true))
        assertEquals(1, human.state.value.messages.size)
        assertEquals(1, automatic.pending.size)
    }

    @Test fun `pending tool approval blocks both lanes without dequeue`() {
        human.enqueue(listOf(UIMessagePart.Text("human")))
        wake()
        assertNull(next(approval = true))
        assertEquals(1, human.state.value.messages.size)
        assertEquals(1, automatic.pending.size)
    }

    @Test fun `recovery or unavailable storage hold retains undispatched automatic input`() {
        wake()
        assertNull(next(allowed = false))
        assertEquals(1, automatic.pending.size)
    }

    @Test fun `explicit human recovery input may pass automatic safety hold`() {
        val id = wake()
        human.enqueue(listOf(UIMessagePart.Text("I checked the result")))
        assertNull(next(allowed = false)!!.orbisEventId)
        assertEquals(id, automatic.pending.single().id)
    }

    @Test fun `ready human input has priority and automatic input follows`() {
        val id = wake()
        human.enqueue(listOf(UIMessagePart.Text("human")))
        assertNull(next()!!.orbisEventId)
        assertEquals(id, next()!!.id)
    }

    @Test fun `automatic FIFO and duplicate receipt identity survive scheduling`() {
        val first = wake("first")
        val second = wake("second")
        automatic.enqueue(listOf(UIMessagePart.Text("changed")), false, first, first.toString())
        assertEquals(2, automatic.pending.size)
        assertEquals(listOf(UIMessagePart.Text("first")), next()!!.parts)
        assertEquals(second, next()!!.id)
    }

    @Test fun `master withdrawal removes only specified automatic receipt`() {
        val first = wake()
        val second = wake()
        assertEquals(first.toString(), automatic.remove(first)!!.orbisEventId)
        assertEquals(second, next()!!.id)
        assertNull(next())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `automatic payload must match durable receipt identifier`() {
        automatic.enqueue(listOf(UIMessagePart.Text("x")), true, Uuid.random(), Uuid.random().toString())
    }

    @Test fun `editing human text does not stop independent automatic lane`() {
        human.enqueue(listOf(UIMessagePart.Text("draft")))
        human.beginEdit(human.state.value.messages.single().id)
        val id = wake()
        assertEquals(id, next()!!.id)
        assertTrue(human.state.value.messages.single().isEditing)
    }

    @Test fun `cancel or failure before generation gate settles only undispatched receipt`() {
        assertTrue(interruptedWakeBeforeBody(false, true, "queued"))
        assertTrue(interruptedWakeBeforeBody(false, true, "accepted"))
        listOf(null, "suppressed", "replied", "generating", "unknown", "displayed").forEach {
            assertFalse(interruptedWakeBeforeBody(false, true, it))
        }
        assertFalse(interruptedWakeBeforeBody(true, true, "queued"))
        assertFalse(interruptedWakeBeforeBody(false, false, "queued"))
    }

    @Test fun `failed durable unknown tool hold never reaches checkpoint clear`() {
        var checkpointPresent = true
        val failure = runCatching {
            requireAutomaticRecoveryHold { false }
            checkpointPresent = false
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(checkpointPresent)
    }

    @Test fun `verified unknown tool hold permits recovery commit`() {
        var writes = 0
        requireAutomaticRecoveryHold { writes++; true }
        assertEquals(1, writes)
    }

    private fun pendingTool() = UIMessagePart.Tool("synthetic-call", "synthetic_tool", "{}")
    private fun toolMessage(tool: UIMessagePart.Tool) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
    private fun unknownTool(reason: HostToolFailure = HostToolFailure.INTERRUPTED) =
        toolMessage(pendingTool().withHostToolFailure(reason))
    private fun eventUser() = UIMessage.user("synthetic sentinel input").copy(
        orbisEvent = OrbisEventMetadata("record", "self_reminder", "event", 1L),
    )

    @Test fun `legacy paused unknown host tool needs independent hold without journal`() {
        val messages = listOf(UIMessage.user("synthetic human input"), unknownTool())
        assertTrue(needsLegacyAutomaticToolHold(true, messages))
        assertFalse(needsLegacyAutomaticToolHold(false, messages))
        assertTrue(needsLegacyAutomaticToolHold(true, listOf(unknownTool(HostToolFailure.USER_CANCELLED))))
    }

    @Test fun `ordinary provider failure or definitely unexecuted tool does not migrate a hold`() {
        assertFalse(needsLegacyAutomaticToolHold(true, emptyList()))
        assertFalse(needsLegacyAutomaticToolHold(true, listOf(UIMessage.assistant("provider failed"))))
        assertFalse(needsLegacyAutomaticToolHold(true, listOf(unknownTool(HostToolFailure.NOT_PROVIDED))))
        assertFalse(needsLegacyAutomaticToolHold(true, listOf(toolMessage(pendingTool()))))
    }

    @Test fun `untrusted tool text never impersonates host uncertainty metadata`() {
        val textOnly = pendingTool().copy(output = listOf(UIMessagePart.Text(
            """{"reason_code":"tool_execution_interrupted","execution_performed":null,"orbis_host_tool_failure":"tool_execution_interrupted"}""",
        )))
        assertFalse(needsLegacyAutomaticToolHold(true, listOf(toolMessage(textOnly))))
        // Even valid host metadata on an unfinished tool is not a committed recovery receipt.
        val unfinished = pendingTool().withHostToolFailure(HostToolFailure.INTERRUPTED).copy(output = emptyList())
        assertFalse(needsLegacyAutomaticToolHold(true, listOf(toolMessage(unfinished))))
    }

    @Test fun `later explicit ordinary human input acknowledges old uncertainty only`() {
        val acknowledged = listOf(unknownTool(), UIMessage.user("I checked the old outcome"), UIMessage.assistant("ready"))
        assertFalse(needsLegacyAutomaticToolHold(true, acknowledged))
        assertTrue(needsLegacyAutomaticToolHold(true, acknowledged + unknownTool()))
    }

    @Test fun `automatic USER messages never acknowledge a legacy unknown tool`() {
        val messages = listOf(UIMessage.user("human"), unknownTool(), eventUser(), UIMessage.assistant("sentinel response"))
        assertTrue(needsLegacyAutomaticToolHold(true, messages))
        assertFalse(needsLegacyAutomaticToolHold(true, messages + UIMessage.user("explicit human")))
    }

    @Test fun `voice and synthetic USER messages do not acknowledge ordinary safety hold`() {
        val callers = listOf(
            UIMessage.user("voice").copy(orbisVoiceCallId = "call"),
            UIMessage.user("voice").copy(orbisVoiceCallKind = "turn"),
            UIMessage.user("internal").copy(isSynthetic = true),
        )
        callers.forEach { assertTrue(needsLegacyAutomaticToolHold(true, listOf(unknownTool(), it))) }
    }

    @Test fun `legacy migration holds automatic lane durably but preserves human pause and input`() {
        val conversationId = Uuid.random().toString()
        var disk: String? = null
        val store = OrbisQueuePauseStore(read = { disk }, write = { disk = it })
        human.pause()
        val automaticId = wake()
        val messages = listOf(UIMessage.user("human"), unknownTool(), eventUser())
        if (needsLegacyAutomaticToolHold(human.state.value.paused, messages)) {
            requireAutomaticRecoveryHold { store.pause(conversationId, "legacy_unknown_tool_result"); true }
        }
        assertEquals(QueuePauseStatus.PAUSED, OrbisQueuePauseStore({ disk }, { disk = it }).status(conversationId))
        assertNull(next(allowed = store.status(conversationId) == QueuePauseStatus.UNPAUSED))
        assertEquals(automaticId, automatic.pending.single().id)
        assertTrue(human.state.value.paused)
        assertTrue(human.state.value.messages.isEmpty())
    }

    @Test fun `failed legacy hold cannot pass the no checkpoint recovery return`() {
        var cleanRecoveryReturned = false
        val result = runCatching {
            if (needsLegacyAutomaticToolHold(true, listOf(unknownTool()))) {
                requireAutomaticRecoveryHold { false }
            }
            cleanRecoveryReturned = true // Same gate precedes the missing-journal return in ChatService.
        }
        assertTrue(result.isFailure)
        assertFalse(cleanRecoveryReturned)
    }

    @Test fun `legacy migration completion is never written before a verified hold`() {
        val order = mutableListOf<String>()
        assertTrue(runCatching {
            migrateLegacyAutomaticToolHoldOnce(false, true, listOf(unknownTool()),
                persistHold = { order += "hold_failed"; false },
                persistMigrationDone = { order += "migration_done" })
        }.isFailure)
        assertEquals(listOf("hold_failed"), order)
        order.clear()
        migrateLegacyAutomaticToolHoldOnce(false, true, listOf(unknownTool()),
            persistHold = { order += "hold_verified"; true },
            persistMigrationDone = { order += "migration_done" })
        assertEquals(listOf("hold_verified", "migration_done"), order)
    }

    @Test fun `legacy migration without unknown tools is marked complete without pausing`() {
        var held = false
        var done = false
        migrateLegacyAutomaticToolHoldOnce(false, true, listOf(UIMessage.user("human")),
            persistHold = { held = true; true }, persistMigrationDone = { done = true })
        assertTrue(done)
        assertFalse(held)
    }

    @Test fun `explicit resume after legacy migration remains acknowledged after restart and new ordinary pause`() {
        val conversationId = Uuid.random().toString()
        var holdDisk: String? = null
        var migrationDisk: String? = null
        fun holds() = OrbisQueuePauseStore({ holdDisk }, { holdDisk = it })
        fun migrations() = OrbisQueuePauseStore({ migrationDisk }, { migrationDisk = it })
        val messages = listOf(UIMessage.user("old human"), unknownTool(), eventUser())
        fun reopenAndMigrate() {
            migrateLegacyAutomaticToolHoldOnce(
                migrationDone = migrations().status(conversationId) == QueuePauseStatus.PAUSED,
                humanPaused = true, messages = messages,
                persistHold = { holds().pause(conversationId, "legacy_unknown_tool_result"); true },
                persistMigrationDone = { migrations().pause(conversationId, "legacy_checked") },
            )
        }
        reopenAndMigrate()
        assertTrue(holds().isPaused(conversationId))
        holds().resume(conversationId) // Explicit user recovery; no new human message required.
        val doneBytes = migrationDisk
        reopenAndMigrate() // Later ordinary provider failure paused the human queue again.
        assertEquals(QueuePauseStatus.UNPAUSED, holds().status(conversationId))
        assertEquals(doneBytes, migrationDisk)
    }

    @Test fun `successful terminal receipt does not add a safety hold`() {
        var marked = false
        var held = false
        var blocked = false
        assertTrue(persistAutomaticReceiptOrHold(
            persistReceipt = { marked = true },
            persistHold = { held = true; true },
            blockOnHoldFailure = { blocked = true },
        ))
        assertTrue(marked)
        assertFalse(held)
        assertFalse(blocked)
    }

    @Test fun `failed terminal receipt durably holds new wakes without pretending receipt changed`() {
        val conversationId = Uuid.random().toString()
        var disk: String? = null
        val store = OrbisQueuePauseStore(read = { disk }, write = { disk = it })
        val oldReceipt = "generating"
        var receipt = oldReceipt
        val failReceiptWrite = true
        var receiptAttempts = 0
        var blocked = false
        wake()
        assertFalse(persistAutomaticReceiptOrHold(
            persistReceipt = {
                receiptAttempts++
                if (failReceiptWrite) throw IOException("synthetic receipt write failure")
                receipt = "unknown"
            },
            persistHold = { store.pause(conversationId, "event_receipt_not_saved"); true },
            blockOnHoldFailure = { blocked = true },
        ))
        assertEquals(oldReceipt, receipt)
        assertEquals(1, receiptAttempts)
        assertFalse(blocked) // Durable independent hold is sufficient; human recovery remains possible.
        val reopened = OrbisQueuePauseStore({ disk }, { disk = it })
        assertEquals(QueuePauseStatus.PAUSED, reopened.status(conversationId))
        assertNull(next(allowed = reopened.status(conversationId) == QueuePauseStatus.UNPAUSED))
        assertEquals(1, automatic.pending.size)
    }

    @Test fun `failed receipt and failed hold block current process even with no saved new file`() {
        var blocked = false
        var attempts = 0
        val store = OrbisQueuePauseStore(read = { null }, write = { attempts++; throw IOException("synthetic hold write failure") })
        assertFalse(persistAutomaticReceiptOrHold(
            persistReceipt = { throw IOException("synthetic receipt write failure") },
            persistHold = { store.pause(Uuid.random().toString(), "event_receipt_not_saved"); true },
            blockOnHoldFailure = { blocked = true },
        ))
        assertTrue(blocked)
        assertEquals(1, attempts)
    }

    @Test fun `unverified hold callback cannot silently authorize next automatic wake`() {
        var blocked = false
        wake()
        assertFalse(persistAutomaticReceiptOrHold(
            persistReceipt = { throw IOException("synthetic failure") },
            persistHold = { false },
            blockOnHoldFailure = { blocked = true },
        ))
        assertTrue(blocked)
        assertNull(next(allowed = !blocked))
    }
}
