package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import org.koin.core.context.GlobalContext

internal object AiBackupOperations {
    private fun prefs(context: Context) = context.getSharedPreferences("orbis-ai-backup-operations", Context.MODE_PRIVATE)
    private fun receiptKey(owner: String, key: String) = "$owner/$key"
    fun receipt(context: Context, owner: String, key: String, state: String) {
        check(prefs(context).edit().putString(receiptKey(owner, key), state).commit())
    }
    fun read(context: Context, owner: String, key: String): String =
        prefs(context).getString(receiptKey(owner, key), "not_checked") ?: "not_checked"
    fun observe(context: Context, owner: String, key: String) = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == receiptKey(owner, key)) trySend(read(context, owner, key))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(context, owner, key))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun enqueue(context: Context, action: String, owner: String, workspace: String, settings: Settings) {
        require(action in setOf("check", "restore_latest"))
        check(AiBackupBinding.owns(settings, owner, workspace))
        val key = ContinuousBackup.key(settings.autoBackup.target, settings)
        WorkManager.getInstance(context).enqueueUniqueWork("orbis-ai-backup-$action-$owner",
            ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AiBackupOperationWorker>()
                .setInputData(workDataOf("action" to action, "owner" to owner, "workspace" to workspace, "target" to key))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    fun status(context: Context, owner: String, workspace: String, settings: Settings): JsonObject {
        val owned = AiBackupBinding.owns(settings, owner, workspace)
        return buildJsonObject {
            put("ok", true); put("bound", owned)
            if (owned) {
                ContinuousBackup.initialize(context)
                val key = ContinuousBackup.key(settings.autoBackup.target, settings)
                val ledger = ContinuousBackup.progress.value
                val progress = ledger.targets[key]
                put("target", settings.autoBackup.target); put("auto_upload", settings.autoBackup.enabled)
                put("completed_at", progress?.completedAt ?: 0L); put("pending_changes", ledger.pending(key))
                put("needs_existing_backup_review", progress?.needsReview ?: false)
                put("backup_status", progress?.message ?: "尚无当前目标的完成回执")
                put("upload_attempt", BackupAttempt.status(context, key))
                put("current_revision", ledger.revision)
                put("completed_revision", progress?.revision ?: 0)
                put("completion_covers_current_changes", !ledger.pending(key) && (progress?.completedAt ?: 0) > 0)
                put("check_or_restore_status", read(context, owner, key))
                // Legacy clients retain this field; it is NOT the save_now result.
                put("operation_status", read(context, owner, key))
            }
        }
    }
}

/** A short tool call queues this work; it never downloads an archive in the model/tool loop. */
class AiBackupOperationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val workspace = inputData.getString("workspace") ?: return@withContext Result.failure()
        val target = inputData.getString("target") ?: return@withContext Result.failure()
        val action = inputData.getString("action") ?: return@withContext Result.failure()
        val store = GlobalContext.get().get<SettingsStore>()
        val selected = store.settingsFlow.first { !it.init }
        fun same(settings: Settings) = AiBackupBinding.owns(settings, owner, workspace) &&
            ContinuousBackup.key(settings.autoBackup.target, settings) == target
        fun record(value: String) = AiBackupOperations.receipt(applicationContext, owner, target, value)
        if (!same(selected)) { record("cancelled_binding_changed"); return@withContext Result.failure() }
        if (action == "restore_latest" && (OrbisVoiceCallRuntime.get(applicationContext).callState.value.isActive ||
            GlobalContext.get().get<ChatService>().hasActiveGeneration())) {
            record("waiting_for_call_or_reply"); return@withContext Result.retry()
        }
        try {
            withTimeout(8 * 60_000L) {
                val job = currentCoroutineContext().job
                val watcher = launch {
                    store.settingsFlow.first { !it.init && !same(it) }
                    job.cancel(CancellationException("backup_binding_changed"))
                }
                try {
                    record(if (action == "check") "checking" else "downloading_and_verifying")
                    when (action) {
                        "check" -> {
                            RemoteBackupStore(selected).use { it.listNames() }
                            record("connected_not_a_backup_receipt")
                        }
                        "restore_latest" -> {
                            val point = newestCompleteBackup(AutoBackupRuntime.list(applicationContext, true, selected))
                            check(same(store.settingsFlow.value))
                            // Capture the installed connection, not the downloaded redacted settings.
                            val bootstrap = store.settingsFlow.value.copy(assistantId = kotlin.uuid.Uuid.parse(owner))
                            AutoBackupRuntime.stage(applicationContext, point, true, selected,
                                bootstrapSettings = bootstrap)
                            record("restore_staged_restart_required")
                        }
                        else -> error("invalid_action")
                    }
                } finally { watcher.cancel() }
            }
            Result.success()
        } catch (e: TimeoutCancellationException) {
            record("timeout_originals_preserved"); Result.failure()
        } catch (e: CancellationException) {
            record("cancelled_originals_preserved"); throw e
        } catch (_: Exception) {
            record("not_completed_check_connection_or_complete_version"); Result.failure()
        }
    }
}
