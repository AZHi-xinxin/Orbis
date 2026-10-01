package me.rerere.rikkahub.data.orbis.schedule

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal interface OrbisSchedulePersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** One app-private file shared by the human page and AI tools; no credentials or network access. */
class OrbisScheduleStore internal constructor(
    private val persistence: OrbisSchedulePersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private var observed = false
    private var unavailable = false
    val changes: StateFlow<Long> = changeCounter.asStateFlow()

    suspend fun load(): OrbisScheduleSnapshot = access { loadLocked() }

    suspend fun create(expectedRevision: Int, draft: OrbisScheduleDraft): OrbisScheduleSnapshot = access {
        validateOrbisScheduleDraft(draft)
        val before = checkedLocked(expectedRevision)
        require(before.entries.size < ORBIS_SCHEDULE_MAX_ENTRIES) { "schedule_entry_limit" }
        val id = newId().also(::validateOrbisScheduleId)
        check(before.entries.none { it.id == id }) { "schedule_id_collision" }
        val now = clock().also { check(it >= 0) { "schedule_invalid_clock" } }
        writeLocked(before.copy(revision = before.revision + 1,
            entries = before.entries + OrbisScheduleEntry(id, draft, now, now)))
    }

    /** Replace exactly one definition; a weekly entry changes all its occurrences, not other entries. */
    suspend fun update(expectedRevision: Int, id: String, draft: OrbisScheduleDraft): OrbisScheduleSnapshot = access {
        validateOrbisScheduleId(id)
        validateOrbisScheduleDraft(draft)
        val before = checkedLocked(expectedRevision)
        val old = before.entries.firstOrNull { it.id == id } ?: error("schedule_not_found")
        val now = clock().coerceAtLeast(old.updatedAt)
        writeLocked(before.copy(revision = before.revision + 1, entries = before.entries.map {
            if (it.id == id) it.copy(details = draft, updatedAt = now) else it
        }))
    }

    suspend fun delete(expectedRevision: Int, id: String): OrbisScheduleSnapshot = access {
        validateOrbisScheduleId(id)
        val before = checkedLocked(expectedRevision)
        check(before.entries.any { it.id == id }) { "schedule_not_found" }
        writeLocked(before.copy(revision = before.revision + 1, entries = before.entries.filterNot { it.id == id }))
    }

    private fun checkedLocked(expectedRevision: Int): OrbisScheduleSnapshot {
        require(expectedRevision >= 0) { "schedule_invalid_revision" }
        val before = loadLocked()
        check(before.revision == expectedRevision) { "schedule_revision_changed" }
        check(before.revision < Int.MAX_VALUE) { "schedule_revision_exhausted" }
        return before
    }

    private fun loadLocked(): OrbisScheduleSnapshot {
        val bytes = try { persistence.read() } catch (_: Exception) {
            unavailable = true
            error("schedule_storage_unavailable")
        }
        if (bytes == null) {
            check(!observed && !unavailable) { "schedule_storage_disappeared" }
            return OrbisScheduleSnapshot()
        }
        observed = true
        val snapshot = try {
            require(bytes.size <= ORBIS_SCHEDULE_MAX_BYTES)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val document = json.parseToJsonElement(text) as? JsonObject ?: error("invalid")
            require(document.keys == setOf("version", "revision", "entries"))
            json.decodeFromString<OrbisScheduleSnapshot>(text).also(::validateOrbisScheduleSnapshot)
        } catch (_: Exception) {
            unavailable = true
            error("schedule_invalid_storage")
        }
        unavailable = false
        return snapshot
    }

    private fun writeLocked(snapshot: OrbisScheduleSnapshot): OrbisScheduleSnapshot {
        validateOrbisScheduleSnapshot(snapshot)
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ORBIS_SCHEDULE_MAX_BYTES) { "schedule_storage_limit" }
        try {
            persistence.write(bytes)
            check(persistence.read()?.contentEquals(bytes) == true)
        } catch (_: Exception) {
            unavailable = true
            error("schedule_write_unverified")
        }
        observed = true
        unavailable = false
        changeCounter.value += 1
        return snapshot
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        sharedMutex.withLock {
            // Keep sanitized storage failures as values across the dispatcher boundary. Coroutine
            // stack-trace recovery must not attach a cloned cause to our closed public error.
            try { Result.success(block()) } catch (failure: Exception) { Result.failure(failure) }
        }
    }.getOrThrow()

    companion object {
        private val sharedMutex = Mutex()
        private val changeCounter = MutableStateFlow(0L)
        @Volatile private var instance: OrbisScheduleStore? = null
        fun open(context: Context): OrbisScheduleStore = instance ?: synchronized(this) {
            instance ?: OrbisScheduleStore(AndroidOrbisSchedulePersistence(context.applicationContext)).also { instance = it }
        }
    }
}

private class AndroidOrbisSchedulePersistence(context: Context) : OrbisSchedulePersistence {
    private val filesRoot = context.filesDir.canonicalFile
    private val directory = File(filesRoot, "orbis-schedule")
    private val target = File(directory, "schedule-v1.json")

    private fun validate(create: Boolean): Boolean {
        check(directory.canonicalFile == directory.absoluteFile) { "schedule_path_invalid" }
        if (!directory.exists()) {
            if (!create) return false
            check(directory.mkdirs() || directory.isDirectory) { "schedule_directory_unavailable" }
        }
        check(directory.isDirectory && directory.canonicalFile.parentFile == filesRoot) { "schedule_path_invalid" }
        listOf(target, File(target.path + ".bak"), File(target.path + ".new")).forEach {
            check(it.canonicalFile == it.absoluteFile && (!it.exists() || it.isFile)) { "schedule_path_invalid" }
        }
        return true
    }

    override fun read(): ByteArray? {
        if (!validate(create = false)) return null
        if (!target.exists() && !File(target.path + ".bak").exists()) {
            check(!File(target.path + ".new").exists()) { "schedule_uncommitted_storage" }
            return null
        }
        return AtomicFile(target).openRead().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(result.size() + size <= ORBIS_SCHEDULE_MAX_BYTES) { "schedule_storage_limit" }
                result.write(buffer, 0, size)
            }
            result.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= ORBIS_SCHEDULE_MAX_BYTES)
        check(validate(create = true))
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            try { atomic.failWrite(output) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
}
