package me.rerere.rikkahub.data.sync

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

/** This observer is separate from Room, never shared, and performs no transaction/write.
 * Requery's pool has exactly one connection without ENABLE_WRITE_AHEAD_LOGGING. A read-only
 * open skips journal-mode changes, so this does not disable WAL on the live database.
 * data_version is meaningful only when both reads use that same native connection.
 */
internal object ConversationBackupConsistency {
    fun <T> capture(context: Context, liveDatabase: File, capture: () -> T): T {
        val configuration = SQLiteConfiguration.configure(context,
            SQLiteDatabaseConfiguration(liveDatabase.absolutePath, SQLiteDatabase.OPEN_READONLY))
        return SQLiteDatabase.openDatabase(configuration, null) {
            error("无法核对聊天备份状态，原记录未改动。")
        }.use { observer ->
            check(observer.isReadOnly && !observer.isWriteAheadLoggingEnabled)
            ConversationRecoveryBackup.requireStableDatabase(readVersion = {
                observer.query("PRAGMA main.data_version").use { cursor ->
                    check(cursor.moveToFirst()) { ConversationRecoveryBackup.CHANGED }
                    cursor.getLong(0).also { check(!cursor.moveToNext()) }
                }
            }, capture = capture)
        }
    }
}
