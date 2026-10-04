package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

internal class MemoryContextPruningStorage : ContextPruningStorage {
    var value: String? = null
    var writes = 0
    var failWrite = false
    var failReadBack = false
    override fun <T> locked(write: Boolean, block: () -> T): T = synchronized(this) { block() }
    override fun read(): String? = if (failReadBack && writes > 0) error("synthetic read-back failure") else value
    override fun write(value: String) {
        if (failWrite) error("synthetic write failure")
        writes++
        this.value = value
    }
}

class ContextPruningRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(storage: ContextPruningStorage, state: ContextPruningState = pruningTestState()) =
        ContextPruningRepository(state.assistantId, state.conversationId, storage, now = { 1 })

    @Test fun `apply is idempotent restore is reversible and an explicit new preview can prune again`() {
        val storage = MemoryContextPruningStorage()
        val repo = repository(storage)
        val messages = pruningTestMessages()
        val plan = repo.preview(messages, ContextPruningMode.BOTH, 100)
        val batch = repo.apply(messages, ContextPruningMode.BOTH, 100, plan.planId)
        assertEquals(batch, repo.apply(messages, ContextPruningMode.BOTH, 100, plan.planId))
        assertEquals(1, storage.writes)
        assertEquals(1, repo.snapshot().batches.size)
        assertFalse(storage.value!!.contains("synthetic thinking"))
        assertFalse(storage.value!!.contains("synthetic tool result"))
        assertFalse(storage.value!!.contains("file:/synthetic"))
        assertTrue(repo.restore(batch.id).restored)
        assertEquals(2, storage.writes)
        assertSame(messages, projectContextPruningForRequest(messages, repo.snapshot()))
        assertTrue(repo.restore(batch.id).restored)
        assertEquals(2, storage.writes)
        val next = repo.preview(messages, ContextPruningMode.BOTH, 100)
        assertNotEquals(plan.planId, next.planId)
        assertNotEquals(batch.id, repo.apply(messages, ContextPruningMode.BOTH, 100, next.planId).id)
    }

    @Test fun `stale preview changed mode and owner mismatches fail without writing`() {
        val storage = MemoryContextPruningStorage()
        val state = pruningTestState()
        val repo = repository(storage, state)
        val messages = pruningTestMessages()
        val plan = repo.preview(messages, ContextPruningMode.BOTH, 100)
        assertThrows(IllegalStateException::class.java) { repo.apply(messages, ContextPruningMode.TOOLS, 100, plan.planId) }
        assertThrows(IllegalStateException::class.java) { repo.apply(messages + me.rerere.ai.ui.UIMessage.user("new wake"), ContextPruningMode.BOTH, 100, plan.planId) }
        assertEquals(0, storage.writes)
        storage.value = contextPruningJson.encodeToString(state.copy(assistantId = Uuid.random().toString()))
        assertThrows(IllegalStateException::class.java) { repo.snapshot() }
        assertEquals(0, storage.writes)
    }

    @Test fun `failed write preserves old policy and unverified write cannot report success`() {
        val storage = MemoryContextPruningStorage()
        val repo = repository(storage)
        val messages = pruningTestMessages()
        val plan = repo.preview(messages, ContextPruningMode.BOTH, 100)
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { repo.apply(messages, ContextPruningMode.BOTH, 100, plan.planId) }
        assertNull(storage.value)
        assertTrue(repo.states.value.batches.isEmpty())
        storage.failWrite = false; storage.failReadBack = true
        assertThrows(IllegalStateException::class.java) { repo.apply(messages, ContextPruningMode.BOTH, 100, plan.planId) }
        assertNotNull(storage.value) // The caller must inspect, not claim that an uncertain write never happened.
        assertTrue(repo.states.value.batches.isEmpty())
        storage.failReadBack = false
        assertEquals(1, repo.snapshot().batches.size)
    }

    @Test fun `corrupt deep or oversized imported policy fails closed without deleting original`() {
        val storage = MemoryContextPruningStorage()
        val repo = repository(storage)
        for (bad in listOf("not-json", "[".repeat(40) + "0" + "]".repeat(40), " ".repeat(MAX_CONTEXT_PRUNING_BYTES + 1))) {
            storage.value = bad
            assertThrows(IllegalStateException::class.java) { repo.snapshot() }
            assertEquals(bad, storage.value)
        }
        assertEquals(0, storage.writes)
    }

    @Test fun `ambiguous duplicated or invalid part identities are rejected in stored policy`() {
        val messages = pruningTestMessages()
        val state = appliedState(messages)
        val storage = MemoryContextPruningStorage()
        val repo = repository(storage, state)
        val batch = state.batches.single()
        for (invalid in listOf(state.copy(batches = listOf(batch, batch)),
            state.copy(batches = listOf(batch.copy(marks = batch.marks.map { it.copy(toolIndexes = listOf(-1)) }))),
            state.copy(version = 99))) {
            storage.value = contextPruningJson.encodeToString(invalid)
            assertThrows(IllegalStateException::class.java) { repo.snapshot() }
        }
        assertEquals(0, storage.writes)
    }

    @Test fun `file policy survives reopening while snapshots of an absent policy do not create directories`() {
        val root = File(temporary.root, "new-policy")
        val state = pruningTestState()
        val repo = repository(FileContextPruningStorage(root, temporary.root), state)
        assertTrue(repo.snapshot().batches.isEmpty())
        assertFalse(root.exists())
        val messages = pruningTestMessages()
        val preview = repo.preview(messages, ContextPruningMode.BOTH, 100)
        val batch = repo.apply(messages, ContextPruningMode.BOTH, 100, preview.planId)
        val reopened = repository(FileContextPruningStorage(root, temporary.root), state)
        assertEquals(batch, reopened.snapshot().batches.single())
        assertTrue(reopened.restore(batch.id).restored)
        assertTrue(repo.snapshot().batches.single().restored)
        assertEquals(setOf("policy.json", ".lock"), root.list()!!.toSet())
    }

    @Test fun `invalid UTF8 and oversized policy files are preserved and refused`() {
        val root = temporary.newFolder()
        val file = File(root, "policy.json")
        val repo = repository(FileContextPruningStorage(root, temporary.root))
        Files.write(file.toPath(), byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(Exception::class.java) { repo.snapshot() }
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), Files.readAllBytes(file.toPath()))
        Files.write(file.toPath(), ByteArray(MAX_CONTEXT_PRUNING_BYTES + 1))
        assertThrows(IllegalStateException::class.java) { repo.snapshot() }
        assertEquals((MAX_CONTEXT_PRUNING_BYTES + 1).toLong(), file.length())
    }

    @Test fun `policy schema never contains original message content`() {
        val storage = MemoryContextPruningStorage()
        val repo = repository(storage)
        val messages = pruningTestMessages()
        val preview = repo.preview(messages, ContextPruningMode.BOTH, 100)
        repo.apply(messages, ContextPruningMode.BOTH, 100, preview.planId)
        assertTrue(storage.value!!.contains("contentHash"))
        assertFalse(storage.value!!.contains("old round"))
        assertFalse(storage.value!!.contains("workspace_read_file"))
        assertFalse(storage.value!!.contains("synthetic final prose"))
    }

    @Test fun `restoring tool batch also keeps reasoning that is required beside those tools`() {
        val repo = repository(MemoryContextPruningStorage())
        val messages = pruningTestMessages()
        val tools = repo.preview(messages, ContextPruningMode.TOOLS, 100)
        val toolBatch = repo.apply(messages, ContextPruningMode.TOOLS, 100, tools.planId)
        val thoughts = repo.preview(messages, ContextPruningMode.REASONING, 100)
        assertEquals(1, thoughts.reasoningCount)
        repo.apply(messages, ContextPruningMode.REASONING, 100, thoughts.planId)
        assertEquals(1, projectContextPruningForRequest(messages, repo.snapshot())[1].parts.size)
        repo.restore(toolBatch.id)
        assertSame(messages, projectContextPruningForRequest(messages, repo.snapshot()))
        assertTrue(projectContextPruningForDisplay(messages[1], repo.snapshot()).hiddenReasoningIndexes.isEmpty())
    }

    @Test fun `separate repository instances serialize identical first write into one durable batch`() {
        val root = File(temporary.root, "shared-policy")
        val state = pruningTestState()
        val one = repository(FileContextPruningStorage(root, temporary.root), state)
        val two = repository(FileContextPruningStorage(root, temporary.root), state)
        val messages = pruningTestMessages()
        val plan = one.preview(messages, ContextPruningMode.BOTH, 100)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(one, two).map { repo -> executor.submit<ContextPruningBatch> {
                repo.apply(messages, ContextPruningMode.BOTH, 100, plan.planId)
            } }
            assertEquals(futures[0].get().id, futures[1].get().id)
            assertEquals(1, one.snapshot().batches.size)
        } finally { executor.shutdownNow() }
    }
}
