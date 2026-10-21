package me.rerere.rikkahub.data.orbis.memory

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface SemanticRecall {
    suspend fun recall(owner: String, messages: List<UIMessage>): List<OrbisMemorySelected>
    suspend fun validate(owner: String, entries: List<OrbisMemorySelected>): List<OrbisMemorySelected>
}

/**
 * Indexing and native inference are independent of the chat job. Await has a hard deadline even
 * if a native call cannot immediately cooperate with cancellation. At most one query is in flight;
 * no human message waits for a model load, a full reindex, or another query.
 */
internal class SemanticMemoryRecall(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: SettingsStore,
    private val embeddingFactory: () -> MemoryEmbedding = { LocalMemoryEmbedding(context) },
    private val queryBudgetMs: Long = 150,
) : SemanticRecall, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        // This optional background worker must not reach Android's fatal uncaught handler.
        // Fail closed, retaining canonical data; a later app start can rebuild the derived index.
        snapshots.set(emptyMap())
    })
    private val started = AtomicBoolean()
    private val querying = AtomicBoolean()
    private val modelLock = Mutex()
    private val snapshots = AtomicReference<Map<String, List<SemanticMemoryChunk>>>(emptyMap())
    private val sources = SemanticMemorySources(database)
    private val cache = MemoryVectorCache(File(context.cacheDir, "orbis-semantic-v1"))
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var model: MemoryEmbedding? = null
    internal fun indexedCount(owner: String) = snapshots.get()[owner].orEmpty().size

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settings.settingsFlow.map { state ->
                if (state.init) emptyList() else state.assistants.filter(::enabled).map { it.id.toString() }
            }.distinctUntilChanged().collect { owners ->
                snapshots.updateAndGet { it.filterKeys(owners::contains) }
                refresh.trySend(Unit)
            }
        }
        scope.launch {
            // Ordinary streamed message/header writes must not continually restart an index scan.
            // Ownership is rechecked live before every projection; event deletion invalidates here.
            database.invalidationTracker.createFlow("orbis_memory_note", "orbis_compaction_event")
                .collect { refresh.trySend(Unit) }
        }
        scope.launch {
            for (ignored in refresh) {
                delay(300)
                try { rebuild() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Keep validated previous index. Next change retries. */ }
                catch (_: LinkageError) { /* Unsupported native ABI: optional recall stays unavailable. */ }
            }
        }
    }

    private fun ownerEnabled(owner: String) = settings.settingsFlow.value.let { state ->
        !state.init && state.assistants.any { it.id.toString() == owner && enabled(it) }
    }

    private suspend fun rebuild() {
        val owners = settings.settingsFlow.value.assistants.filter(::enabled).map { it.id.toString() }
        if (owners.isEmpty()) return
        if (model == null) modelLock.withLock { if (model == null) model = embeddingFactory() }
        for (owner in owners) {
            val chunks = mutableListOf<SemanticMemoryChunk>()
            var offset = 0
            // Bounded resident index, newest first. Older canonical records remain searchable by tool.
            while (chunks.size < 8192 && ownerEnabled(owner)) {
                currentCoroutineContext().ensureActive()
                val events = sources.page(owner, offset)
                if (events.isEmpty()) break
                for (event in events) {
                    if (!ownerEnabled(owner)) break
                    val source = sources.read(owner, event.id) ?: continue
                    val text = source.note.summary.ifBlank { source.note.body }
                    val slices = semanticMemorySlices(text)
                    if (slices.size > 8192 - chunks.size) continue
                    val fingerprint = memoryDigest("${source.note.revision}:$text")
                    val cached = cache.read(owner, source.note.id, fingerprint, slices.size)
                    val vectors = cached ?: slices.map { slice ->
                        if (!ownerEnabled(owner)) return
                        // Yield between small chunks; a query has priority over the next index chunk.
                        while (querying.get()) delay(10)
                        currentCoroutineContext().ensureActive()
                        modelLock.withLock { checkNotNull(model).encode(slice, query = false) }.also { delay(8) }
                    }.also { value -> runCatching { cache.write(owner, source.note.id, fingerprint, value) } }
                    chunks += slices.mapIndexed { i, slice -> SemanticMemoryChunk(owner, source.note.id,
                        source.note.revision, event.id, event.createdAt, i, slice, vectors[i]) }
                    // Publish incrementally, so a large old archive does not have to finish first.
                    snapshots.updateAndGet { it + (owner to chunks.toList()) }
                }
                offset += events.size
            }
            snapshots.updateAndGet { if (ownerEnabled(owner)) it + (owner to chunks.toList()) else it - owner }
        }
    }

    override suspend fun recall(owner: String, messages: List<UIMessage>): List<OrbisMemorySelected> {
        start()
        val query = semanticRecallQuery(messages) ?: return emptyList()
        val snapshot = snapshots.get()[owner].orEmpty()
        if (snapshot.isEmpty() || model == null || !ownerEnabled(owner) || !querying.compareAndSet(false, true))
            return emptyList()
        val pending = scope.async {
            try {
                val vector = modelLock.withLock {
                    currentCoroutineContext().ensureActive()
                    checkNotNull(model).encode(query, query = true)
                }
                currentCoroutineContext().ensureActive()
                val selected = rankSemanticMemory(owner, vector, snapshot).map { chunk ->
                    OrbisMemorySelected(chunk.noteId, chunk.text, chunk.revision, "compaction_excerpt",
                        chunk.eventId, chunk.createdAt)
                }
                validate(owner, selected)
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { emptyList()
            } catch (_: LinkageError) { emptyList()
            }
        }
        pending.invokeOnCompletion { querying.set(false) }
        return try { withTimeoutOrNull(queryBudgetMs) { pending.await() } ?: emptyList() }
        finally { pending.cancel() }
    }

    override fun close() {
        scope.coroutineContext[Job]!!.invokeOnCompletion { model?.close(); model = null }
        scope.cancel()
        snapshots.set(emptyMap())
    }

    override suspend fun validate(owner: String, entries: List<OrbisMemorySelected>): List<OrbisMemorySelected> {
        if (!ownerEnabled(owner)) return emptyList()
        return entries.take(3).mapNotNull { selected ->
            val source = selected.sourceEventId?.let { sources.read(owner, it, selected.id) } ?: return@mapNotNull null
            if (source.note.revision != selected.revision ||
                !source.note.summary.ifBlank { source.note.body }.contains(selected.text)) null
            else selected.copy(minIntervalTurns = source.note.minIntervalTurns)
        }
    }

    companion object {
        fun enabled(assistant: Assistant) = assistant.orbisMemoryAutoInject &&
            assistant.orbisMemoryMode != OrbisMemoryMode.LIGHT && assistant.orbisSemanticRecall
    }
}
