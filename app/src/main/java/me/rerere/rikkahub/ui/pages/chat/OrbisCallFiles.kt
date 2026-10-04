package me.rerere.rikkahub.ui.pages.chat

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation

/** Evidence-backed display links, not instructions and never an excuse to rerun an old tool. */
internal sealed interface OrbisCallFile {
    val key: String
    val name: String
    val sourceMessageId: String

    data class Workspace(
        val path: String,
        val sizeBytes: Long,
        val updatedAt: Long,
        override val sourceMessageId: String,
        val toolCallId: String,
        /** Null means old history has no verifiable binding; never guess the current workspace. */
        val workspaceId: String?,
        /** Kept by reference; decoding belongs to an explicit preview/export action, not composition. */
        private val writeInput: String? = null,
    ) : OrbisCallFile {
        override val key: String get() = "workspace:${workspaceId ?: "unbound"}:$path"
        override val name: String get() = path.substringAfterLast('/')
        val hasWriteSnapshot: Boolean get() = writeInput != null

        /**
         * Call only on IO after an explicit preview/export action; input decoding is capped at
         * 16 MiB characters. The UI separately caps preview bytes (2 MiB), and must label this as
         * the write-time copy, NOT the current on-disk version. An edit never synthesizes a copy.
         */
        fun writtenTextOrNull(): String? {
            val input = writeInput ?: return null
            if (input.length > 16 * 1024 * 1024) return null
            val arguments = runCatching { receiptJson.parseToJsonElement(input) as? JsonObject }.getOrNull() ?: return null
            if (arguments.string("path")?.replace('\\', '/')?.trim() != path) return null
            return arguments.string("text")
        }
    }

    data class Document(
        val part: UIMessagePart.Document,
        override val sourceMessageId: String,
    ) : OrbisCallFile {
        override val key: String get() = "document:${part.url}"
        override val name: String get() = part.fileName
    }
}

/**
 * Inspect selected message parts only. Text mentioning a path, read_file output, shell stdout,
 * failed/denied/unexecuted tool calls and inactive branches are not proof of a produced file.
 * This function does not touch storage. The UI must revalidate existence and ownership on click.
 */
internal fun orbisCallFiles(nodes: List<MessageNode>): List<OrbisCallFile> {
    val result = linkedMapOf<String, OrbisCallFile>()
    nodes.forEach { node ->
        val message = node.currentMessage.privateRoomSafePresentation()
        message.parts.forEach partLoop@ { part ->
            when (part) {
                is UIMessagePart.Document -> safeCallDocument(part, message.id.toString())?.let { result[it.key] = it }
                is UIMessagePart.Tool -> {
                    if (part.approvalState is ToolApprovalState.Denied || !part.isExecuted) return@partLoop
                    successfulWorkspaceFile(part, message.id.toString())?.let { result[it.key] = it }
                    // A structured attachment is different from a filename quoted in tool text.
                    part.output.filterIsInstance<UIMessagePart.Document>().forEach { document ->
                        safeCallDocument(document, message.id.toString())?.let { result[it.key] = it }
                    }
                }
                else -> Unit
            }
        }
    }
    return result.values.toList()
}

private val workspaceWriteKeys = setOf("path", "name", "isDirectory", "sizeBytes", "updatedAt")
private val workspaceEditKeys = setOf("path", "replacements", "matchStrategy", "sizeBytes", "updatedAt")
private val receiptJson = Json { isLenient = false }

private fun successfulWorkspaceFile(tool: UIMessagePart.Tool, messageId: String): OrbisCallFile.Workspace? {
    if (tool.toolName !in setOf("workspace_write_file", "workspace_edit_file")) return null
    // These built-ins produce exactly one short JSON Text receipt. Never parse the potentially
    // multi-megabyte requested file contents in tool.input merely to find its model-supplied path.
    val output = tool.output.singleOrNull() as? UIMessagePart.Text ?: return null
    if (output.text.length > 16_384) return null
    val receipt = runCatching { receiptJson.parseToJsonElement(output.text) as? JsonObject }.getOrNull() ?: return null
    val path = receipt.string("path")?.takeIf(::safeRootfsFilePath) ?: return null
    val size = receipt.number("sizeBytes")?.takeIf { it >= 0 } ?: return null
    val updated = receipt.number("updatedAt")?.takeIf { it >= 0 } ?: return null
    when (tool.toolName) {
        "workspace_write_file" -> {
            if (!workspaceWriteKeys.containsAll(receipt.keys) ||
                receipt.string("name") != path.substringAfterLast('/') ||
                receipt.boolean("isDirectory") != false) return null
        }
        "workspace_edit_file" -> {
            if (!workspaceEditKeys.containsAll(receipt.keys) ||
                receipt.number("replacements")?.takeIf { it > 0 } == null) return null
        }
    }
    val prefix = "workspace:"
    val suffix = ":${tool.toolName}"
    val binding = tool.hostApproval?.stableId?.takeIf { it.startsWith(prefix) && it.endsWith(suffix) }
        ?.removePrefix(prefix)?.removeSuffix(suffix)
        ?.takeIf { it.isNotBlank() && it.length <= 128 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
    return OrbisCallFile.Workspace(path, size, updated, messageId, tool.toolCallId, binding,
        writeInput = tool.input.takeIf { tool.toolName == "workspace_write_file" })
}

/** Linux rootfs paths are sandbox-relative absolute paths, not Android host file URIs. */
internal fun safeRootfsFilePath(path: String): Boolean =
    path.length in 2..4096 && path.startsWith('/') && !path.startsWith("//") && !path.endsWith('/') &&
        path.none { it == '\\' || it.code < 32 || it.code == 127 } &&
        path.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun safeCallDocument(part: UIMessagePart.Document, messageId: String): OrbisCallFile.Document? {
    if (part.url.length !in 1..16_384 || part.url.any { it.code < 32 || it.code == 127 || it == '\\' }) return null
    val uri = runCatching { URI(part.url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in setOf("file", "content", "https", "http") || uri.isOpaque || uri.userInfo != null) return null
    if (scheme == "file" && !uri.authority.isNullOrBlank()) return null
    if (scheme != "file" && uri.authority.isNullOrBlank()) return null
    val decodedPath = uri.path ?: return null
    if (decodedPath.isBlank() || decodedPath.any { it.code < 32 || it.code == 127 || it == '\\' } ||
        decodedPath.split('/').any { it == "." || it == ".." }) return null
    val name = part.fileName.ifBlank { decodedPath.substringAfterLast('/') }
        .replace('\\', '/').substringAfterLast('/').filterNot { it.code < 32 || it.code == 127 }.take(160)
        .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
    return OrbisCallFile.Document(part.copy(url = uri.normalize().toASCIIString(), fileName = name), messageId)
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
