package me.rerere.rikkahub.data.ai.approval

import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

fun approvalFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonical() })
    is JsonArray -> JsonArray(map { it.canonical() })
    else -> this
}

fun approvalInputFingerprint(input: String): String = approvalFingerprint(
    Json.parseToJsonElement(input.ifBlank { "{}" }).canonical().toString()
)

fun bindToolApproval(tool: Tool, assistantId: String, store: ToolApprovalStore): Tool {
    if (tool.name == "ask_user" || tool.name == "toy_bluetooth_stop") return tool
    val base = tool.hostApproval ?: HostToolApproval(
        stableId = "local:${tool.name}",
        revision = approvalFingerprint("local-v1\n${tool.description}\n${tool.parameters()}"),
        label = tool.name,
    )
    val bound = base.copy(
        stableId = "$assistantId:${base.stableId}",
        revision = approvalFingerprint("${store.installationScope}\n${base.revision}"),
        // Execution approval is the user's choice, not a hardcoded sensitive-tool prohibition.
        // Keep the field for old messages/clients; target and OS guards are independent.
        rememberable = true,
    )
    return tool.copy(
        hostApproval = bound,
        needsApproval = { args ->
            (tool.needsApproval(args) || tool.requiresFreshApproval(args)) && !(tool.isApprovalCurrent() &&
                (store.allowsAll(assistantId) || store.permits(assistantId, bound)))
        },
        requiresFreshApproval = { false },
    )
}

fun UIMessagePart.Tool.awaitHostApproval(definition: Tool): UIMessagePart.Tool = copy(
    approvalState = ToolApprovalState.Pending,
    hostApproval = definition.hostApproval,
    approvalInputFingerprint = approvalInputFingerprint(input),
)

/** Old rememberable=false snapshots can match; authority comes from current identity/revision/input, not UI flags. */
fun UIMessagePart.Tool.matchesHostApproval(definition: Tool): Boolean {
    if (!definition.isApprovalCurrent()) return false
    val live = definition.hostApproval ?: return hostApproval == null
    val shown = hostApproval ?: return false
    return live.stableId == shown.stableId && live.revision == shown.revision &&
        approvalInputFingerprint == approvalInputFingerprint(input)
}

/** Rendering only; the execution input is never replaced with this redacted copy. */
fun redactApprovalJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, child) ->
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        if (listOf("token", "password", "passwd", "secret", "authorization", "apikey", "cookie", "credential")
                .any { normalized.contains(it) }) JsonPrimitive("[已隐藏凭证]") else redactApprovalJson(child)
    })
    is JsonArray -> JsonArray(value.map(::redactApprovalJson))
    else -> value
}
