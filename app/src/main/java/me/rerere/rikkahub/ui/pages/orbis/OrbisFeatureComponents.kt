package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01

/** A destination, not an action that silently changes settings or runs a tool. */
@Composable
internal fun OrbisFeatureRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = OrbisTheme.colors
    val action = if (onClick == null) Modifier else Modifier.clickable(
        role = Role.Button,
        onClickLabel = "打开$title",
        onClick = onClick,
    )
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(action).semantics(mergeDescendants = true) {}
            .heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = colors.tintedPanel,
            contentColor = colors.indigo,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
        }
        if (onClick != null) {
            Icon(HugeIcons.ArrowRight01, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = colors.mutedInk)
        }
    }
}

@Composable
internal fun OrbisFeatureNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = OrbisTheme.colors.mutedInk, modifier = Modifier.padding(horizontal = 8.dp))
}
