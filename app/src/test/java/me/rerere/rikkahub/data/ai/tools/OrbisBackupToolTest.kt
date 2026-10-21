package me.rerere.rikkahub.data.ai.tools

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupRecoveryConnection
import org.junit.Assert.*
import org.junit.Test

class OrbisBackupToolTest {
    @Test fun workspaceBindingIsApprovedOnceAndReceiptsNeverEchoSecrets() = runBlocking {
        val source = BackupRecoveryConnection(target = "webdav", webdav = WebDavConfig(
            url = "https://example.invalid/dav", username = "synthetic-user", password = "synthetic-password"))
        val bytes = source.encode()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var bindings = 0
        val requests = mutableListOf<String>()
        val tool = createOrbisBackupTool("workspace", { true }, { bytes.copyOf() },
            bind = { config, auto, secrets ->
                assertEquals(source, config); assertTrue(auto); assertFalse(secrets); bindings++
            }, status = { buildJsonObject { put("bound", true) } }, request = { requests += it })
        val inspect = buildJsonObject { put("action", "inspect"); put("connection_file", "/workspace/backup.json") }
        assertFalse(tool.needsApproval(inspect))
        val inspected = tool.execute(inspect).filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(hash, Json.parseToJsonElement(inspected).jsonObject["expected_sha256"]!!.jsonPrimitive.content)
        assertFalse(inspected.contains("synthetic-password"))
        assertFalse(inspected.contains("synthetic-user"))
        assertEquals(0, bindings)
        assertTrue(requests.isEmpty())
        val args = buildJsonObject { put("action", "bind"); put("connection_file", "/workspace/backup.json"); put("expected_sha256", hash) }
        assertTrue(tool.needsApproval(args))
        assertTrue(tool.hostApproval!!.rememberable)
        val result = tool.execute(args).toString()
        assertTrue(result.contains("bound"))
        assertFalse(result.contains("synthetic-password"))
        assertFalse(result.contains("synthetic-user"))
        assertEquals(1, bindings)
        assertTrue(requests.isEmpty())
        val queued = tool.execute(buildJsonObject { put("action", "check") }).toString()
        assertTrue(queued.contains("queued"))
        assertEquals(listOf("check"), requests)
        assertTrue(tool.needsApproval(buildJsonObject { put("action", "restore_latest") }))
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "status") }))
    }
    @Test fun invalidPathHashOrStaleOwnerNeverBinds() = runBlocking {
        var bindings = 0
        var live = true
        val tool = createOrbisBackupTool("workspace", { live }, { "{}".toByteArray() },
            bind = { _, _, _ -> bindings++ }, status = { buildJsonObject {} }, request = {})
        for (path in listOf("/upload/key.json", "/data/private.json", "/workspace/bad.json")) {
            val result = tool.execute(buildJsonObject {
                put("action", "bind"); put("connection_file", path); put("expected_sha256", "0".repeat(64))
            }).toString()
            assertTrue(result.contains("not_confirmed"))
        }
        live = false
        assertTrue(tool.execute(buildJsonObject { put("action", "status") }).toString().contains("not_confirmed"))
        assertEquals(0, bindings)
    }
}
