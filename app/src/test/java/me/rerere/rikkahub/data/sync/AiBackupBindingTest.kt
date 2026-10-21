package me.rerere.rikkahub.data.sync

import java.security.MessageDigest
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AiBackupBindingTest {
    private val workspace = Uuid.random()
    private val owner = Assistant(name = "Synthetic", workspaceId = workspace)
    private val other = Assistant(name = "Other", workspaceId = workspace)
    private val initial = Settings(assistants = listOf(owner, other))
    private val connection = BackupRecoveryConnection(target = "webdav",
        webdav = WebDavConfig(url = "https://example.invalid/dav", username = "synthetic", password = "fake"))
    private fun bind() = AiBackupBinding.bind(initial, owner.id.toString(), workspace.toString(), connection, true, false)
    @Test fun bindChangesOnlyBackupAndIsScopedToAssistantWorkspaceAndExactDestination() {
        val next = bind()
        assertTrue(next.autoBackup.enabled)
        assertFalse(next.autoBackup.includeConnectionSecrets)
        assertEquals(initial.providers, next.providers)
        assertEquals(initial.assistants, next.assistants)
        assertTrue(AiBackupBinding.owns(next, owner.id.toString(), workspace.toString()))
        assertFalse(AiBackupBinding.owns(next, other.id.toString(), workspace.toString()))
        assertFalse(AiBackupBinding.owns(next, owner.id.toString(), Uuid.random().toString()))
        assertFalse(AiBackupBinding.owns(next.copy(webDavConfig = next.webDavConfig.copy(path = "different")),
            owner.id.toString(), workspace.toString()))
        assertFalse(AiBackupBinding.owns(connection.applyTo(next), owner.id.toString(), workspace.toString()))
    }
    @Test fun changedWorkspaceAndHashCannotBeSilentlyAccepted() {
        assertThrows(IllegalStateException::class.java) {
            AiBackupBinding.bind(initial, owner.id.toString(), Uuid.random().toString(), connection, true, false)
        }
        val bytes = connection.encode()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(connection, AiBackupBinding.read(bytes, hash))
        assertThrows(IllegalArgumentException::class.java) { AiBackupBinding.read(bytes + 32.toByte(), hash) }
        assertThrows(IllegalArgumentException::class.java) { AiBackupBinding.read(bytes, "") }
    }
}
