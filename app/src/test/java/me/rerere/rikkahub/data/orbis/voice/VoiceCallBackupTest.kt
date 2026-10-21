package me.rerere.rikkahub.data.orbis.voice

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCallBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private val record = OrbisVoiceCallRecord("call-1", "conversation-1", "owner-1", 10)
    private fun stage(value: OrbisVoiceCallRecord = record): File {
        val payload = File(temporary.root, "payload")
        File(payload, "files/orbis-voice-calls/${value.id}.json").apply {
            parentFile!!.mkdirs(); writeText(json.encodeToString(value))
        }
        return payload
    }
    @Test fun `valid records require matching assistant and conversation`() {
        val payload = stage()
        VoiceCallBackup.validateStaged(payload)
        VoiceCallBackup.validateOwnership(payload, mapOf("conversation-1" to "owner-1"))
        assertEquals("voice_backup_owner", runCatching {
            VoiceCallBackup.validateOwnership(payload, mapOf("conversation-1" to "other"))
        }.exceptionOrNull()?.message)
        assertTrue(runCatching { VoiceCallBackup.validateOwnership(payload, emptyMap()) }.isFailure)
    }
    @Test fun `conflicting existing records are not silently overwritten`() {
        val payload = stage()
        val live = File(temporary.root, "live")
        val old = File(live, "orbis-voice-calls/call-1.json").apply {
            parentFile!!.mkdirs(); writeText(json.encodeToString(record.copy(modelId = "different")))
        }
        val before = old.readBytes()
        assertEquals("voice_backup_conflict", runCatching { VoiceCallBackup.prepareBeforeJournal(payload, live) }.exceptionOrNull()?.message)
        assertArrayEquals(before, old.readBytes())
        assertNull(VoiceCallBackup.maxBytes("orbis-voice-calls/../secret.json"))
        assertNull(VoiceCallBackup.maxBytes("orbis-voice-calls/call-1.json.bak"))
        assertTrue(runCatching { VoiceCallBackup.decode("orbis-voice-calls/wrong.json", json.encodeToString(record).toByteArray()) }.isFailure)
    }
    @Test fun `locked snapshots exclude other owners and consultations`() = runBlocking {
        val data = mutableMapOf<String, String>()
        val repo = OrbisVoiceCallRepository(object : OrbisVoiceCallStorage {
            override val lockKey = temporary.root.path
            override fun ids() = data.keys.toList()
            override fun read(id: String) = data[id]
            override fun write(id: String, value: String) { data[id] = value }
        })
        repo.create(record)
        repo.create(record.copy(id = "consultation", conversationId = "private"))
        val result = mutableListOf<String>()
        repo.snapshotForBackup(mapOf("conversation-1" to "owner-1")) { name, bytes ->
            assertEquals(record, VoiceCallBackup.decode(name, bytes)); result += name
        }
        assertEquals(listOf("orbis-voice-calls/call-1.json"), result)
    }
}
