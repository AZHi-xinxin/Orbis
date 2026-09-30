package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisGenerationParametersTest {
    private val original = Assistant(temperature = 0.5f, topP = 0.8f, contextMessageLimit = 20,
        streamOutput = true, maxTokens = 500, reasoningLevel = ReasoningLevel.AUTO)
    private val before = OrbisGenerationParameters.from(original)

    @Test fun `snapshot contains the same existing assistant parameter values`() {
        assertEquals(before, OrbisGenerationParameterDraft.from(before).parameters())
        assertEquals(before, Json.decodeFromString<OrbisGenerationParameters>(Json.encodeToString(before)))
    }

    @Test fun `all six changed fields merge into current assistant`() {
        val after = OrbisGenerationParameters(1.5f, 0.4f, 60, false, 2048, ReasoningLevel.HIGH)
        val saved = OrbisGenerationParameterEdit(original.id, before, after).mergeInto(original)
        assertEquals(after, OrbisGenerationParameters.from(saved))
        assertEquals(original.id, saved.id)
    }

    @Test fun `merge preserves unrelated concurrent fields and untouched parameters`() {
        val latest = original.copy(name = "edited elsewhere", systemPrompt = "latest prompt",
            background = "synthetic background", workspaceId = Uuid.random(), topP = 0.6f,
            maxTokens = 900, reasoningLevel = ReasoningLevel.MAX)
        val saved = OrbisGenerationParameterEdit(original.id, before, before.copy(temperature = 1f)).mergeInto(latest)
        assertEquals(latest.copy(temperature = 1f), saved)
    }

    @Test fun `no changed parameter means no overwrite of concurrent values`() {
        val latest = original.copy(temperature = 1.6f, streamOutput = false, maxTokens = null)
        assertEquals(latest, OrbisGenerationParameterEdit(original.id, before, before).mergeInto(latest))
    }

    @Test fun `same field modified to a third value rejects whole merge`() {
        val latest = original.copy(temperature = 1.6f)
        val failure = assertThrows(OrbisGenerationParameterConflict::class.java) {
            OrbisGenerationParameterEdit(original.id, before, before.copy(temperature = 1f, maxTokens = 700)).mergeInto(latest)
        }
        assertEquals("温度", failure.fieldName)
        assertEquals(500, latest.maxTokens) // the merge is pure, never partially mutates the assistant
    }

    @Test fun `each conflict capable field uses the latest value`() {
        val conflicts = listOf(
            before.copy(topP = 0.4f) to original.copy(topP = 0.7f),
            before.copy(contextMessageLimit = 40) to original.copy(contextMessageLimit = 60),
            before.copy(maxTokens = 800) to original.copy(maxTokens = 900),
            before.copy(reasoningLevel = ReasoningLevel.HIGH) to original.copy(reasoningLevel = ReasoningLevel.LOW),
        )
        conflicts.forEach { (after, latest) ->
            assertThrows(OrbisGenerationParameterConflict::class.java) {
                OrbisGenerationParameterEdit(original.id, before, after).mergeInto(latest)
            }
        }
    }

    @Test fun `retry after persistence failure accepts desired value already in memory`() {
        val after = before.copy(temperature = 1f, streamOutput = false)
        val edit = OrbisGenerationParameterEdit(original.id, before, after)
        val memoryUpdatedBeforeDiskFailure = edit.mergeInto(original)
        val latest = memoryUpdatedBeforeDiskFailure.copy(name = "concurrent name", topP = 0.6f)
        assertEquals(latest, edit.mergeInto(latest))
    }

    @Test fun `retry after failure still rejects a subsequent different edit`() {
        val edit = OrbisGenerationParameterEdit(original.id, before, before.copy(maxTokens = 700))
        val memoryUpdated = edit.mergeInto(original)
        assertThrows(OrbisGenerationParameterConflict::class.java) {
            edit.mergeInto(memoryUpdated.copy(maxTokens = 800))
        }
    }

    @Test fun `cannot save into a different assistant`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrbisGenerationParameterEdit(original.id, before, before.copy(temperature = 1f))
                .mergeInto(original.copy(id = Uuid.random()))
        }
    }

    @Test fun `disabling overrides and blank max tokens restores provider defaults`() {
        val draft = OrbisGenerationParameterDraft.from(before).copy(temperatureEnabled = false,
            temperature = "unfinished", topPEnabled = false, topP = "unfinished", maxTokens = "  ",
            contextMessageLimit = "0")
        val result = draft.parameters()
        assertNull(result.temperature)
        assertNull(result.topP)
        assertNull(result.maxTokens)
        assertEquals(0, result.contextMessageLimit)
    }

    @Test fun `rejects partial invalid and overflowing numeric input`() {
        val draft = OrbisGenerationParameterDraft.from(before)
        listOf(draft.copy(temperature = "."), draft.copy(topP = ""),
            draft.copy(contextMessageLimit = "2147483648"), draft.copy(maxTokens = "1.5"),
            draft.copy(maxTokens = "NaN")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.parameters() }
        }
    }

    @Test fun `rejects out of range and nonfinite values without silently clamping`() {
        listOf(before.copy(temperature = Float.NaN), before.copy(temperature = Float.POSITIVE_INFINITY),
            before.copy(temperature = -0.1f), before.copy(temperature = 2.1f),
            before.copy(topP = Float.NaN), before.copy(topP = 1.1f), before.copy(topP = -0.1f),
            before.copy(contextMessageLimit = -1), before.copy(contextMessageLimit = 19),
            before.copy(maxTokens = 0), before.copy(maxTokens = -1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validated() }
        }
    }

    @Test fun `numeric endpoints and every existing reasoning level remain supported`() {
        ReasoningLevel.entries.forEach {
            assertEquals(it, before.copy(temperature = 0f, topP = 1f, maxTokens = 1,
                reasoningLevel = it).validated().reasoningLevel)
        }
        assertEquals(2f, before.copy(temperature = 2f, topP = 0f).validated().temperature)
    }
}
