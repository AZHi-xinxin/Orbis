package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisGenerationParameterConflict
import me.rerere.rikkahub.data.model.OrbisGenerationParameterEdit
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** In-memory stores with gates: no real SettingsStore, files, database, or model calls. */
class OrbisGenerationParameterPersistenceTest {
    private val assistant = Assistant(temperature = 0.5f)
    private val before = OrbisGenerationParameters.from(assistant)
    private val edit = OrbisGenerationParameterEdit(assistant.id, before, before.copy(temperature = 1f))

    @Test fun `write merges against callback latest state and preserves other assistants and settings`() = runBlocking {
        val other = Assistant(name = "other")
        var state = Settings(assistants = listOf(assistant.copy(name = "concurrent"), other), enableSuggestion = false)
        val saved = persistOrbisGenerationParameters(edit) { transform -> state = transform(state) }
        assertEquals(1f, saved.temperature)
        assertEquals("concurrent", state.assistants.first().name)
        assertEquals(other, state.assistants.last())
        assertFalse(state.enableSuggestion)
    }

    @Test fun `route cancellation after memory publication completes the already started durable write`() = runBlocking {
        val diskGate = CompletableDeferred<Unit>()
        var memory = Settings(assistants = listOf(assistant))
        var disk = memory
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            persistOrbisGenerationParameters(edit) { transform ->
                memory = transform(memory)
                diskGate.await()
                disk = memory
            }
        }
        assertEquals(1f, memory.assistants.single().temperature)
        job.cancel()
        assertFalse(job.isCompleted)
        diskGate.complete(Unit)
        job.join()
        assertEquals(1f, disk.assistants.single().temperature)
        assertTrue(job.isCancelled)
    }

    @Test fun `pre-cancelled caller never begins a settings write`() = runBlocking {
        var writes = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            persistOrbisGenerationParameters(edit) { writes++ }
        }
        job.join()
        assertEquals(0, writes)
    }

    @Test fun `disk failure reports no success and retry can persist memory value without overwriting concurrent name`() = runBlocking {
        var state = Settings(assistants = listOf(assistant))
        var saved: OrbisGenerationParameters? = null
        val failure = runCatching {
            saved = persistOrbisGenerationParameters(edit) { transform ->
                state = transform(state)
                throw IOException("synthetic disk failure")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertNull(saved)
        state = state.copy(assistants = listOf(state.assistants.single().copy(name = "concurrent")))
        saved = persistOrbisGenerationParameters(edit) { transform -> state = transform(state) }
        assertEquals(1f, saved?.temperature)
        assertEquals("concurrent", state.assistants.single().name)
    }

    @Test fun `conflicting or missing assistant never publishes a partial update`() = runBlocking {
        val latest = Settings(assistants = listOf(assistant.copy(temperature = 1.5f)))
        var writes = 0
        val conflict = runCatching {
            persistOrbisGenerationParameters(edit) { transform -> transform(latest); writes++ }
        }.exceptionOrNull()
        assertTrue(conflict is OrbisGenerationParameterConflict)
        val missing = runCatching {
            persistOrbisGenerationParameters(edit) { transform -> transform(latest.copy(assistants = emptyList())); writes++ }
        }.exceptionOrNull()
        assertTrue(missing is IllegalStateException)
        assertEquals(0, writes)
    }
}
