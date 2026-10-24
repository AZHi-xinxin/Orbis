package me.rerere.rikkahub.data.orbis.memory

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrbisMemoryEmotionTest {
    @Test fun oldNotesDecodeWithNoInventedEmotion() {
        val note = Json.decodeFromString<OrbisMemoryNote>("""{"id":"n","assistantId":"a","body":"whole original","createdAt":1,"updatedAt":2}""")
        assertNull(note.emotion); assertEquals("whole original", note.body)
        val tagged = note.copy(emotion = "暖")
        assertEquals(tagged, Json.decodeFromString<OrbisMemoryNote>(Json.encodeToString(tagged)))
    }
    @Test fun fixedLabelsAreOptionalAndDoNotPretendToInferValues() {
        assertTrue(OrbisMemoryEmotions.valid(null)); assertEquals(7, OrbisMemoryEmotions.labels.size)
        assertTrue(OrbisMemoryEmotions.labels.all(OrbisMemoryEmotions::valid))
        assertFalse(OrbisMemoryEmotions.valid("999")); assertFalse(OrbisMemoryEmotions.valid(""))
    }
}
