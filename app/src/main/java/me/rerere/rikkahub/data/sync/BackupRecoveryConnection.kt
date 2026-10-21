package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** User-held connection document, NOT a public URL or a new recovery password. */
@Serializable
internal data class BackupRecoveryConnection(
    val format: String = "orbis-backup-connection",
    val version: Int = 1,
    val target: String,
    val webdav: WebDavConfig? = null,
    val s3: S3Config? = null,
) {
    fun validate() {
        require(format == "orbis-backup-connection" && version == 1) { "backup_connection_format" }
        require((target == "webdav" && webdav != null && s3 == null) ||
            (target == "s3" && s3 != null && webdav == null)) { "backup_connection_target" }
        val url = requireNotNull((webdav?.url ?: s3?.endpoint).orEmpty().toHttpUrlOrNull()) { "backup_connection_address" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "backup_connection_address"
        }
        webdav?.let { require(it.path.split('/').none { part -> part == "." || part == ".." }) { "backup_connection_path" } }
        s3?.let {
            require(it.bucket.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{1,62}")) &&
                it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank()) { "backup_connection_s3" }
        }
    }
    fun displayDestination(): String {
        validate()
        val url = (webdav?.url ?: s3!!.endpoint).toHttpUrlOrNull()!!
        // No credentials/query may enter a label, error or receipt.
        return "${if (target == "webdav") "WebDAV" else "S3"} · ${url.scheme}://${url.host}:${url.port}"
    }
    fun applyTo(settings: Settings): Settings {
        validate()
        // Importing a connection never enables uploading/pruning an empty fresh installation.
        return settings.copy(webDavConfig = webdav ?: settings.webDavConfig,
            s3Config = s3 ?: settings.s3Config,
            autoBackup = settings.autoBackup.copy(target = target, enabled = false,
                controllerAssistant = null, controllerWorkspace = null, controllerDestination = null))
    }
    fun encode(): ByteArray {
        validate()
        return json.encodeToString(this).toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..MAX_BYTES) { "backup_connection_size" }
        }
    }
    companion object {
        const val MAX_BYTES = 64 * 1024
        private val json = Json { encodeDefaults = true }
        fun from(settings: Settings): BackupRecoveryConnection = when (settings.autoBackup.target) {
            "webdav" -> BackupRecoveryConnection(target = "webdav", webdav = settings.webDavConfig)
            "s3" -> BackupRecoveryConnection(target = "s3", s3 = settings.s3Config)
            else -> error("backup_connection_target")
        }.also { it.validate() }
        fun ready(settings: Settings): Boolean = runCatching { from(settings) }.isSuccess
        fun decode(bytes: ByteArray): BackupRecoveryConnection {
            require(bytes.size in 1..MAX_BYTES) { "backup_connection_size" }
            return json.decodeFromString<BackupRecoveryConnection>(bytes.decodeToString(throwOnInvalidSequence = true))
                .also { it.validate() }
        }
    }
}

internal fun newestCompleteBackup(points: List<BackupRestorePoint>): BackupRestorePoint =
    requireNotNull(points.filter { it.includesDatabase && it.includesFiles }
        .sortedWith(compareByDescending<BackupRestorePoint> { it.createdAt }.thenBy { it.id }).firstOrNull()) {
        "backup_no_complete_version"
    }.also { it.validate() }
