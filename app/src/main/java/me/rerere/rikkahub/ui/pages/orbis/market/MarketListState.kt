package me.rerere.rikkahub.ui.pages.orbis.market

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject

/** A request owns its rows from the first composition, before loading effects can run.
 * Notifications have no item id and must never be measured as a feed or wish list. */
internal data class MarketListRequest(
    val page: String,
    val category: String,
    val mineWishes: Boolean,
    val revision: Int,
) {
    val loadsRows: Boolean get() = page in setOf("discover", "favorites", "mine", "wishes", "messages")

    fun path(offset: Int): String = when (page) {
        "wishes" -> "/wishes?offset=$offset" + if (mineWishes) "&mode=mine" else ""
        "messages" -> "/notifications"
        else -> "/items?mode=${when (page) { "favorites" -> "favorites"; "mine" -> "mine"; else -> "feed" }}&offset=$offset" +
            if (category.isNotEmpty()) "&category=$category" else ""
    }
}

internal class MarketListState(val request: MarketListRequest) {
    var rows by mutableStateOf(emptyList<JsonObject>())
    var cases by mutableStateOf(emptyList<JsonObject>())
    var next by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(request.loadsRows)
    var loaded by mutableStateOf(false)
    var dataRevision = -1
    val scroll = androidx.compose.foundation.lazy.LazyListState()
}

@Composable
internal fun rememberMarketListState(request: MarketListRequest): MarketListState {
    // The market owns this cache only while open. Keep each list's loaded pages and exact
    // pixel offset through detail navigation; refresh creates a wholly new generation.
    val lists = remember(request.revision) { mutableMapOf<MarketListRequest, MarketListState>() }
    val transient = remember(request) { MarketListState(request) }
    return if (request.loadsRows) lists.getOrPut(request) { MarketListState(request) } else transient
}
