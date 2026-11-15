package me.rerere.rikkahub.ui.components.richtext

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** This parser represents an angle-wrapped destination as AUTOLINK, not LINK_DESTINATION. */
internal fun ASTNode.markdownLinkDestination(source: String): String {
    val destination = children.firstOrNull {
        it.type == MarkdownElementTypes.LINK_DESTINATION || it.type == MarkdownElementTypes.AUTOLINK
    } ?: return ""
    // Only remove Markdown delimiters. Percent escapes and fragment payloads belong to the URL.
    return source.substring(destination.startOffset, destination.endOffset).removeSurrounding("<", ">")
}

/** Adapt only this inline node for the library's HTML provider; retain its escaping/safe-link rules. */
internal fun ASTNode.withCompatibleLinkDestination(): ASTNode {
    if (children.none { it.type == MarkdownElementTypes.AUTOLINK }) return this
    val original = this
    return object : ASTNode by original {
        override val children = original.children.map { child ->
            // With <url>, the parser also labels a following quoted title as LINK_DESTINATION.
            val compatibleType = when (child.type) {
                MarkdownElementTypes.AUTOLINK -> MarkdownElementTypes.LINK_DESTINATION
                MarkdownElementTypes.LINK_DESTINATION -> MarkdownElementTypes.LINK_TITLE
                else -> child.type
            }
            if (compatibleType == child.type) child else object : ASTNode by child {
                override val type = compatibleType
            }
        }
    }
}

/** Compose's default link click swallows IllegalArgumentException; report it before that boundary. */
internal class MarkdownUriHandler(private val delegate: UriHandler, private val onFailure: () -> Unit) : UriHandler {
    override fun openUri(uri: String) {
        if (uri.isBlank()) {
            onFailure()
            return
        }
        try {
            delegate.openUri(uri)
        } catch (_: ActivityNotFoundException) {
            onFailure()
        } catch (_: IllegalArgumentException) {
            onFailure()
        } catch (_: SecurityException) {
            onFailure()
        }
    }
}

@Composable
internal fun MarkdownLinkFeedback(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val original = LocalUriHandler.current
    val handler = remember(original, context) {
        if (original is MarkdownUriHandler) original else MarkdownUriHandler(original) {
            // Never put URL fragments, query payloads or exception messages in logs/toasts.
            Toast.makeText(context, "无法打开链接，请复制链接后在浏览器中打开。", Toast.LENGTH_LONG).show()
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
