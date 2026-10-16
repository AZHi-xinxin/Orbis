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

class OrbisToolsScopeTest {
    private val companion = Assistant(id = Uuid.parse("00000000-0000-0000-0000-000000000001"), name = "Companion")
    private val gpt = Assistant(id = Uuid.parse("00000000-0000-0000-0000-000000000002"), name = "GPT")
    private val conversationId = "00000000-0000-0000-0000-000000000003"
    private fun settings() = Settings(assistantId = companion.id, assistants = listOf(companion, gpt))

    @Test fun chatToolsResolveTheConversationOwnerNotTheGlobalCompanion() {
        val scope = orbisToolsScope(settings(), gpt.id.toString(), conversationId)!!
        assertSame(gpt, scope.assistant)
        assertEquals(conversationId, scope.conversationId)
        assertEquals(Screen.ImageWorkshop(gpt.id.toString(), conversationId), scope.imageWorkshopRoute())
    }

    @Test fun changingGlobalSelectionCannotRetargetTheChatToolsOrWorkshop() {
        val before = orbisToolsScope(settings(), gpt.id.toString(), conversationId)
        val after = orbisToolsScope(settings().copy(assistantId = Uuid.random()), gpt.id.toString(), conversationId)
        assertEquals(before, after)
        assertEquals(before?.imageWorkshopRoute(), after?.imageWorkshopRoute())
    }

    @Test fun legacyGeneralEntryStillUsesItsGlobalSelectionWithoutInventingAConversation() {
        val scope = orbisToolsScope(settings())!!
        assertSame(companion, scope.assistant)
        assertNull(scope.conversationId)
        assertEquals(Screen.ImageWorkshop(companion.id.toString(), null), scope.imageWorkshopRoute())
        assertSame(gpt, orbisToolsScope(settings().copy(assistantId = gpt.id))!!.assistant)
    }

    @Test fun explicitMissingOrMalformedOwnerCannotBecomeAGlobalEntry() {
        for (owner in listOf("", "bad-id", Uuid.random().toString())) {
            assertNull(orbisToolsScope(settings(), owner, conversationId))
        }
        assertNull(orbisToolsScope(settings().copy(assistants = listOf(companion)), gpt.id.toString(), conversationId))
    }

    @Test fun partialOrMalformedChatScopeFailsClosed() {
        assertNull(orbisToolsScope(settings(), gpt.id.toString(), null))
        assertNull(orbisToolsScope(settings(), null, conversationId))
        for (conversation in listOf("", "bad-id", "../another-chat")) {
            assertNull(orbisToolsScope(settings(), gpt.id.toString(), conversation))
        }
    }

    @Test fun loadingOrAmbiguousOwnerDisablesOwnerSpecificTools() {
        assertNull(orbisToolsScope(settings().copy(init = true), gpt.id.toString(), conversationId))
        assertNull(orbisToolsScope(settings().copy(assistants = listOf(companion, gpt, gpt)), gpt.id.toString(), conversationId))
    }

    @Test fun dockRouteCarriesBothChatIdentifiersAndLeavesGeneralRouteSeparate() {
        assertEquals(Screen.OrbisTools, orbisToolsRoute(null, null))
        assertEquals(Screen.OrbisConversationTools(gpt.id.toString(), conversationId),
            orbisToolsRoute(gpt.id.toString(), conversationId))
    }

    @Test fun partiallyRestoredDockDoesNotSilentlyNavigateToTheGlobalRoute() {
        val missingOwner = orbisToolsRoute(null, conversationId) as Screen.OrbisConversationTools
        val missingConversation = orbisToolsRoute(gpt.id.toString(), null) as Screen.OrbisConversationTools
        assertNull(orbisToolsScope(settings(), missingOwner.assistantId, missingOwner.conversationId))
        assertNull(orbisToolsScope(settings(), missingConversation.assistantId, missingConversation.conversationId))
    }

    @Test fun scopedAndLegacyRoutesRoundTripIndependently() {
        val route = Screen.OrbisConversationTools(gpt.id.toString(), conversationId)
        assertEquals(route, Json.decodeFromString(Screen.OrbisConversationTools.serializer(),
            Json.encodeToString(Screen.OrbisConversationTools.serializer(), route)))
        assertEquals(Screen.OrbisTools, Json.decodeFromString(Screen.OrbisTools.serializer(), "{}"))
        val workshop = Screen.ImageWorkshop(gpt.id.toString(), conversationId)
        assertEquals(workshop, Json.decodeFromString(Screen.ImageWorkshop.serializer(),
            Json.encodeToString(Screen.ImageWorkshop.serializer(), workshop)))
    }

    @Test fun chatMcpSettingsPreserveScopeForTheirToolsDock() {
        val route = Screen.OrbisConversationMcpSettings(gpt.id.toString(), conversationId)
        val restored = Json.decodeFromString(Screen.OrbisConversationMcpSettings.serializer(),
            Json.encodeToString(Screen.OrbisConversationMcpSettings.serializer(), route))
        assertEquals(Screen.OrbisConversationTools(gpt.id.toString(), conversationId),
            orbisToolsRoute(restored.assistantId, restored.conversationId))
    }

    @Test fun oldOwnerOnlyMcpRouteStillDecodesButCannotBecomeGlobalTools() {
        val restored = Json.decodeFromString(Screen.OrbisConversationMcpSettings.serializer(),
            """{"assistantId":"${gpt.id}"}""")
        assertNull(restored.conversationId)
        assertSame(gpt, orbisSettingsAssistant(settings(), restored.assistantId))
        val tools = orbisToolsRoute(restored.assistantId, restored.conversationId) as Screen.OrbisConversationTools
        assertNull(orbisToolsScope(settings(), tools.assistantId, tools.conversationId))
    }

    @Test fun workshopUsesItsPinnedOwnerEvenAfterGlobalChanges() {
        val newer = settings().copy(assistantId = companion.id, assistants = listOf(companion, gpt.copy(name = "Renamed GPT")))
        assertEquals("Renamed GPT", orbisWorkshopAssistant(newer, gpt.id.toString(), conversationId)?.name)
        assertNull(orbisWorkshopAssistant(newer.copy(assistants = listOf(companion)), gpt.id.toString(), conversationId))
        assertNull(orbisWorkshopAssistant(newer, "", conversationId))
    }

    @Test fun workshopKeepsExistingOwnerOnlyWidgetEntryAndRejectsInvalidConversation() {
        assertSame(gpt, orbisWorkshopAssistant(settings(), gpt.id.toString(), null))
        assertNull(orbisWorkshopAssistant(settings(), gpt.id.toString(), "bad-id"))
        assertNull(orbisWorkshopAssistant(settings().copy(init = true), gpt.id.toString(), conversationId))
    }

    @Test fun scopeResolutionDoesNotWriteGlobalAssistantModelOrPermissions() {
        val before = settings()
        orbisToolsScope(before, gpt.id.toString(), conversationId)!!.imageWorkshopRoute()
        orbisWorkshopAssistant(before, gpt.id.toString(), conversationId)
        assertEquals(companion.id, before.assistantId)
        assertSame(companion, before.assistants.first())
        assertSame(gpt, before.assistants.last())
    }
}
