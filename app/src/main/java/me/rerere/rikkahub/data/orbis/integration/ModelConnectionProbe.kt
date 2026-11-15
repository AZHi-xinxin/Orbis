package me.rerere.rikkahub.data.orbis.integration

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.CustomHeader

internal class ModelConnectionProbeFailure(val code: String) : IllegalStateException(code)

/** Keep connection requirements, but never borrow the interactive thread's routing identity. */
internal fun connectionProbeHeaders(headers: List<CustomHeader>, session: String): List<CustomHeader> =
    headers.filterNot { it.name.lowercase() in setOf("x-st-thread-id", "x-session-id", "x-st-client-id", "x-st-request-id") }
        .map { if (it.name.equals("x-opencode-session", true)) it.copy(value = session) else it }

/** Discover against the effective endpoint, not the user's possibly incomplete saved list.
 * Only an exact advertised alias of the requested model is eligible. No endpoint/account
 * override from a different saved model can leak into this one-shot connectivity check.
 */
internal fun connectionProbeModel(selected: Model, advertised: List<Model>): Model {
    val suffix = "--auxiliary-no-memory"
    val names = advertised.map { it.modelId }.toSet()
    val chosen = when {
        selected.modelId.endsWith(suffix) && selected.modelId in names -> selected.modelId
        selected.modelId + suffix in names -> selected.modelId + suffix
        names.any { it.endsWith(suffix) } -> throw ModelConnectionProbeFailure("probe_auxiliary_alias_missing")
        selected.modelId !in names -> throw ModelConnectionProbeFailure("probe_model_not_advertised")
        else -> selected.modelId
    }
    return selected.copy(modelId = chosen, tools = emptySet(), customHeaders = emptyList(), customBodies = emptyList())
}
