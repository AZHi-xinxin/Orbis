package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local rendering preferences only. They never alter stored prose or model requests. */
@Serializable
data class OrbisChatFlowSettings(
    val enabled: Boolean = true,
    val distinguishActions: Boolean = false,
    val markers: Set<OrbisActionMarker> = emptySet(),
    val collapseActions: Boolean = false,
) {
    /** Both master switches override remembered marker/folding preferences. */
    val effectiveMarkers: Set<OrbisActionMarker>
        get() = if (enabled && distinguishActions) markers else emptySet()

    val foldActions: Boolean
        get() = effectiveMarkers.isNotEmpty() && collapseActions

    /** Disabling recognition deliberately keeps the previous selection for later. */
    fun withNoActionDistinction(noDistinction: Boolean): OrbisChatFlowSettings =
        copy(distinguishActions = !noDistinction)

    /** An explicit marker selection opts in; no marker is ever selected implicitly. */
    fun withMarker(marker: OrbisActionMarker, selected: Boolean): OrbisChatFlowSettings = copy(
        distinguishActions = if (selected) true else distinguishActions,
        markers = if (selected) markers + marker else markers - marker,
    )
}

@Serializable
enum class OrbisActionMarker(val open: Char, val close: Char, val label: String) {
    @SerialName("ascii_round") ASCII_ROUND('(', ')', "( )"),
    @SerialName("fullwidth_round") FULLWIDTH_ROUND('（', '）', "（ ）"),
    @SerialName("square") SQUARE('[', ']', "[ ]"),
    @SerialName("double_corner") DOUBLE_CORNER('『', '』', "『 』"),
    @SerialName("corner") CORNER('「', '」', "「 」"),
    @SerialName("tortoise") TORTOISE('〔', '〕', "〔 〕"),
    @SerialName("lenticular") LENTICULAR('【', '】', "【 】"),
}
