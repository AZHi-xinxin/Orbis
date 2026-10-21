package me.rerere.rikkahub.data.orbis.memory

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.OrbisCompactionEventEntity

internal data class SemanticMemorySource(val event: OrbisCompactionEventEntity, val note: OrbisMemoryEntity)

/** Receipt provenance is checked, not guessed from a static note's contents. Reads only. */
internal class SemanticMemorySources(private val database: AppDatabase) {
    suspend fun page(owner: String, offset: Int): List<OrbisCompactionEventEntity> =
        database.orbisCompactionDao().semanticEvents(owner, 25, offset)

    suspend fun read(owner: String, eventId: String, expectedNote: String? = null): SemanticMemorySource? =
        database.withTransaction { readSnapshot(owner, eventId, expectedNote) }

    private suspend fun readSnapshot(owner: String, eventId: String, expectedNote: String?): SemanticMemorySource? {
        val event = database.orbisCompactionDao().getEvent(eventId, owner)
            ?.takeIf { it.status == "committed" } ?: return null
        val receipt = database.orbisMemoryDao().operation(owner, memoryDigest("compaction:$eventId")) ?: return null
        val id = runCatching { Json.parseToJsonElement(receipt.resultJson).jsonObject["id"]
            ?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
        if (expectedNote != null && id != expectedNote) return null
        val note = database.orbisMemoryDao().get(owner, id)?.takeIf(::semanticNoteEligible) ?: return null
        // A host-created static revision must still match the committed compaction. Explicitly
        // conditional revisions are controlled by the owning AI and may have updated their text.
        if (note.state == "static" && (memoryDigest(note.body) != event.summaryHash ||
                !semanticStaticLineageEligible(database.orbisMemoryDao().semanticLineage(owner, id)))) return null
        return SemanticMemorySource(event, note)
    }
}
