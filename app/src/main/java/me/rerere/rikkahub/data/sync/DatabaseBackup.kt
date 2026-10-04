package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.partsWithToolUndoAttachments
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.URLDecoder

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /** Redacts only the generated export copy, never the live database or any real attachment.
     * Returns only confirmed consultation-exclusive upload paths. Shared/unattributed files remain.
     */
    fun excludeConsultations(context: Context, snapshot: File, settingsJson: String = ""): Set<String> {
        val configuration = SQLiteConfiguration.configure(context,
            SQLiteDatabaseConfiguration(snapshot.absolutePath, SQLiteDatabase.OPEN_READWRITE))
        val excluded = SQLiteDatabase.openDatabase(configuration, null) { error("Backup database is corrupt") }.use { copy ->
            copy.execSQL("PRAGMA foreign_keys=ON")
            val hiddenIds = buildSet {
                copy.query("SELECT id FROM conversationentity WHERE consultation_binding != ''").use { rows ->
                    while (rows.moveToNext()) add(rows.getString(0))
                }
            }
            if (hiddenIds.isEmpty()) return@use emptySet<String>()
            val candidates = linkedSetOf<String>()
            fun collectMessages(table: String) {
                copy.query("SELECT m.messages FROM $table m JOIN conversationentity c ON c.id = m.conversation_id WHERE c.consultation_binding != ''").use { rows ->
                    while (rows.moveToNext()) {
                        val messages = try { JsonInstant.decodeFromString<List<UIMessage>>(rows.getString(0)) }
                        catch (_: Exception) { error("无法可靠识别咨询附件，本次备份未导出；原数据未改动。") }
                        messages.flatMap { it.partsWithToolUndoAttachments() }.localFileUrls().forEach { url ->
                            backupUploadPath(context.filesDir, url)?.let(candidates::add)
                        }
                    }
                }
            }
            collectMessages("message_node")
            collectMessages("orbis_compaction_backup_node")
            val hiddenFavorites = linkedSetOf<String>()
            copy.query("SELECT id, ref_key, ref_json, snapshot_json, meta_json FROM favorites").use { rows ->
                while (rows.moveToNext()) {
                    if (backupFavoriteConversation(rows.getString(1), rows.getString(2)) in hiddenIds) {
                        hiddenFavorites += rows.getString(0)
                        // Legacy favorites can contain full message snapshots, including old attachments.
                        for (index in 2..4) if (!rows.isNull(index)) {
                            backupJsonStrings(rows.getString(index)).filter { it.startsWith("file://") }.forEach { url ->
                                backupUploadPath(context.filesDir, url)?.let(candidates::add)
                            }
                        }
                    }
                }
            }
            copy.beginTransaction()
            try {
                val hidden = "SELECT id FROM conversationentity WHERE consultation_binding != ''"
                hiddenFavorites.forEach { copy.execSQL("DELETE FROM favorites WHERE id = ?", arrayOf(it)) }
                copy.execSQL("DELETE FROM message_fts WHERE conversation_id IN ($hidden)")
                copy.execSQL("DELETE FROM conversationentity WHERE consultation_binding != ''")
                val exclusive = candidates.filterNotTo(linkedSetOf()) { backupTextReferencesUpload(settingsJson, it) }
                // Protect all surviving data, not just current chat branches: favorites, rollback
                // pages, generated media and other independently retained data can share a file.
                // managed_files is the attachment inventory itself, not an independent reference.
                val tables = buildList {
                    copy.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                        while (rows.moveToNext()) {
                            val name = rows.getString(0)
                            if (name != "managed_files" && !name.startsWith("sqlite_") && !name.startsWith("room_") &&
                                name != "message_fts" && !name.startsWith("message_fts_")) add(name)
                        }
                    }
                }
                for (table in tables) {
                    if (exclusive.isEmpty()) break
                    copy.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { rows ->
                        while (rows.moveToNext() && exclusive.isNotEmpty()) for (index in 0 until rows.columnCount) {
                            if (rows.getType(index) == android.database.Cursor.FIELD_TYPE_STRING) {
                                val text = rows.getString(index)
                                exclusive.removeAll { backupTextReferencesUpload(text, it) }
                            }
                        }
                    }
                }
                exclusive.forEach { copy.execSQL("DELETE FROM managed_files WHERE relative_path = ?", arrayOf(it)) }
                candidates.clear(); candidates.addAll(exclusive)
                copy.setTransactionSuccessful()
            } finally { copy.endTransaction() }
            // Rebuild FTS segments/free pages in the copy, so deleted bodies are not left in it.
            copy.execSQL("INSERT INTO message_fts(message_fts) VALUES('rebuild')")
            copy.execSQL("VACUUM")
            checkpoint(copy)
            candidates.toSet()
        }
        removeSidecars(snapshot)
        return excluded
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    /** Metadata only; no Room initialization, migration, tool execution or conversation decoding. */
    fun conversationOwners(database: SupportSQLiteDatabase): Map<String, String> = buildMap {
        database.query("SELECT id, assistant_id FROM conversationentity WHERE consultation_binding = ''").use { rows ->
            while (rows.moveToNext()) {
                val conversation = rows.getString(0)
                val owner = rows.getString(1)
                check(put(conversation, owner) == null) { "context_pruning_backup_owner" }
            }
        }
    }

    /** Startup revalidation opens only the chosen database in read-only mode, before repositories. */
    fun conversationOwners(context: Context, databaseFile: File): Map<String, String> {
        if (!databaseFile.exists()) return emptyMap()
        require(databaseFile.isFile && !java.nio.file.Files.isSymbolicLink(databaseFile.toPath())) {
            "context_pruning_backup_owner"
        }
        val configuration = SQLiteConfiguration.configure(context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READONLY))
        return SQLiteDatabase.openDatabase(configuration, null) {
            error("context_pruning_backup_owner")
        }.use { conversationOwners(it) }
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}

/** Only local upload children are eligible; never an arbitrary file or a prefix sibling. */
internal fun backupUploadPath(filesDir: File, url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
    FileUtils.getRelativePathInFilesDir(filesDir, File(uri))?.takeIf {
        it.startsWith("upload/") && it.removePrefix("upload/").isNotBlank() && '/' !in it.removePrefix("upload/")
    }
}.getOrNull()

/** Exact reference ownership only; conflicting identifiers abort rather than drop a normal favorite. */
internal fun backupFavoriteConversation(refKey: String, refJson: String): String? {
    val key = refKey.split(':').takeIf { it.size == 3 && it[0] in setOf("node", "message") }?.get(1)
    val json = runCatching { Json.parseToJsonElement(refJson).jsonObject["conversationId"]?.jsonPrimitive?.content }.getOrNull()
    check(key == null || json == null || key == json) { "收藏引用不一致，本次备份未导出；原数据未改动。" }
    return json ?: key
}

internal fun backupJsonStrings(raw: String): List<String> = runCatching {
    buildList {
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> value.values.forEach(::collect)
                is JsonArray -> value.forEach(::collect)
                is JsonPrimitive -> if (value.isString) add(value.content)
            }
        }
        collect(Json.parseToJsonElement(raw))
    }
}.getOrDefault(emptyList())

private val backupPercentOctets = Regex("(?:%[0-9A-Fa-f]{2})+")

/** Conservative substring protection also covers quoted/encoded URLs embedded in text or settings. */
internal fun backupTextReferencesUpload(raw: String, relativePath: String): Boolean {
    val name = relativePath.removePrefix("upload/")
    val encodedName = URI(null, null, name, null).toASCIIString()
    // rawPath may retain Unicode. URI percent escapes are case-insensitive, and over-preserving
    // a case-variant name is safer than removing a potentially shared file from the export.
    fun matches(text: String): Boolean {
        if (text.contains(name) || text.contains(encodedName, ignoreCase = true)) return true
        // URI encoders may also escape unreserved ASCII (for example %73hared.png).
        // Decode valid octet runs once, not the whole text: literal '+' must stay '+',
        // and a malformed '%' elsewhere must not hide an otherwise valid reference.
        val decoded = backupPercentOctets.replace(text) { URLDecoder.decode(it.value, "UTF-8") }
        return decoded.contains(name)
    }
    return matches(raw) || backupJsonStrings(raw).any(::matches)
}
