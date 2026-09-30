package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart

/** Local display policy; it does not rewrite the archive, message, or model request. */
val LocalImportedHistory = staticCompositionLocalOf { false }

fun List<UIMessagePart>.isDeepSeekHistory(): Boolean = any { part ->
    val metadata = when (part) {
        is UIMessagePart.Text -> part.metadata
        is UIMessagePart.Reasoning -> part.metadata
        else -> null
    }
    (metadata?.get("import_source") as? JsonPrimitive)?.content == "deepseek"
}
