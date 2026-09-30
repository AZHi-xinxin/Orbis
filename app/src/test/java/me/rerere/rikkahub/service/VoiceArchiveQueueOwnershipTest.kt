package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceArchiveQueueOwnershipTest {
    private fun item(callId: String = "call-A", kind: String = "archive") = QueuedMessage(
        parts = listOf(UIMessagePart.Text("synthetic saved item")), voiceCallId = callId, voiceCallKind = kind)

    @Test fun `isolated archive failure leaves old paused queue exactly unchanged`() {
        val queue = MessageQueue(initiallyPaused = true)
        val oldArchive = item()
        val oldRestore = item(kind = "restore")
        val unrelated = item("call-B", "turn")
        listOf(oldArchive, oldRestore, unrelated).forEach {
            queue.enqueue(it.parts, id = it.id, voiceCallId = it.voiceCallId, voiceCallKind = it.voiceCallKind)
        }
        val before = queue.state.value
        ownedVoiceArchiveQueueItem(true, null, "call-A", before.messages)?.let(queue::remove)
        assertEquals(before, queue.state.value)
        assertTrue(queue.state.value.paused)
        assertNull(ownedVoiceArchiveQueueItem(true, oldArchive.id, "call-A", before.messages))
    }

    @Test fun `ordinary archive cleanup removes only the allocated item not old same call requests`() {
        val queue = MessageQueue(initiallyPaused = true)
        val oldArchive = item()
        val owned = item()
        val oldRestore = item(kind = "restore")
        listOf(oldArchive, owned, oldRestore).forEach {
            queue.enqueue(it.parts, id = it.id, voiceCallId = it.voiceCallId, voiceCallKind = it.voiceCallKind)
        }
        ownedVoiceArchiveQueueItem(false, owned.id, "call-A", queue.state.value.messages)?.let(queue::remove)
        assertEquals(listOf(oldArchive.id, oldRestore.id), queue.state.value.messages.map { it.id })
        assertTrue(queue.state.value.paused)
    }

    @Test fun `missing ownership wrong call and ordinary voice turn are never cleanup targets`() {
        val archive = item()
        val turn = item(kind = "turn")
        val messages = listOf(archive, turn)
        assertNull(ownedVoiceArchiveQueueItem(false, null, "call-A", messages))
        assertNull(ownedVoiceArchiveQueueItem(false, Uuid.random(), "call-A", messages))
        assertNull(ownedVoiceArchiveQueueItem(false, archive.id, "call-B", messages))
        assertNull(ownedVoiceArchiveQueueItem(false, turn.id, "call-A", messages))
    }

    @Test fun `isolated READY archive cannot initialize an absent or unloaded chat session`() {
        assertNotNull(isolatedVoiceArchiveCommitBlockReason(false, false, false, false, false, true))
        assertNotNull(isolatedVoiceArchiveCommitBlockReason(true, false, false, false, false, true))
    }

    @Test fun `checkpoint and recovery block retain READY without repairing queue or journal`() {
        assertTrue(isolatedVoiceArchiveCommitBlockReason(true, true, true, false, false, true)!!.contains("未恢复旧队列"))
        assertTrue(isolatedVoiceArchiveCommitBlockReason(true, true, false, false, true, true)!!.contains("清除恢复记录"))
    }

    @Test fun `publication requires idle initialized same owner with no checkpoint`() {
        assertNotNull(isolatedVoiceArchiveCommitBlockReason(true, true, false, true, false, true))
        assertNotNull(isolatedVoiceArchiveCommitBlockReason(true, true, false, false, false, false))
        assertNull(isolatedVoiceArchiveCommitBlockReason(true, true, false, false, false, true))
    }
}
