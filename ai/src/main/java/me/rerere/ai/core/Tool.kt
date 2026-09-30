package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    val hostApproval: HostToolApproval? = null,
    /** Legacy metadata only. The Orbis host lets users persist any execution approval; never use as a target/OS guard. */
    val requiresFreshApproval: (JsonElement) -> Boolean = { false },
    val isApprovalCurrent: () -> Boolean = { true },
)

/** Host-only identity. Never supplied by the model or sent as a tool parameter. */
@Serializable
data class HostToolApproval(
    val stableId: String,
    val revision: String,
    val label: String,
    /** Retained for old persisted cards. The host's current approval policy decides whether to save a grant. */
    val rememberable: Boolean = true,
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
        /** Keep each tool's references and definitions in the same schema document. */
        @SerialName("\$defs") val defs: JsonObject? = null,
        @SerialName("\$schema") val schema: String? = null,
    ) : InputSchema() {
        // Existing local approval fingerprints use this string. Optional schema metadata must
        // not invalidate every unrelated built-in tool's remembered approval on an app upgrade.
        override fun toString(): String = if (defs == null && schema == null) {
            "Obj(properties=$properties, required=$required)"
        } else {
            "Obj(properties=$properties, required=$required, defs=$defs, schema=$schema)"
        }
    }
}
