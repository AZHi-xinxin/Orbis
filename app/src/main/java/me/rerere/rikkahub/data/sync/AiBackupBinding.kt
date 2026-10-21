package me.rerere.rikkahub.data.sync

import java.security.MessageDigest
import me.rerere.rikkahub.data.datastore.Settings

internal object AiBackupBinding {
    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun read(bytes: ByteArray, expectedSha256: String): BackupRecoveryConnection {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "backup_connection_hash_required" }
        val actual = fingerprint(bytes)
        require(actual == expectedSha256) { "backup_connection_file_changed" }
        return BackupRecoveryConnection.decode(bytes)
    }
    fun bind(settings: Settings, owner: String, workspace: String, connection: BackupRecoveryConnection,
             automatic: Boolean, includeSecrets: Boolean): Settings {
        check(!settings.init && settings.assistants.any {
            it.id.toString() == owner && it.workspaceId?.toString() == workspace
        }) { "backup_assistant_workspace_changed" }
        val selected = connection.applyTo(settings)
        val key = ContinuousBackup.key(selected.autoBackup.target, selected)
        return selected.copy(autoBackup = selected.autoBackup.copy(enabled = automatic,
            includeConnectionSecrets = includeSecrets,
            controllerAssistant = owner, controllerWorkspace = workspace, controllerDestination = key))
    }
    fun owns(settings: Settings, owner: String, workspace: String): Boolean {
        val config = settings.autoBackup
        return !settings.init && settings.assistants.any {
            it.id.toString() == owner && it.workspaceId?.toString() == workspace
        } && config.controllerAssistant == owner && config.controllerWorkspace == workspace &&
            config.controllerDestination == ContinuousBackup.key(config.target, settings)
    }
}
