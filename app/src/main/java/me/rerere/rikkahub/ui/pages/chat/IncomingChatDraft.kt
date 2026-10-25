package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** Navigation3 retains this marker with the entry, not with its visible composition.
 * A new explicit handoff gets a fresh delivery id, even when its text is identical. */
@Composable internal fun ConsumeIncomingChatDraft(chatId: String, deliveryId: String?, text: String?, files: List<String>,
    onImport: suspend () -> Unit) {
    var consumed by rememberSaveable(chatId, deliveryId, text, files) { mutableStateOf(false) }
    LaunchedEffect(chatId, deliveryId, text, files) {
        if(!consumed) { onImport(); consumed = true }
    }
}
