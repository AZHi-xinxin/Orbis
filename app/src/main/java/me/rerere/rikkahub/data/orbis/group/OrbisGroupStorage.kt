package me.rerere.rikkahub.data.orbis.group

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate durable store. It never opens or updates the private-chat Room database. */
internal interface OrbisGroupStorage {
    suspend fun recoverInterrupted(now: Long)
    suspend fun rooms(): List<OrbisGroupRoom>
    suspend fun room(id: String): OrbisGroupRoom?
    suspend fun messages(roomId: String, limit: Int, beforeSequence: Long? = null): List<OrbisGroupMessage>
    suspend fun message(id: String): OrbisGroupMessage?
    suspend fun latestRound(roomId: String): GroupRound?
    suspend fun countMessages(roomId: String): Long
    suspend fun commit(room: OrbisGroupRoom? = null, round: GroupRound? = null, messages: List<OrbisGroupMessage> = emptyList())
    suspend fun close() {}
}

internal class AndroidOrbisGroupStorage(context: Context, dbName: String = "orbis-groups-v1.db") : OrbisGroupStorage {
    init { require(dbName.matches(Regex("[A-Za-z0-9_-]+\\.db"))) }
    private val json = Json { encodeDefaults = true }
    private val helper = object : SQLiteOpenHelper(context.applicationContext, dbName, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE rooms (id TEXT PRIMARY KEY NOT NULL, updated_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE rounds (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, room_id TEXT NOT NULL REFERENCES rooms(id), status TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(id,room_id))")
            db.execSQL("CREATE INDEX rounds_room ON rounds(room_id, seq)")
            db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, room_id TEXT NOT NULL REFERENCES rooms(id), round_id TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL, FOREIGN KEY(round_id,room_id) REFERENCES rounds(id,room_id))")
            db.execSQL("CREATE INDEX messages_room ON messages(room_id, seq)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("unsupported_group_schema") // Never erase an unknown schema as a migration shortcut.
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) { helper.close() }

    override suspend fun rooms(): List<OrbisGroupRoom> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id,payload FROM rooms ORDER BY updated_at DESC,id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val room = json.decodeFromString<OrbisGroupRoom>(cursor.getString(1))
                    check(room.id == cursor.getString(0))
                    add(room)
                }
            }
        }
    }

    override suspend fun room(id: String): OrbisGroupRoom? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT payload FROM rooms WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) null else json.decodeFromString<OrbisGroupRoom>(cursor.getString(0)).also { check(it.id == id) }
        }
    }

    override suspend fun messages(roomId: String, limit: Int, beforeSequence: Long?): List<OrbisGroupMessage> = withContext(Dispatchers.IO) {
        require(limit in 1..(GROUP_PAGE_SIZE + 1))
        val before = if (beforeSequence != null) " AND seq < ?" else ""
        val args = if (beforeSequence == null) arrayOf(roomId) else arrayOf(roomId, beforeSequence.toString())
        helper.readableDatabase.rawQuery("SELECT seq,id,payload FROM messages WHERE room_id=?$before ORDER BY seq DESC LIMIT $limit", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val message = json.decodeFromString<OrbisGroupMessage>(cursor.getString(2))
                    check(message.id == cursor.getString(1) && message.roomId == roomId)
                    add(message.copy(sequence = cursor.getLong(0)))
                }
            }.asReversed()
        }
    }

    override suspend fun message(id: String): OrbisGroupMessage? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT seq,payload FROM messages WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) null else json.decodeFromString<OrbisGroupMessage>(cursor.getString(1))
                .also { check(it.id == id) }.copy(sequence = cursor.getLong(0))
        }
    }

    override suspend fun latestRound(roomId: String): GroupRound? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT id,payload FROM rounds WHERE room_id=? ORDER BY seq DESC LIMIT 1", arrayOf(roomId)).use { cursor ->
            if (!cursor.moveToFirst()) null else json.decodeFromString<GroupRound>(cursor.getString(1))
                .also { check(it.id == cursor.getString(0) && it.roomId == roomId) }
        }
    }

    override suspend fun countMessages(roomId: String): Long = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT count(*) FROM messages WHERE room_id=?", arrayOf(roomId)).use { cursor ->
            check(cursor.moveToFirst()); cursor.getLong(0)
        }
    }

    override suspend fun commit(room: OrbisGroupRoom?, round: GroupRound?, messages: List<OrbisGroupMessage>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            room?.let {
                val values = ContentValues().apply { put("id", it.id); put("updated_at", it.updatedAt); put("payload", json.encodeToString(it)) }
                if (db.update("rooms", values, "id=?", arrayOf(it.id)) == 0) check(db.insertOrThrow("rooms", null, values) != -1L)
            }
            round?.let {
                val values = ContentValues().apply { put("id", it.id); put("room_id", it.roomId); put("status", it.status.name); put("payload", json.encodeToString(it)) }
                if (db.update("rounds", values, "id=? AND room_id=?", arrayOf(it.id, it.roomId)) == 0)
                    check(db.insertOrThrow("rounds", null, values) != -1L)
            }
            messages.forEach { putMessage(db, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun putMessage(db: SQLiteDatabase, message: OrbisGroupMessage) {
        val values = ContentValues().apply {
            put("id", message.id); put("room_id", message.roomId); put("round_id", message.roundId)
            put("status", message.status.name); put("payload", json.encodeToString(message.copy(sequence = 0)))
        }
        // UPDATE, not REPLACE: streaming chunks must keep their original timeline sequence.
        if (db.update("messages", values, "id=? AND room_id=? AND round_id=?", arrayOf(message.id, message.roomId, message.roundId)) == 0)
            check(db.insertOrThrow("messages", null, values) != -1L)
    }

    override suspend fun recoverInterrupted(now: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val interrupted = db.rawQuery("SELECT payload FROM messages WHERE status IN ('QUEUED','GENERATING')", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(json.decodeFromString<OrbisGroupMessage>(cursor.getString(0))) }
            }
            interrupted.forEach { putMessage(db, it.copy(status = OrbisGroupMessageStatus.INTERRUPTED,
                errorReason = "interrupted", updatedAt = now)) }
            val rounds = db.rawQuery("SELECT payload FROM rounds WHERE status='ACTIVE'", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(json.decodeFromString<GroupRound>(cursor.getString(0))) }
            }
            rounds.forEach { round ->
                val values = ContentValues().apply {
                    put("status", GroupRoundStatus.INTERRUPTED.name)
                    put("payload", json.encodeToString(round.copy(status = GroupRoundStatus.INTERRUPTED)))
                }
                check(db.update("rounds", values, "id=? AND room_id=?", arrayOf(round.id, round.roomId)) == 1)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
