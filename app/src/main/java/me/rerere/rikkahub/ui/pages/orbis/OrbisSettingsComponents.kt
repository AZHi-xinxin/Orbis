package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Local to the Orbis settings page; does not change typography or row density in other editors. */
@Composable
internal fun OrbisSettingsSection(
    title: String,
    description: String,
    glyph: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OrbisTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(), color = colors.panel, contentColor = colors.ink,
        shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, colors.border.copy(alpha = .65f)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起$title" else "展开$title", onClick = onToggle)
                    .semantics(mergeDescendants = true) { stateDescription = if (expanded) "已展开" else "已收起" }
                    .heightIn(min = 64.dp).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(Modifier.size(35.dp), color = colors.tintedPanel, shape = RoundedCornerShape(12.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(glyph, fontSize = 19.sp, color = colors.indigo) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                    Text(description, fontSize = 10.sp, lineHeight = 15.sp, color = colors.mutedInk)
                }
                Text(if (expanded) "⌄" else "›", fontSize = 18.sp, color = colors.mutedInk)
            }
            if (expanded) Column(Modifier.padding(start = 13.dp, end = 13.dp, bottom = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
internal fun OrbisSettingsLink(title: String, description: String, onClick: (() -> Unit)?) {
    val colors = OrbisTheme.colors
    val action = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "打开$title", onClick = onClick)
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(description, fontSize = 10.sp, lineHeight = 15.sp, color = colors.mutedInk)
        }
        if (onClick != null) Text("›", fontSize = 18.sp, color = colors.mutedInk)
    }
}

@Composable
internal fun OrbisSettingsToggle(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = OrbisTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "已开启" else "已关闭" },
        shape = RoundedCornerShape(18.dp), color = if (checked) colors.sand.copy(alpha = .3f) else colors.raisedPanel,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .7f)),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 12.sp, lineHeight = 17.sp, color = colors.ink, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 10.sp, lineHeight = 15.sp, color = colors.mutedInk)
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        }
    }
}

@Composable
internal fun OrbisSettingsNote(text: String) {
    val colors = OrbisTheme.colors
    Surface(color = colors.sand.copy(alpha = .55f), shape = RoundedCornerShape(11.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            fontSize = 10.sp, lineHeight = 16.sp, color = colors.onSand)
    }
}

@Composable
internal fun OrbisSettingsFact(label: String, value: String) {
    val colors = OrbisTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = colors.mutedInk)
        Text(value, fontSize = 12.sp, lineHeight = 18.sp, color = colors.ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun OrbisSettingsDivider() {
    HorizontalDivider(color = OrbisTheme.colors.border.copy(alpha = .7f))
}
