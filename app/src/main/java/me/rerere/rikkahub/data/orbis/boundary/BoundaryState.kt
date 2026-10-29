package me.rerere.rikkahub.data.orbis.boundary

import kotlinx.serialization.Serializable

const val BOUNDARY_TOOL = "orbis_boundary"
const val BOUNDARY_HISTORY_LIMIT = 5
const val BOUNDARY_REQUEST_LIMIT = 3
const val BOUNDARY_MIN_PAUSE_MS = 60_000L
const val BOUNDARY_MAX_PAUSE_MS = 86_400_000L
val BOUNDARY_CATEGORIES = setOf("conversation", "model", "mcp", "tool")

@Serializable
data class BoundaryChoice(val enabled: Boolean = true, val hidden: Boolean = false)

@Serializable
data class ConversationBoundary(
    val paused: Boolean = true,
    val reason: String = "",
    val until: Long? = null,
    val allowRequest: Boolean = false,
    val requestUsed: Boolean = false,
    val revision: Long = 0,
    val scope: String = "conversation",
    val startedAt: Long? = null,
    val requestCount: Int = 0,
) {
    val requestsRemaining: Int get() = (BOUNDARY_REQUEST_LIMIT - requestCount.coerceAtLeast(0)).coerceAtLeast(0)
}

@Serializable
data class BoundaryEvent(val at: Long, val actor: String, val action: String, val target: String)

@Serializable
data class AssistantBoundary(
    val grants: Set<String> = emptySet(),
    val revision: Long = 0,
    val conversations: Map<String, ConversationBoundary> = emptyMap(),
    val assistantPause: ConversationBoundary? = null,
    val models: Map<String, BoundaryChoice> = emptyMap(),
    val mcps: Map<String, BoundaryChoice> = emptyMap(),
    val tools: Map<String, BoundaryChoice> = emptyMap(),
    val modelOverride: String? = null,
    val events: List<BoundaryEvent> = emptyList(),
) {
    fun pause(conversation: String?, now: Long): ConversationBoundary? =
        if ("conversation" !in grants) null else activeAssistantPause(now) ?: conversations[conversation]?.takeIf {
            it.paused && (it.until == null || it.until > now)
        }
    fun activeAssistantPause(now: Long): ConversationBoundary? =
        if ("conversation" !in grants) null else assistantPause?.takeIf {
            it.paused && (it.until == null || it.until > now)
        }
    fun permits(kind: String, id: String): Boolean = kind !in grants || when (kind) {
        "model" -> models[id]
        "mcp" -> mcps[id]
        "tool" -> tools[id]
        else -> null
    }?.enabled != false
    fun hidden(kind: String, id: String): Boolean = kind in grants && when (kind) {
        "model" -> models[id]
        "mcp" -> mcps[id]
        "tool" -> tools[id]
        else -> null
    }?.hidden == true
    fun event(now: Long, actor: String, action: String, target: String) = copy(
        revision = revision + 1,
        events = (events + BoundaryEvent(now, actor, action, target)).takeLast(BOUNDARY_HISTORY_LIMIT),
    )
}

/** Pure transition: caller supplies the owner-bound state, never an owner from tool arguments. */
fun changeBoundary(state: AssistantBoundary, expectedRevision: Long, conversation: String?,
    action: String, kind: String, id: String, hidden: Boolean = false,
    reason: String = "", until: Long? = null, allowRequest: Boolean = false,
    alternateModel: String? = null, now: Long, scope: String = "conversation"): AssistantBoundary {
    require(expectedRevision == state.revision) { "boundary_revision_changed" }
    require(kind in state.grants) { "boundary_category_not_granted" }
    require(scope in setOf("conversation", "assistant") && (kind == "conversation" || scope == "conversation")) { "boundary_scope_invalid" }
    if (kind == "conversation") {
        require(!conversation.isNullOrBlank()) { "boundary_conversation_missing" }
        require(action in setOf("pause", "resume")) { "boundary_action_invalid" }
        if (action == "pause") {
            require(until != null) { "boundary_deadline_missing" }
            require(until > now && until - now in BOUNDARY_MIN_PAUSE_MS..BOUNDARY_MAX_PAUSE_MS) { "boundary_deadline_out_of_range" }
        }
        val next = ConversationBoundary(action == "pause", reason, if (action == "pause") until else null,
            action == "pause" && allowRequest, revision = state.revision + 1, scope = scope,
            startedAt = if (action == "pause") now else null)
        return (if (scope == "assistant") state.copy(assistantPause = next)
            else state.copy(conversations = state.conversations + (conversation to next)))
            .event(now, "ai", action, if (scope == "assistant") "assistant" else conversation)
    }
    require(action in setOf("enable", "disable")) { "boundary_action_invalid" }
    require(id.isNotBlank()) { "boundary_target_missing" }
    require(kind != "tool" || id != BOUNDARY_TOOL) { "boundary_control_is_separate" }
    if (alternateModel != null) {
        require(kind == "model" && action == "disable" && alternateModel != id &&
            state.permits("model", alternateModel)) { "boundary_alternative_unavailable" }
    }
    val choice = BoundaryChoice(action == "enable", action == "disable" && hidden)
    return when (kind) {
        "model" -> state.copy(models = state.models + (id to choice),
            modelOverride = alternateModel ?: state.modelOverride)
        "mcp" -> state.copy(mcps = state.mcps + (id to choice))
        "tool" -> state.copy(tools = state.tools + (id to choice))
        else -> error("boundary_kind_invalid")
    }.event(now, "ai", action, "$kind:$id")
}

/** Provider-hosted tools are selectable options, not callable function schemas. */
fun builtinBoundaryId(tool: me.rerere.ai.provider.BuiltInTools): String = when (tool) {
    me.rerere.ai.provider.BuiltInTools.Search -> "model_builtin_search"
    me.rerere.ai.provider.BuiltInTools.UrlContext -> "model_builtin_url_context"
    me.rerere.ai.provider.BuiltInTools.ImageGeneration -> "model_builtin_image_generation"
}
fun AssistantBoundary.selectModelTools(model: me.rerere.ai.provider.Model) = model.copy(
    tools = model.tools.filter { permits("tool", builtinBoundaryId(it)) }.toSet(),
)
