package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningBackup
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            require(!settings.init) { "请先完成应用初始化，再导出可恢复的备份" }
            val settingsJson = json.encodeToString(settings)
            // Even a files-only export needs the same committed reference snapshot for exclusions.
            val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
            val excludedUploads = if (includeDatabase || includeFiles) {
                val live = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
                ArchiveCapacity.requireSpace(staging.usableSpace, 2 * (live.length() + File(live.path + "-wal").length()))
                DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                DatabaseBackup.excludeConsultations(context, snapshot, settingsJson)
            } else emptySet()
            var expanded = 0L
            var entries = 0
            val coroutine = currentCoroutineContext()
            fun reserve(name: String, size: Long) {
                ArchiveCapacity.requireSafePath(name)
                ArchiveCapacity.requireSize(size, NativeBackupBudget.entryLimit(name), allowEmpty = true)
                expanded += size
                ArchiveCapacity.requireSize(expanded, ArchiveCapacity.MAX_EXPANDED_BYTES, allowEmpty = true)
                require(++entries <= ArchiveCapacity.MAX_ENTRIES) { "备份文件数量超过安全上限" }
            }
            ZipOutputStream(NativeBackupBudget.Output(FileOutputStream(archive)) { archive.parentFile!!.usableSpace }).use { zip ->
                val settingsBytes = settingsJson.toByteArray(Charsets.UTF_8)
                reserve("settings.json", settingsBytes.size.toLong())
                zip.putNextEntry(ZipEntry("settings.json")); zip.write(settingsBytes); zip.closeEntry()
                if (includeDatabase) {
                    reserve(DatabaseBackup.ARCHIVE_DATABASE, snapshot.length())
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE) { coroutine.ensureActive() }
                }
                if (includeFiles) {
                    for (folder in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS)) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder == FileFolders.SKILLS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            if (folder == FileFolders.UPLOAD && "$folder/$relative" in excludedUploads) continue
                            reserve("$folder/$relative", file.length())
                            addFile(zip, file, "$folder/$relative") { coroutine.ensureActive() }
                        }
                    }
                    // Semantic, locked snapshots only. Do not scan these directories or include credentials/sidecars.
                    val schedule = me.rerere.rikkahub.data.orbis.schedule.OrbisScheduleStore.open(context).load()
                    val kaomoji = me.rerere.rikkahub.data.orbis.OrbisKaomojis.open(context).snapshotForBackup()
                    for ((name, bytes) in listOf(
                        OrbisLocalToolBackup.SCHEDULE to OrbisLocalToolBackup.encodeSchedule(schedule),
                        OrbisLocalToolBackup.KAOMOJI to OrbisLocalToolBackup.encodeKaomoji(kaomoji),
                        me.rerere.rikkahub.data.files.FileProtection.PATH to me.rerere.rikkahub.data.files.FileProtection.encode(
                            me.rerere.rikkahub.data.files.FileProtection(File(context.filesDir.canonicalFile,
                                me.rerere.rikkahub.data.files.FileProtection.PATH)).snapshot()).toByteArray(),
                    )) {
                        currentCoroutineContext().ensureActive()
                        reserve(name, bytes.size.toLong())
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    val gallery = me.rerere.rikkahub.data.orbis.gallery.GalleryBackup.stageSnapshot(
                        context.filesDir, File(staging, "gallery-snapshot"),
                        checkCancelled = { coroutine.ensureActive() }, beforeFile = ::reserve)
                    for ((name, file) in gallery) {
                        coroutine.ensureActive()
                        addFile(zip, file, name) { coroutine.ensureActive() }
                    }
                    val spaces = me.rerere.rikkahub.data.orbis.spaces.CompanionSpacesBackup.stageSnapshot(
                        context.filesDir, File(staging, "companion-spaces-snapshot"),
                        checkCancelled = { coroutine.ensureActive() }, beforeFile = ::reserve)
                    for ((name, file) in spaces) {
                        coroutine.ensureActive()
                        addFile(zip, file, name) { coroutine.ensureActive() }
                    }
                    val policies = ContextPruningBackup.stageSnapshot(
                        context.filesDir, File(staging, "context-pruning-snapshot"),
                        DatabaseBackup.conversationOwners(context, snapshot),
                        checkCancelled = { coroutine.ensureActive() }, beforeFile = ::reserve)
                    for ((name, file) in policies) {
                        coroutine.ensureActive()
                        addFile(zip, file, name) { coroutine.ensureActive() }
                    }
                }
            }
            // Exactly the restore metadata policy, after ZIP footer has been written. No false success
            // for an oversized settings file/database/attachment, entry count or aggregate expansion.
            ZipFile(archive).use { NativeBackupBudget.inspect(archive.length(), it.entries().asSequence()) }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            ArchiveCapacity.requireSize(archive.length(), ArchiveCapacity.MAX_ZIP_BYTES)
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    var archiveEntries = 0
                    var expandedBytes = 0L
                    ZipFile(archive).use { zip ->
                        val budget = NativeBackupBudget.inspect(archive.length(), zip.entries().asSequence())
                        // Reserve extraction plus possible WAL normalization/migration copies before writing.
                        ArchiveCapacity.requireSpace(staging.usableSpace, budget.expandedBytes +
                            if (includeDatabase) 2 * budget.databaseBytes else 0)
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            require(++archiveEntries <= ArchiveCapacity.MAX_ENTRIES) { "备份文件数量过多" }
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && (OrbisLocalToolBackup.maxBytes(entry.name) != null || isAttachment(entry.name))) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            FileOutputStream(target).use { output ->
                                val coroutine = currentCoroutineContext()
                                expandedBytes += ArchiveCapacity.copyZipEntry(zip, entry, output,
                                    minOf(NativeBackupBudget.entryLimit(entry.name), ArchiveCapacity.MAX_EXPANDED_BYTES - expandedBytes),
                                    { coroutine.ensureActive() }, { ArchiveCapacity.requireSpace(staging.usableSpace, it.toLong()) })
                                output.fd.sync()
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    try { OrbisLocalToolBackup.validateStaged(payload) }
                    catch (failure: Exception) {
                        throw IllegalArgumentException(OrbisLocalToolBackup.publicError(failure.message) ?: "本地工具备份校验失败；原数据未更改")
                    }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }
                    if (ContextPruningBackup.hasStagedPolicies(payload)) {
                        try {
                            val owners = if (stagedDatabase.exists()) DatabaseBackup.conversationOwners(context, stagedDatabase)
                                else DatabaseBackup.conversationOwners(database.openHelper.readableDatabase)
                            ContextPruningBackup.validateOwnership(payload, owners)
                        } catch (failure: Exception) {
                            throw IllegalArgumentException(OrbisLocalToolBackup.publicError(failure.message)
                                ?: "上下文清理标记归属校验失败；原数据未更改")
                        }
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        val text = java.io.InputStreamReader(settingsFile.inputStream(), decoder).use { it.readText() }
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(text))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        val migrated = json.encodeToString(settings)
                        ArchiveCapacity.requireSize(migrated.toByteArray(Charsets.UTF_8).size.toLong(), NativeBackupBudget.MAX_SETTINGS_BYTES)
                        PendingRestore.writeDurably(settingsFile, migrated)
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS) || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder == FileFolders.SKILLS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String, checkCancelled: () -> Unit) {
        val expected = file.length()
        zip.putNextEntry(ZipEntry(name))
        val copied = file.inputStream().use {
            me.rerere.rikkahub.data.sync.importer.RikkaChatArchive.copyLimited(it, zip,
                minOf(expected, NativeBackupBudget.entryLimit(name)), checkCancelled)
        }
        require(copied == expected) { "备份期间源文件发生变化；请停止编辑后重新导出" }
        zip.closeEntry()
    }

    companion object {
        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
            validateBeforeJournal = { payload ->
                if (ContextPruningBackup.hasStagedPolicies(payload)) {
                    val staged = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val selected = if (staged.exists()) staged else context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
                    ContextPruningBackup.validateOwnership(payload, DatabaseBackup.conversationOwners(context, selected))
                }
            },
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
