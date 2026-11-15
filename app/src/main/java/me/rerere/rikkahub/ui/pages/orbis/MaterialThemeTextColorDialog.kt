package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.pages.setting.components.OrbisChatTextColorEditor
import java.util.Locale

@Composable internal fun MaterialThemeTextColorDialog(
    body: ThemePackage,
    colors: MaterialTextColors,
    target: MaterialTextTarget,
    image: String?,
    assets: Map<String, String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    OrbisInterfaceTheme {
        var draft by rememberSaveable { mutableStateOf(colors.get(target)) }
        val displayed = colors.with(target, draft).display(body)
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(target.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeChatPreview(displayed, image, compact = target != MaterialTextTarget.CHROME, assets = assets, followHumanAccent = true,
                        followHumanChrome = colors.with(target,draft).chrome == null)
                    Text("只调整这款主题的字色，背景和玻璃质感保持原样。", style = MaterialTheme.typography.bodySmall)
                    OrbisChatTextColorEditor(
                        color = draft?.let(::themeArgb),
                        defaultColor = if(target==MaterialTextTarget.CHROME) OrbisTheme.colors.ink else Color(themeArgb(body.defaultTextColor(target))),
                        onColorChange = { draft = it?.let { argb -> String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF) } },
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = { onSave(draft) }, modifier = Modifier.testTag("material-text-save")) { Text("保存字色") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
        )
    }
}
