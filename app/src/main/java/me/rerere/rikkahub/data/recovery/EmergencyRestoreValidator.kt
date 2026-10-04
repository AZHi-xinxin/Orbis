package me.rerere.rikkahub.data.recovery

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/** Validates and migrates only the isolated restore copy. Never reads or repairs the live DB. */
internal fun validateEmergencyRestore(context: Context, prepared: File) {
    // Room's dictionary callback writes through Context.filesDir: isolate that too, not just the DB path.
    val stagingContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(prepared, "files")
        override fun getDatabasePath(name: String): File =
            if (File(name).isAbsolute) File(name) else File(prepared, "databases/$name")
        override fun getNoBackupFilesDir(): File = File(prepared, "no_backup")
        override fun getCacheDir(): File = File(prepared.parentFile, "validation-cache").apply { mkdirs() }
    }
    val settings = File(prepared, "files/datastore/settings.preferences_pb")
    check(settings.isFile && settings.length() > 0) { "备份中没有可读取的助手设置；原始备份仍可保留供后续修复。" }
    runBlocking(Dispatchers.IO) {
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { settings }
            val preferences = data.data.first()
            val assistants = preferences[stringPreferencesKey("assistants")]
            check(assistants != null) {
                "助手设置暂时无法解析，已停止自动恢复；原始备份没有改动。"
            }
            requireEmergencyJsonShape(assistants)
            JsonInstant.decodeFromString<List<Assistant>>(assistants)
            val mcpKey = stringPreferencesKey("mcp_servers")
            val servers = preferences[mcpKey]?.let {
                requireEmergencyJsonShape(it)
                JsonInstant.decodeFromString<List<McpServerConfig>>(it)
            }.orEmpty()
            val disabledServers = servers.map { server -> server.clone(commonOptions = server.commonOptions.copy(
                enable = false, oauth = null, tools = server.commonOptions.tools.map { it.copy(needsApproval = true) },
            )) }
            // Restoring old configuration must not expose a server or schedule automatic cloud sync.
            data.edit {
                it[booleanPreferencesKey("web_server_enabled")] = false
                it[mcpKey] = JsonInstant.encodeToString(disabledServers)
            }
        } finally {
            job.cancelAndJoin()
        }
    }
    val db = File(prepared, "databases/${SQLiteConfiguration.DATABASE_NAME}")
    DatabaseBackup.normalize(stagingContext, db)
    val room = AppDatabaseFactory.create(stagingContext, db.absolutePath)
    try { DatabaseBackup.checkpoint(room.openHelper.writableDatabase) } finally { room.close() }
    DatabaseBackup.removeSidecars(db)
}
