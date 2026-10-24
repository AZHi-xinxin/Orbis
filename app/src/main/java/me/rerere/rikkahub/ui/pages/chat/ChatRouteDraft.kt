package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.utils.base64Decode

/** A malformed or restored legacy route must not crash the chat or replace its draft. */
internal fun decodeChatRouteDraft(encoded: String?): String? = try {
    encoded?.base64Decode()
} catch (_: IllegalArgumentException) {
    null
}
