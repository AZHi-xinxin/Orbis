package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.privateroom.hasPrivateRoomToolContent
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomPublicParts
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        indexConversationInTransaction(conversation)
    }

    /** Caller owns the Room transaction dispatcher; do not hop threads around raw SQLite writes. */
    internal fun indexConversationInTransaction(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
        includeConsultations: Boolean = false,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val indexedText = mutableListOf<String>()
        val assistantFilter = if (assistantId != null) {
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id = ?
            )
            """.trimIndent()
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet, text
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            AND length(text) <= 10000
            $assistantFilter
            ${if (includeConsultations) "" else "AND EXISTS (SELECT 1 FROM conversationentity c WHERE c.id = message_fts.conversation_id AND c.consultation_binding = '')"}
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            if (assistantId != null) arrayOf(keyword, assistantId) else arrayOf(keyword)
        )
        // Search text can contain private conversation details; never log it.
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
                indexedText.add(it.getString(6))
            }
        }
        // Existing indexes (including imported backups) can predate the private presentation rule.
        // Recheck the bounded, owner-qualified source, not just the stale snippet. Fail closed when
        // the original is missing/oversized/invalid or its visible text no longer matches the index.
        val visibleTextByNode = mutableMapOf<Pair<String, String>, Map<String, String>>()
        results.filterIndexed { index, result ->
            val key = result.conversationId to result.nodeId
            val visible = visibleTextByNode[key] ?: visibleFtsMessages(
                database.messageNodeDao().getBoundedNodeOfConversation(result.conversationId, result.nodeId)?.messages
            ).also { visibleTextByNode[key] = it }
            visible[result.messageId]?.let { it.isNotBlank() && it == indexedText[index] } == true
        }
    }
}

internal fun UIMessage.extractFtsText(): String =
    // Public Text remains searchable even in a private-tool turn. Never index the empty-tool
    // placeholder, Reasoning, tool input/output, translation, or other derived attachments.
    (if (hasPrivateRoomToolContent()) privateRoomPublicParts(parts, includePlaceholder = false) else parts)
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
        .ifBlank { "" }

/** Only current visible text may validate a historical search hit. No source text is logged. */
internal fun visibleFtsMessages(encoded: String?): Map<String, String> {
    if (encoded == null || encoded.length > 786_432 || !ftsJsonNestingAllowed(encoded)) return emptyMap()
    return try {
        val messages = JsonInstant.decodeFromString<List<UIMessage>>(encoded)
        // Duplicate message identities cannot safely disambiguate an old index entry.
        if (messages.map { it.id }.toSet().size != messages.size) emptyMap()
        else messages.associate { it.id.toString() to it.extractFtsText() }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun ftsJsonNestingAllowed(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 64) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return !quoted && depth == 0
}
