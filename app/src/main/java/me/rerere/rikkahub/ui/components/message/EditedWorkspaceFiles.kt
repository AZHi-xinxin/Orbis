package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.pages.chat.safeRootfsFilePath

/** A host-confirmed file and its write-time workspace, never the assistant's current binding. */
internal data class EditedWorkspaceFile(val workspaceId: String, val path: String) {
    val name: String get() = path.substringAfterLast('/')
    fun saveExportState(): List<String> = listOf(workspaceId, path)
}

private val editedFileJson = Json { isLenient = false }
private val writeReceiptKeys = setOf("path", "name", "isDirectory", "sizeBytes", "updatedAt")
private val editReceiptKeys = setOf("path", "replacements", "matchStrategy", "sizeBytes", "updatedAt")
private val workspaceBindingId = Regex("[A-Za-z0-9_-]{1,128}")

/** Restore only the saved original pair; a missing or malformed pair is never repaired from current settings. */
internal fun restoreEditedWorkspaceExport(saved: List<String>): EditedWorkspaceFile? =
    saved.takeIf { it.size == 2 && workspaceBindingId.matches(it[0]) && safeRootfsFilePath(it[1]) }
        ?.let { EditedWorkspaceFile(it[0], it[1]) }

/** Pure receipt inspection: no input parsing, file access, tool execution or current-workspace fallback. */
internal fun editedWorkspaceFiles(parts: List<UIMessagePart>): List<EditedWorkspaceFile> =
    parts.filterIsInstance<UIMessagePart.Tool>().mapNotNull(::successfulEditedWorkspaceFile).distinct()

private fun successfulEditedWorkspaceFile(tool: UIMessagePart.Tool): EditedWorkspaceFile? {
    if (tool.toolName != "workspace_write_file" && tool.toolName != "workspace_edit_file") return null
    if (!tool.isExecuted || tool.approvalState !in listOf(ToolApprovalState.Auto, ToolApprovalState.Approved) ||
        tool.metadata?.containsKey("orbis_host_tool_failure") == true) return null
    val prefix = "workspace:"
    val suffix = ":${tool.toolName}"
    val binding = tool.hostApproval?.stableId ?: return null
    if (!binding.startsWith(prefix) || !binding.endsWith(suffix)) return null
    val workspaceId = binding.removePrefix(prefix).removeSuffix(suffix)
    if (!workspaceBindingId.matches(workspaceId)) return null

    // These built-ins emit one small structured Text receipt. Output merely existing is not success.
    val output = tool.output.singleOrNull() as? UIMessagePart.Text ?: return null
    if (output.text.length > 16_384) return null
    val receipt = runCatching { editedFileJson.parseToJsonElement(output.text) as? JsonObject }.getOrNull() ?: return null
    val path = receipt.string("path")?.takeIf(::safeRootfsFilePath) ?: return null
    if (receipt.number("sizeBytes")?.takeIf { it >= 0 } == null ||
        receipt.number("updatedAt")?.takeIf { it >= 0 } == null) return null
    when (tool.toolName) {
        "workspace_write_file" -> if (!writeReceiptKeys.containsAll(receipt.keys) ||
            receipt.string("name") != path.substringAfterLast('/') || receipt.boolean("isDirectory") != false) return null
        "workspace_edit_file" -> if (!editReceiptKeys.containsAll(receipt.keys) ||
            receipt.number("replacements")?.takeIf { it > 0 } == null ||
            ("matchStrategy" in receipt && receipt.string("matchStrategy") !in setOf("exact", "line_trimmed", "block_anchor"))) return null
    }
    return EditedWorkspaceFile(workspaceId, path)
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
