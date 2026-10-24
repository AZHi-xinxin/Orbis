package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Chat-only projection of a foreign RikkaHub backup, NOT an Orbis Room database.
 * The caller checkpoints a private staging copy first. This reader never migrates the source,
 * edits its identity hash, opens app settings, or installs source triggers/FTS in the target.
 * Compatibility is determined by the required chat projection, not a foreign app's database
 * version number or Room identity hash. Unknown table/column layouts still fail closed.
 */
internal class RikkaChatSnapshotReader private constructor(private val database: SQLiteDatabase,
    private val checkCancelled: () -> Unit) : RikkaChatSource {
    data class Chat(val id: String, val title: String, val createAt: Long, val updateAt: Long)
    data class Node(val id: String, val messages: String, val selectIndex: Int)

    override fun conversations(): List<Chat> = database.query(
        "SELECT id, title, create_at, update_at FROM ConversationEntity ORDER BY id"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                checkCancelled()
                add(Chat(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)))
            }
        }
    }

    fun nodes(conversationId: String): List<Node> = buildList {
        visitNodeRows(conversationId) { add(it) }
    }

    override suspend fun visitNodes(conversationId: String, visit: suspend (Node) -> Unit) {
        visitNodeRows(conversationId) { visit(it) }
    }

    private inline fun visitNodeRows(conversationId: String, visit: (Node) -> Unit) {
        requireRikkaSourceId(conversationId)
        // A foreign message may be larger than CursorWindow even though it can be imported
        // as several bounded historical records. Never put the whole source cell in a cursor.
        database.query(
            "SELECT id, length(CAST(messages AS BLOB)), select_index, " +
                "CASE WHEN length(CAST(messages AS BLOB)) <= 65536 THEN CAST(messages AS BLOB) ELSE NULL END " +
                "FROM message_node WHERE conversation_id COLLATE BINARY = ? ORDER BY node_index",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val id = cursor.getString(0)
                val inline = if (cursor.isNull(3)) null else cursor.getBlob(3)
                visit(Node(id, readMessages(conversationId, id, cursor.getLong(1), inline), cursor.getInt(2)))
            }
        }
    }

    private fun readMessages(conversationId: String, nodeId: String, size: Long, inline: ByteArray?): String =
        readRikkaStage(RikkaChatReadStage.MESSAGE) {
            if (size !in 0..RikkaChatLimits.MAX_NODE_BYTES.toLong())
                throw ArchiveReadException(ArchiveFailure.NODE_LIMIT)
            check(inline == null || inline.size.toLong() == size)
            val bytes = inline ?: ByteArray(size.toInt())
            var offset = if (inline == null) 0 else bytes.size
            while (offset < bytes.size) {
                checkCancelled()
                val count = minOf(64 * 1024, bytes.size - offset)
                database.query(
                    "SELECT substr(CAST(messages AS BLOB), ?, ?) FROM message_node " +
                        "WHERE conversation_id COLLATE BINARY = ? AND id COLLATE BINARY = ?",
                    arrayOf<Any>(offset + 1, count, conversationId, nodeId),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    val chunk = cursor.getBlob(0)
                    check(chunk.size == count && !cursor.moveToNext())
                    chunk.copyInto(bytes, offset)
                }
                offset += count
            }
            // Decode after joining bytes: a chunk may end in the middle of a Chinese character.
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }

    private fun validate() {
        checkCancelled()
        // user_version belongs to the exporting app's entire database, including tables that
        // chat-only import never reads. A number alone neither proves nor disproves compatibility.
        requireTable("ConversationEntity", mapOf("id" to "TEXT", "title" to "TEXT", "create_at" to "INTEGER", "update_at" to "INTEGER"))
        requireTable("message_node", mapOf("id" to "TEXT", "conversation_id" to "TEXT", "node_index" to "INTEGER", "messages" to "TEXT", "select_index" to "INTEGER"))
        val chats = scalar("SELECT COUNT(*) FROM ConversationEntity")
        val nodes = scalar("SELECT COUNT(*) FROM message_node")
        requireRikkaSchema(chats <= 10000 && nodes <= 1000000, RikkaSchemaCheck.ROW_COUNT)
        requireNoRows("SELECT 1 FROM ConversationEntity WHERE typeof(id) != 'text' OR length(trim(id)) = 0 OR length(id) > 128 OR typeof(title) != 'text' OR typeof(create_at) != 'integer' OR typeof(update_at) != 'integer' LIMIT 1", RikkaSchemaCheck.ROW_TYPES)
        requireNoRows("SELECT 1 FROM message_node WHERE typeof(id) != 'text' OR length(trim(id)) = 0 OR length(id) > 128 OR typeof(conversation_id) != 'text' OR typeof(messages) != 'text' OR typeof(node_index) != 'integer' OR node_index < 0 OR typeof(select_index) != 'integer' OR select_index < 0 OR select_index > 2147483647 LIMIT 1", RikkaSchemaCheck.ROW_TYPES)
        requireNoRows("SELECT id FROM ConversationEntity GROUP BY id COLLATE BINARY HAVING COUNT(*) > 1 LIMIT 1", RikkaSchemaCheck.DUPLICATE_IDS)
        requireNoRows("SELECT id FROM message_node GROUP BY id COLLATE BINARY HAVING COUNT(*) > 1 LIMIT 1", RikkaSchemaCheck.DUPLICATE_IDS)
        requireNoRows("SELECT conversation_id FROM message_node GROUP BY conversation_id COLLATE BINARY, node_index HAVING COUNT(*) > 1 LIMIT 1", RikkaSchemaCheck.NODE_ORDER)
        requireNoRows("SELECT 1 FROM message_node n LEFT JOIN ConversationEntity c ON n.conversation_id COLLATE BINARY = c.id COLLATE BINARY WHERE c.id IS NULL LIMIT 1", RikkaSchemaCheck.ORPHAN_NODES)
        // Upstream IDs are UUIDs. In particular, disallow '/' so deterministic compound IDs in
        // the importer cannot collide between (conversation='a/b', node='c') and ('a', 'b/c').
        database.query("SELECT id FROM ConversationEntity UNION ALL SELECT id FROM message_node").use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                try { requireRikkaSourceId(cursor.getString(0)) }
                catch (_: IllegalArgumentException) { throw RikkaChatReadException(RikkaChatReadStage.SCHEMA, RikkaSchemaCheck.SOURCE_IDS) }
            }
        }
        // Bound deserialization before loading message JSON; oversized archives fail without touching chats.
        ArchiveCapacity.requireSize(scalar("SELECT COALESCE(SUM(length(CAST(messages AS BLOB))), 0) FROM message_node"),
            ArchiveCapacity.MAX_STREAM_JSON_BYTES, allowEmpty = true)
        requireRikkaSchema(scalar("SELECT COALESCE(SUM(length(CAST(title AS BLOB))), 0) FROM ConversationEntity") <= 4L * 1024 * 1024,
            RikkaSchemaCheck.TITLE_SIZE)
        // Titles fit a cursor; message payloads use bounded byte chunks instead.
        requireRikkaSchema(scalar("SELECT COALESCE(MAX(length(CAST(title AS BLOB))), 0) FROM ConversationEntity") <= 64L * 1024 &&
            scalar("SELECT COALESCE(MAX(length(CAST(messages AS BLOB))), 0) FROM message_node") <= RikkaChatLimits.MAX_NODE_BYTES, RikkaSchemaCheck.ROW_SIZE)
    }

    private fun requireTable(name: String, required: Map<String, String>) {
        // Names below are constants, never identifiers supplied by an archive. Reject views/virtual tables
        // and generated required columns rather than executing a foreign projection expression.
        database.query("SELECT type, sql FROM sqlite_master WHERE name = ?", arrayOf(name)).use { cursor ->
            requireRikkaSchema(cursor.moveToFirst() && cursor.getString(0) == "table" &&
                cursor.getString(1).trimStart().startsWith("CREATE TABLE", ignoreCase = true) && !cursor.moveToNext(), RikkaSchemaCheck.TABLE)
        }
        val columns = database.query("PRAGMA table_xinfo(`$name`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(1), cursor.getString(2).uppercase() to cursor.getInt(cursor.getColumnIndexOrThrow("hidden")))
                }
            }
        }
        requireRikkaSchema(required.all { (column, type) -> columns[column] == (type to 0) }, RikkaSchemaCheck.COLUMNS)
        if (name == "ConversationEntity" && "nodes" in columns) {
            requireRikkaSchema(columns["nodes"] == ("TEXT" to 0), RikkaSchemaCheck.LEGACY_NODES)
            // Some exports retain this obsolete column as []. Never silently drop unconverted history.
            requireNoRows("SELECT 1 FROM ConversationEntity WHERE nodes IS NOT NULL AND trim(nodes) NOT IN ('', '[]') LIMIT 1", RikkaSchemaCheck.LEGACY_NODES)
        }
    }

    private fun scalar(sql: String): Long { checkCancelled(); return database.query(sql).use { it.moveToFirst(); it.getLong(0) } }
    private fun requireNoRows(sql: String, check: RikkaSchemaCheck) = database.query(sql).use { cursor ->
        requireRikkaSchema(!cursor.moveToFirst(), check)
    }

    override fun close() = database.close()

    companion object {
        fun open(context: Context, snapshot: File, checkCancelled: () -> Unit = {}): RikkaChatSnapshotReader {
            checkCancelled()
            val configuration = SQLiteConfiguration.configure(context,
                SQLiteDatabaseConfiguration(snapshot.absolutePath, SQLiteDatabase.OPEN_READONLY))
            val database = readRikkaStage(RikkaChatReadStage.SCHEMA) {
                try { SQLiteDatabase.openDatabase(configuration, null) { error("database_open_failed") } }
                catch (_: Exception) { throw RikkaChatReadException(RikkaChatReadStage.SCHEMA, RikkaSchemaCheck.OPEN) }
            }
            val reader = RikkaChatSnapshotReader(database, checkCancelled)
            try {
                reader.validate()
                return reader
            } catch (error: Throwable) {
                reader.close()
                throw error
            }
        }
    }
}
