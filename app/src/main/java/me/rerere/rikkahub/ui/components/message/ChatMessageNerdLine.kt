package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.presentMessageUsage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Duration

/** A plain-language summary, with optional detail. The persisted display switch never deletes usage. */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val settings = LocalSettings.current.displaySetting
    val settingsStore = koinInject<SettingsStore>() // Existing singleton, not a new store per message.
    val toaster = LocalToaster.current
    // Keep this scope in composition while the display preference hides the content.
    val scope = rememberCoroutineScope()
    var hiding by remember { mutableStateOf(false) }
    var hideFailed by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(message.id.toString()) { mutableStateOf(false) }
    val presentation = remember(message.usage, message.createdAt, message.finishedAt) {
        val millis = message.finishedAt?.let { finished ->
            runCatching { Duration.between(message.createdAt.toJavaLocalDateTime(), finished.toJavaLocalDateTime()).toMillis() }.getOrNull()
        }
        presentMessageUsage(message.usage, millis)
    }
    if (!settings.showTokenUsage || presentation == null) return

    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起用量记录" else "展开用量记录") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(presentation.summary, fontSize = 11.sp, lineHeight = 17.sp,
                    color = color, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", fontSize = 10.sp, lineHeight = 15.sp, color = color)
            }
            TextButton(onClick = {
                if (!hiding) {
                    hiding = true
                    hideFailed = false
                    scope.launch {
                        try {
                            // Finish this small, explicitly requested local preference write even
                            // if hiding the row or leaving the conversation disposes its UI.
                            withContext(NonCancellable) {
                                settingsStore.update { current ->
                                    current.copy(displaySetting = current.displaySetting.copy(showTokenUsage = false))
                                }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            hideFailed = true
                            toaster.show("显示偏好未能保存；若用量记录重新出现，请在设置中重试。", type = ToastType.Error)
                        }
                        finally { hiding = false }
                    }
                }
            }, enabled = !hiding, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("隐藏", fontSize = 11.sp, lineHeight = 17.sp, color = color)
            }
        }
        if (hideFailed) Text("显示偏好未能保存，请在设置中重新调整。", fontSize = 11.sp,
            lineHeight = 17.sp, color = MaterialTheme.colorScheme.error)
        if (expanded) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    presentation.details.forEach { detail ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(detail.label, fontSize = 11.sp, lineHeight = 16.sp,
                                color = color, fontWeight = FontWeight.SemiBold)
                            Text(detail.value, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text("隐藏后可在 设置→外观与显示→消息用量记录 重新开启",
                        fontSize = 11.sp, lineHeight = 18.sp, color = color)
                }
            }
        }
    }
}
