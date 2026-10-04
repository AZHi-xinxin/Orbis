package me.rerere.rikkahub.data.orbis.voice

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceArchiveOwnershipTest {
    private class Storage : OrbisVoiceCallStorage {
        override val lockKey = UUID.randomUUID().toString()
        val data = mutableMapOf<String, String>()
        var writes = 0
        var failAfterWrite = false
        override fun ids() = data.keys.toList()
        override fun read(id: String) = data[id]
        override fun write(id: String, value: String) {
            data[id] = value; writes++
            if (failAfterWrite) throw IOException("synthetic post-write")
        }
    }
    private val source = OrbisVoiceCallRecord("call", "chat", "assistant", 1, connectedAtMs = 2,
        endedAtMs = 3, durationMs = 1, status = OrbisVoiceCallStatus.ENDED,
        transcript = listOf(OrbisVoiceTranscriptEntry("speech", "USER", "明天一起看海。🙂", 2)))
    private val archive = OrbisVoiceModelArchive("约好明天一起看海。", "用户：明天一起看海。🙂")
    private val digest = voiceArchiveSourceDigest(source)
    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }

    @Test fun `claim is durable idempotent and preserves raw source`() = runTest {
        val storage = Storage(); val repo = OrbisVoiceCallRepository(storage); repo.create(source)
        val first = repo.claimArchiveAttempt("call", "assistant", digest, "attempt-a", OrbisVoiceArchiveAuthor.ASSISTANT, "model")!!
        assertEquals(1, first.archiveRequestCount)
        assertEquals(source.transcript, first.transcript)
        assertEquals(first, OrbisVoiceCallRepository(storage).get("call"))
        val writes = storage.writes
        assertEquals(first, repo.claimArchiveAttempt("call", "assistant", digest, "attempt-a", OrbisVoiceArchiveAuthor.ASSISTANT, "model"))
        assertEquals(writes, storage.writes)
        assertNull(repo.claimArchiveAttempt("call", "assistant", digest, "unrelated", OrbisVoiceArchiveAuthor.ASSISTANT, "model"))
    }

    @Test fun `failed primary permits exact fallback while late primary can never overwrite winner`() = runTest {
        val repo = OrbisVoiceCallRepository(Storage()); repo.create(source)
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        repo.failArchiveAttempt("call", "assistant", digest, "primary", "request_failed")
        assertNull(repo.claimArchiveAttempt("call", "assistant", digest, "bad", OrbisVoiceArchiveAuthor.FALLBACK, "external", "wrong"))
        repo.claimArchiveAttempt("call", "assistant", digest, "fallback", OrbisVoiceArchiveAuthor.FALLBACK, "external", "primary")
        assertNull(repo.completeArchiveAttempt("call", "assistant", digest, "primary", archive))
        val ready = repo.completeArchiveAttempt("call", "assistant", digest, "fallback", archive)!!
        assertEquals(OrbisVoiceArchiveAuthor.FALLBACK, ready.archiveAuthor)
        assertEquals(2, ready.archiveRequestCount)
        assertNull(repo.failArchiveAttempt("call", "assistant", digest, "primary", "request_failed"))
        assertNull(repo.failArchiveAttempt("call", "assistant", digest, "fallback", "request_failed"))
        assertEquals(ready, repo.get("call"))
        assertNull(repo.claimArchiveAttempt("call", "assistant", digest, "late", OrbisVoiceArchiveAuthor.ASSISTANT, "model"))
    }

    @Test fun `durable failure itself invalidates cancellation resistant response before fallback starts`() = runTest {
        val repo = OrbisVoiceCallRepository(Storage()); repo.create(source)
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        repo.failArchiveAttempt("call", "assistant", digest, "primary", "archive_timeout")
        assertNull(repo.completeArchiveAttempt("call", "assistant", digest, "primary", archive))
        assertEquals(OrbisVoiceArchiveStatus.FAILED, repo.get("call")!!.archiveStatus)
    }

    @Test fun `shared disk mutex permits just one new active request across repository instances`() = runTest {
        val storage = Storage(); val first = OrbisVoiceCallRepository(storage); val second = OrbisVoiceCallRepository(storage)
        first.create(source)
        val claims = listOf(async { first.claimArchiveAttempt("call", "assistant", digest, "a", OrbisVoiceArchiveAuthor.ASSISTANT, "model") },
            async { second.claimArchiveAttempt("call", "assistant", digest, "b", OrbisVoiceArchiveAuthor.ASSISTANT, "model") }).awaitAll()
        assertEquals(1, claims.count { it != null })
        assertEquals(1, first.get("call")!!.archiveRequestCount)
    }

    @Test fun `source change rejects stale summary and failure unlocks the source without rewriting it`() = runTest {
        val repo = OrbisVoiceCallRepository(Storage()); repo.create(source)
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        val changed = repo.update("call") { it.copy(transcript = it.transcript + OrbisVoiceTranscriptEntry("late", "ASSISTANT", "我记住了。", 3)) }
        assertNull(repo.completeArchiveAttempt("call", "assistant", digest, "primary", archive))
        val failed = repo.failArchiveAttempt("call", "assistant", digest, "primary", "request_failed")!!
        assertEquals("archive_source_changed", failed.archiveFailureCode)
        assertEquals(changed.transcript, failed.transcript)
        assertEquals(OrbisVoiceArchiveStatus.FAILED, failed.archiveStatus)
        fails { repo.submitAssistantArchive("call", "assistant", digest, "receipt", archive) }
    }

    @Test fun `manual assistant publication is idempotent and cannot replace READY with another receipt`() = runTest {
        val storage = Storage(); val repo = OrbisVoiceCallRepository(storage); repo.create(source)
        val ready = repo.submitAssistantArchive("call", "assistant", digest, "receipt-1", archive)
        val writes = storage.writes
        assertEquals(OrbisVoiceArchiveAuthor.ASSISTANT_TOOL, ready.archiveAuthor)
        assertEquals("receipt-1", ready.archiveReceiptId)
        assertEquals(0, ready.archiveRequestCount)
        assertEquals(ready, repo.submitAssistantArchive("call", "assistant", digest, "receipt-1", archive))
        assertEquals(writes, storage.writes)
        fails { repo.submitAssistantArchive("call", "assistant", digest, "receipt-2", archive) }
        fails { repo.submitAssistantArchive("call", "assistant", digest, "receipt-1", archive.copy(summary = "另一份总结")) }
        assertEquals(source.transcript, ready.transcript)
    }

    @Test fun `manual assistant cannot steal active attempt or cross assistant ownership`() = runTest {
        val repo = OrbisVoiceCallRepository(Storage()); repo.create(source)
        fails { repo.submitAssistantArchive("call", "other", digest, "receipt", archive) }
        fails { repo.claimArchiveAttempt("call", "other", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model") }
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        fails { repo.submitAssistantArchive("call", "assistant", digest, "receipt", archive) }
        assertEquals("primary", repo.get("call")!!.archiveAttemptId)
    }

    @Test fun `postcommit storage failure can be verified idempotently without duplicate summary or request`() = runTest {
        val storage = Storage(); val repo = OrbisVoiceCallRepository(storage); repo.create(source)
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        storage.failAfterWrite = true
        fails { repo.completeArchiveAttempt("call", "assistant", digest, "primary", archive) }
        storage.failAfterWrite = false
        val writes = storage.writes
        assertNotNull(repo.completeArchiveAttempt("call", "assistant", digest, "primary", archive))
        assertEquals(writes, storage.writes)
        assertEquals(1, repo.get("call")!!.archiveRequestCount)
    }

    @Test fun `process recovery leaves attempt failed and cannot accidentally accept its late completion`() = runTest {
        val storage = Storage(); val repo = OrbisVoiceCallRepository(storage); repo.create(source)
        repo.claimArchiveAttempt("call", "assistant", digest, "primary", OrbisVoiceArchiveAuthor.ASSISTANT, "model")
        val restarted = OrbisVoiceCallRepository(storage)
        restarted.recoverInterrupted()
        assertNull(restarted.completeArchiveAttempt("call", "assistant", digest, "primary", archive))
        assertEquals(source.transcript, restarted.get("call")!!.transcript)
        assertEquals("process_interrupted_no_auto_retry", restarted.get("call")!!.archiveFailureCode)
    }

    @Test fun `legacy READY provenance is unknown not retroactively claimed by current assistant`() = runTest {
        val repo = OrbisVoiceCallRepository(Storage())
        val old = source.copy(archiveStatus = OrbisVoiceArchiveStatus.READY, summary = archive.summary, modelTranscript = archive.transcript)
        repo.create(old)
        assertEquals("旧记录未标注整理来源", old.archiveAuthorLabel())
        fails { repo.update("call") { it.copy(archiveAuthor = OrbisVoiceArchiveAuthor.ASSISTANT) } }
        assertEquals(old, repo.get("call"))
    }

    @Test fun `invalid source and oversized submit do not begin or write an archive`() = runTest {
        val storage = Storage(); val repo = OrbisVoiceCallRepository(storage); repo.create(source)
        fails { repo.submitAssistantArchive("call", "assistant", "0".repeat(64), "receipt", archive) }
        fails { repo.submitAssistantArchive("call", "assistant", digest, "receipt", archive.copy(summary = "x".repeat(65537))) }
        assertEquals(1, storage.writes)
        assertNull(repo.get("call")!!.summary)
    }
}
