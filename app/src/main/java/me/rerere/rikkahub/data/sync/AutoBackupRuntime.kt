package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime

data class AutoBackupStatus(val busy: Boolean = false, val message: String = "尚无自动存档", val localAt: Long = 0, val remoteAt: Long = 0)

object AutoBackupRuntime {
    private const val PERIODIC = "orbis-automatic-backup"
    private const val IMMEDIATE = BackupWorkPolicy.AUTOMATIC
    private val gate = Mutex()
    private val mutable = MutableStateFlow(AutoBackupStatus())
    val status = mutable.asStateFlow()
    fun schedule(context: Context, config: AutoBackupConfig) {
        config.validate()
        val work = WorkManager.getInstance(context)
        if(!config.enabled && !config.localEnabled) { work.cancelUniqueWork(PERIODIC); return }
        // This is a process-death safety net. Committed changes trigger independent work.
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        val prefs = context.getSharedPreferences("orbis-backup-status", Context.MODE_PRIVATE)
        if(!mutable.value.busy) mutable.value = AutoBackupStatus(false,
            prefs.getString("message", "存档已安排，等待后台空闲") ?: "等待存档", prefs.getLong("localAt", 0), prefs.getLong("remoteAt", 0))
    }
    fun now(context: Context) {
        // Never reuse an old automatic request's exponential backoff. KEEP only
        // coalesces simultaneous explicit requests, without cancelling an upload.
        WorkManager.getInstance(context).enqueueUniqueWork(BackupWorkPolicy.REQUESTED, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInputData(workDataOf(BackupWorkPolicy.MANUAL to true)).build())
    }
    internal fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    internal fun enqueueReconnect(context: Context) {
        // A returning network gets one prompt attempt even if the older worker is
        // in a long offline backoff. The shared gate prevents parallel snapshots.
        WorkManager.getInstance(context).enqueueUniqueWork("orbis-backup-reconnected", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    internal fun local(context: Context) = LocalBackupStore(File(context.noBackupFilesDir, "automatic-versions"))
    internal suspend fun list(context: Context, remote: Boolean, source: Settings? = null, portable: Boolean = false): List<BackupRestorePoint> = withContext(Dispatchers.IO) {
        val selected = source ?: ready()
        if(portable) VersionedBackup(DocumentBackupStore(context, requireNotNull(selected.autoBackup.archiveDirectoryUri)), context.cacheDir).list()
        else if(remote) RemoteBackupStore(selected).use {
            if (it.usesOverwrite) OverwriteBackup(it, context.cacheDir).list()
            else VersionedBackup(it, context.cacheDir).list()
        }
        else VersionedBackup(local(context), context.cacheDir).list()
    }
    internal suspend fun stage(context: Context, point: BackupRestorePoint, remote: Boolean, source: Settings? = null, portable: Boolean = false,
        bootstrapSettings: Settings? = null) = withContext(Dispatchers.IO) {
        check(!OrbisVoiceCallRuntime.get(context).callState.value.isActive) { "请先结束通话再准备恢复" }
        check(!GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>().hasActiveGeneration()) { "请等当前回复结束再准备恢复" }
        val selected = source ?: ready()
        val file = File.createTempFile("version-restore-", ".zip", context.cacheDir)
        try {
            if(portable) VersionedBackup(DocumentBackupStore(context, requireNotNull(selected.autoBackup.archiveDirectoryUri)), context.cacheDir).download(point, file)
            else if(remote) RemoteBackupStore(selected).use {
                if (it.usesOverwrite) OverwriteBackup(it, context.cacheDir).download(point, file)
                else VersionedBackup(it, context.cacheDir).download(point, file)
            }
            else VersionedBackup(local(context), context.cacheDir).download(point, file)
            check(!OrbisVoiceCallRuntime.get(context).callState.value.isActive &&
                !GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>().hasActiveGeneration()) { "请等通话和回复结束后恢复" }
            GlobalContext.get().get<BackupManager>().stageRestore(file, true, true, replaceLocalTools = true,
                bootstrapSettings = bootstrapSettings,
                verifiedCloudSource = if (remote && !portable && point.includesDatabase && point.includesFiles)
                    VerifiedCloudRestoreSource(ContinuousBackup.key(selected.autoBackup.target, selected), point)
                else null)
        } finally { file.delete() }
    }
    private suspend fun ready(): Settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
    private fun saveStatus(context: Context, value: AutoBackupStatus) {
        mutable.value = value
        context.getSharedPreferences("orbis-backup-status", Context.MODE_PRIVATE).edit()
            .putString("message", value.message).putLong("localAt", value.localAt).putLong("remoteAt", value.remoteAt).apply()
    }
    internal suspend fun run(context: Context): Boolean = withContext(Dispatchers.IO) {
        if(!gate.tryLock()) return@withContext false
        var file: File? = null
        var portableFile: File? = null
        var attemptTarget: String? = null
        var phase = "starting"
        var specificFailure = false
        fun attempt(next: String, error: Throwable? = null) {
            phase = next
            attemptTarget?.let { BackupAttempt.write(context, it, next, error?.let(::backupFailureCode)) }
        }
        try {
            val settings = ready(); val config = settings.autoBackup
            attemptTarget = ContinuousBackup.key(config.target, settings)
            ContinuousBackup.initialize(context)
            if(!config.enabled && !config.localEnabled) return@withContext true
            if (config.enabled) ContinuousBackup.acknowledgeAppliedCloudRestore(
                context, ContinuousBackup.key(config.target, settings))
            val selected = ContinuousBackup.selected(settings)
            val captured = ContinuousBackup.progress.value.revision
            if(!ContinuousBackup.pending(settings)) { attempt("up_to_date"); return@withContext true }
            if(OrbisVoiceCallRuntime.get(context).callState.value.isActive ||
                GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>().hasActiveGeneration()) {
                attempt("waiting_for_call_or_reply")
                saveStatus(context, mutable.value.copy(busy = false, message = "正在回复或通话，独立存档稍后补存；各位置最近完成时间见下方")); return@withContext false
            }
            saveStatus(context, mutable.value.copy(busy = true, message = "正在制作一致存档，聊天仍可使用"))
            withTimeout(8 * 60_000L) {
                val job = currentCoroutineContext().job
                val watcher = launch {
                    GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init &&
                        (it.autoBackup != config || it.webDavConfig != settings.webDavConfig || it.s3Config != settings.s3Config) }
                    job.cancel(CancellationException("backup_destination_changed"))
                }
                try {
                // Inspect a newly bound destination before uploading/pruning. An empty
                // reinstallation must never rotate away the old complete restore points.
                suspend fun publish(kind: String, store: BackupObjectStore, archive: File, secrets: Boolean, keep: Int) {
                    val key = selected.getValue(kind)
                    val progress = ContinuousBackup.progress.value
                    if(!progress.pending(key) || progress.targets[key]?.needsReview == true) return
                    val versions = VersionedBackup(store, context.cacheDir)
                    val overwrite = if (kind == "webdav" && store is AtomicBackupObjectStore)
                        OverwriteBackup(store, context.cacheDir) else null
                    val firstUnreviewed = kind != "local" && progress.targets[key]?.completedAt.let { it == null || it == 0L } &&
                        !ContinuousBackup.reviewed(context, key)
                    if(firstUnreviewed &&
                        (overwrite?.list() ?: versions.list()).isNotEmpty()) {
                        ContinuousBackup.failed(context, key, "发现这个位置已有存档；请先查看恢复，原件未改动", true)
                        return
                    }
                    val point = overwrite?.publish(archive, secrets, requireEmpty = firstUnreviewed)
                        ?: versions.publish(archive, secrets)
                    ContinuousBackup.complete(context, key, captured, point.createdAt)
                    if(kind == "local") saveStatus(context, mutable.value.copy(localAt = point.createdAt))
                    if(kind in setOf("webdav","s3")) saveStatus(context, mutable.value.copy(remoteAt = point.createdAt))
                    try {
                        if (overwrite != null) overwrite.cleanupLegacyAfterPublished(point)
                        else versions.pruneAfterPublished(point, keep)
                    }
                    catch(c: CancellationException) { throw c }
                    catch(_: Exception) { /* Verified newest copy remains valid; keep older objects. */ }
                }
                attempt("snapshot")
                val archive = GlobalContext.get().get<BackupManager>().createBackup(true, true, true).also { file = it }
                suspend fun portableArchive(): File = if(config.includeConnectionSecrets) archive else portableFile
                    ?: portableBackupCopy(archive, context.cacheDir).also { portableFile = it }
                if(config.localEnabled) {
                    attempt("local_publish")
                    try { publish("local", local(context), archive, true, 3) }
                    catch(c: CancellationException) { throw c }
                    catch(_: Exception) { ContinuousBackup.failed(context, selected.getValue("local"), "本机版本未完成，旧副本保留；稍后自动补存") }
                    if(config.archiveDirectoryUri != null && ready().autoBackup.archiveDirectoryUri == config.archiveDirectoryUri) {
                        attempt("folder_publish")
                        try {
                            publish("folder", DocumentBackupStore(context, config.archiveDirectoryUri),
                                portableArchive(), config.includeConnectionSecrets, config.keepVersions)
                        } catch(c: CancellationException) { throw c }
                        catch(_: Exception) { ContinuousBackup.failed(context, selected.getValue("folder"), "自选文件夹未完成，请检查授权和空间；待自动补存") }
                    }
                }
                val live = ready()
                if(config.enabled && live.autoBackup == config && live.webDavConfig == settings.webDavConfig && live.s3Config == settings.s3Config) {
                    if (!BackupRecoveryConnection.ready(settings)) {
                        attempt("connection_incomplete")
                        ContinuousBackup.failed(context, selected.getValue(config.target),
                            "服务器连接未填写完整，尚无云端副本；本机及自选文件夹分别保存，不受此项影响")
                        return@withTimeout
                    }
                    val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
                    if(connectivity.activeNetwork == null || (!config.allowMetered && connectivity.isActiveNetworkMetered)) {
                        attempt("waiting_for_network")
                        ContinuousBackup.failed(context, selected.getValue(config.target), "等待可用网络，联网后自动补传")
                        return@withTimeout
                    }
                    try { RemoteBackupStore(settings).use { remote ->
                        attempt("remote_prepare")
                        remote.prepare()
                        if (remote.usesOverwrite) remote.requireAtomicOverwrite()
                        attempt("remote_publish_and_verify")
                        publish(config.target, remote, portableArchive(), config.includeConnectionSecrets, config.keepVersions)
                        attempt(if (ContinuousBackup.progress.value.targets[selected.getValue(config.target)]?.needsReview == true)
                            "existing_backup_review_required" else "remote_attempt_finished")
                    } } catch(c: CancellationException) { throw c }
                    catch(e: AtomicBackupUnsupportedException) {
                        specificFailure = true; attempt("failed_at_$phase", e)
                        ContinuousBackup.failed(context, selected.getValue(config.target),
                            "此WebDAV尚未支持安全覆盖协议；未改动旧存档，请为服务器启用Orbis备份扩展")
                        saveStatus(context, mutable.value.copy(message = "服务器尚未支持安全覆盖协议；旧存档保留"))
                    }
                    catch(e: Exception) {
                        val failedPhase = phase
                        specificFailure = true; attempt("failed_at_$phase", e)
                        ContinuousBackup.failed(context, selected.getValue(config.target), "服务器存档未完成（${backupFailureCode(e)}）；旧存档保留，稍后自动补传")
                        saveStatus(context, mutable.value.copy(message = "${backupPhaseLabel(failedPhase)}未完成（${backupFailureCode(e)}）；旧存档保留"))
                    }
                }
                } finally { watcher.cancel() }
            }
        } catch(cancelled: CancellationException) {
            specificFailure = true
            attempt(if (cancelled is TimeoutCancellationException) "timeout_at_$phase" else "cancelled_at_$phase")
            saveStatus(context, mutable.value.copy(busy = false, message = "本次存档未全部完成，旧存档保留"))
            if(cancelled !is TimeoutCancellationException) throw cancelled
        } catch(e: Exception) {
            val failedPhase = phase
            specificFailure = true; attempt("failed_at_$phase", e)
            // Never surface remote bodies, URLs, credentials or parser excerpts in chat/logs.
            saveStatus(context, mutable.value.copy(busy = false, message = "${backupPhaseLabel(failedPhase)}未完成（${backupFailureCode(e)}）；旧存档保留"))
        } finally { portableFile?.delete(); file?.delete(); mutable.value = mutable.value.copy(busy = false); gate.unlock() }
        val settings = ready()
        val pending = ContinuousBackup.pending(settings)
        if (!specificFailure) saveStatus(context, mutable.value.copy(message = if(pending) "变化已记下，尚未完成的目标会自动补存" else "各位置状态见下方；新变化会自动保存"))
        !pending
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manual = inputData.getBoolean(BackupWorkPolicy.MANUAL, false)
        // Mark the requested revision before the worker checks pending(), not in
        // the observer's 3-second debounce, which could otherwise skip this save.
        if (manual) ContinuousBackup.requested(applicationContext)
        val completed = AutoBackupRuntime.run(applicationContext)
        if (manual && !completed) AutoBackupRuntime.enqueue(applicationContext)
        if (BackupWorkPolicy.retry(manual, completed)) Result.retry() else Result.success()
    }
}
