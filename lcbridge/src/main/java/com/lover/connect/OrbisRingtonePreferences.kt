package com.lover.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

enum class OrbisRingtoneKind { INCOMING, ALARM }

data class OrbisRingtoneSelection(val uri: String, val label: String)

/** Local-only preferences; imported settings must not confer document access. */
class OrbisRingtonePreferences(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("orbis_local_ringtones", Context.MODE_PRIVATE)

    fun get(kind: OrbisRingtoneKind): OrbisRingtoneSelection? = synchronized(lock) {
        val uri = prefs.getString("${kind.name}_uri", null)?.takeIf(::isLocalRingtoneUri) ?: return null
        OrbisRingtoneSelection(uri, prefs.getString("${kind.name}_label", null) ?: "已选择本地音频")
    }

    /** Call on IO after OpenDocument; no broad storage/media permission and no test playback. */
    fun select(kind: OrbisRingtoneKind, uri: Uri): OrbisRingtoneSelection = synchronized(lock) {
        require(isLocalRingtoneUri(uri.toString())) { "请选择系统文件选择器中的音频" }
        val resolver = context.contentResolver
        val previouslyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            require(resolver.getType(uri)?.let { it.startsWith("audio/") || it == "application/ogg" } != false) {
                "所选文件不是音频"
            }
            requireNotNull(resolver.openAssetFileDescriptor(uri, "r")).use {
                require(it.length != 0L) { "所选音频为空" }
            }
            val label = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.take(120) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "已选择本地音频"
            val old = get(kind)
            val ownedGrants = prefs.getStringSet("owned_grants", emptySet()).orEmpty().toMutableSet()
            if (!previouslyGranted) ownedGrants += uri.toString()
            check(prefs.edit().putString("${kind.name}_uri", uri.toString())
                .putString("${kind.name}_label", label).putStringSet("owned_grants", ownedGrants).commit()) {
                "铃声设置未保存，请重试"
            }
            old?.uri?.takeIf { it != uri.toString() }?.let(::releaseIfUnused)
            OrbisRingtoneSelection(uri.toString(), label)
        } catch (failure: Exception) {
            if (!previouslyGranted && OrbisRingtoneKind.entries.none { get(it)?.uri == uri.toString() }) {
                runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            throw failure
        }
    }

    fun reset(kind: OrbisRingtoneKind) = synchronized(lock) {
        val old = get(kind)
        check(prefs.edit().remove("${kind.name}_uri").remove("${kind.name}_label").commit()) {
            "铃声设置未保存，请重试"
        }
        old?.uri?.let(::releaseIfUnused)
    }

    private fun releaseIfUnused(raw: String) {
        if (OrbisRingtoneKind.entries.any { get(it)?.uri == raw }) return
        // Never revoke a grant acquired earlier by another feature in this application.
        val owned = prefs.getStringSet("owned_grants", emptySet()).orEmpty()
        if (raw !in owned) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess { prefs.edit().putStringSet("owned_grants", owned - raw).commit() }
    }

    companion object { private val lock = Any() }
}
