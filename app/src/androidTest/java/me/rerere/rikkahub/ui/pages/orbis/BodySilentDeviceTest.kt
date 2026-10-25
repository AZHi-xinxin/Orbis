package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import me.rerere.rikkahub.data.orbis.body.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BodySilentDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun visibleValuesTickWithoutMessagesOrWritesAndPauseFreezes() {
        var writes = 0
        val storage = object : ActivityStorage {
            override fun read(): String? = null
            override fun write(text: String) { writes++ }
        }
        var clock = BodyClock("test", 0, 1_000_000)
        val repo = BodyRepository(storage) { clock }
        repo.define(BodyDefinition(listOf(BodyDimension("mood", "心情", baseline = 50.0, halfLifeMinutes = 60.0)),
            triggerMode = "auto", absence = BodyAbsence(graceMinutes = 1, capHours = 1, perHour = mapOf("mood" to 30.0))), 0, "draft")
        repo.activate(1, "activate"); repo.setRunning(true); repo.human("chat", "one", "hello", false)
        val before = writes
        compose.setContent { MaterialTheme {
            val state = rememberLiveBodyState(repo)
            Text(bodyNumber(BodyRules.values(state.snapshot, "chat", state.versions.last().definition).getValue("mood")))
        } }
        compose.onNodeWithText("50.0").assertExists()
        compose.runOnIdle { clock = clock.copy(wallMs = 1_000_000 + 31 * 60000L) }
        compose.waitUntil(6000) { compose.onAllNodesWithText("62.7").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, writes)
        compose.runOnIdle { repo.setRunning(false); clock = clock.copy(wallMs = clock.wallMs + 3600000) }
        compose.waitForIdle()
        compose.onNodeWithText("62.7").assertExists()
    }
}
