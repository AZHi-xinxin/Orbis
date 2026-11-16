package me.rerere.rikkahub.data.orbis.webshortcuts

import android.content.Context
import java.io.File
import java.util.UUID

internal fun openWebShortcuts(context: Context, assistant: String): WebShortcutRepository {
    val id = UUID.fromString(assistant).toString()
    return WebShortcutRepository(File(context.filesDir, "orbis-web-shortcuts/$id/shelf.json"))
}
