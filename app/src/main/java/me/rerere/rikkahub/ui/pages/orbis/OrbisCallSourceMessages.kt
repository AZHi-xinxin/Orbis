package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation
import me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolPart
import me.rerere.rikkahub.data.orbis.privateroom.PRIVATE_ROOM_CONTENT_HIDDEN

private const val SOURCE_PREVIEW_CHARS = 16 * 1024
private const val SOURCE_PARTS_PER_NODE = 64

/**
 * Historical inspection only: no services, repositories, web renderers, or action callbacks.
 * In particular, tools and attachments must not reuse the live chat's actionable components.
 * Nodes are lazy; unusually large individual nodes/blocks have explicitly labelled previews.
 */
@Composable
internal fun OrbisCallSourceMessages(nodes: List<MessageNode>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("orbis-call-source-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "notice") {
            Text(
                "只读查看 · 仅显示当前选中的消息分支；不会执行工具或打开网页。每块最多预览 16384 个字符，原始记录不变。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (nodes.isEmpty()) item(key = "empty") { Text("没有可显示的原始消息。") }
        // Include the index so a damaged duplicate node ID cannot crash this recovery-style viewer.
        items(count = nodes.size, key = { index -> "$index:${nodes[index].id}" }) { index ->
            val node = nodes[index]
            val message = node.messages.getOrNull(node.selectIndex)?.privateRoomSafePresentation()
            Column(
                modifier = Modifier.fillMaxWidth().testTag("orbis-call-source-node-$index"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${index + 1} · ${when (message?.role) {
                    MessageRole.USER -> "人类"
                    MessageRole.ASSISTANT -> "助手"
                    MessageRole.SYSTEM -> "系统"
                    MessageRole.TOOL -> "工具"
                    null -> "消息分支不可读"
                }}", style = MaterialTheme.typography.titleSmall)
                if (message == null) {
                    Text("当前分支索引无效；未更改或丢弃原记录。")
                } else {
                    if (message.parts.isEmpty()) Text("（空消息）")
                    message.parts.take(SOURCE_PARTS_PER_NODE).forEachIndexed { partIndex, part ->
                        key(message.id, partIndex) {
                            SourcePart(part, "$index-$partIndex")
                        }
                    }
                    if (message.parts.size > SOURCE_PARTS_PER_NODE) {
                        Text("此消息包含 ${message.parts.size} 块内容，仅预览前 $SOURCE_PARTS_PER_NODE 块；原始记录未修改。")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun SourcePart(part: UIMessagePart, tag: String) {
    if (part.isPrivateRoomToolPart()) {
        Text(PRIVATE_ROOM_CONTENT_HIDDEN)
        return
    }
    when (part) {
        is UIMessagePart.Text -> SourceLiteral(sourceTextPreview(part.text))
        is UIMessagePart.Document -> Text(sourceDocumentLabel(part.fileName))
        is UIMessagePart.Reasoning -> SourceExpandable("思考记录", "$tag-reasoning") {
            SourceLiteral(sourceTextPreview(part.reasoning))
        }
        is UIMessagePart.Tool -> {
            Text("工具 · ${part.toolName.take(160)}", style = MaterialTheme.typography.labelLarge)
            Text(sourceToolStatus(part.approvalState, part.isExecuted), style = MaterialTheme.typography.bodySmall)
            SourceExpandable("输入与结果", "$tag-tool") {
                Text("输入", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(sourceTextPreview(part.input))
                Text("结果", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(remember(part) { sourceOutputPreview(part.output) })
            }
        }
        is UIMessagePart.ServerTool -> {
            Text("服务端工具 · ${part.toolName.take(160)}", style = MaterialTheme.typography.labelLarge)
            Text(when (part.status) {
                ServerToolStatus.IN_PROGRESS -> "记录状态：进行中（不代表现在仍在执行）"
                ServerToolStatus.COMPLETED -> "记录状态：已完成"
                ServerToolStatus.FAILED -> "记录状态：失败"
            }, style = MaterialTheme.typography.bodySmall)
            SourceExpandable("输入与结果", "$tag-tool") {
                Text("输入", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(remember(part) { sourceJsonPreview(part.input) })
                Text("结果", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(remember(part) { sourceJsonPreview(part.output) })
            }
        }
        is UIMessagePart.ToolCall -> {
            Text("历史工具调用 · ${part.toolName.take(160)}")
            Text(sourceToolStatus(part.approvalState, false), style = MaterialTheme.typography.bodySmall)
            SourceExpandable("输入", "$tag-tool") { SourceLiteral(sourceTextPreview(part.arguments)) }
        }
        is UIMessagePart.ToolResult -> {
            Text("历史工具结果 · ${part.toolName.take(160)}")
            Text("已有结果（成败请查看结果原文）", style = MaterialTheme.typography.bodySmall)
            SourceExpandable("输入与结果", "$tag-tool") {
                Text("输入", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(remember(part) { sourceJsonPreview(part.arguments) })
                Text("结果", style = MaterialTheme.typography.labelMedium)
                SourceLiteral(remember(part) { sourceJsonPreview(part.content) })
            }
        }
        is UIMessagePart.Image -> Text("[图片附件 · 此处不加载]")
        is UIMessagePart.Audio -> Text("[音频附件 · 此处不播放]")
        is UIMessagePart.Video -> Text("[视频附件 · 此处不播放]")
        UIMessagePart.Search -> Text("[历史搜索记录]")
    }
}

private fun sourceToolStatus(approval: ToolApprovalState, hasOutput: Boolean): String = when {
    approval is ToolApprovalState.Denied -> "记录状态：已拒绝"
    hasOutput -> "已有结果（成败请查看结果原文）"
    approval is ToolApprovalState.Pending -> "记录状态：等待批准；此处不可操作，请返回原消息处理"
    else -> "尚无工具结果（历史记录，不会在此重试）"
}

@Composable
private fun SourceExpandable(label: String, tag: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("orbis-call-source-toggle-$tag")) {
        Text(if (expanded) "收起$label" else "展开$label")
    }
    if (expanded) Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
private fun SourceLiteral(preview: SourcePreview) {
    // Plain Text, intentionally not Markdown/WebView/ChatMessage, even for HTML/SVG/tool payloads.
    SelectionContainer { Text(preview.text.ifEmpty { "（空）" }, style = MaterialTheme.typography.bodySmall) }
    if (preview.truncated) Text("预览已截断；原始记录未修改。", style = MaterialTheme.typography.labelSmall)
}

private data class SourcePreview(val text: String, val truncated: Boolean)
private fun sourceTextPreview(value: String) = SourcePreview(value.take(SOURCE_PREVIEW_CHARS), value.length > SOURCE_PREVIEW_CHARS)

private fun sourceDocumentLabel(fileName: String): String {
    val start = maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1
    val name = fileName.substring(start, minOf(fileName.length, start + 160)).filterNot { it.isISOControl() }
    return "文件 · ${name.ifBlank { "未命名文件" }}（文件入口在卡片下方）"
}

/** Bounded preview builder: never stringify or join an entire historical tool payload first. */
private class SourcePreviewBuilder {
    private val text = StringBuilder()
    private var truncated = false
    private var visited = 0
    val full get() = text.length >= SOURCE_PREVIEW_CHARS

    fun append(value: String) {
        val length = minOf(value.length, SOURCE_PREVIEW_CHARS - text.length)
        text.append(value, 0, length)
        if (length < value.length) truncated = true
    }

    fun skip() { truncated = true }

    fun json(value: JsonElement?, depth: Int = 0) {
        if (full || depth > 12 || ++visited > 1024) { skip(); return }
        when (value) {
            null -> append("（无）")
            is JsonPrimitive -> if (value.isString) quoted(value.content) else append(value.content)
            is JsonObject -> {
                append("{")
                val iterator = value.entries.iterator()
                var first = true
                while (iterator.hasNext()) {
                    if (full || visited >= 1024) { skip(); break }
                    val entry = iterator.next()
                    if (!first) append(", ")
                    first = false
                    quoted(entry.key); append(": "); json(entry.value, depth + 1)
                }
                append("}")
            }
            is JsonArray -> {
                append("[")
                val iterator = value.iterator()
                var first = true
                while (iterator.hasNext()) {
                    if (full || visited >= 1024) { skip(); break }
                    if (!first) append(", ")
                    first = false
                    json(iterator.next(), depth + 1)
                }
                append("]")
            }
        }
    }

    private fun quoted(value: String) {
        append("\"")
        for (char in value) {
            if (full) { skip(); break }
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (char.isISOControl()) "\\u${char.code.toString(16).padStart(4, '0')}" else char.toString()
            })
        }
        append("\"")
    }

    fun result() = SourcePreview(text.toString(), truncated)
}

private fun sourceJsonPreview(value: JsonElement?) = SourcePreviewBuilder().apply { json(value) }.result()

@Suppress("DEPRECATION")
private fun sourceOutputPreview(parts: List<UIMessagePart>): SourcePreview {
    val builder = SourcePreviewBuilder()
    if (parts.isEmpty()) builder.append("（尚无工具结果）")
    parts.take(128).forEachIndexed { index, part ->
        if (index > 0) builder.append("\n")
        if (part.isPrivateRoomToolPart()) {
            builder.append(PRIVATE_ROOM_CONTENT_HIDDEN)
            return@forEachIndexed
        }
        when (part) {
            is UIMessagePart.Text -> builder.append(part.text)
            is UIMessagePart.Document -> builder.append(sourceDocumentLabel(part.fileName))
            is UIMessagePart.Reasoning -> builder.append("[思考记录]\n").also { builder.append(part.reasoning) }
            is UIMessagePart.Image -> builder.append("[图片附件 · 此处不加载]")
            is UIMessagePart.Audio -> builder.append("[音频附件 · 此处不播放]")
            is UIMessagePart.Video -> builder.append("[视频附件 · 此处不播放]")
            is UIMessagePart.Tool -> { builder.append("[嵌套工具记录 · "); builder.append(part.toolName.take(160)); builder.append(" · 此处不执行]") }
            is UIMessagePart.ServerTool -> { builder.append("[服务端工具结果]\n"); builder.json(part.output) }
            is UIMessagePart.ToolResult -> builder.json(part.content)
            is UIMessagePart.ToolCall -> builder.append("[历史工具调用 · 此处不执行]")
            UIMessagePart.Search -> builder.append("[历史搜索记录]")
        }
    }
    if (parts.size > 128) builder.skip()
    return builder.result()
}
