package me.rerere.rikkahub.data.sync

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime
import me.rerere.rikkahub.utils.JsonInstant

/** Cold launcher only. Intact/current stores are NEVER replaced because an older checkpoint exists. */
internal object LocalCheckpointRecovery {
    fun needed(context: Context): Boolean {
        val db = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        return !db.exists() && !File(db.path + "-wal").exists() &&
            File(context.noBackupFilesDir, "automatic-versions").list().orEmpty().any(BackupRestorePoint::isMarker)
    }
    suspend fun recover(context: Context): Boolean = withContext(Dispatchers.IO) {
        if(!needed(context)) return@withContext false
        // Opening an existing, running app is a no-op; do not interrupt a call/generation.
        val lease = EmergencyAndroidRuntime.gate(context).tryAcquireColdBackupRestoreLease() ?: return@withContext false
        lease.use {
            if(!needed(context)) return@withContext false
            // Reuse the transactional installer. It journals originals and rejects conflicting
            // activity/other-owner data; no guessed passwords, deleted caches or network calls.
            if(File(context.noBackupFilesDir, "backup-restore/pending").isDirectory)
                return@withContext BackupManager.applyPendingRestore(context, JsonInstant)
            val versions = VersionedBackup(AutoBackupRuntime.local(context), context.cacheDir)
            val point = versions.list().filter { it.includesDatabase && it.includesFiles }.maxByOrNull { it.createdAt }
                ?: error("没有通过检查的完整本地备份。原备份已保留，请选择其他恢复点；未新建空档案覆盖原内容。")
            val archive = File.createTempFile("checkpoint-recovery-", ".zip", context.cacheDir)
            try {
                versions.download(point, archive)
                BackupManager(context, null, null, JsonInstant).stageRestore(archive, true, true)
                BackupManager.applyPendingRestore(context, JsonInstant)
            } finally { archive.delete() }
        }
    }
}
