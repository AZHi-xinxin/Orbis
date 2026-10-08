package me.rerere.rikkahub.data.orbis.sentinel

import java.io.File
import java.io.InputStream

/**
 * AtomicFile.openRead() owns recovery of the committed .bak file. Checking only baseFile
 * first bypasses that recovery and can mistake an interrupted write for an empty store.
 * A lone .new is uncommitted evidence, not a fresh installation or a usable backup.
 * The caller must hold the same lock used by its AtomicFile writer.
 */
internal fun readSentinelAtomicText(baseFile: File, openRead: () -> InputStream): String? {
    val backup = File(baseFile.path + ".bak")
    if (!baseFile.exists() && !backup.exists()) {
        check(!File(baseFile.path + ".new").exists()) { "sentinel_store_incomplete_write" }
        return null
    }
    // Do not swallow read/recovery errors or replace an unreadable store with empty rules.
    return openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
}
