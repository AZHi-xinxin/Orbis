package me.rerere.rikkahub.data.orbis.voice

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallRepositoryTest {
    @Test fun `archive intent is durable and process interruption never retries or loses raw source`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        val original = call().copy(status = OrbisVoiceCallStatus.INTERRUPTED, transcript = listOf(entry()),
            archiveStatus = OrbisVoiceArchiveStatus.GENERATING, archiveRequestCount = 1, archiveLastModelId = "explicit-model")
        repo.create(original)
        val recovered = OrbisVoiceCallRepository(storage).recoverInterrupted().single()
        assertEquals(OrbisVoiceArchiveStatus.FAILED, recovered.archiveStatus)
        assertEquals("process_interrupted_no_auto_retry", recovered.archiveFailureCode)
        assertEquals(1, recovered.archiveRequestCount)
        assertEquals(original.transcript, recovered.transcript)
        fails { repo.update(original.id) { it.copy(archiveRequestCount = 0) } }
        fails { repo.update(original.id) { it.copy(archiveFailureCode = "PRIVATE_EXCEPTION_BODY") } }
        assertTrue(repo.recoverInterrupted().isEmpty())
    }
    private class MemoryStorage : OrbisVoiceCallStorage {
        override val lockKey = UUID.randomUUID().toString()
        val records = mutableMapOf<String, String>()
        var failBeforeWrite = false
        var failAfterWrite = false
        var silentlyDropWrite = false
        var writes = 0
        override fun ids() = records.keys.toList()
        override fun read(id: String) = records[id]
        override fun write(id: String, value: String) {
            writes++
            if (failBeforeWrite) throw IOException("synthetic_write_failure")
            if (!silentlyDropWrite) records[id] = value
            if (failAfterWrite) throw IOException("synthetic_postcommit_failure")
        }
    }

    private fun call(id: String = "call-1", assistantId: String = "assistant-1", started: Long = 1000) =
        OrbisVoiceCallRecord(id, "conversation-1", assistantId, started)

    private fun entry(id: String = "message-1", text: String = "明天一起去公园。") =
        OrbisVoiceTranscriptEntry(id, "user", text, 2000, id)

    private suspend fun fails(block: suspend () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected an exception")
    } catch (error: Exception) { error }

    @Test fun `summary failure and retry preserve exact original transcript and nodes`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        val source = call().copy(status = OrbisVoiceCallStatus.ENDED, connectedAtMs = 1100,
            endedAtMs = 5000, durationMs = 3900, transcript = listOf(entry()),
            sourceMessageIds = listOf("message-1"), sourceNodesJson = "[{\"tool\":\"original-result\"}]")
        repo.create(source)
        repo.update(source.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING) }
        repo.update(source.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED, error = "retry") }
        assertEquals(source.transcript, repo.get(source.id)!!.transcript)
        fails { repo.update(source.id) { it.copy(transcript = emptyList()) } }
        fails { repo.update(source.id) { it.copy(sourceMessageIds = emptyList()) } }
        fails { repo.update(source.id) { it.copy(sourceNodesJson = null) } }
        fails { repo.update(source.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY, summary = "", modelTranscript = "全文") } }
        val ready = repo.update(source.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = "约好明天去公园。", modelTranscript = "用户提议明天一起去公园。", error = null) }
        assertEquals(source.transcript, ready.transcript)
        assertEquals(source.sourceNodesJson, ready.sourceNodesJson)
        assertEquals(ready, OrbisVoiceCallRepository(storage).get(source.id))
        fails { repo.update(source.id) { it.copy(summary = "替换已确认的摘要") } }
    }

    @Test fun `ready archive survives page commit gap without requesting model again`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        val ready = call().copy(status = OrbisVoiceCallStatus.ENDED,
            archiveStatus = OrbisVoiceArchiveStatus.READY, summary = "约好明天去公园。",
            modelTranscript = "用户提议明天一起去公园。", transcript = listOf(entry()))
        repo.create(ready)
        assertTrue(repo.recoverInterrupted().isEmpty())
        assertFalse(OrbisVoiceCallRepository(storage).get(ready.id)!!.chatCommitted)
        repo.update(ready.id) { it.copy(chatCommitted = true, error = null) }
        assertTrue(OrbisVoiceCallRepository(storage).get(ready.id)!!.chatCommitted)
        assertEquals(ready.summary, repo.get(ready.id)!!.summary)
        assertEquals(ready.transcript, repo.get(ready.id)!!.transcript)
        fails { repo.update(ready.id) { it.copy(chatCommitted = false) } }
    }

    @Test fun `recover interrupted recording keeps source and does not invent hangup or duration`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(call().copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 1200,
            transcript = listOf(entry())))
        val recovered = OrbisVoiceCallRepository(storage).recoverInterrupted().single()
        assertEquals(OrbisVoiceCallStatus.INTERRUPTED, recovered.status)
        assertEquals(OrbisVoiceArchiveStatus.PENDING, recovered.archiveStatus)
        assertNull(recovered.endedAtMs)
        assertNull(recovered.durationMs)
        assertEquals(listOf(entry()), recovered.transcript)
        assertTrue(repo.recoverInterrupted().isEmpty())
        fails { repo.update(recovered.id) { it.copy(status = OrbisVoiceCallStatus.ACTIVE) } }
    }

    @Test fun `recover interrupted archive preserves confirmed hangup time and duration`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        repo.create(call().copy(status = OrbisVoiceCallStatus.ENDED, connectedAtMs = 2000,
            endedAtMs = 7000, durationMs = 5000, archiveStatus = OrbisVoiceArchiveStatus.GENERATING,
            transcript = listOf(entry())))
        val recovered = repo.recoverInterrupted().single()
        assertEquals(OrbisVoiceCallStatus.ENDED, recovered.status)
        assertEquals(OrbisVoiceArchiveStatus.FAILED, recovered.archiveStatus)
        assertEquals(7000L, recovered.endedAtMs)
        assertEquals(5000L, recovered.durationMs)
    }

    @Test fun `independent repository instances serialize append updates without losing a turn`() = runTest {
        val storage = MemoryStorage()
        val first = OrbisVoiceCallRepository(storage)
        val second = OrbisVoiceCallRepository(storage)
        first.create(call())
        (1..60).map { index -> async {
            val repo = if (index % 2 == 0) first else second
            repo.update("call-1") { it.copy(transcript = it.transcript + entry("message-$index")) }
        } }.awaitAll()
        assertEquals((1..60).map { "message-$it" }.toSet(), first.get("call-1")!!.transcript.map { it.id }.toSet())
    }

    @Test fun `write failure cannot acknowledge success or clear source`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(call().copy(transcript = listOf(entry())))
        storage.failBeforeWrite = true
        fails { repo.update("call-1") { it.copy(error = "new error") } }
        assertEquals(listOf(entry()), repo.get("call-1")!!.transcript)
        assertNull(repo.get("call-1")!!.error)
        storage.failBeforeWrite = false
        storage.silentlyDropWrite = true
        assertTrue(fails { repo.update("call-1") { it.copy(error = "new error") } } is IOException)
        assertNull(repo.get("call-1")!!.error)
    }

    @Test fun `postcommit failure is recovered from disk instead of overwriting with stale snapshot`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(call())
        storage.failAfterWrite = true
        fails { repo.update("call-1") { it.copy(transcript = listOf(entry())) } }
        storage.failAfterWrite = false
        repo.update("call-1") { it.copy(transcript = it.transcript + entry("message-2")) }
        assertEquals(listOf("message-1", "message-2"), repo.get("call-1")!!.transcript.map { it.id })
    }

    @Test fun `list and search scope by assistant while permitting cross conversation lookup`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        repo.create(call("older", started = 1000).copy(transcript = listOf(entry())))
        repo.create(call("newer", started = 3000).copy(conversationId = "conversation-2", transcript = listOf(entry())))
        repo.create(call("other", assistantId = "assistant-2", started = 4000).copy(transcript = listOf(entry())))
        assertEquals(listOf("newer", "older"), repo.list(assistantId = "assistant-1").map { it.id })
        assertEquals(listOf("older"), repo.list(assistantId = "assistant-1", offset = 1, limit = 1).map { it.id })
        assertEquals(listOf("newer", "older"), repo.search("公园", assistantId = "assistant-1").map { it.id })
        assertEquals(listOf("older"), repo.search("公园", "conversation-1", "assistant-1").map { it.id })
        assertTrue(repo.search("不存在").isEmpty())
    }

    @Test fun `unsafe ids corrupted records and unconfirmed durations fail without writes`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        listOf("../outside", "a/b", "a\\b", "", ".hidden").forEach { id -> fails { repo.create(call(id)) } }
        fails { repo.create(call().copy(durationMs = 100)) }
        assertEquals(0, storage.writes)
        storage.records["call-1"] = "broken-json"
        fails { repo.get("call-1") }
        fails { repo.update("call-1") { call() } }
        assertEquals("broken-json", storage.records["call-1"])
        storage.records["call-1"] = Json.encodeToString(call("wrong-id"))
        fails { repo.get("call-1") }
        assertEquals(0, storage.writes)
    }

    @Test fun `archive retry keeps interruption cause duration and legacy diagnosis separate`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        val original = call().copy(status = OrbisVoiceCallStatus.INTERRUPTED, connectedAtMs = 1100,
            endedAtMs = 4100, durationMs = 3000, endReason = "ERROR", endError = "synthetic call failure",
            archiveStatus = OrbisVoiceArchiveStatus.FAILED, archiveError = "synthetic queue pause",
            error = "legacy combined diagnosis", transcript = listOf(entry()))
        repo.create(original)
        repo.update(original.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING, archiveError = null) }
        val ready = repo.update(original.id) { it.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = "一起去公园。", modelTranscript = "用户提议一起去公园。", archiveError = null, chatCommitted = true) }
        assertEquals(OrbisVoiceCallStatus.INTERRUPTED, ready.status)
        assertEquals(original.endReason, ready.endReason)
        assertEquals(original.endError, ready.endError)
        assertEquals(original.error, ready.error)
        assertEquals(original.durationMs, ready.durationMs)
        assertNull(ready.archiveErrorForDisplay())
        fails { repo.update(original.id) { it.copy(endError = null) } }
        fails { repo.update(original.id) { it.copy(endReason = "USER") } }
    }

    @Test fun `legacy JSON remains readable with no fabricated typed end reason`() = runTest {
        val storage = MemoryStorage()
        storage.records["call-1"] = """{"id":"call-1","conversationId":"conversation-1","assistantId":"assistant-1","startedAtMs":1000,"status":"INTERRUPTED","archiveStatus":"FAILED","error":"old queue paused"}"""
        val record = OrbisVoiceCallRepository(storage).get("call-1")!!
        assertNull(record.endReason)
        assertNull(record.endError)
        assertEquals("old queue paused", record.archiveErrorForDisplay())
        assertEquals(OrbisVoiceOpeningStatus.NONE, record.openingStatus)
        assertEquals(0, storage.writes)
    }

    @Test fun `incoming opening claim is bound durable and one shot across repository instances`() = runTest {
        val storage = MemoryStorage()
        val first = OrbisVoiceCallRepository(storage)
        val second = OrbisVoiceCallRepository(storage)
        first.create(call().copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 1100))
        fails { first.claimIncomingOpening("call-1", "other", "assistant-1", "request-1", "reason") }
        fails { first.claimIncomingOpening("call-1", "conversation-1", "other", "request-1", "reason") }
        val results = (1..12).map { index -> async {
            (if (index % 2 == 0) first else second).claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-$index", "reason")
        } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(results.filterNotNull().single().openingRequestId, second.get("call-1")!!.openingRequestId)
        fails { first.update("call-1") { it.copy(openingRequestId = "different-request") } }
        fails { first.update("call-1") { it.copy(openingStatus = OrbisVoiceOpeningStatus.NONE, openingRequestId = null) } }
    }

    @Test fun `process recovery never retries an opening with unknown outcome`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        repo.create(call().copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 1100))
        repo.claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-1", "reason")
        repo.update("call-1") { it.copy(openingStatus = OrbisVoiceOpeningStatus.GENERATING) }
        val recovered = repo.recoverInterrupted().single()
        assertEquals(OrbisVoiceOpeningStatus.UNKNOWN, recovered.openingStatus)
        assertEquals("PROCESS_INTERRUPTED", recovered.endReason)
        assertNull(repo.claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-2", "reason"))
        assertTrue(repo.recoverInterrupted().isEmpty())
        fails { repo.update("call-1") { it.copy(openingStatus = OrbisVoiceOpeningStatus.GENERATING) } }
    }

    @Test fun `AI end request is immutable and archive completion cannot erase it`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        repo.create(call().copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 1100))
        repo.update("call-1") { it.copy(aiEndRequestedAtMs = 2000, aiEndReasonText = "先休息") }
        fails { repo.update("call-1") { it.copy(aiEndRequestedAtMs = null) } }
        fails { repo.update("call-1") { it.copy(aiEndReasonText = "different") } }
        val ended = repo.update("call-1") { it.copy(status = OrbisVoiceCallStatus.ENDED,
            endedAtMs = 2100, durationMs = 1000, endReason = "AI_END", endReasonText = "先休息") }
        assertEquals(2000L, ended.aiEndRequestedAtMs)
        assertEquals("先休息", ended.aiEndReasonText)
    }

    @Test fun `failed opening claim does not acknowledge an unstored dispatch`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(call().copy(status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 1100))
        storage.failBeforeWrite = true
        fails { repo.claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-1", "reason") }
        assertEquals(OrbisVoiceOpeningStatus.NONE, repo.get("call-1")!!.openingStatus)
        storage.failBeforeWrite = false
        storage.failAfterWrite = true
        fails { repo.claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-1", "reason") }
        storage.failAfterWrite = false
        assertNull(repo.claimIncomingOpening("call-1", "conversation-1", "assistant-1", "request-2", "reason"))
    }
}
