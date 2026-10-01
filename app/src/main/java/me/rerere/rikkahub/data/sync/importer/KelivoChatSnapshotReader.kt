package me.rerere.rikkahub.data.sync.importer

import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File

/** Fixed chat projection of an untrusted snapshot. No custom extensions, migrations, prompts or settings. */
internal class KelivoChatSnapshotReader private constructor(private val database: SQLiteDatabase,
    private val checkCancelled: () -> Unit) : KelivoChatSource {
    override fun conversations(): List<KelivoChat> = database.query(
        "SELECT id,title,created_at,updated_at,version_selections_json FROM conversation_rows ORDER BY id COLLATE BINARY"
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            checkCancelled()
            add(KelivoChat(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4)))
        }
    } }

    override fun messages(chatId: String): List<KelivoMessage> {
        requireKelivoId(chatId)
        val attachments = database.query(
            "SELECT revision_id,COUNT(*) FROM message_asset_rows WHERE conversation_id COLLATE BINARY=? GROUP BY revision_id COLLATE BINARY",
            arrayOf(chatId)
        ).use { cursor -> buildMap { while (cursor.moveToNext()) {
            checkCancelled(); put(cursor.getString(0), cursor.getInt(1))
        } } }
        return database.query(
            "SELECT id,group_id,version,message_order,role,timestamp,reasoning_segments_json FROM message_rows " +
                "WHERE conversation_id COLLATE BINARY=? ORDER BY message_order,id COLLATE BINARY", arrayOf(chatId)
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                checkCancelled()
                require(size < KelivoChatLimits.MAX_WINDOW_MESSAGES) { "kelivo_window_messages" }
                add(KelivoMessage(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1), cursor.getInt(2),
                    cursor.getLong(3), cursor.getString(4), cursor.getLong(5),
                    if (cursor.isNull(6)) null else cursor.getString(6), attachments[cursor.getString(0)] ?: 0))
            }
        } }
    }

    override fun parts(messageId: String): List<KelivoPart> {
        requireKelivoId(messageId)
        return database.query("SELECT kind,payload FROM message_part_rows WHERE revision_id COLLATE BINARY=? ORDER BY ordinal",
            arrayOf(messageId)).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                checkCancelled()
                require(size < KelivoChatLimits.MAX_PARTS_PER_MESSAGE) { "kelivo_parts_count" }
                add(KelivoPart(cursor.getString(0), cursor.getString(1)))
            }
        } }
    }

    private fun validate(expectedChats: Int, expectedMessages: Int) {
        require(scalar("PRAGMA user_version") == 3L) { "kelivo_schema_version" }
        requireTable("conversation_rows", mapOf("id" to "TEXT", "title" to "TEXT", "created_at" to "INTEGER",
            "updated_at" to "INTEGER", "version_selections_json" to "TEXT"))
        requireTable("message_rows", mapOf("id" to "TEXT", "conversation_id" to "TEXT", "role" to "TEXT",
            "timestamp" to "INTEGER", "group_id" to "TEXT", "version" to "INTEGER", "message_order" to "INTEGER",
            "reasoning_segments_json" to "TEXT"))
        requireTable("message_part_rows", mapOf("conversation_id" to "TEXT", "revision_id" to "TEXT", "ordinal" to "INTEGER",
            "kind" to "TEXT", "payload" to "TEXT"))
        requireTable("message_asset_rows", mapOf("conversation_id" to "TEXT", "revision_id" to "TEXT",
            "asset_id" to "TEXT", "kind" to "TEXT"))
        require(expectedChats in 1..10000 && expectedMessages in 0..100000 &&
            scalar("SELECT COUNT(*) FROM conversation_rows") == expectedChats.toLong() &&
            scalar("SELECT COUNT(*) FROM message_rows") == expectedMessages.toLong()) { "kelivo_row_count" }
        require(scalar("SELECT COUNT(*) FROM message_part_rows") <= 300000 &&
            scalar("SELECT COUNT(*) FROM message_asset_rows") <= 100000) { "kelivo_part_count" }
        noRows("SELECT 1 FROM conversation_rows WHERE typeof(id)!='text' OR length(id) NOT BETWEEN 1 AND 256 OR " +
            "typeof(title)!='text' OR length(CAST(title AS BLOB))>4096 OR typeof(created_at)!='integer' OR " +
            "typeof(updated_at)!='integer' OR typeof(version_selections_json)!='text' OR length(CAST(version_selections_json AS BLOB))>524288 LIMIT 1")
        noRows("SELECT 1 FROM message_rows WHERE typeof(id)!='text' OR length(id) NOT BETWEEN 1 AND 256 OR " +
            "typeof(conversation_id)!='text' OR typeof(role)!='text' OR role NOT IN ('user','assistant','system','tool') OR " +
            "typeof(timestamp)!='integer' OR (group_id IS NOT NULL AND (typeof(group_id)!='text' OR length(group_id) NOT BETWEEN 1 AND 256)) OR " +
            "typeof(version)!='integer' OR version NOT BETWEEN 0 AND 2147483647 OR typeof(message_order)!='integer' OR message_order<0 OR " +
            "(reasoning_segments_json IS NOT NULL AND (typeof(reasoning_segments_json)!='text' OR length(CAST(reasoning_segments_json AS BLOB))>524288)) LIMIT 1")
        noRows("SELECT 1 FROM message_part_rows WHERE typeof(conversation_id)!='text' OR typeof(revision_id)!='text' OR " +
            "typeof(ordinal)!='integer' OR ordinal<0 OR typeof(kind)!='text' OR length(kind) NOT BETWEEN 1 AND 128 OR " +
            "typeof(payload)!='text' OR length(CAST(payload AS BLOB))>524288 LIMIT 1")
        noRows("SELECT 1 FROM message_asset_rows WHERE typeof(conversation_id)!='text' OR typeof(revision_id)!='text' OR " +
            "typeof(asset_id)!='text' OR length(asset_id) NOT BETWEEN 1 AND 256 OR typeof(kind)!='text' LIMIT 1")
        noRows("SELECT id FROM conversation_rows GROUP BY id COLLATE BINARY HAVING COUNT(*)>1 LIMIT 1")
        noRows("SELECT id FROM message_rows GROUP BY id COLLATE BINARY HAVING COUNT(*)>1 LIMIT 1")
        noRows("SELECT 1 FROM message_rows m LEFT JOIN conversation_rows c ON m.conversation_id COLLATE BINARY=c.id COLLATE BINARY WHERE c.id IS NULL LIMIT 1")
        noRows("SELECT 1 FROM message_part_rows p LEFT JOIN message_rows m ON p.revision_id COLLATE BINARY=m.id COLLATE BINARY " +
            "AND p.conversation_id COLLATE BINARY=m.conversation_id COLLATE BINARY WHERE m.id IS NULL LIMIT 1")
        noRows("SELECT 1 FROM message_asset_rows p LEFT JOIN message_rows m ON p.revision_id COLLATE BINARY=m.id COLLATE BINARY " +
            "AND p.conversation_id COLLATE BINARY=m.conversation_id COLLATE BINARY WHERE m.id IS NULL LIMIT 1")
        noRows("SELECT revision_id FROM message_part_rows GROUP BY revision_id COLLATE BINARY,ordinal HAVING COUNT(*)>1 LIMIT 1")
        require(scalar("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM message_part_rows") <= 64L*1024*1024 &&
            scalar("SELECT COALESCE(SUM(length(CAST(reasoning_segments_json AS BLOB))),0) FROM message_rows") <= 32L*1024*1024 &&
            scalar("SELECT COALESCE(SUM(length(CAST(version_selections_json AS BLOB))),0) FROM conversation_rows") <= 8L*1024*1024) {
            "kelivo_total_content_size"
        }
    }

    private fun requireTable(name: String, required: Map<String, String>) {
        database.query("SELECT type,sql FROM sqlite_master WHERE name=?", arrayOf(name)).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0) == "table" &&
                cursor.getString(1).trimStart().startsWith("CREATE TABLE", ignoreCase = true) && !cursor.moveToNext()) {
                "kelivo_required_table"
            }
        }
        val columns = database.query("PRAGMA table_xinfo(`$name`)").use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2).uppercase() to
                cursor.getInt(cursor.getColumnIndexOrThrow("hidden")))
        } }
        require(required.all { (column, type) -> columns[column] == (type to 0) }) { "kelivo_required_columns" }
    }
    private fun scalar(sql: String): Long {
        checkCancelled()
        return database.query(sql).use { require(it.moveToFirst()); it.getLong(0) }
    }
    private fun noRows(sql: String) {
        checkCancelled()
        database.query(sql).use { require(!it.moveToFirst()) { "kelivo_invalid_rows" } }
    }
    override fun close() = database.close()

    companion object {
        fun open(file: File, expectedChats: Int, expectedMessages: Int,
            checkCancelled: () -> Unit = {}): KelivoChatSnapshotReader {
            checkCancelled()
            val database = SQLiteDatabase.openDatabase(
                SQLiteDatabaseConfiguration(file.absolutePath, SQLiteDatabase.OPEN_READONLY), null
            ) { error("kelivo_corrupt_database") }
            val reader = KelivoChatSnapshotReader(database, checkCancelled)
            try {
                database.execSQL("PRAGMA query_only=ON")
                database.execSQL("PRAGMA trusted_schema=OFF")
                require(reader.scalar("PRAGMA query_only") == 1L && reader.scalar("PRAGMA trusted_schema") == 0L)
                reader.validate(expectedChats, expectedMessages)
                return reader
            } catch (failure: Throwable) { reader.close(); throw failure }
        }
    }
}
