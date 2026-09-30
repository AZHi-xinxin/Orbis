package me.rerere.rikkahub.data.orbis

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TreeMap
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** A separate app-private SQLite database, shared only with assistants explicitly granted LocalGarden.
 * No chat/provider/workspace files, remote website data or credentials are opened by this store.
 */
class OrbisGardenStore internal constructor(context: Context, name: String = "orbis-local-garden-v1.db") {
    init { require(name == "orbis-local-garden-v1.db" || name.matches(Regex("garden-test-[a-z0-9-]+\\.db"))) }
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("garden_database_corrupt") }) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, updated_at INTEGER NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX garden_kind_updated ON entries(kind,updated_at DESC,id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("garden_schema_unknown") }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("garden_schema_unknown") }
    }

    suspend fun list(kind: OrbisGardenKind?, limit: Int = 30, offset: Int = 0): List<OrbisGardenEntry> = access {
        require(limit in 1..100 && offset in 0..1_000_000) { "garden_invalid_page" }
        val where = if (kind == null) "" else " WHERE kind=?"
        helper.readableDatabase.rawQuery("SELECT payload FROM entries$where ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
            if (kind == null) arrayOf(limit.toString(), offset.toString()) else arrayOf(kind.name, limit.toString(), offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
        }
    }

    /** Counts every chosen day (or legacy creation day), across all kinds when kind is null. */
    suspend fun monthDays(kind: OrbisGardenKind?, month: YearMonth, zone: ZoneId): Map<LocalDate, Int> = access {
        val firstDay = month.atDay(1)
        val nextMonth = month.plusMonths(1).atDay(1)
        val counts = TreeMap<LocalDate, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT payload FROM entries" + if (kind == null) "" else " WHERE kind=?",
            kind?.let { arrayOf(it.name) },
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = decode(cursor.getString(0))
                check(kind == null || entry.kind == kind) { "garden_kind_mismatch" }
                val day = gardenEntryDate(entry, zone)
                if (!day.isBefore(firstDay) && day.isBefore(nextMonth)) {
                    counts[day] = (counts[day] ?: 0) + 1
                }
            }
        }
        counts.toMap()
    }

    /** Lists one chosen day across the requested kinds, preserving updated_at DESC,id order. */
    suspend fun listOnDate(
        kind: OrbisGardenKind?,
        day: LocalDate,
        zone: ZoneId,
        limit: Int = 30,
        offset: Int = 0,
    ): List<OrbisGardenEntry> = access {
        require(limit in 1..100 && offset in 0..1_000_000) { "garden_invalid_page" }
        helper.readableDatabase.rawQuery(
            "SELECT payload FROM entries" + (if (kind == null) "" else " WHERE kind=?") + " ORDER BY updated_at DESC,id",
            kind?.let { arrayOf(it.name) },
        ).use { cursor ->
            val page = ArrayList<OrbisGardenEntry>(limit)
            var matchingBeforePage = 0
            while (page.size < limit && cursor.moveToNext()) {
                val entry = decode(cursor.getString(0))
                check(kind == null || entry.kind == kind) { "garden_kind_mismatch" }
                if (gardenEntryDate(entry, zone) != day) continue
                if (matchingBeforePage < offset) {
                    matchingBeforePage += 1
                } else {
                    page += entry
                }
            }
            page
        }
    }

    suspend fun read(id: String): OrbisGardenEntry? = access { find(helper.readableDatabase, id) }

    suspend fun create(
        kind: OrbisGardenKind,
        title: String,
        body: String,
        author: String,
        entryDate: LocalDate = LocalDate.now(),
    ): OrbisGardenEntry = access {
        val now = System.currentTimeMillis()
        val entry = OrbisGardenEntry(UUID.randomUUID().toString(), kind, title, body, author, now, now,
            entryDate = entryDate.toString())
        validateGardenEntry(entry)
        check(helper.writableDatabase.insertOrThrow("entries", null, values(entry)) != -1L)
        changed(); entry
    }

    /** UI updates are compare-and-swap; an intervening edit cannot silently be lost. */
    suspend fun update(original: OrbisGardenEntry, title: String, body: String, author: String): OrbisGardenEntry = access {
        val next = original.copy(title = title, body = body, author = author,
            updatedAt = maxOf(original.updatedAt, System.currentTimeMillis()), revision = original.revision + 1)
        validateGardenEntry(next)
        val db = helper.writableDatabase
        val payload = expectedPayload(db, original)
        check(db.update("entries", values(next), "id=? AND revision=? AND payload=?",
            arrayOf(original.id, original.revision.toString(), payload)) == 1) { "garden_revision_changed" }
        changed(); next
    }

    suspend fun delete(original: OrbisGardenEntry) = access {
        val db = helper.writableDatabase
        val payload = expectedPayload(db, original)
        check(db.delete("entries", "id=? AND revision=? AND payload=?",
            arrayOf(original.id, original.revision.toString(), payload)) == 1) { "garden_revision_changed" }
        changed()
    }

    suspend fun exportBackup(): ByteArray = access {
        // Bound memory before constructing the JSON document; fail rather than exporting a silent subset.
        var bytes = 0L
        val entries = helper.readableDatabase.rawQuery("SELECT payload FROM entries ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(0)
                    bytes += payload.toByteArray(Charsets.UTF_8).size
                    require(size < GARDEN_BACKUP_RECORDS && bytes <= GARDEN_BACKUP_BYTES) { "garden_backup_too_large" }
                    add(decode(payload))
                }
            }
        }
        encodeGardenBackup(entries)
    }

    suspend fun importBackup(bytes: ByteArray): Int = access {
        val incoming = decodeGardenBackup(bytes)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = incoming.mapNotNull { find(db, it.id) }.associateBy { it.id }
            val additions = gardenMergeAdditions(existing, incoming)
            additions.forEach { check(db.insertOrThrow("entries", null, values(it)) != -1L) }
            db.setTransactionSuccessful()
            additions.size
        } finally { db.endTransaction(); changed() }
    }

    internal suspend fun close() = access { helper.close() }
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private fun changed() { changes.value += 1 }
    private fun decode(payload: String) = gardenJson.decodeFromString<OrbisGardenEntry>(payload).also(::validateGardenEntry)
    private fun expectedPayload(db: SQLiteDatabase, original: OrbisGardenEntry): String =
        db.rawQuery("SELECT payload FROM entries WHERE id=?", arrayOf(original.id)).use { cursor ->
            gardenExpectedPayload(original, if (cursor.moveToFirst()) cursor.getString(0) else null)
        }
    private fun find(db: SQLiteDatabase, id: String): OrbisGardenEntry? =
        db.rawQuery("SELECT payload FROM entries WHERE id=?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) null else decode(cursor.getString(0)).also { check(it.id == id) }
        }
    private fun values(entry: OrbisGardenEntry) = ContentValues().apply {
        put("id", entry.id); put("kind", entry.kind.name); put("updated_at", entry.updatedAt)
        put("revision", entry.revision); put("payload", gardenJson.encodeToString(entry))
    }

    companion object {
        @Volatile private var instance: OrbisGardenStore? = null
        fun open(context: Context): OrbisGardenStore = instance ?: synchronized(this) {
            instance ?: OrbisGardenStore(context.applicationContext).also { instance = it }
        }
    }
}
