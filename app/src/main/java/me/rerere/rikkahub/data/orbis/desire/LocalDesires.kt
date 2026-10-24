package me.rerere.rikkahub.data.orbis.desire

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import me.rerere.rikkahub.data.orbis.memory.*

@Serializable internal data class LocalDesire(val memoryId: String, val text: String,
    val state: String = "active", val revision: Int = 1, val updatedAt: Long = 0)
@Serializable internal data class LocalDesireState(val version: Int = 1,
    val entries: List<LocalDesire> = emptyList(), val history: List<LocalDesire> = emptyList())
internal val desireJson = Json { encodeDefaults=true; ignoreUnknownKeys=false }
internal class LocalDesireRepository(private val storage: ActivityStorage, private val now: () -> Long = System::currentTimeMillis) {
    fun snapshot(): LocalDesireState = storage.read()?.let { desireJson.decodeFromString<LocalDesireState>(it).also(::validate) } ?: LocalDesireState()
    @Synchronized fun put(memoryId: String, text: String, expectedRevision: Int): LocalDesire {
        require(memoryId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "desire_memory_invalid" }
        require(text.isNotBlank() && text.codePointCount(0,text.length) <= 200) { "desire_text_invalid" }
        val state=snapshot(); val old=state.entries.firstOrNull { it.memoryId==memoryId }
        check(expectedRevision == (old?.revision ?: 0)) { "desire_revision_conflict" }
        if (old?.text == text && old.state=="active") return old
        check(old != null || state.entries.size < 500) { "desire_capacity" }
        return LocalDesire(memoryId,text,"active",Math.addExact(expectedRevision,1),now()).also { save(state,it) }
    }
    @Synchronized fun transition(memoryId: String, target: String, expectedRevision: Int): LocalDesire {
        require(target in setOf("active","archived","deleted")) { "desire_state_invalid" }
        val state=snapshot(); val old=state.entries.firstOrNull { it.memoryId==memoryId } ?: error("desire_not_found")
        check(old.revision == expectedRevision) { "desire_revision_conflict" }
        if(old.state==target) return old
        check(old.state!="deleted" || target=="deleted") { "desire_deleted_rewrite_required" }
        return old.copy(text=if(target=="deleted") "" else old.text,state=target,revision=Math.addExact(old.revision,1),updatedAt=now()).also { save(state,it) }
    }
    private fun save(state: LocalDesireState, entry: LocalDesire) {
        val next=state.copy(entries=state.entries.filterNot { it.memoryId==entry.memoryId }+entry,
            history=(state.history+entry).takeLast(3000));validate(next)
        storage.write(desireJson.encodeToString(next))
    }
    companion object {
        fun validate(state: LocalDesireState) {
            require(state.version==1 && state.entries.size<=500 && state.history.size<=3000 && state.entries.map { it.memoryId }.distinct().size==state.entries.size) { "desire_store_invalid" }
            (state.entries+state.history).forEach {
                require(it.memoryId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.revision>0 && it.updatedAt>=0 && it.state in setOf("active","archived","deleted") &&
                    (it.state=="deleted" && it.text.isEmpty() || it.state!="deleted" && it.text.isNotBlank() && it.text.codePointCount(0,it.text.length)<=200)) { "desire_store_invalid" }
            }
        }
    }
}
internal fun pendingDesires(entries: List<LocalDesire>, eligibleParentIds: Set<String>) = entries.count { it.state=="active" && it.memoryId in eligibleParentIds }
internal fun desiresOverflow(count: Int) = count > 10

/** Local matching uses existing lexical/semantic admission, not a fictitious 80% confidence. */
internal fun desireAnnotations(entries: List<LocalDesire>): Map<String,String> = entries.filter { it.state!="deleted" }.associate {
    it.memoryId to if(it.state=="archived") "想做的事：已归档" else "想做的事：${it.text}"
}
internal fun attachLocalDesires(selection: OrbisMemorySelection, labels: Map<String,String>, mode: OrbisMemoryMode): OrbisMemorySelection {
    var result=selection; var count=0
    selection.entries.filter { it.kind !in setOf("pinned","compaction_excerpt") }.forEach { selected ->
        if(count>=3) return@forEach
        val label=labels[selected.id]?.takeIf { it.length<=430 } ?: return@forEach
        val trial=result.copy(entries=result.entries.map { if(it.id==selected.id) it.copy(desire=label) else it })
        if(limitFrozenMemory(trial,mode)==trial) { result=trial;count++ }
    }
    return result
}
