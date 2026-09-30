package me.rerere.rikkahub.ui.components.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelConnectionEntryTest {
    @Test fun reorderingOneConnectionsFavoritesKeepsOtherConnectionsInTheirSlots() {
        val (otherA, first, otherB, second) = List(4) { Uuid.random() }
        assertEquals(
            listOf(otherA, second, otherB, first),
            reorderVisibleModelFavorites(listOf(otherA, first, otherB, second), setOf(first, second), second, first),
        )
    }

    @Test fun favoriteRemovedDuringDragDoesNotMoveAnotherModel() {
        val (other, first, removed) = List(3) { Uuid.random() }
        val latest = listOf(other, first)
        assertEquals(latest, reorderVisibleModelFavorites(latest, setOf(first, removed), removed, first))
    }

    @Test fun favoriteAddedDuringDragIsPreserved() {
        val (first, second, added) = List(3) { Uuid.random() }
        assertEquals(
            listOf(second, added, first),
            reorderVisibleModelFavorites(listOf(first, added, second), setOf(first, second), second, first),
        )
    }

    @Test fun existingProviderDetailRoutesStillOpenConfiguration() {
        val route = Json.decodeFromString<Screen.SettingProviderDetail>("""{"providerId":"synthetic-provider"}""")
        assertFalse(route.showModels)
    }

    @Test fun newlyAddedConnectionCanOpenItsModelsTabDirectly() {
        val route = Screen.SettingProviderDetail("synthetic-provider", showModels = true)
        val restored = Json.decodeFromString<Screen.SettingProviderDetail>(Json.encodeToString(route))
        assertEquals(route, restored)
        assertTrue(restored.showModels)
    }

    @Test fun emptyPickerCanRecoverWhenAChatModelIsAddedWithoutSelectingItAutomatically() {
        val state = ModelListState(null, emptyList(), ModelType.CHAT)
        state.open()
        assertTrue(state.visible)
        assertTrue(state.filteredProviders.isEmpty())

        val model = Model(modelId = "synthetic-chat", displayName = "Synthetic chat", type = ModelType.CHAT)
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        state.update(null, listOf(provider), ModelType.CHAT)
        assertEquals(listOf(provider), state.filteredProviders)
        assertEquals(null, state.currentModel)
        state.close()
        assertFalse(state.visible)
    }
}
