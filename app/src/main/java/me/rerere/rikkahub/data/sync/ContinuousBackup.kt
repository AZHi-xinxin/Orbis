package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.FileObserver
import androidx.room.InvalidationTracker
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase

/** Change notices never perform a snapshot or network operation on the caller's thread. */
object ContinuousBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(BackupChangeLedger())
    val progress = state.asStateFlow()
    private var initialized = false
    private var started = false
    @Volatile private var nextAttemptAt = 0L
    private val observers = mutableMapOf<String, FileObserver>()
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("orbis-continuous-backup", Context.MODE_PRIVATE)
    fun initialize(context: Context) = synchronized(lock) {
        if (!initialized) {
            state.value = runCatching {
                json.decodeFromString<BackupChangeLedger>(prefs(context).getString("ledger", null) ?: "")
            }.getOrDefault(BackupChangeLedger())
            initialized = true
        }
    }
    private fun update(context: Context, edit: (BackupChangeLedger) -> BackupChangeLedger) = synchronized(lock) {
        initialize(context)
        val next = edit(state.value)
        // Called on the observer/worker IO scope, never the chat commit path.
        check(prefs(context).edit().putString("ledger", json.encodeToString(next)).commit()) { "backup_journal_not_saved" }
        state.value = next
    }
    fun changed() { changes.trySend(Unit) }
    internal fun requested(context: Context) = update(context) { it.changed() }
    internal fun complete(context: Context, key: String, revision: Long, at: Long) =
        update(context) { it.completed(key, revision, at) }
    internal fun failed(context: Context, key: String, message: String, review: Boolean = false) =
        update(context) { it.failed(key, message, review) }
    internal fun reviewed(context: Context, key: String): Boolean = prefs(context).getBoolean("reviewed-$key", false)
    internal fun acknowledgeAppliedCloudRestore(context: Context, key: String): Boolean =
        CloudRestoreProof.consumeApplied(File(context.noBackupFilesDir, "backup-restore"), key) {
            synchronized(lock) {
                initialize(context)
                val current = state.value
                val next = current.allowExisting(key)
                // Same atomic preference commit, no network, no completion receipt and no
                // auto-enable. The original bind/ownership approval is still required.
                check(prefs(context).edit().putBoolean("reviewed-$key", true)
                    .putString("ledger", json.encodeToString(next)).commit()) { "backup_journal_not_saved" }
                state.value = next
            }
        }
    suspend fun allowExisting(context: Context, key: String) = withContext(Dispatchers.IO) {
        check(prefs(context).edit().putBoolean("reviewed-$key", true).commit())
        update(context) { it.allowExisting(key) }
        AutoBackupRuntime.now(context)
    }
    fun key(kind: String, settings: Settings): String {
        val destination = when (kind) {
            "local" -> "private"
            "folder" -> settings.autoBackup.archiveDirectoryUri.orEmpty()
            "webdav" -> settings.webDavConfig.toString()
            "s3" -> settings.s3Config.toString()
            else -> error("invalid_backup_target")
        }
        // Neither URLs nor credentials are persisted/displayed in progress identifiers.
        val hash = MessageDigest.getInstance("SHA-256").digest(destination.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$kind-$hash"
    }
    fun selected(settings: Settings): Map<String, String> = buildMap {
        if (settings.autoBackup.localEnabled) put("local", key("local", settings))
        if (settings.autoBackup.localEnabled && settings.autoBackup.archiveDirectoryUri != null)
            put("folder", key("folder", settings))
        if (settings.autoBackup.enabled) put(settings.autoBackup.target, key(settings.autoBackup.target, settings))
    }
    fun pending(settings: Settings): Boolean = selected(settings).values.any {
        state.value.pending(it) && state.value.targets[it]?.needsReview != true
    }

    suspend fun start(context: Context, database: AppDatabase, store: SettingsStore, scope: CoroutineScope) {
        synchronized(lock) { if (started) return; started = true }
        initialize(context)
        val tables = arrayOf("ConversationEntity", "MemoryEntity", "GenMediaEntity", "message_node",
            "managed_files", "favorites", "workspaces", "conversation_folder", "orbis_compaction_event",
            "orbis_compaction_rollback", "orbis_compaction_backup_node", "orbis_original_archive",
            "orbis_original_chunk", "orbis_memory_note", "orbis_memory_revision", "orbis_memory_operation",
            "orbis_memory_turn", "orbis_memory_surfacing")
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(tables) {
            override fun onInvalidated(tables: Set<String>) { changed() }
        })
        // AtomicFile replacements, attachments and non-Room study/pet records also count.
        fun watchTree(directory: File) {
            if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return
            if (directory.path !in observers) {
                val watcher = object : FileObserver(directory, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                    FileObserver.MOVED_FROM or FileObserver.DELETE or FileObserver.CREATE) {
                    override fun onEvent(event: Int, path: String?) {
                        changed()
                        if (event and (CREATE or MOVED_TO) != 0 && path != null)
                            scope.launch(Dispatchers.IO) {
                                val child = File(directory, path)
                                if(child.isDirectory) synchronized(observers) { watchTree(child) }
                            }
                    }
                }
                observers[directory.path] = watcher
                watcher.startWatching()
            }
            directory.listFiles()?.filter { it.isDirectory }?.forEach(::watchTree)
        }
        synchronized(observers) {
            watchTree(context.filesDir.canonicalFile)
            // Only the credential-free namespace already covered by CharacterBackup.
            // Do NOT observe backup output/journals, which would trigger a backup feedback loop.
            val characters = File(context.noBackupFilesDir, "orbis-characters")
            if (characters.isDirectory || characters.mkdirs()) watchTree(characters.canonicalFile)
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.IO) {
                    val settings = store.settingsFlow.first { !it.init }
                    if (pending(settings)) runCatching { AutoBackupRuntime.enqueueReconnect(context) }
                }
            }
        }) }
        scope.launch(Dispatchers.IO) {
            var previous: Settings? = null
            store.settingsFlow.collect { settings ->
                if (!settings.init && settings != previous) {
                    previous = settings
                    runCatching { AutoBackupRuntime.schedule(context, settings.autoBackup) }
                    changed()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (ignored in changes) {
                runCatching { update(context) { it.changed() } }
                // Batch committed changes, not an indefinitely resetting debounce.
                delay(3_000)
                while (changes.tryReceive().isSuccess) { /* all covered by next conservative revision */ }
                runCatching { update(context) { it.changed() } }
                val settings = store.settingsFlow.first { !it.init }
                if (pending(settings) && System.currentTimeMillis() >= nextAttemptAt) {
                    nextAttemptAt = System.currentTimeMillis() + 30_000
                    runCatching { AutoBackupRuntime.enqueue(context) }
                }
            }
        }
        // Startup catches writes preceding observer registration / process termination.
        changed()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                val settings = store.settingsFlow.first { !it.init }
                if (pending(settings)) runCatching { AutoBackupRuntime.enqueue(context) }
            }
        }
    }
}
