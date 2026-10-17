package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions

/** Adding a provider is not consent to switch a live chat to a different, possibly paid service. */
internal fun settingsWithAddedSearchService(settings: Settings, options: SearchServiceOptions): Settings =
    settings.copy(
        searchServices = listOf(options) + settings.searchServices,
        searchServiceSelected = if (settings.searchServices.isEmpty()) 0
            else settings.searchServiceSelected.coerceIn(settings.searchServices.indices) + 1,
    )
