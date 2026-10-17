package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test

class SearchProviderSelectionTest {
    @Test fun addingOfficialSearchPreservesSelectedBochaAndExistingSettings() {
        val bocha = SearchServiceOptions.BochaOptions(apiKey = "synthetic-bocha")
        val old = Settings(searchServices = listOf(SearchServiceOptions.BingLocalOptions(), bocha), searchServiceSelected = 1)
        val added = SearchServiceOptions.DeepSeekOptions()
        val updated = settingsWithAddedSearchService(old, added)
        assertEquals(2, updated.searchServiceSelected)
        assertSame(bocha, updated.searchServices[updated.searchServiceSelected])
        assertSame(added, updated.searchServices.first())
        assertEquals(old, updated.copy(searchServices = old.searchServices, searchServiceSelected = old.searchServiceSelected))
    }
    @Test fun emptyAndOutOfRangeSettingsRemainValid() {
        val added = SearchServiceOptions.DeepSeekOptions()
        assertEquals(0, settingsWithAddedSearchService(Settings(searchServices = emptyList()), added).searchServiceSelected)
        assertEquals(1, settingsWithAddedSearchService(Settings(searchServiceSelected = 100), added).searchServiceSelected)
    }
}
