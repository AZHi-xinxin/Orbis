package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable internal data class AgentStep(val title: String, val state: String = "pending")
@Serializable internal data class AgentReceipt(val callId: String, val tool: String, val state: String, val at: Long)
@Serializable internal data class AgentTask(
    val id: String, val conversation: String, val title: String,
    val steps: List<AgentStep>, val revision: Int = 1, val state: String = "active",
    val summary: String = "", val receipts: List<AgentReceipt> = emptyList(), val updatedAt: Long,
)

/** Per assistant, atomic and bounded. Plans are AI statements; receipts come only from the host. */
internal class AgentTaskStore(private val root: File) {
    private val lock = locks.getOrPut(root.absolutePath) { Any() }
    val changes get() = signals.getOrPut(root.absolutePath) { MutableStateFlow(0L) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init { require(root.name.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    fun list(): List<AgentTask> = synchronized(lock) {
        val file = File(root, "tasks.json")
        if (!file.exists()) emptyList() else {
            check(file.length() in 1..2_000_000) { "task_store_invalid" }
            json.decodeFromString<List<AgentTask>>(file.readText()).also { check(it.size <= 100) }
        }
    }
    private fun save(tasks: List<AgentTask>) {
        check(root.canonicalFile == root.absoluteFile) { "task_store_invalid" }
        check(root.isDirectory || root.mkdirs())
        val bytes = json.encodeToString(tasks).toByteArray()
        check(bytes.size <= 2_000_000) { "task_store_full" }
        val target = File(root, "tasks.json")
        check(target.canonicalFile == target.absoluteFile)
        val temp = File(root, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        changes.value++
    }
    fun create(conversation: String, title: String, steps: List<AgentStep>, now: Long = System.currentTimeMillis(),
        state: String = "active", summary: String = ""): AgentTask = synchronized(lock) {
        UUID.fromString(conversation)
        require(title.isNotBlank() && title.length <= 120) { "task_invalid_title" }
        require(state in setOf("active", "waiting", "blocked")) { "task_invalid_initial_state" }
        require(summary.length <= 1000) { "task_invalid_summary" }
        validateSteps(steps)
        require(steps.all { it.state == "pending" }) { "task_create_steps_must_be_pending" }
        val all = list()
        check(all.none { it.conversation == conversation && it.state in openStates }) { "task_already_open" }
        check(all.size < 100) { "task_store_full" }
        AgentTask(UUID.randomUUID().toString(), conversation, title, steps, state = state, summary = summary,
            updatedAt = now).also { save(all + it) }
    }
    fun update(conversation: String, id: String, expected: Int, steps: List<AgentStep>, state: String, summary: String): AgentTask = synchronized(lock) {
        require(state in setOf("active", "waiting", "blocked", "completed")) { "task_invalid_state" }
        require(summary.length <= 1000) { "task_invalid_summary" }; validateSteps(steps)
        val all = list(); val old = all.firstOrNull { it.id == id && it.conversation == conversation } ?: error("task_missing")
        check(old.revision == expected) { "task_revision_conflict" }
        check(old.state in setOf("active", "waiting", "blocked")) { "task_not_editable" }
        check(state != "completed" || steps.all { it.state == "done" }) { "task_steps_incomplete" }
        old.copy(steps = steps, state = state, summary = summary, revision = old.revision + 1,
            updatedAt = System.currentTimeMillis()).also { next -> save(all.map { if (it.id == id) next else it }) }
    }
    /** Human controls only; AI cannot unpause, cancel or erase receipts. */
    fun control(id: String, expected: Int, action: String): AgentTask = synchronized(lock) {
        val all = list(); val old = all.firstOrNull { it.id == id } ?: error("task_missing")
        check(old.revision == expected) { "task_revision_conflict" }
        val state = when (action) {
            "pause" -> { check(old.state in setOf("active", "waiting", "blocked")); "paused" }
            "resume" -> { check(old.state == "paused"); "active" }
            "cancel" -> { check(old.state in openStates); "cancelled" }
            else -> error("task_invalid_action")
        }
        old.copy(state = state, revision = old.revision + 1, updatedAt = System.currentTimeMillis()).also { next ->
            save(all.map { if (it.id == id) next else it })
        }
    }
    fun receipt(taskId: String, callId: String, tool: String, state: String) = synchronized(lock) {
        require(state in setOf("started", "returned", "failed", "unknown"))
        val all = list(); val old = all.firstOrNull { it.id == taskId } ?: return@synchronized
        val prior = old.receipts.firstOrNull { it.callId == callId }
        // A completed call may never be changed back into an invitation to execute it again.
        if (prior != null && (state == "started" || prior.state != "started")) return@synchronized
        val next = old.copy(receipts = (old.receipts.filterNot { it.callId == callId } +
            AgentReceipt(callId.take(200), tool.take(200), state, System.currentTimeMillis())).takeLast(100),
            updatedAt = System.currentTimeMillis())
        save(all.map { if (it.id == old.id) next else it })
    }
    fun claim(taskId: String, callId: String, tool: String): Boolean = synchronized(lock) {
        require(callId.length in 1..200 && tool.length in 1..200)
        val task = list().firstOrNull { it.id == taskId } ?: error("task_missing")
        check(task.state in setOf("active", "waiting", "blocked", "completed")) { "task_paused_or_closed" }
        if (task.receipts.any { it.callId == callId }) return@synchronized false
        receipt(taskId,callId,tool,"started")
        true
    }
    fun active(conversation: String) = list().lastOrNull { it.conversation == conversation && it.state in openStates }
    fun requireRunnable(taskId: String) {
        check(list().firstOrNull { it.id == taskId }?.state in setOf("active", "waiting", "blocked", "completed")) { "task_paused_or_closed" }
    }
    companion object {
        val openStates = setOf("active", "waiting", "blocked", "paused")
        private val locks = ConcurrentHashMap<String, Any>()
        private val signals = ConcurrentHashMap<String, MutableStateFlow<Long>>()
        fun validateSteps(steps: List<AgentStep>) {
            require(steps.size in 1..20 && steps.all { it.title.isNotBlank() && it.title.length <= 200 &&
                it.state in setOf("pending", "running", "done") }) { "task_invalid_steps" }
            require(steps.count { it.state == "running" } <= 1) { "task_invalid_steps" }
        }
    }
}
