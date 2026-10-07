package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.orbis.soup.SoupAction
import me.rerere.rikkahub.data.orbis.soup.soupErrorText

internal sealed interface SoupDraftPreparation<out T> {
    data class Ready<T>(val value: T) : SoupDraftPreparation<T>
    data class Blocked(val message: String, val canConfigureHost: Boolean) : SoupDraftPreparation<Nothing>
}

/** Local preflight only: producing a confirmation is not permission to execute a paid request. */
internal fun <T> prepareSoupDraft(prepare: () -> T): SoupDraftPreparation<T> = try {
    SoupDraftPreparation.Ready(prepare())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    SoupDraftPreparation.Blocked(
        message = soupErrorText(error),
        canConfigureHost = error.message in setOf(
            "soup_select_host", "soup_model_missing", "soup_model_disabled", "soup_provider_key_missing",
            "soup_direct_provider_required", "soup_dm_invalid", "soup_dm_confirmation_required",
            "soup_dm_unavailable",
        ),
    )
}

/** Keep a rejected draft and its explanation together instead of dismissing into an off-screen notice. */
@Composable
internal fun <T> OrbisSoupDraftDialog(
    action: SoupAction,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onPrepare: (String) -> SoupDraftPreparation<T>,
    onPrepared: (T) -> Unit,
    onConfigureHost: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isQuestion = action == SoupAction.ASK
    var failure by remember(action) { mutableStateOf<SoupDraftPreparation.Blocked?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(failure) { if (failure != null) scroll.scrollTo(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isQuestion) "向主持提问" else "提交完整推理") },
        text = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                failure?.let { problem ->
                    Text(problem.message, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (problem.canConfigureHost) TextButton(onClick = onConfigureHost) { Text("配置独立主持（保留输入）") }
                }
                Text(if (isQuestion) "问一个可以用是、否、是也不是或无关回答的问题。"
                    else "先和伙伴讨论，再写下完整故事。提交后你的剩余提问机会结束，伙伴仍可继续。")
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        if (next.length <= if (isQuestion) 1000 else 6000) {
                            onValueChange(next)
                            failure = null
                        }
                    },
                    label = { Text(if (isQuestion) "你的问题" else "你的推理") },
                    minLines = if (isQuestion) 3 else 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("下一步还会核对模型和费用，当前尚未发送。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val result = onPrepare(value)) {
                    is SoupDraftPreparation.Ready -> { failure = null; onPrepared(result.value) }
                    is SoupDraftPreparation.Blocked -> failure = result
                }
            }, enabled = enabled && value.isNotBlank()) { Text("核对这次发送…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("先不发") } },
    )
}
