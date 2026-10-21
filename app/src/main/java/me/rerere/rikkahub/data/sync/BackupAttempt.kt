package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.sync.webdav.WebDavException

/** Progress is separate from a verified receipt and from check/restore operations. */
internal object BackupAttempt {
    private fun prefs(context: Context) = context.getSharedPreferences("orbis-backup-attempt", Context.MODE_PRIVATE)
    fun write(context: Context, target: String, phase: String, failure: String? = null) {
        check(prefs(context).edit().putString("target", target).putString("phase", phase)
            .putString("failure", failure).putLong("at", System.currentTimeMillis()).commit())
    }
    fun status(context: Context, target: String): JsonObject {
        val p = prefs(context)
        if (p.getString("target", null) != target) return buildJsonObject { put("phase", "not_started") }
        return buildJsonObject {
            put("phase", p.getString("phase", "not_started")); put("at", p.getLong("at", 0))
            p.getString("failure", null)?.let { put("failure", it) }
        }
    }
}

/** Never include exception messages, remote bodies, file paths, or connection credentials. */
internal fun backupFailureCode(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    causes.filterIsInstance<WebDavException>().firstOrNull()?.let { return "http_${it.statusCode}" }
    if (causes.any { it is AtomicBackupUnsupportedException }) return "atomic_replace_unsupported"
    if (causes.any { it is java.net.SocketTimeoutException }) return "network_timeout"
    if (causes.any { it is java.net.UnknownHostException }) return "dns_unavailable"
    if (causes.any { it is javax.net.ssl.SSLException }) return "tls_failed"
    if (causes.any { it is java.io.IOException }) return "io_failed"
    if (causes.any { it is IllegalArgumentException }) return "validation_failed"
    if (causes.any { it is IllegalStateException }) return "consistency_check_failed"
    return "not_completed"
}

internal fun backupPhaseLabel(phase: String): String = when (phase) {
    "snapshot" -> "制作本机快照"
    "local_publish" -> "写入本机副本"
    "folder_publish" -> "写入自选文件夹"
    "remote_prepare" -> "检查服务器连接"
    "remote_publish_and_verify" -> "上传并校验服务器副本"
    else -> "准备存档"
}

internal object BackupWorkPolicy {
    const val AUTOMATIC = "orbis-backup-now-v2"
    const val REQUESTED = "orbis-backup-requested-v1"
    const val MANUAL = "manual_request"
    // Manual work is a fresh attempt, not another exponential retry. The regular
    // worker/observer remains responsible for deferred pending revisions.
    fun retry(manual: Boolean, completed: Boolean) = !manual && !completed
}
