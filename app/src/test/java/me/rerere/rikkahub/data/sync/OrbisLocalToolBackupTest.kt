package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.orbis.OrbisKaomoji
import me.rerere.rikkahub.data.orbis.OrbisKaomojiState
import me.rerere.rikkahub.data.orbis.schedule.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisLocalToolBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private fun schedule(id: String, title: String = "合成日程") = OrbisScheduleEntry(id,
        OrbisScheduleDraft(OrbisScheduleKind.DATE, title, date = "2026-10-01", allDay = true), 1, 1)
    private fun scheduleBytes(vararg entries: OrbisScheduleEntry, revision: Int = 1) = OrbisLocalToolBackup.encodeSchedule(OrbisScheduleSnapshot(revision = revision, entries = entries.toList()))
    private fun kaomoji(id: String, text: String = "(＾▽＾)") = OrbisKaomoji(id, "合成颜文字", text)
    private fun kaomojiBytes(vararg entries: OrbisKaomoji) = OrbisLocalToolBackup.encodeKaomoji(OrbisKaomojiState(entries = entries.toList()))
    private fun write(file: File, bytes: ByteArray): File { file.parentFile!!.mkdirs(); file.writeBytes(bytes); return file }
    private fun live(path: String) = File(temporary.root, "files/$path")
    private fun restore() = PendingRestore(File(temporary.root, "restore"), File(temporary.root, "database/main"), File(temporary.root, "files"))
    private fun publish(vararg values: Pair<String, ByteArray>, withSettings: Boolean = false) {
        val manager = restore()
        val staging = manager.createStagingDirectory()
        values.forEach { (path, bytes) -> write(File(staging, "payload/files/$path"), bytes) }
        if (withSettings) write(File(staging, "settings.json"), "synthetic settings".toByteArray())
        manager.publish(staging)
    }

    @Test fun `archive allowlist contains only two exact documents not directories or sidecars`() {
        assertEquals(setOf("orbis-schedule/schedule-v1.json", "orbis-kaomoji/library-v1.json"), OrbisLocalToolBackup.paths.toSet())
        listOf("orbis-schedule", "orbis-schedule/schedule-v1.json.bak", "orbis-schedule/schedule-v1.json.new", "orbis-kaomoji/token.json",
            "orbis-schedule/../secret", "orbis-consultation/runtime.json", "no_backup/config.json").forEach { assertNull(OrbisLocalToolBackup.maxBytes(it)) }
        assertTrue(OrbisLocalToolBackup.sidecarPaths(setOf("files/upload/photo")).isEmpty())
        assertEquals(listOf("files/${OrbisLocalToolBackup.SCHEDULE}.bak", "files/${OrbisLocalToolBackup.SCHEDULE}.new"),
            OrbisLocalToolBackup.sidecarPaths(setOf("files/${OrbisLocalToolBackup.SCHEDULE}")))
    }

    @Test fun `valid semantic snapshots round trip without injecting settings`() {
        val source = scheduleBytes(schedule("s1"))
        assertArrayEquals(source, OrbisLocalToolBackup.merge(OrbisLocalToolBackup.SCHEDULE, null, source))
        val emoji = kaomojiBytes(kaomoji("k1"))
        assertArrayEquals(emoji, OrbisLocalToolBackup.merge(OrbisLocalToolBackup.KAOMOJI, null, emoji))
        assertFalse(source.toString(Charsets.UTF_8).contains("settings"))
    }

    @Test fun `schedule merge preserves local only entries skips identical and advances revision`() {
        val local = schedule("local")
        val shared = schedule("shared")
        val incoming = schedule("incoming")
        val result = OrbisLocalToolBackup.merge(OrbisLocalToolBackup.SCHEDULE,
            scheduleBytes(local, shared, revision = 4), scheduleBytes(shared, incoming, revision = 2))
        val state = json.decodeFromString<OrbisScheduleSnapshot>(result.toString(Charsets.UTF_8))
        assertEquals(listOf(local, shared, incoming), state.entries)
        assertEquals(5, state.revision)
        assertArrayEquals(result, OrbisLocalToolBackup.merge(OrbisLocalToolBackup.SCHEDULE, result, scheduleBytes(shared, incoming, revision = 2)))
    }

    @Test fun `schedule differing same ID fails without choosing newer version`() {
        val old = scheduleBytes(schedule("same"), revision = 1)
        val incoming = scheduleBytes(schedule("same", "different"), revision = 99)
        val failure = runCatching { OrbisLocalToolBackup.merge(OrbisLocalToolBackup.SCHEDULE, old, incoming) }.exceptionOrNull()
        assertEquals("local_tool_backup_conflict_schedule", failure?.message)
        assertTrue(OrbisLocalToolBackup.publicError(failure?.message)!!.contains("课表与日程"))
        assertFalse(OrbisLocalToolBackup.publicError(failure?.message)!!.contains("different"))
    }

    @Test fun `kaomoji merge preserves local entries and rejects same text different identity`() {
        val a = kaomoji("a")
        val b = kaomoji("b", "(T_T)")
        val merged = OrbisLocalToolBackup.merge(OrbisLocalToolBackup.KAOMOJI, kaomojiBytes(a), kaomojiBytes(b))
        assertEquals(listOf(a, b), json.decodeFromString<OrbisKaomojiState>(merged.toString(Charsets.UTF_8)).entries)
        assertArrayEquals(merged, OrbisLocalToolBackup.merge(OrbisLocalToolBackup.KAOMOJI, merged, kaomojiBytes(b)))
        assertEquals("local_tool_backup_conflict_kaomoji", runCatching {
            OrbisLocalToolBackup.merge(OrbisLocalToolBackup.KAOMOJI, kaomojiBytes(a), kaomojiBytes(a.copy(id = "other")))
        }.exceptionOrNull()?.message)
    }

    @Test fun `invalid UTF8 missing fields versions extra keys and duplicate identities are rejected`() {
        listOf("{}".toByteArray(), "{\"version\":2,\"revision\":0,\"entries\":[]}".toByteArray(),
            "{\"version\":1,\"revision\":0,\"entries\":[],\"credential\":\"synthetic\"}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28)).forEach {
            assertEquals("local_tool_backup_invalid_schedule", runCatching { OrbisLocalToolBackup.validate(OrbisLocalToolBackup.SCHEDULE, it) }.exceptionOrNull()?.message)
        }
        assertNotNull(runCatching { scheduleBytes(schedule("s1"), schedule("s1")) }.exceptionOrNull())
        assertNotNull(runCatching { kaomojiBytes(kaomoji("a"), kaomoji("a", "(T_T)")) }.exceptionOrNull())
        assertNotNull(runCatching { OrbisLocalToolBackup.validate(OrbisLocalToolBackup.KAOMOJI, "{\"version\":1,\"entries\":[],\"token\":\"x\"}".toByteArray()) }.exceptionOrNull())
        assertNotNull(runCatching { OrbisLocalToolBackup.validate(OrbisLocalToolBackup.KAOMOJI, ByteArray(OrbisLocalToolBackup.maxBytes(OrbisLocalToolBackup.KAOMOJI)!! + 1)) }.exceptionOrNull())
    }

    @Test fun `old archive without local tools leaves both libraries and sidecars untouched`() = runBlocking {
        val schedule = scheduleBytes(schedule("s1"))
        val emoji = kaomojiBytes(kaomoji("k1"))
        write(live(OrbisLocalToolBackup.SCHEDULE), schedule)
        write(live(OrbisLocalToolBackup.KAOMOJI), emoji)
        write(live("${OrbisLocalToolBackup.SCHEDULE}.bak"), schedule)
        publish("upload/synthetic" to "attachment".toByteArray())
        assertTrue(restore().apply { error("no settings") })
        assertArrayEquals(schedule, live(OrbisLocalToolBackup.SCHEDULE).readBytes())
        assertArrayEquals(emoji, live(OrbisLocalToolBackup.KAOMOJI).readBytes())
        assertArrayEquals(schedule, live("${OrbisLocalToolBackup.SCHEDULE}.bak").readBytes())
    }

    @Test fun `new archive merges only on startup and removes stale atomic sidecars through journal`() = runBlocking {
        val committed = scheduleBytes(schedule("committed"))
        val stale = scheduleBytes(schedule("uncommitted"))
        write(live(OrbisLocalToolBackup.SCHEDULE), stale)
        write(live("${OrbisLocalToolBackup.SCHEDULE}.bak"), committed)
        write(live("${OrbisLocalToolBackup.SCHEDULE}.new"), "incomplete".toByteArray())
        publish(OrbisLocalToolBackup.SCHEDULE to scheduleBytes(schedule("incoming")))
        assertArrayEquals(stale, live(OrbisLocalToolBackup.SCHEDULE).readBytes())
        assertTrue(restore().apply { error("no settings") })
        val state = json.decodeFromString<OrbisScheduleSnapshot>(live(OrbisLocalToolBackup.SCHEDULE).readText())
        assertEquals(listOf("committed", "incoming"), state.entries.map { it.id })
        assertFalse(live("${OrbisLocalToolBackup.SCHEDULE}.bak").exists())
        assertFalse(live("${OrbisLocalToolBackup.SCHEDULE}.new").exists())
    }

    @Test fun `second library conflict rejects entire restore before any live file changes`() = runBlocking {
        val originalSchedule = scheduleBytes(schedule("local"))
        val originalKaomoji = kaomojiBytes(kaomoji("same"))
        write(live(OrbisLocalToolBackup.SCHEDULE), originalSchedule)
        write(live(OrbisLocalToolBackup.KAOMOJI), originalKaomoji)
        publish(OrbisLocalToolBackup.SCHEDULE to scheduleBytes(schedule("incoming")),
            OrbisLocalToolBackup.KAOMOJI to kaomojiBytes(kaomoji("same", "(T_T)")), withSettings = true)
        val failure = runCatching { restore().apply { error("settings must not run") } }.exceptionOrNull()
        assertTrue(failure is RestoreFailedException)
        assertTrue(failure?.message.orEmpty().contains("颜文字"))
        assertArrayEquals(originalSchedule, live(OrbisLocalToolBackup.SCHEDULE).readBytes())
        assertArrayEquals(originalKaomoji, live(OrbisLocalToolBackup.KAOMOJI).readBytes())
    }

    @Test fun `later settings failure rolls back base and every original atomic sidecar`() = runBlocking {
        val original = scheduleBytes(schedule("original"))
        val backup = scheduleBytes(schedule("backup"))
        val unfinished = "unfinished".toByteArray()
        write(live(OrbisLocalToolBackup.SCHEDULE), original)
        write(live("${OrbisLocalToolBackup.SCHEDULE}.bak"), backup)
        write(live("${OrbisLocalToolBackup.SCHEDULE}.new"), unfinished)
        publish(OrbisLocalToolBackup.SCHEDULE to scheduleBytes(schedule("incoming")), withSettings = true)
        assertTrue(runCatching { restore().apply { throw IOException("synthetic disk failure") } }.exceptionOrNull() is RestoreFailedException)
        assertArrayEquals(original, live(OrbisLocalToolBackup.SCHEDULE).readBytes())
        assertArrayEquals(backup, live("${OrbisLocalToolBackup.SCHEDULE}.bak").readBytes())
        assertArrayEquals(unfinished, live("${OrbisLocalToolBackup.SCHEDULE}.new").readBytes())
    }

    @Test fun `journal resumes interrupted install without remerging or replaying later`() = runBlocking {
        write(live(OrbisLocalToolBackup.SCHEDULE), scheduleBytes(schedule("local")))
        publish(OrbisLocalToolBackup.SCHEDULE to scheduleBytes(schedule("incoming")), withSettings = true)
        assertTrue(runCatching { restore().apply { throw SimulatedDeath() } }.exceptionOrNull() is SimulatedDeath)
        val once = live(OrbisLocalToolBackup.SCHEDULE).readBytes()
        assertTrue(restore().apply { })
        assertArrayEquals(once, live(OrbisLocalToolBackup.SCHEDULE).readBytes())
        assertFalse(restore().apply { error("must not replay") })
    }

    @Test fun `uncommitted only live document cannot be silently replaced`() = runBlocking {
        val bytes = "uncommitted".toByteArray()
        write(live("${OrbisLocalToolBackup.SCHEDULE}.new"), bytes)
        publish(OrbisLocalToolBackup.SCHEDULE to scheduleBytes(schedule("incoming")))
        assertTrue(runCatching { restore().apply { } }.exceptionOrNull() is RestoreFailedException)
        assertFalse(live(OrbisLocalToolBackup.SCHEDULE).exists())
        assertArrayEquals(bytes, live("${OrbisLocalToolBackup.SCHEDULE}.new").readBytes())
    }

    @Test fun `malformed archive is rejected before publication and raw values do not enter error text`() {
        val payload = File(temporary.root, "payload")
        write(File(payload, "files/${OrbisLocalToolBackup.KAOMOJI}"), "private synthetic invalid value".toByteArray())
        val failure = runCatching { OrbisLocalToolBackup.validateStaged(payload) }.exceptionOrNull()
        assertEquals("local_tool_backup_invalid_kaomoji", failure?.message)
        assertFalse(OrbisLocalToolBackup.publicError(failure?.message).orEmpty().contains("private"))
        assertFalse(File(temporary.root, "files").exists())
    }

    private class SimulatedDeath : Error()
}
