package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.context.orbisChatTextStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme

/** Presentation state only. Collapsing never changes model-visible human content. */
@Composable
internal fun HumanThoughtCard(content: String, messageKey: String) {
    var expanded by remember(messageKey) { mutableStateOf(false) }
    val ink = LocalOrbisChatTextColor.current ?: OrbisTheme.colors.mutedInk
    val family = LocalTextStyle.current.fontFamily
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .testTag("human-thought-toggle")
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HugeIcons.Idea01, null, Modifier.size(16.dp), tint = ink)
            Text("思考链", style = MaterialTheme.typography.labelLarge.copy(fontFamily = family),
                color = ink, modifier = Modifier.weight(1f))
            Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                null, Modifier.size(16.dp), tint = ink)
        }
        if (expanded) SelectionContainer {
            MarkdownBlock(content = content.ifBlank { "（为空）" },
                style = orbisChatTextStyle(MaterialTheme.typography.bodySmall.copy(fontFamily = family)),
                modifier = Modifier.fillMaxWidth().testTag("human-thought-content"))
        }
    }
}
