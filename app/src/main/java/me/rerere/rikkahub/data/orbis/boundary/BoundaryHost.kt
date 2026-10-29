package me.rerere.rikkahub.data.orbis.boundary

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import me.rerere.ai.provider.ModelAccessContext

class BoundaryUnavailable(message: String) : CancellationException(message)

/** One host ledger shared by native UI, Web and generation. No credentials are stored here. */
class BoundaryHost private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "orbis-boundaries/state.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(read())
    val states = mutable.asStateFlow()
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "orbis-boundary-time").apply { isDaemon = true } }
    private var pendingExpiry: java.util.concurrent.ScheduledFuture<*>? = null
    init { scheduleExpiry() }
    @Synchronized private fun scheduleExpiry() {
        pendingExpiry?.cancel(false)
        val due = mutable.value.values.flatMap { it.conversations.values + listOfNotNull(it.assistantPause) }.filter { it.paused }.mapNotNull { it.until }.minOrNull() ?: return
        pendingExpiry = timer.schedule({ expire() }, (due - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun expire() {
        val now = System.currentTimeMillis()
        mutable.value.toMap().forEach { (owner, state) ->
            val due = state.conversations.filterValues { it.paused && it.until != null && it.until <= now }
            val assistantDue = state.assistantPause?.let { it.paused && it.until != null && it.until <= now } == true
            if (due.isNotEmpty() || assistantDue) update(owner) { old -> old.copy(
                assistantPause = if (assistantDue) old.assistantPause?.copy(paused = false) else old.assistantPause,
                conversations = old.conversations.mapValues { (id, pause) ->
                    if (id in due) pause.copy(paused = false) else pause
                }).event(now, "ai", "timed_resume", (due.keys + if (assistantDue) setOf("assistant") else emptySet()).joinToString(",")) }
        }
        scheduleExpiry()
    }
    private fun read(): Map<String, AssistantBoundary> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyMap()
        // A damaged ledger must not silently turn an AI's choices into permission to run.
        val stored = json.decodeFromString<Map<String, AssistantBoundary>>(
            file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val now = System.currentTimeMillis()
        val compact = stored.mapValues { (_, state) -> state.restoreBoundedPauses(now) }
        // Persist bounded legacy deadlines and release orphaned claims, retaining all capability choices.
        if (compact != stored) write(compact)
        return compact
    }
    private fun write(all: Map<String, AssistantBoundary>) {
        file.baseFile.parentFile!!.mkdirs()
        val stream = file.startWrite()
        try { stream.write(json.encodeToString(all).toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    @Synchronized fun state(owner: String): AssistantBoundary = mutable.value[owner] ?: AssistantBoundary()
    @Synchronized fun update(owner: String, change: (AssistantBoundary) -> AssistantBoundary): AssistantBoundary {
        val changed = change(state(owner))
        val next = changed.copy(events = changed.events.takeLast(BOUNDARY_HISTORY_LIMIT))
        val all = mutable.value + (owner to next)
        write(all)
        mutable.value = all
        scheduleExpiry()
        return next
    }
    fun grant(owner: String, grants: Set<String>) = update(owner) {
        require(grants.all { category -> category in BOUNDARY_CATEGORIES })
        it.copy(grants = grants).event(System.currentTimeMillis(), "human", "grant", "")
    }
    fun reset(owner: String, conversation: String? = null) = update(owner) {
        (if (conversation == null) AssistantBoundary(grants = it.grants, revision = it.revision, events = it.events)
         else it.copy(conversations = it.conversations - conversation))
            .event(System.currentTimeMillis(), "human", "reset", conversation.orEmpty())
    }
    fun pause(owner: String, conversation: String?) = state(owner).pause(conversation, System.currentTimeMillis())
    fun permits(owner: String, kind: String, id: String) = state(owner).permits(kind, id)
    @Synchronized fun recoveryPending(owner: String, conversation: String?, revision: Long): Boolean {
        val pause = pause(owner, conversation) ?: return false
        return pause.allowRequest && pause.requestUsed && pause.revision == revision
    }
    @Synchronized fun requireInput(owner: String, conversation: String?, recoveryRevision: Long? = null, wake: BoundaryWakePermit? = null) {
        if (recoveryRevision != null) {
            if (!recoveryPending(owner, conversation, recoveryRevision))
                throw BoundaryUnavailable("本次继续申请的约定已变化")
        } else if (pause(owner, conversation) != null && !permitsWake(wake, owner, conversation))
            throw BoundaryUnavailable("此对话已由对方暂停")
    }
    fun permitsWake(wake: BoundaryWakePermit?, owner: String, conversation: String?): Boolean =
        wake?.permits(state(owner), owner, conversation, System.currentTimeMillis()) == true
    fun requestContext(owner: String, conversation: String? = null, recoveryRevision: Long? = null, wake: BoundaryWakePermit? = null) =
        ModelAccessContext { model ->
            synchronized(this) {
                val state = state(owner)
                if (!state.permits("model", model)) throw BoundaryUnavailable("此模型已被当前 AI 停用")
                val pause = state.pause(conversation, System.currentTimeMillis())
                if (pause != null && !(pause.allowRequest && pause.requestUsed && pause.revision == recoveryRevision) && !permitsWake(wake, owner, conversation))
                    throw BoundaryUnavailable("此对话已由对方暂停")
            }
        }
    @Synchronized fun claimRecovery(owner: String, conversation: String, revision: Long): Long {
        val current = state(owner)
        val pause = current.pause(conversation, System.currentTimeMillis()) ?: error("boundary_not_paused")
        require(pause.revision == revision && pause.allowRequest && !pause.requestUsed) { "boundary_request_unavailable" }
        require(pause.requestsRemaining > 0) { "本次暂停的3次继续申请已用完，请等待约定时间自动恢复。" }
        val claimed = pause.copy(requestUsed = true, requestCount = pause.requestCount.coerceAtLeast(0) + 1)
        update(owner) {
            (if (pause.scope == "assistant") it.copy(assistantPause = claimed)
                else it.copy(conversations = it.conversations + (conversation to claimed)))
                .event(System.currentTimeMillis(), "human", "request_resume", conversation)
        }
        return revision
    }
    /** Called only after the owning generation has ended. A successful decision already replaced this pause. */
    @Synchronized fun finishRecovery(owner: String, conversation: String, revision: Long) {
        val current = state(owner)
        // Another scope may now cover this pause, or its deadline may have elapsed.
        // Release only the claim owned by this completed job, even when it is no longer visible.
        val pause = current.assistantPause?.takeIf { it.revision == revision && it.requestUsed }
            ?: current.conversations[conversation]?.takeIf { it.revision == revision && it.requestUsed }
            ?: return
        update(owner) {
            (if (pause.scope == "assistant") it.copy(assistantPause = pause.copy(requestUsed = false))
                else it.copy(conversations = it.conversations + (conversation to pause.copy(requestUsed = false))))
                .event(System.currentTimeMillis(), "system", "request_without_decision", conversation)
        }
    }
    companion object {
        @Volatile private var instance: BoundaryHost? = null
        fun getIfInitialized(): BoundaryHost? = instance
        fun get(context: Context): BoundaryHost = instance ?: synchronized(this) {
            instance ?: BoundaryHost(context.applicationContext).also { instance = it }
        }
    }
}
