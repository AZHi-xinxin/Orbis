package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.utils.base64Encode

/** Chat routes carry Base64 text, just like the system share entry point. */
internal fun galleryChatRoute(conversationId: String, draft: String): Screen.Chat =
    Screen.Chat(conversationId, text = draft.base64Encode())
