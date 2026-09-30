package me.rerere.rikkahub.data.orbis.integration

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrbisTechHubOutboxTest {
    private val token = "synthetic-outbox-credential-1234567890"
    private fun credential(revision: Long = 3, secret: String = token, url: String = "http://127.0.0.1:18901") =
        OrbisConnectionCredential(url, secret, revision)
    private val receipt = listOf(HubMessage(1, "synthetic-event", "synthetic-peer", "hello", "2026-09-26T00:00:00Z"))

    private class MemoryDisk : HubOutboxPersistence {
        @Volatile var bytes: ByteArray? = null
        @Volatile var readFail = false
        @Volatile var failAtWrite = 0
        @Volatile var commitBeforeFailure = false
        @Volatile var beforeWrite: (() -> Unit)? = null
        val writes = AtomicInteger()
        override fun read(): ByteArray? {
            if (readFail) throw IOException("synthetic-read-error")
            return bytes?.copyOf()
        }
        override fun write(bytes: ByteArray) {
            beforeWrite?.invoke()
            val count = writes.incrementAndGet()
            if (count == failAtWrite) {
                if (commitBeforeFailure) this.bytes = bytes.copyOf()
                throw IOException("synthetic-write-error")
            }
            this.bytes = bytes.copyOf()
        }
    }
    private suspend fun unknown(box: OrbisTechHubOutbox, cred: OrbisConnectionCredential = credential(),
        now: Long = 1000, text: String = "原始正文"): HubSendIntent {
        var captured: HubSendIntent? = null
        try {
            box.send(cred, "general", text, retry = false, nowMillis = now) {
                captured = it
                throw IOException("synthetic-unknown")
            }
            fail("expected unknown outcome")
        } catch (_: IOException) { }
        return requireNotNull(captured)
    }
    private suspend fun expectReason(reason: String, block: suspend () -> Unit) {
        try { block(); fail("expected $reason") }
        catch (failure: HubOutboxException) { assertEquals(reason, failure.reason); assertNull(failure.cause) }
    }

    @Test fun emptyLoadDoesNotWriteOrSendAnything() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        box.load(); box.load()
        assertTrue(box.state.value.canSendNew)
        assertFalse(box.state.value.hasPending)
        assertEquals(0, disk.writes.get())
    }
    @Test fun durableIntentExistsBeforeFirstNetworkCall() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        box.send(credential(), "general", "  hello  ", false, 1000) { intent ->
            assertEquals(1, disk.writes.get())
            val loaded = OrbisTechHubOutbox(disk).pendingFor(credential(999))!!
            assertEquals(intent.key, loaded.key); assertEquals("hello", loaded.text)
            assertTrue(box.state.value.busy)
            receipt
        }
        assertTrue(box.state.value.canSendNew)
        assertNull(OrbisTechHubOutbox(disk).pendingFor(credential()))
    }
    @Test fun unknownOutcomeSurvivesRestartAndRebindsOnlyRevision() = runBlocking {
        val disk = MemoryDisk(); val first = OrbisTechHubOutbox(disk)
        val original = unknown(first)
        val second = OrbisTechHubOutbox(disk)
        second.load()
        assertEquals(1, disk.writes.get()) // load must never POST or remint
        val restored = second.pendingFor(credential(55))!!
        assertEquals(original.key, restored.key); assertEquals(original.text, restored.text)
        assertEquals(original.createdAtMillis, restored.createdAtMillis)
        assertEquals(55L, restored.connectionRevision)
        second.send(credential(55), "another-room", "cannot replace body", true, 1100) {
            assertEquals(restored, it)
            receipt
        }
        assertTrue(second.state.value.canSendNew)
    }
    @Test fun changedTokenKeepsOldPendingButDoesNotRevealOrSendIt() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        val original = unknown(box); val previous = disk.bytes!!.copyOf()
        val changed = credential(secret = token + "other")
        assertNull(box.pendingFor(changed))
        expectReason("configuration_changed") { box.send(changed, "general", "", true, 1100) { fail(); receipt } }
        expectReason("pending_exists") { box.send(changed, "general", "new", false, 1100) { fail(); receipt } }
        assertArrayEquals(previous, disk.bytes)
        assertEquals(original.key, box.pendingFor(credential())!!.key)
    }
    @Test fun changedServerCannotReusePendingEvenWithSameToken() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        unknown(box)
        val changed = credential(url = "http://127.0.0.1:18902")
        assertNull(box.pendingFor(changed))
        expectReason("configuration_changed") { box.send(changed, "general", "", true, 1100) { fail(); receipt } }
        assertTrue(box.state.value.hasPending)
    }
    @Test fun rawTokenAndServerAddressAreNeverSerialized() = runBlocking {
        val disk = MemoryDisk(); unknown(OrbisTechHubOutbox(disk))
        val text = disk.bytes!!.toString(Charsets.UTF_8)
        assertFalse(text.contains(token)); assertFalse(text.contains("127.0.0.1"))
        assertFalse(text.contains("connectionRevision"))
        val pending = Json.parseToJsonElement(text).jsonObject.getValue("pending").jsonObject
        assertTrue(Regex("[0-9a-f]{64}").matches(pending.getValue("scope").jsonPrimitive.content))
    }
    @Test fun writeFailureDoesNotCallNetworkAndLocksFurtherWrites() = runBlocking {
        val disk = MemoryDisk().apply { failAtWrite = 1 }
        val box = OrbisTechHubOutbox(disk)
        expectReason("storage_unavailable") { box.send(credential(), "general", "hello", false, 1000) { fail(); receipt } }
        assertTrue(box.state.value.failed); assertFalse(box.state.value.canSendNew)
        expectReason("storage_unavailable") { box.send(credential(), "general", "again", false, 1100) { fail(); receipt } }
        assertEquals(1, disk.writes.get())
    }
    @Test fun uncertainCommittedWriteRecoversSameIntentWithoutNetwork() = runBlocking {
        val disk = MemoryDisk().apply { failAtWrite = 1; commitBeforeFailure = true }
        val box = OrbisTechHubOutbox(disk)
        expectReason("storage_unavailable") { box.send(credential(), "general", "hello", false, 1000) { fail(); receipt } }
        val second = OrbisTechHubOutbox(disk)
        val intent = second.pendingFor(credential(4))!!
        assertEquals("hello", intent.text); assertEquals(1000L, intent.createdAtMillis)
        assertTrue(second.state.value.canRetry)
    }
    @Test fun corruptStoreNeverGetsOverwrittenBySendOrLoad() = runBlocking {
        val disk = MemoryDisk().apply { bytes = "broken-json".toByteArray() }
        val original = disk.bytes!!.copyOf(); val box = OrbisTechHubOutbox(disk)
        box.load(); box.load()
        expectReason("storage_unavailable") { box.send(credential(), "general", "hello", false, 1000) { fail(); receipt } }
        assertTrue(box.state.value.failed); assertEquals(0, disk.writes.get())
        assertArrayEquals(original, disk.bytes)
    }
    @Test fun duplicateKeysOversizeAndWrongSchemaFailClosed() = runBlocking {
        val bad = listOf(
            "{\"schema\":\"orbis.techhub.outbox/1\",\"pending\":null,\"pending\":null}".toByteArray(),
            "{\"schema\":\"wrong\",\"pending\":null}".toByteArray(),
            ByteArray(HUB_OUTBOX_LIMIT + 1), byteArrayOf(0xc3.toByte(), 0x28),
        )
        for (bytes in bad) {
            val disk = MemoryDisk().apply { this.bytes = bytes }
            val box = OrbisTechHubOutbox(disk); box.load()
            assertTrue(box.state.value.failed); assertFalse(box.state.value.canSendNew)
            assertEquals(0, disk.writes.get())
        }
    }
    @Test fun invalidPersistedUuidRoomTimeOrBodyCannotRetry() = runBlocking {
        val good = MemoryDisk(); unknown(OrbisTechHubOutbox(good))
        val root = Json.parseToJsonElement(good.bytes!!.toString(Charsets.UTF_8)).jsonObject
        val pending = root.getValue("pending").jsonObject
        val invalid = mapOf("key" to JsonPrimitive("not-uuid"), "room" to JsonPrimitive("../ack"),
            "createdAtMillis" to JsonPrimitive(-1), "text" to JsonPrimitive("x".repeat(4001)),
            "scope" to JsonPrimitive(token), "confirmed" to JsonPrimitive("false"))
        for ((key, value) in invalid) {
            val changed = JsonObject(root + ("pending" to JsonObject(pending + (key to value))))
            val disk = MemoryDisk().apply { bytes = changed.toString().toByteArray() }
            val box = OrbisTechHubOutbox(disk); box.load()
            assertTrue("invalid $key", box.state.value.failed)
            assertEquals(0, disk.writes.get())
        }
    }
    @Test fun readFailureIsNotTreatedAsEmptyOutbox() = runBlocking {
        val disk = MemoryDisk().apply { readFail = true }
        val box = OrbisTechHubOutbox(disk); box.load()
        assertTrue(box.state.value.failed)
        expectReason("storage_unavailable") { box.send(credential(), "general", "hello", false, 1000) { fail(); receipt } }
        assertEquals(0, disk.writes.get())
    }
    @Test fun successfulReceiptButClearFailureBlocksNewAndRetryAcrossRestart() = runBlocking {
        val disk = MemoryDisk().apply { failAtWrite = 3 }
        val box = OrbisTechHubOutbox(disk)
        expectReason("receipt_cleanup_failed") { box.send(credential(), "general", "hello", false, 1000) { receipt } }
        assertTrue(box.state.value.hasPending); assertTrue(box.state.value.receiptConfirmed)
        assertFalse(box.state.value.canSendNew); assertFalse(box.state.value.canRetry)
        val second = OrbisTechHubOutbox(disk); second.load()
        assertTrue(second.state.value.receiptConfirmed); assertFalse(second.state.value.canRetry)
        expectReason("already_confirmed") { second.send(credential(), "general", "", true, 1100) { fail(); receipt } }
        expectReason("pending_exists") { second.send(credential(), "general", "new", false, 1100) { fail(); receipt } }
    }
    @Test fun failedReceiptCheckpointStillKeepsOriginalIdempotencyKey() = runBlocking {
        val disk = MemoryDisk().apply { failAtWrite = 2 }
        val box = OrbisTechHubOutbox(disk); var key = ""
        expectReason("receipt_cleanup_failed") { box.send(credential(), "general", "hello", false, 1000) { key = it.key; receipt } }
        assertTrue(box.state.value.failed); assertTrue(box.state.value.receiptConfirmed)
        val second = OrbisTechHubOutbox(disk)
        assertEquals(key, second.pendingFor(credential())!!.key)
        assertTrue(second.state.value.canRetry) // Same key only; lost receipt must not mint a new intent.
    }
    @Test fun explicitAbandonClearsOnlyLocalRecordAndAllowsNewIntent() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        val original = unknown(box)
        box.abandon()
        assertTrue(box.state.value.canSendNew)
        val next = unknown(box, now = 2000)
        assertNotEquals(original.key, next.key)
    }
    @Test fun explicitAbandonCanRecoverCorruptionButFailureRemainsClosed() = runBlocking {
        val disk = MemoryDisk().apply { bytes = "corrupt".toByteArray(); failAtWrite = 1 }
        val box = OrbisTechHubOutbox(disk); box.load()
        expectReason("storage_unavailable") { box.abandon() }
        assertTrue(box.state.value.failed); assertFalse(box.state.value.canSendNew)
        box.abandon()
        assertTrue(box.state.value.canSendNew)
    }
    @Test fun expiryAndClockRollbackDoNotRemintOrSendPending() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        val original = unknown(box)
        for (now in listOf(999L, 1000L + HUB_RETRY_MILLIS)) {
            expectReason("retry_expired") { box.send(credential(), "general", "", true, now) { fail(); receipt } }
        }
        assertEquals(original.key, box.pendingFor(credential())!!.key)
        assertEquals(1, disk.writes.get())
    }
    @Test fun simultaneousSendAndAbandonCannotBypassSingleFlight() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val job = launch { box.send(credential(), "general", "hello", false, 1000) { entered.complete(Unit); hold.await(); receipt } }
        withTimeout(5000) { entered.await() }
        expectReason("send_in_progress") { box.send(credential(), "general", "again", false, 1000) { fail(); receipt } }
        expectReason("send_in_progress") { box.abandon() }
        job.cancelAndJoin()
        assertFalse(box.state.value.busy); assertTrue(box.state.value.hasPending)
        assertEquals(1, disk.writes.get())
    }
    @Test fun cancellationDuringDurableSaveRetainsIntentAndReleasesSingleFlight() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val disk = MemoryDisk().apply { beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val box = OrbisTechHubOutbox(disk); val calls = AtomicInteger()
        val job = launch(Dispatchers.Default) { box.send(credential(), "general", "hello", false, 1000) { calls.incrementAndGet(); receipt } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel(); release.countDown(); job.join()
        } finally { release.countDown(); job.cancelAndJoin() }
        assertEquals(0, calls.get()); assertTrue(box.state.value.hasPending); assertFalse(box.state.value.busy)
        assertNotNull(OrbisTechHubOutbox(disk).pendingFor(credential()))
    }
    @Test fun maximumUnicodeBodyFitsBoundedDiskAndRequest() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        val intent = unknown(box, text = "😀".repeat(4000))
        assertTrue(disk.bytes!!.size <= HUB_OUTBOX_LIMIT)
        assertEquals(4000, intent.text.codePointCount(0, intent.text.length))
        assertNotNull(OrbisTechHubOutbox(disk).pendingFor(credential()))
    }

    private fun attachment() = HubUploadAttachment(java.util.UUID.randomUUID().toString(), "synthetic.txt", "text/plain", 7, "a".repeat(64))
    @Test fun mediaUnknownOutcomeRestartsWithExactlySamePayloadAndEmptyCaption() = runBlocking {
        val disk = MemoryDisk(); val first = OrbisTechHubOutbox(disk); val media = attachment()
        var captured: HubSendIntent? = null
        try { first.send(credential(), "general", "", false, 1000, media) { captured = it; throw IOException("unknown") }; fail() }
        catch (_: IOException) { }
        assertTrue(disk.bytes!!.toString(Charsets.UTF_8).contains("orbis.techhub.outbox/2"))
        assertFalse(disk.bytes!!.toString(Charsets.UTF_8).contains(token))
        val afterRestart = OrbisTechHubOutbox(disk)
        afterRestart.load(); assertEquals(1, disk.writes.get())
        val restored = afterRestart.pendingFor(credential())!!
        assertEquals(captured, restored); assertEquals(media, restored.attachment); assertEquals("", restored.text)
        afterRestart.send(credential(), "different", "not original", true, 1100, attachment()) {
            assertEquals(restored, it); emptyList()
        }
        assertTrue(afterRestart.state.value.canSendNew)
    }
    @Test fun textAndMediaShareOneCrossTypePendingSlot() = runBlocking {
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        unknown(box)
        expectReason("pending_exists") { box.send(credential(), "general", "", false, 1100, attachment()) { fail(); receipt } }
        box.abandon()
        try { box.send(credential(), "general", "", false, 1200, attachment()) { throw IOException("unknown") }; fail() } catch (_: IOException) { }
        expectReason("pending_exists") { box.send(credential(), "general", "new text", false, 1300) { fail(); receipt } }
    }
    @Test fun mediaPayloadOnlyDiscardedAfterDurableReceiptClear() = runBlocking {
        val disk = MemoryDisk(); val media = attachment(); val discarded = mutableListOf<HubUploadAttachment>()
        val box = OrbisTechHubOutbox(disk) { value ->
            assertEquals(JsonNull, Json.parseToJsonElement(disk.bytes!!.toString(Charsets.UTF_8)).jsonObject["pending"])
            discarded += value
        }
        box.send(credential(), "general", "caption", false, 1000, media) { assertTrue(discarded.isEmpty()); emptyList() }
        assertEquals(listOf(media), discarded)
        assertNull(OrbisTechHubOutbox(disk).pendingFor(credential()))
    }
    @Test fun mediaReceiptClearFailureKeepsPayloadAndBlocksReplay() = runBlocking {
        val disk = MemoryDisk().apply { failAtWrite = 3 }; val discarded = mutableListOf<HubUploadAttachment>()
        val box = OrbisTechHubOutbox(disk) { discarded += it }
        expectReason("receipt_cleanup_failed") { box.send(credential(), "general", "", false, 1000, attachment()) { emptyList() } }
        assertTrue(discarded.isEmpty()); assertFalse(box.state.value.canRetry)
        assertTrue(OrbisTechHubOutbox(disk).pendingFor(credential())!!.attachment != null)
    }
    @Test fun humanAbandonClearsRecordBeforeDiscardingOnlyItsMedia() = runBlocking {
        val disk = MemoryDisk(); val discarded = mutableListOf<HubUploadAttachment>(); val media = attachment()
        val box = OrbisTechHubOutbox(disk) { discarded += it }
        try { box.send(credential(), "general", "", false, 1000, media) { throw IOException("unknown") }; fail() } catch (_: IOException) { }
        assertTrue(discarded.isEmpty()); box.abandon(); assertEquals(listOf(media), discarded)
        assertTrue(box.state.value.canSendNew)
    }
    @Test fun invalidPersistedMediaRemainsLockedAndUnmodified() = runBlocking {
        val good = MemoryDisk()
        try { OrbisTechHubOutbox(good).send(credential(), "general", "", false, 1000, attachment()) { throw IOException("unknown") }; fail() } catch (_: IOException) { }
        val root = Json.parseToJsonElement(good.bytes!!.toString(Charsets.UTF_8)).jsonObject
        val pending = root.getValue("pending").jsonObject
        val original = pending.getValue("attachment").jsonObject
        val corruptions = mapOf("payloadId" to JsonPrimitive("../../outside"), "filename" to JsonPrimitive("../x"),
            "mediaType" to JsonPrimitive("Text/Plain"), "size" to JsonPrimitive(HUB_FILE_LIMIT + 1), "sha256" to JsonPrimitive("bad"))
        for ((key, value) in corruptions) {
            val bytes = JsonObject(root + ("pending" to JsonObject(pending + ("attachment" to JsonObject(original + (key to value)))))).toString().toByteArray()
            val disk = MemoryDisk().apply { this.bytes = bytes }; val box = OrbisTechHubOutbox(disk)
            box.load(); assertTrue("invalid $key", box.state.value.failed)
            assertEquals(0, disk.writes.get()); assertArrayEquals(bytes, disk.bytes)
        }
    }
    @Test fun legacyPendingRecordStillUsesV1WithoutMediaField() = runBlocking {
        val disk = MemoryDisk(); val original = unknown(OrbisTechHubOutbox(disk))
        val root = Json.parseToJsonElement(disk.bytes!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("orbis.techhub.outbox/1", root.getValue("schema").jsonPrimitive.content)
        assertFalse(root.getValue("pending").jsonObject.containsKey("attachment"))
        assertEquals(original, OrbisTechHubOutbox(disk).pendingFor(credential()))
    }
    @Test fun outboxClaimsBeforePossibleWriteAndRetainsUncertainCommitBytes() = runBlocking {
        val media = attachment(); val removed = mutableListOf<HubUploadAttachment>()
        val owner = HubMediaDraftOwner { removed += it }; owner.replace(media)
        var claimed = false
        val disk = MemoryDisk().apply {
            failAtWrite = 1; commitBeforeFailure = true
            beforeWrite = { assertTrue(claimed) }
        }
        val box = OrbisTechHubOutbox(disk)
        expectReason("storage_unavailable") {
            box.send(credential(), "general", "", false, 1000, media,
                onAttachmentClaimed = { owner.claim(it); claimed = true }) { fail(); receipt }
        }
        owner.clear(); assertTrue(removed.isEmpty())
        val restored = OrbisTechHubOutbox(disk).pendingFor(credential())!!
        assertEquals(media, restored.attachment)
    }
    @Test fun rejectedNewSendDoesNotTakeOwnershipOrWriteMedia() = runBlocking {
        val media = attachment(); val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        unknown(box)
        var claimed = false
        expectReason("pending_exists") {
            box.send(credential(), "general", "", false, 1100, media,
                onAttachmentClaimed = { claimed = true }) { fail(); receipt }
        }
        assertFalse(claimed); assertEquals(1, disk.writes.get())
    }
    @Test fun disposedDraftCannotBePersistedOrSentByDelayedJob() = runBlocking {
        val media = attachment(); val removed = mutableListOf<HubUploadAttachment>()
        val owner = HubMediaDraftOwner { removed += it }; owner.replace(media); owner.clear()
        val disk = MemoryDisk(); val box = OrbisTechHubOutbox(disk)
        try {
            box.send(credential(), "general", "", false, 1100, media, onAttachmentClaimed = owner::claim) { fail(); receipt }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(media), removed); assertEquals(0, disk.writes.get()); assertTrue(box.state.value.canSendNew)
    }
}
