package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisSettingsAssistantScopeTest {
    private val companion = Assistant(id = Uuid.parse("00000000-0000-0000-0000-000000000001"), name = "Companion")
    private val chatOwner = Assistant(id = Uuid.parse("00000000-0000-0000-0000-000000000002"), name = "GPT chat")
    private val server = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private fun settings() = Settings(assistantId = companion.id, assistants = listOf(companion, chatOwner))

    @Test fun chatSettingsResolveConversationOwnerNotGlobalSelection() {
        assertEquals(chatOwner, orbisSettingsAssistant(settings(), chatOwner.id.toString()))
        assertEquals(companion, orbisSettingsAssistant(settings()))
    }

    @Test fun explicitOwnerSurvivesGlobalSelectionChanges() {
        val selectedElsewhere = settings().copy(assistantId = Uuid.random())
        assertEquals(chatOwner, orbisSettingsAssistant(selectedElsewhere, chatOwner.id.toString()))
        assertNull(orbisSettingsAssistant(selectedElsewhere))
    }

    @Test fun unavailableExplicitOwnerNeverFallsBackToGlobalOrFirstAssistant() {
        for (id in listOf("", "bad-id", Uuid.random().toString())) {
            assertNull(orbisSettingsAssistant(settings(), id))
        }
        assertNull(orbisSettingsAssistant(settings().copy(assistants = listOf(companion)), chatOwner.id.toString()))
    }

    @Test fun loadingAndAmbiguousOwnerCannotExposeAnotherSelection() {
        assertNull(orbisSettingsAssistant(settings().copy(init = true), chatOwner.id.toString()))
        assertNull(orbisSettingsAssistant(settings().copy(assistants = listOf(chatOwner, chatOwner)), chatOwner.id.toString()))
    }

    @Test fun mcpSelectionChangesOnlyTheOwningAssistantField() {
        val before = settings()
        val proposed = chatOwner.copy(mcpServers = setOf(server), name = "Stale name", chatModelId = Uuid.random())
        val after = orbisSettingsWithMcpSelection(before, chatOwner.id.toString(), proposed)
        assertEquals(before.copy(assistants = listOf(companion, chatOwner.copy(mcpServers = setOf(server)))), after)
        assertEquals(companion.id, after.assistantId)
        assertSame(companion, after.assistants.first())
    }

    @Test fun oldRenderedSnapshotCannotOverwriteNewNameModelOrOtherAssistant() {
        val newerOwner = chatOwner.copy(name = "Renamed", chatModelId = Uuid.random())
        val newerCompanion = companion.copy(mcpServers = setOf(Uuid.random()))
        val current = settings().copy(assistants = listOf(newerCompanion, newerOwner))
        val after = orbisSettingsWithMcpSelection(current, chatOwner.id.toString(), chatOwner.copy(mcpServers = setOf(server)))
        assertEquals(newerOwner.copy(mcpServers = setOf(server)), after.assistants.last())
        assertSame(newerCompanion, after.assistants.first())
    }

    @Test fun mismatchedCallbackCannotModifyGlobalAssistant() {
        val before = settings()
        assertSame(before, orbisSettingsWithMcpSelection(before, chatOwner.id.toString(), companion.copy(mcpServers = setOf(server))))
    }

    @Test fun deletedOwnerAndLoadingIgnoreDelayedCallbacks() {
        val proposed = chatOwner.copy(mcpServers = setOf(server))
        val removed = settings().copy(assistants = listOf(companion))
        val loading = settings().copy(init = true)
        assertSame(removed, orbisSettingsWithMcpSelection(removed, chatOwner.id.toString(), proposed))
        assertSame(loading, orbisSettingsWithMcpSelection(loading, chatOwner.id.toString(), proposed))
    }

    @Test fun removingOwnerMcpDoesNotRemoveGlobalCompanionSelections() {
        val current = settings().copy(assistants = listOf(companion.copy(mcpServers = setOf(server)), chatOwner.copy(mcpServers = setOf(server))))
        val after = orbisSettingsWithMcpSelection(current, chatOwner.id.toString(), chatOwner)
        assertEquals(setOf(server), after.assistants.first().mcpServers)
        assertEquals(emptySet<Uuid>(), after.assistants.last().mcpServers)
    }

    @Test fun ownerRouteRoundTripsAndLegacyGeneralRouteRemainsSeparate() {
        val route = Screen.OrbisConversationMcpSettings(chatOwner.id.toString())
        val encoded = Json.encodeToString(Screen.OrbisConversationMcpSettings.serializer(), route)
        assertEquals(route, Json.decodeFromString(Screen.OrbisConversationMcpSettings.serializer(), encoded))
        assertEquals(Screen.OrbisMcpSettings, Json.decodeFromString(Screen.OrbisMcpSettings.serializer(), "{}"))
    }
}
