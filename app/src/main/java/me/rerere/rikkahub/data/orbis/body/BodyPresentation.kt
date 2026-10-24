package me.rerere.rikkahub.data.orbis.body

/** Human-only view preference. Never part of the assistant's state, tools, settings sync or backups. */
@kotlinx.serialization.Serializable
data class BodyDisplayPreference(val privateVisible: Boolean = false)

data class BodyMetricPresentation(val dimension: BodyDimension, val value: Double, val peak: Double,
    val delta: Double, val isPrivate: Boolean)

/** One privacy projection used by cards, peaks, chart selection and event history. */
object BodyPresentation {
    fun dimensions(state: BodyState, privateVisible: Boolean, conversation: String? = null): List<BodyDimension> {
        val privateIds = BodyRules.privateIds(state)
        return state.versions.lastOrNull()?.definition?.dimensions.orEmpty()
            .filter { privateVisible && state.snapshot.sessions[conversation]?.privateShared == true || it.id !in privateIds }
    }
    fun metrics(state: BodyState, conversation: String, privateVisible: Boolean): List<BodyMetricPresentation> {
        val version = state.versions.lastOrNull() ?: return emptyList()
        val session = state.snapshot.sessions[conversation]?.takeIf { it.visible } ?: return emptyList()
        val values = BodyRules.values(state.snapshot, conversation, version.definition)
        val privateIds = BodyRules.privateIds(state)
        return dimensions(state, privateVisible, conversation).map { dim ->
            val prior = state.logs.filter { it.conversation == conversation && dim.id in it.values }.dropLast(1).lastOrNull()?.values?.get(dim.id)
            BodyMetricPresentation(dim, values.getValue(dim.id),
                (if (dim.scope == "persistent") state.snapshot.peaks[dim.id] else session.peaks[dim.id]) ?: dim.baseline,
                values.getValue(dim.id) - (prior ?: values.getValue(dim.id)), dim.id in privateIds)
        }
    }
    fun chart(state: BodyState, conversation: String, dimensionId: String, privateVisible: Boolean,
        from: Long, to: Long): List<BodyLog> {
        if (state.snapshot.sessions[conversation]?.visible != true) return emptyList()
        val dim = dimensions(state, privateVisible, conversation).find { it.id == dimensionId } ?: return emptyList()
        // No cross-chat logs in a chat-specific view, including shared persistent dimensions.
        return state.logs.filter { it.conversation == conversation && it.at in from..to && dim.id in it.values }
            .map { it.copy(values = mapOf(dim.id to it.values.getValue(dim.id))) }
    }
    fun history(state: BodyState, conversation: String, privateVisible: Boolean): List<BodyLog> {
        if (state.snapshot.sessions[conversation]?.visible != true) return emptyList()
        val allowed = dimensions(state, privateVisible, conversation).map { it.id }.toSet()
        val hidden = BodyRules.privateIds(state) - allowed
        return state.logs.filter { log ->
            log.conversation == conversation &&
                state.versions.getOrNull(log.version - 1)?.definition?.rules?.find { it.event == log.event }
                    ?.deltas?.keys?.none { it in hidden } != false
        }.map { it.copy(values = it.values.filterKeys { id -> id in allowed }) }
    }
}
