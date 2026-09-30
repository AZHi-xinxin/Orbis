package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/**
 * Chat-only projection of a foreign RikkaHub backup, NOT an Orbis Room database.
 * The caller checkpoints a private staging copy first. This reader never migrates the source,
 * edits its identity hash, opens app settings, or installs source triggers/FTS in the target.
 * Versions 16..26 use the supported message_node / UIMessage layout; other layouts fail closed.
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

    fun nodes(conversationId: String): List<Node> = database.query(
        "SELECT id, messages, select_index FROM message_node WHERE conversation_id COLLATE BINARY = ? ORDER BY node_index",
        arrayOf(conversationId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Node(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
        }
    }

    override suspend fun visitNodes(conversationId: String, visit: suspend (Node) -> Unit) {
        requireRikkaSourceId(conversationId)
        database.query(
            "SELECT id, messages, select_index FROM message_node WHERE conversation_id COLLATE BINARY = ? ORDER BY node_index",
            arrayOf(conversationId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                visit(Node(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
            }
        }
    }

    private fun validate() {
        checkCancelled()
        val version = database.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }
        require(version in 16..26) {
            "暂不支持此备份的数据库版本（$version）；请保留原 ZIP，使用受支持版本重新导出或等待导入器适配"
        }
        requireTable("ConversationEntity", mapOf("id" to "TEXT", "title" to "TEXT", "create_at" to "INTEGER", "update_at" to "INTEGER"))
        requireTable("message_node", mapOf("id" to "TEXT", "conversation_id" to "TEXT", "node_index" to "INTEGER", "messages" to "TEXT", "select_index" to "INTEGER"))
        val chats = scalar("SELECT COUNT(*) FROM ConversationEntity")
        val nodes = scalar("SELECT COUNT(*) FROM message_node")
        require(chats <= 10000) { "一次最多导入一万个窗口，请拆分备份" }
        require(nodes <= 100000) { "聊天消息过多，请拆分备份" }
        requireNoRows("SELECT 1 FROM ConversationEntity WHERE typeof(id) != 'text' OR length(trim(id)) = 0 OR length(id) > 128 OR typeof(title) != 'text' OR typeof(create_at) != 'integer' OR typeof(update_at) != 'integer' LIMIT 1")
        requireNoRows("SELECT 1 FROM message_node WHERE typeof(id) != 'text' OR length(trim(id)) = 0 OR length(id) > 128 OR typeof(conversation_id) != 'text' OR typeof(messages) != 'text' OR typeof(node_index) != 'integer' OR node_index < 0 OR typeof(select_index) != 'integer' OR select_index < 0 OR select_index > 2147483647 LIMIT 1")
        requireNoRows("SELECT id FROM ConversationEntity GROUP BY id COLLATE BINARY HAVING COUNT(*) > 1 LIMIT 1")
        requireNoRows("SELECT id FROM message_node GROUP BY id COLLATE BINARY HAVING COUNT(*) > 1 LIMIT 1")
        requireNoRows("SELECT conversation_id FROM message_node GROUP BY conversation_id COLLATE BINARY, node_index HAVING COUNT(*) > 1 LIMIT 1")
        requireNoRows("SELECT 1 FROM message_node n LEFT JOIN ConversationEntity c ON n.conversation_id COLLATE BINARY = c.id COLLATE BINARY WHERE c.id IS NULL LIMIT 1")
        // Upstream IDs are UUIDs. In particular, disallow '/' so deterministic compound IDs in
        // the importer cannot collide between (conversation='a/b', node='c') and ('a', 'b/c').
        database.query("SELECT id FROM ConversationEntity UNION ALL SELECT id FROM message_node").use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                requireRikkaSourceId(cursor.getString(0))
            }
        }
        // Bound deserialization before loading message JSON; oversized archives fail without touching chats.
        require(scalar("SELECT COALESCE(SUM(length(CAST(messages AS BLOB))), 0) FROM message_node") <= 64L * 1024 * 1024) {
            "聊天正文超过本次安全导入上限（64 MB），请拆分备份"
        }
        require(scalar("SELECT COALESCE(SUM(length(CAST(title AS BLOB))), 0) FROM ConversationEntity") <= 4L * 1024 * 1024) {
            "聊天标题总量过大，已停止导入；原聊天未更改"
        }
        // Stay below Android CursorWindow limits, including UTF-8 -> UTF-16 expansion.
        require(scalar("SELECT COALESCE(MAX(length(CAST(title AS BLOB))), 0) FROM ConversationEntity") <= 64L * 1024 &&
            scalar("SELECT COALESCE(MAX(length(CAST(messages AS BLOB))), 0) FROM message_node") <= 1024L * 1024) {
            "备份中单条消息或标题过大，已停止导入；请保留原 ZIP 以便分批适配"
        }
    }

    private fun requireTable(name: String, required: Map<String, String>) {
        // Names below are constants, never identifiers supplied by an archive. Reject views/virtual tables
        // and generated required columns rather than executing a foreign projection expression.
        database.query("SELECT type, sql FROM sqlite_master WHERE name = ?", arrayOf(name)).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0) == "table" &&
                cursor.getString(1).trimStart().startsWith("CREATE TABLE", ignoreCase = true) && !cursor.moveToNext()) {
                "备份缺少受支持的聊天数据表（$name）；原聊天未更改"
            }
        }
        val columns = database.query("PRAGMA table_xinfo(`$name`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(1), cursor.getString(2).uppercase() to cursor.getInt(cursor.getColumnIndexOrThrow("hidden")))
                }
            }
        }
        require(required.all { (column, type) -> columns[column] == (type to 0) }) {
            "备份聊天表结构暂不兼容（$name）；请保留 ZIP 以便适配，原聊天未更改"
        }
        if (name == "ConversationEntity" && "nodes" in columns) {
            require(columns["nodes"] == ("TEXT" to 0)) { "备份含未知旧消息格式；原聊天未更改" }
            // Upstream 16+ keeps this obsolete column as []. Never silently drop unconverted history.
            requireNoRows("SELECT 1 FROM ConversationEntity WHERE nodes IS NOT NULL AND trim(nodes) NOT IN ('', '[]') LIMIT 1")
        }
    }

    private fun scalar(sql: String): Long { checkCancelled(); return database.query(sql).use { it.moveToFirst(); it.getLong(0) } }
    private fun requireNoRows(sql: String) = database.query(sql).use { cursor ->
        require(!cursor.moveToFirst()) { "备份聊天数据格式异常或消息顺序有冲突；原聊天未更改" }
    }

    override fun close() = database.close()

    companion object {
        fun open(context: Context, snapshot: File, checkCancelled: () -> Unit = {}): RikkaChatSnapshotReader {
            checkCancelled()
            val configuration = SQLiteConfiguration.configure(context,
                SQLiteDatabaseConfiguration(snapshot.absolutePath, SQLiteDatabase.OPEN_READONLY))
            val database = SQLiteDatabase.openDatabase(configuration, null) { error("备份数据库已损坏") }
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
