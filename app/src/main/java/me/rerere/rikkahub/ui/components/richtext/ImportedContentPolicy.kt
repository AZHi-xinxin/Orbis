package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** Local display policy; it does not rewrite the archive, message, or model request. */
val LocalImportedHistory = staticCompositionLocalOf { false }

/** Mermaid image nodes can fetch URLs, so imported diagrams stay visible as source code. */
internal fun shouldAutoRenderMermaid(language: String, completeCodeBlock: Boolean, importedHistory: Boolean): Boolean =
    completeCodeBlock && language.equals("mermaid", ignoreCase = true) && !importedHistory

fun List<UIMessagePart>.isDeepSeekHistory(): Boolean = any { part ->
    val metadata = when (part) {
        is UIMessagePart.Text -> part.metadata
        is UIMessagePart.Reasoning -> part.metadata
        is UIMessagePart.Image -> part.metadata
        is UIMessagePart.Audio -> part.metadata
        is UIMessagePart.Video -> part.metadata
        is UIMessagePart.Document -> part.metadata
        else -> null
    }
    (metadata?.get("import_source") as? JsonPrimitive)?.content in setOf(
        "deepseek", "operit_json_v2", "kelivo_sqlite_v2", "polaris_export_v1", "claude_export_v1", "chatgpt_export_v1", "rikka_chat_v1")
}
