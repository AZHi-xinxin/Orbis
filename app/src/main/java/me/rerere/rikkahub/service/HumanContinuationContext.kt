package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.ai.checkpoint.ContinuationStreamEnrichment
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Proof-only view: browsing alternatives cannot erase an old receipt. Every body/version
 * stays exactly as persisted; only restore the journal owner's original selection for its
 * containment check. Never save this view or use it as a new model request.
 */
internal fun Conversation.withCheckpointSelections(baseline: Conversation): Conversation {
    require(id == baseline.id && assistantId == baseline.assistantId && compactionEpoch == baseline.compactionEpoch)
    val selections = baseline.messageNodes.associate { it.id to it.currentMessage.id }
    return copy(messageNodes = messageNodes.map { node ->
        val index = node.messages.indexOfFirst { it.id == selections[node.id] }
        if (index < 0) node else node.copy(selectIndex = index)
    })
}

/** Maps an in-flight request's OLD context onto a committed summary without joining that
 * request. New input/replies appended during the local transaction remain after the summary.
 * Only this exact prefix can be replaced; stale shorter snapshots cannot resurrect history.
 */
internal class ContinuationContextRebase(before: Conversation, private val after: Conversation) {
    private val conversationId = before.id
    private val owner = before.assistantId
    val fromEpoch = before.compactionEpoch
    private val prefix = before.messageNodes.map { it.id to it.currentMessage.id }

    init {
        require(before.id == after.id && before.assistantId == after.assistantId)
        require(after.compactionEpoch == Math.addExact(fromEpoch, 1L))
    }

    fun project(snapshot: Conversation): Conversation? {
        if (snapshot.id != conversationId || snapshot.assistantId != owner ||
            snapshot.compactionEpoch != fromEpoch || snapshot.messageNodes.size < prefix.size ||
            snapshot.messageNodes.take(prefix.size).zip(prefix).any { (node, original) ->
                node.id != original.first || node.messages.none { it.id == original.second }
            }) return null
        return snapshot.copy(messageNodes = after.messageNodes + snapshot.messageNodes.drop(prefix.size),
            compactionEpoch = after.compactionEpoch, chatSuggestions = emptyList())
    }
}

/** Two independent writers as before: a stuck Room write cannot hold the recovery copy.
 * A compaction is a local storage transaction, never a wait for an old tool/network request.
 * Only during that transaction are both storage anchors advanced together. Chat generation
 * does not acquire these locks and can append/publish while the commit is in flight.
 */
internal class HumanContinuationContextStore(
    baseline: Conversation,
    durableBaseline: Conversation,
    private val runId: Uuid,
    private val writeMutex: Mutex,
    private val journal: HumanContinuationJournal,
    private val read: suspend () -> Conversation?,
    private val proveBaseline: (Conversation, Conversation) -> Boolean,
    private val commit: suspend (Conversation, Conversation) -> Conversation?,
    private val canWrite: () -> Boolean,
    private val project: (Conversation) -> Conversation?,
    private val latest: () -> Conversation,
    private val onStored: (Conversation) -> Unit,
    private val onRebased: (Conversation, Conversation) -> Unit,
    private val retireOldJournal: (Conversation) -> Unit,
    private val reconcileStream: (Conversation, Conversation) -> ContinuationStreamEnrichment? = { _, _ -> null },
    private val onEnriched: (ContinuationStreamEnrichment) -> Unit = {},
) {
    private var lastSaved = durableBaseline // writeMutex
    private var checkpointBaseline = baseline // checkpointMutex
    private val checkpointMutex = Mutex()

    suspend fun save(snapshot: Conversation): Boolean = writeMutex.withLock {
        if (!canWrite()) return@withLock false
        val current = project(snapshot) ?: return@withLock false
        saveLocked(current) != null
    }

    private suspend fun saveLocked(snapshot: Conversation): Conversation? {
        val proof: suspend (Conversation, Conversation) -> Unit = { disk, candidate ->
            journal.prepareRoomCommit(runId, checkpointBaseline, disk, candidate)
        }
        fun proved(disk: Conversation, expected: Conversation): Boolean =
            journal.provesRoomWrite(runId, disk, snapshot) || proveBaseline(disk, expected)
        val direct = commitHumanContinuationSnapshot(lastSaved, snapshot, read, ::proved, commit, proof)
        val stored = direct ?: run {
            val disk = read() ?: return null
            val enrichment = reconcileStream(disk, lastSaved) ?: return null
            val enriched = enrichment.project(snapshot) ?: return null
            val saved = commitHumanContinuationSnapshot(enrichment.provenBaseline, enriched, read, proveBaseline, commit, proof)
                ?: return null
            onEnriched(enrichment) // All late publishers retain the added durable suffix too.
            saved
        }
        return stored.also {
            lastSaved = it
            onStored(it)
        }
    }

    suspend fun checkpoint(snapshot: Conversation): Boolean = checkpointMutex.withLock {
        val current = project(snapshot) ?: return@withLock false
        journal.checkpoint(runId, checkpointBaseline, current)
        true
    }

    suspend fun compact(
        capture: () -> Conversation,
        rewrite: suspend (Conversation, Conversation) -> Conversation,
    ): Conversation {
        // Contention is a tool-local failure, not a five-minute admission lock on the next send.
        withTimeout(1_500) { writeMutex.lock() }
        try {
            withTimeout(1_500) { checkpointMutex.lock() }
            try {
                check(canWrite()) { "本次本地整理未提交，聊天可继续。" }
                val before = capture() // Recheck turn ownership AFTER waiting for local writers.
                return withContext(NonCancellable) {
                    val stored = checkNotNull(saveLocked(before)) { "本次整理未提交，原消息保留。" }
                    val completeBefore = checkNotNull(project(before))
                    // The complete source is now in Room. Refresh the exact recovery tail before
                    // retiring it; never delete an unseen newer tail or leave an old epoch behind.
                    retireOldJournal(stored)
                    journal.checkpoint(runId, checkpointBaseline, completeBefore)
                    check(journal.clearCommitted(runId, stored)) { "本次整理未提交，原消息保留。" }
                    try {
                        val after = rewrite(completeBefore, stored)
                        lastSaved = after
                        checkpointBaseline = after
                        onRebased(completeBefore, after)
                        onStored(after)
                        after
                    } finally {
                        // Reopen the recovery copy against the new anchor (or original anchor
                        // after rollback), including human input that arrived during the commit.
                        // A failure here must NOT turn an already committed compact into failure.
                        runCatching { journal.checkpoint(runId, checkpointBaseline, latest()) }
                    }
                }
            } finally {
                checkpointMutex.unlock()
            }
        } finally {
            writeMutex.unlock()
        }
    }
}
