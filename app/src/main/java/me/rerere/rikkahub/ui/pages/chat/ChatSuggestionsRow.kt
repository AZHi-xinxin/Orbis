package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

/**
 * Dismissal is UI-only: this component cannot send messages, mutate the draft, or
 * change suggestion settings. A different message or suggestion batch resets it.
 * Keep this composed while [visible] is false (for example during scroll capture)
 * so a temporary presentation change does not undo the user's dismissal.
 */
@Composable
internal fun ChatSuggestionsRow(
    conversationId: String,
    latestMessageId: String?,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val group = listOf(conversationId, latestMessageId.orEmpty()) + suggestions
    // rememberSaveable inputs reset live changes but are not validated on restore.
    // Save the dismissed identity too, so restoring against newer messages cannot
    // hide an unrelated group that arrived while this screen was absent.
    val dismissalSaver = listSaver<Boolean, String>(
        save = { if (it) group else emptyList() },
        restore = { it == group },
    )
    var dismissed by rememberSaveable(
        conversationId, latestMessageId, suggestions, stateSaver = dismissalSaver,
    ) {
        mutableStateOf(false)
    }
    if (!visible || dismissed || suggestions.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(suggestions) { suggestion ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onClickSuggestion(suggestion) }
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = suggestion, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Outside the scrolling row: the close action remains reachable even for long suggestions.
        IconButton(
            onClick = { dismissed = true },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = "收起本组建议回复",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
