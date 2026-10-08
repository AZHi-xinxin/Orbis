package me.rerere.rikkahub.data.orbis.memory

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.orbisCompactionSummaryHash
import kotlin.uuid.Uuid
import java.util.Locale

/** One immutable selection per human wake, shared by retries and the complete tool loop. */
class OrbisMemoryTurn internal constructor(
    val selection: OrbisMemorySelection,
    private val projectLive: suspend (List<UIMessage>, OrbisMemorySelection) -> List<UIMessage>,
) {
    suspend fun project(messages: List<UIMessage>): List<UIMessage> = projectLive(messages, selection)
}

class OrbisMemoryRuntime(
    private val database: AppDatabase,
    private val repository: OrbisMemoryRepository,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun begin(assistant: Assistant, conversationId: Uuid?, messages: List<UIMessage>): OrbisMemoryTurn? {
        if (conversationId == null) return null
        val human = latestMemoryHuman(messages) ?: return null
        val owner = assistant.id.toString()
        val key = orbisCompactionSummaryHash("$conversationId:${human.id}:${human.toText()}")
        val frozen = try {
            database.withTransaction {
                val dao = database.orbisMemoryTurnDao()
                dao.get(owner, key)?.let { return@withTransaction json.decodeFromString<OrbisMemorySelection>(it.payload) }
                val ordinal = Math.addExact(dao.latestOrdinal(owner), 1L)
                val eligible = assistant.orbisMemoryAutoInject && assistant.orbisMemoryMode != OrbisMemoryMode.LIGHT
                val notes = mutableListOf<MemoryInjectionNote>()
                val normalizedQuery = human.toText().lowercase(Locale.ROOT)
                if (eligible) {
                    // Bounded work per wake. Pins sort first and are never displaced by recent hits.
                    var offset = 0
                    while (offset < 2000) {
                        val page = repository.injectionCandidates(owner, limit = 50, offset = offset)
                        val surfaced = dao.lastSurfaced(owner, page.map { it.id }).associate { it.noteId to it.lastOrdinal }
                        for (n in page) {
                            val matches = (n.tags + n.keywords).asSequence().map { it.trim().lowercase(Locale.ROOT) }
                                .filter(String::isNotEmpty).distinct().count { normalizedQuery.contains(it) }
                            val previous = surfaced[n.id]
                            if (n.state != "pinned" && (matches == 0 || previous != null &&
                                    ordinal - previous < n.minIntervalTurns.coerceAtLeast(1))) continue
                            // Retain only the lexical score, not large tag/keyword arrays. A bounded
                            // shortlist prevents 2000 maximal metadata rows accumulating in RAM.
                            notes += MemoryInjectionNote(n.id, n.state, n.summary, n.body, n.pinText,
                                emptyList(), emptyList(), n.important, n.minIntervalTurns, n.revision, matches)
                        }
                        notes.sortWith(compareBy<MemoryInjectionNote> { if (it.state == "pinned") 0 else 1 }
                            .thenByDescending { it.matchedTerms }.thenByDescending { it.important }.thenBy { it.id })
                        if (notes.size > 256) notes.subList(256, notes.size).clear()
                        if (page.size < 50) break
                        offset += page.size
                    }
                }
                val selection = if (!eligible) OrbisMemorySelection() else selectOrbisMemory(
                    assistant.orbisMemoryMode, human.toText(), notes, ordinal)
                dao.insert(OrbisMemoryTurnEntity(owner, key, ordinal, json.encodeToString(selection), System.currentTimeMillis()))
                if (selection.entries.isNotEmpty()) dao.recordSurfacing(selection.entries.map {
                    OrbisMemorySurfacingEntity(owner, it.id, ordinal)
                })
                dao.prune(owner, (ordinal - 63).coerceAtLeast(1))
                selection
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            // A failed optional memory read must not halt the human conversation or reset storage.
            // Explicit tool operations still report their error; there is no empty-store repair.
            OrbisMemorySelection()
        }
        return OrbisMemoryTurn(frozen) { request, selected ->
            val current = settingsStore.settingsFlow.value.getAssistantById(assistant.id)
            if (current == null || !current.orbisMemoryAutoInject || current.orbisMemoryMode == OrbisMemoryMode.LIGHT) {
                projectOrbisMemory(request, OrbisMemorySelection())
            } else {
                val ids = try { repository.eligibleInjectionIds(owner, selected.entries.map { it.id })
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { emptySet() }
                // Pausing, soft deletion and static downgrade take effect without replaying this turn.
                projectOrbisMemory(request, limitFrozenMemory(
                    selected.copy(entries = selected.entries.filter { it.id in ids }), current.orbisMemoryMode))
            }
        }
    }
}
