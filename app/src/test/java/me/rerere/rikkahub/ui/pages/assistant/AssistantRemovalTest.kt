package me.rerere.rikkahub.ui.pages.assistant

import java.io.IOException
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AssistantRemovalTest {
    private val target = Assistant(name = "current saved assistant")
    private val other = Assistant(name = "unrelated assistant")

    @Test fun usesLatestSettingsAndCleansOnlyAfterConfirmedSave() = runTest {
        var persisted = Settings(assistants = listOf(target, other))
        var cleaned: Assistant? = null
        assertTrue(removeAssistantAfterConfirmedSave(target.id, { transform ->
            assertNull(cleaned)
            persisted = transform(persisted)
            true
        }, { persisted }) { cleaned = it })
        assertEquals(target, cleaned)
        assertEquals(listOf(other), persisted.assistants)
    }

    @Test fun rejectedWriteNeverDeletesAssociatedData() = runTest {
        val persisted = Settings(assistants = listOf(target))
        assertFalse(removeAssistantAfterConfirmedSave(target.id, { transform ->
            transform(persisted); false
        }, { persisted }) { fail("must not clean rejected removal") })
    }

    @Test fun failedWriteNeverDeletesAssociatedData() = runTest {
        try {
            removeAssistantAfterConfirmedSave(target.id, { throw IOException("synthetic") },
                { Settings(assistants = listOf(target)) }) { fail("must not clean failed removal") }
            fail("failure must propagate")
        } catch (_: IOException) { }
    }

    @Test fun normalizationRetainingAssistantNeverCleansIt() = runTest {
        val retained = Settings(assistants = listOf(target))
        assertFalse(removeAssistantAfterConfirmedSave(target.id, { transform ->
            transform(retained); true
        }, { retained }) { fail("built-in remains present") })
    }

    @Test fun missingAssistantAndUninitializedReadNeverTriggerCleanup() = runTest {
        assertFalse(removeAssistantAfterConfirmedSave(target.id, { transform ->
            transform(Settings(assistants = listOf(other))); true
        }, { Settings(assistants = listOf(other)) }) { fail("not removed by this operation") })
        assertFalse(removeAssistantAfterConfirmedSave(target.id, { transform ->
            transform(Settings(assistants = listOf(target))); true
        }, { Settings.dummy() }) { fail("read not confirmed") })
    }
}
