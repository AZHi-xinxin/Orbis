package com.lover.connect

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class CompanionAlarmRecord(
    val id: String,
    val hour: Int,
    val minute: Int,
    val message: String,
    val triggerAt: Long,
    val timeZone: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val detail: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("alarm_id", id)
        put("hour", hour)
        put("minute", minute)
        put("message", message)
        put("trigger_at_ms", triggerAt)
        put("time_zone", timeZone)
        put("created_at_ms", createdAt)
        put("updated_at_ms", updatedAt)
        put("status", status)
        put("detail", detail ?: JSONObject.NULL)
    }
}

/**
 * The application's alarm ledger, not a claim that Android still holds each scheduled operation.
 * Callers can hold [LOCK] around ledger changes and their Android scheduling/cancellation operation.
 * Reads never create a file. Existing malformed, unsupported or oversized data fails closed.
 */
class CompanionAlarmStore internal constructor(
    private val filesDir: File,
    private val atomicReplace: (Path, Path) -> Unit,
) {
    constructor(filesDir: File) : this(filesDir, { temporary, destination ->
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    })

    companion object {
        /** Shared with Android receivers, services and native/MCP callers; Java monitors are reentrant. */
        val LOCK = Any()
        const val FILE_NAME = "lc_alarms.json"
        const val HISTORY_LIMIT = 256
        const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_MESSAGE_BYTES = 128 * 1024
        private val statuses = setOf(
            "scheduled", "scheduling", "schedule_unknown", "cancelled", "fired", "ringing",
            "stopped", "ring_failed", "missed_unconfirmed", "cancel_unknown", "superseded",
        )
        private val historyStatuses = setOf("cancelled", "fired", "stopped", "ring_failed", "missed_unconfirmed", "superseded")
        private val recordKeys = setOf("alarm_id", "hour", "minute", "message", "trigger_at_ms", "time_zone", "created_at_ms", "updated_at_ms", "status", "detail")
        private val detailPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,95}")

        private fun validate(record: CompanionAlarmRecord) {
            require(UUID.fromString(record.id).toString().equals(record.id, ignoreCase = true)) { "alarm_id_invalid" }
            require(record.hour in 0..23 && record.minute in 0..59) { "alarm_time_invalid" }
            require(record.message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "alarm_message_too_large" }
            require(record.triggerAt >= 0 && record.createdAt >= 0 && record.updatedAt >= 0) { "alarm_timestamp_invalid" }
            require(record.timeZone.length <= 128) { "alarm_timezone_invalid" }
            ZoneId.of(record.timeZone)
            require(record.status in statuses) { "alarm_status_invalid" }
            require(record.detail == null || detailPattern.matches(record.detail)) { "alarm_detail_invalid" }
        }

        private fun JSONObject.strictLong(key: String): Long {
            val value = get(key)
            require(value is Int || value is Long) { "alarm_integer_invalid" }
            return (value as Number).toLong()
        }

        private fun JSONObject.strictString(key: String): String =
            (get(key) as? String) ?: error("alarm_string_invalid")

        private fun decodeRecord(json: JSONObject): CompanionAlarmRecord {
            require(json.keys().asSequence().all { it in recordKeys }) { "alarm_record_schema_unsupported" }
            val hour = json.strictLong("hour")
            val minute = json.strictLong("minute")
            require(hour in 0L..23L && minute in 0L..59L) { "alarm_time_invalid" }
            return CompanionAlarmRecord(
                id = json.strictString("alarm_id"),
                hour = hour.toInt(),
                minute = minute.toInt(),
                message = json.strictString("message"),
                triggerAt = json.strictLong("trigger_at_ms"),
                timeZone = json.strictString("time_zone"),
                createdAt = json.strictLong("created_at_ms"),
                updatedAt = json.strictLong("updated_at_ms"),
                status = json.strictString("status"),
                detail = if (!json.has("detail") || json.isNull("detail")) null else json.strictString("detail"),
            ).also(::validate)
        }
    }

    private val file get() = File(filesDir, FILE_NAME)

    /** Original array order is significant: last generation for a wall-clock slot wins. */
    fun records(): List<CompanionAlarmRecord> = synchronized(LOCK) { load() }

    /** New IDs append; updating an old generation never moves it after a newer generation. */
    fun put(record: CompanionAlarmRecord) = synchronized(LOCK) {
        validate(record)
        val previous = load()
        val position = previous.indexOfFirst { it.id == record.id }
        val next = previous.toMutableList().apply {
            if (position >= 0) set(position, record) else add(record)
        }
        write(pruneHistory(next))
    }

    fun update(id: String, status: String, now: Long, detail: String? = null): CompanionAlarmRecord? = synchronized(LOCK) {
        require(status in statuses) { "alarm_status_invalid" }
        require(now >= 0) { "alarm_timestamp_invalid" }
        require(detail == null || detailPattern.matches(detail)) { "alarm_detail_invalid" }
        val previous = load()
        val position = previous.indexOfFirst { it.id == id }
        if (position < 0) return@synchronized null
        val updated = previous[position].copy(status = status, updatedAt = now, detail = detail)
        validate(updated)
        write(pruneHistory(previous.toMutableList().apply { set(position, updated) }))
        updated
    }

    private fun pruneHistory(records: List<CompanionAlarmRecord>): List<CompanionAlarmRecord> {
        // A terminal head is a durable tombstone, not disposable audit history. Losing it can
        // expose an older still-active generation after an interrupted superseded receipt.
        val heads = records.associateBy { it.hour * 100 + it.minute }.values.map { it.id }.toSet()
        val retainedHistory = records.filter { it.status in historyStatuses && it.id !in heads }
            .takeLast(HISTORY_LIMIT).map { it.id }.toSet()
        return records.filter { it.status !in historyStatuses || it.id in heads || it.id in retainedHistory }
    }

    private fun load(): List<CompanionAlarmRecord> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() <= MAX_BYTES) { "alarm_file_invalid_or_too_large" }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "alarm_file_too_large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokener = JSONTokener(content)
        val json = tokener.nextValue() as? JSONObject ?: error("alarm_file_invalid")
        require(tokener.nextClean() == '\u0000') { "alarm_file_trailing_data" }
        require(json.keys().asSequence().toSet() == setOf("version", "records") && json.strictLong("version") == 1L) {
            "alarm_file_schema_unsupported"
        }
        val array = json.get("records") as? JSONArray ?: error("alarm_file_invalid")
        val records = (0 until array.length()).map { index ->
            decodeRecord(array.get(index) as? JSONObject ?: error("alarm_record_invalid"))
        }
        require(records.map { it.id }.distinct().size == records.size) { "alarm_duplicate_id" }
        return records
    }

    private fun write(records: List<CompanionAlarmRecord>) {
        val content = JSONObject().put("version", 1).put("records", JSONArray().apply {
            records.forEach { put(it.toJson()) }
        }).toString()
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "alarm_file_too_large" }
        check(filesDir.isDirectory || filesDir.mkdirs()) { "alarm_directory_unavailable" }
        val temporary = Files.createTempFile(filesDir.toPath(), ".lc_alarms-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            // No copy/delete fallback: a failed/unsupported atomic replacement leaves the old file.
            atomicReplace(temporary, file.toPath())
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
