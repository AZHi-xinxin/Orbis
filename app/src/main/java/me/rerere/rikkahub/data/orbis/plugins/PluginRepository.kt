package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PluginRepository(val root: File) {
    private val lock = locks.getOrPut(root.absolutePath) { Any() }
    val changes: StateFlow<Long> get() = signals.getOrPut(root.absolutePath) { MutableStateFlow(0L) }
    init { require(root.parentFile.name == "orbis-plugins" && root.name.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    fun <T> locked(block: () -> T): T = synchronized(lock, block)
    private fun target(): File = File(root, "plugins.json").also {
        check(root.canonicalFile == root.absoluteFile && it.canonicalFile == it.absoluteFile) { "plugin_store_invalid" }
    }
    fun snapshot(): PluginState = locked {
        val file = target()
        if (!file.exists()) return@locked PluginState()
        check(file.isFile && file.length() in 1..MAX_STORE_BYTES) { "plugin_store_invalid" }
        try { pluginJson.decodeFromString<PluginState>(file.readText()).also(::validateState) }
        catch (error: Exception) { throw IllegalStateException("plugin_store_invalid", error) }
    }
    private fun save(state: PluginState) {
        validateState(state)
        val bytes = pluginJson.encodeToString(state).toByteArray()
        check(bytes.size <= MAX_STORE_BYTES) { "plugin_store_full" }
        val dest = target(); check(root.isDirectory || root.mkdirs()) { "plugin_store_invalid" }
        val temp = File(root,"pending-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        signals.getOrPut(root.absolutePath) { MutableStateFlow(0L) }.value++
    }
    fun propose(body: PluginManifest, id: String?, expected: Int, now: Long = System.currentTimeMillis()): PluginEntry = locked {
        body.validate()
        val state = snapshot()
        val old = id?.let { editable(state,it,expected) }
        check(old != null || expected == 0) { "plugin_revision_conflict" }
        check(old != null || state.entries.size < 30) { "plugin_store_full" }
        check(state.entries.none { it.id != id && it.latest().body.name == body.name }) { "plugin_name_exists" }
        check(old == null || old.latest().body.name == body.name) { "plugin_name_immutable" }
        val number = (old?.versions?.maxOfOrNull { it.number } ?: 0) + 1
        val version = PluginVersion(number,body,body.hash(),now)
        val versions = (old?.versions.orEmpty() + version).let { all ->
            val keep = setOfNotNull(old?.activeVersion,old?.previousVersion)
            (all.filter { it.number in keep } + all.takeLast(8)).distinctBy { it.number }.sortedBy { it.number }
        }
        val entry = old?.copy(revision = old.revision + 1, versions = versions, updatedAt = now)
            ?: PluginEntry(UUID.randomUUID().toString(), versions = versions, updatedAt = now)
        save(state.copy(entries = if (old == null) state.entries + entry else state.entries.map { if (it.id == id) entry else it }))
        entry
    }
    /** Called only by human controls; authoring tools cannot activate, grant, or silently replace an active version. */
    fun activate(id: String, expected: Int, version: Int): PluginEntry = locked {
        val state = snapshot(); val old = editable(state,id,expected)
        check(old.versions.any { it.number == version }) { "plugin_version_missing" }
        val next = old.copy(revision=old.revision+1, activeVersion=version, enabled=true,
            previousVersion=if(old.activeVersion != version) old.activeVersion else old.previousVersion)
        replace(state,next); next
    }
    fun setEnabled(id: String, expected: Int, enabled: Boolean): PluginEntry = locked {
        val state = snapshot(); val old = editable(state,id,expected)
        check(!enabled || old.active() != null) { "plugin_not_approved" }
        val next = old.copy(revision=old.revision+1,enabled=enabled); replace(state,next); next
    }
    fun delete(id: String, expected: Int) = locked {
        val state = snapshot(); editable(state,id,expected)
        save(state.copy(entries=state.entries.filterNot { it.id == id }))
        // The human-facing host removes this plugin's grants and scoped files after deleting its entry.
    }
    private fun replace(state: PluginState, next: PluginEntry) = save(state.copy(entries=state.entries.map { if(it.id==next.id) next else it }))
    private fun editable(state: PluginState, id: String, expected: Int): PluginEntry {
        val entry = state.entries.firstOrNull { it.id==id } ?: error("plugin_not_found")
        check(entry.revision == expected) { "plugin_revision_conflict" }; return entry
    }
    fun begin(id: String, hash: String, now: Long = System.currentTimeMillis()): PluginRun = locked {
        val state = snapshot(); val entry = state.entries.firstOrNull { it.id==id } ?: error("plugin_not_found")
        check(entry.enabled && entry.active()?.hash==hash) { "plugin_disabled" }
        check(entry.runs.none { it.status=="running" }) { "plugin_busy" }
        val run = PluginRun(UUID.randomUUID().toString(),entry.activeVersion!!,now)
        replace(state,entry.copy(runs=(entry.runs+run).takeLast(100))); run
    }
    fun finish(id: String, runId: String, status: String, elapsedMs: Long, error: String? = null, now: Long = System.currentTimeMillis()): Boolean = locked {
        require(status in setOf("succeeded","failed","timed_out","cancelled","interrupted"))
        val state = snapshot(); val entry = state.entries.firstOrNull { it.id==id } ?: return@locked false
        if(entry.runs.none { it.id==runId && it.status=="running" }) return@locked false
        val day = now / 86_400_000
        val days = entry.usesByDay.filterKeys { it >= day-29 }.toMutableMap()
        if(status=="succeeded") days[day]=(days[day] ?: 0)+1
        replace(state,entry.copy(totalUses=entry.totalUses+if(status=="succeeded") 1 else 0, usesByDay=days,
            runs=entry.runs.map { if(it.id==runId) it.copy(status=status,finishedAt=now,elapsedMs=elapsedMs.coerceAtLeast(0),error=error?.take(160)) else it }))
        true
    }
    fun recoverInterrupted(now: Long = System.currentTimeMillis()) = locked {
        snapshot().entries.forEach { entry -> entry.runs.filter { it.status=="running" && now-it.startedAt>30_000 }.forEach {
            finish(entry.id,it.id,"interrupted",0,"plugin_interrupted",now)
        } }
    }
    /** Persist intent before dispatch. Interrupted calls remain unknown; never auto-replayed. */
    fun recordCall(id: String, runId: String, index: Int, method: String, outcome: String? = null) = locked {
        require(index in 1..32 && method.matches(Regex("[a-zA-Z.]{1,64}")))
        val state=snapshot(); val entry=state.entries.firstOrNull { it.id==id } ?: error("plugin_disabled")
        val run=entry.runs.firstOrNull { it.id==runId && it.status=="running" } ?: error("plugin_interrupted")
        val old=run.calls.firstOrNull { it.index==index }
        check((old==null)==(outcome==null)) { "plugin_host_call_replayed" }
        check(old?.finishedAt==null) { "plugin_host_call_replayed" }
        require(outcome==null || outcome in setOf("returned","not_started","not_completed","unknown"))
        val next=if(old==null) PluginHostCall(index,method,System.currentTimeMillis())
            else old.copy(outcome=outcome!!,finishedAt=System.currentTimeMillis())
        replace(state,entry.copy(runs=entry.runs.map { if(it.id==runId) it.copy(calls=run.calls.filterNot { c->c.index==index }+next) else it }))
    }
    companion object {
        const val MAX_STORE_BYTES = 8L * 1024 * 1024
        private val locks = ConcurrentHashMap<String,Any>()
        private val signals = ConcurrentHashMap<String,MutableStateFlow<Long>>()
        fun validateState(state: PluginState) {
            require(state.schema==1 && state.entries.size<=30 && state.entries.map { it.id }.distinct().size==state.entries.size)
            state.entries.forEach { e ->
                require(e.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && e.revision>0 && e.versions.size in 1..10 && e.runs.size<=100 && e.totalUses>=0)
                require(e.versions.map { it.number }.distinct().size==e.versions.size && e.usesByDay.size<=60 && e.usesByDay.values.all { it>=0 })
                e.versions.forEach { require(it.number>0); it.body.validate(); require(it.hash==it.body.hash()) }
                require(e.versions.map { it.body.name }.distinct().size==1)
                require(listOfNotNull(e.activeVersion,e.previousVersion).all { n -> e.versions.any { it.number==n } })
                require(e.runs.map { it.id }.distinct().size==e.runs.size && e.runs.all { it.status in setOf("running","succeeded","failed","timed_out","cancelled","interrupted") })
                require(e.runs.all { r -> r.calls.size<=32 && r.calls.map { it.index }.distinct().size==r.calls.size })
            }
            require(state.entries.map { it.latest().body.name }.distinct().size==state.entries.size)
        }
    }
}
