package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.group.OrbisGroupMessage
import org.koin.compose.koinInject
import java.util.Locale

/** A local draft reference, never automatic injection or an authorization to send. */
internal fun groupQuotedReply(message: OrbisGroupMessage): String {
    val limit = 400
    var end = message.text.length.coerceAtMost(limit)
    if (end > 0 && end < message.text.length && message.text[end - 1].isHighSurrogate() && message.text[end].isLowSurrogate()) end--
    val excerpt = message.text.substring(0, end).ifBlank {
        message.attachments.joinToString("、") { it.name.take(80) }.ifBlank { "（无文字）" }
    }
    val name = message.name.map { if (it.isISOControl()) ' ' else it }.joinToString("").take(80)
    return "[引用 #${message.sequence} · $name]\n" + excerpt.lines().joinToString("\n") { "> $it" } +
        (if (end < message.text.length) "\n> （引用节选，原文仍在群聊）" else "") + "\n\n"
}

@Composable
internal fun GroupTranslationDialog(message: OrbisGroupMessage, settings: Settings, language: Locale, onDismiss: () -> Unit) {
    val handler = koinInject<TranslationHandler>()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val configured = settings.providers.flatMap { provider -> provider.models.filter { it.id == settings.translateModeId }.map { provider to it } }
    val available = !settings.init && configured.size == 1 && configured.single().first.enabled &&
        configured.single().second.providerOverwrite?.enabled != false
    var translation by remember(message.id, language) { mutableStateOf("") }
    var error by remember(message.id, language) { mutableStateOf<String?>(null) }
    var busy by remember(message.id, language) { mutableStateOf(false) }
    var job by remember(message.id, language) { mutableStateOf<Job?>(null) }
    var page by remember { mutableIntStateOf(0) }
    val pages = ((translation.length + 3999) / 4000).coerceAtLeast(1)
    val shownPage = page.coerceIn(0, pages - 1)
    fun close() { job?.cancel(); onDismiss() }
    AlertDialog(onDismissRequest = ::close, title = { Text("翻译为 ${language.getDisplayLanguage(Locale.getDefault())}") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (available) "仅把这条消息的文字交给已配置的翻译模型。原群聊不修改，译文不会自动发出或写入记忆。"
                    else "请先在后台设置配置翻译模型；没有改用群成员或私人聊天模型。", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (translation.isNotEmpty()) {
                    val start = groupTranslationBoundary(translation, shownPage * 4000)
                    val end = groupTranslationBoundary(translation, (shownPage + 1) * 4000)
                    SelectionContainer { Text(translation.substring(start, end)) }
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(translation)) }) { Text("复制译文") }
                        if (pages > 1) {
                            TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text("上一段") }
                            TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage + 1 < pages) { Text("下一段") }
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = available && !busy, onClick = {
                if (!busy) {
                    busy = true; translation = ""; error = null; page = 0
                    job = scope.launch {
                        try {
                            withTimeout(90_000) {
                                handler.translateText(settings, message.text, language).collect { value ->
                                    check(value.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "translation_limit" }
                                    translation = value
                                }
                            }
                            if (translation.isBlank()) error = "翻译模型没有返回文字；没有自动重试。"
                        } catch (_: TimeoutCancellationException) { error = "翻译等待超时，已停止；没有自动重试。" }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "本次翻译未完成或超过安全长度。原文完整保留，没有自动重试。" }
                        finally { busy = false }
                    }
                }
            }) { Text(if (translation.isEmpty()) "开始翻译" else "重新翻译") }
        }, dismissButton = { TextButton(onClick = ::close) { Text(if (busy) "取消并关闭" else "关闭") } },
    )
}

private fun groupTranslationBoundary(text: String, offset: Int): Int {
    val end = offset.coerceIn(0, text.length)
    return if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end
}
