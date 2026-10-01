package me.rerere.rikkahub.data.ai.compaction

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.orbisVoiceNoteMetadata
import me.rerere.rikkahub.data.model.withVoiceNoteTranscripts
import org.junit.Assert.*
import org.junit.Test

class CompactionTokenEstimateTest {
    @Test fun `native voice notes estimate the same model projection without discarding stored audio`() {
        val note = UIMessagePart.Audio("file:///private/upload/synthetic.wav",
            metadata = orbisVoiceNoteMetadata("合成测试称呼", "synthetic original", 1200))
        val user = UIMessage.user("").copy(parts = listOf(note))
        val tool = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("synthetic-voice", "orbis_voice_note", "{}", listOf(note))))
        val originals = listOf(user, tool)
        assertEquals(estimateCompactionTokens(originals.map { it.withVoiceNoteTranscripts() }),
            estimateCompactionTokens(originals))
        assertEquals(note, user.parts.single())
        assertEquals(note, (tool.parts.single() as UIMessagePart.Tool).output.single())
    }
    @Test fun `fresh message and reasoning defaults are always encoded as a stable snapshot`() {
        val fullSnapshotJson = Json { encodeDefaults = true }
        repeat(64) {
            // These defaults use the live clock. Compare with a full snapshot reference rather
            // than relying on a sleep or on a particular operating system clock resolution.
            val messages = listOf(UIMessage.user("synthetic request"), UIMessage.assistant("synthetic answer").copy(
                parts = listOf(UIMessagePart.Reasoning("synthetic reasoning"), UIMessagePart.Text("synthetic answer"))))
            val snapshots = messages.map { fullSnapshotJson.encodeToString(UIMessage.serializer(), it) }
            val expected = snapshots.sumOf { estimateCompactionTextTokens(it) + 8L }
            repeat(8) { assertEquals(expected, estimateCompactionTokens(messages)) }
            assertEquals(snapshots, messages.map { fullSnapshotJson.encodeToString(UIMessage.serializer(), it) })
        }
    }

    @Test fun `stable snapshot still excludes local deleted tool data without mutating it`() {
        val message = UIMessage.assistant("synthetic visible text")
        val deleted = DeletedToolRecord(
            UIMessagePart.Tool("synthetic-old", "noop", "{}", listOf(UIMessagePart.Text("private undo ".repeat(1000)))),
            0, message.createdAt,
        )
        val withUndo = message.copy(deletedToolRecords = listOf(deleted), toolRecordRevision = 3,
            toolRecordsUpdatedAt = message.createdAt)
        repeat(32) { assertEquals(estimateCompactionTokens(listOf(message)), estimateCompactionTokens(listOf(withUndo))) }
        assertEquals(listOf(deleted), withUndo.deletedToolRecords)
        assertEquals(3L, withUndo.toolRecordRevision)
        assertEquals(message.createdAt, withUndo.toolRecordsUpdatedAt)
    }
}
