package me.rerere.rikkahub.data.orbis

import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.model.validOrbisGardenName

@Serializable
enum class OrbisGardenKind(val label: String) { DIARY("日记"), ANCHOR("锚点"), LETTER("信件"), WISH("心愿"), SONG("歌曲") }

/** Plain text only. No executable HTML, remote URL fetching or private-chat linkage. */
@Serializable
data class OrbisGardenEntry(
    val id: String,
    val kind: OrbisGardenKind,
    val title: String,
    val body: String,
    val author: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 1,
    /** Chosen calendar day. Absent legacy records keep deriving their day from createdAt. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val entryDate: String? = null,
)

@Serializable
data class OrbisGardenBackup(val format: String = "orbis-local-garden/1", val entries: List<OrbisGardenEntry>)

internal const val GARDEN_BODY_BYTES = 32 * 1024
internal const val GARDEN_BACKUP_BYTES = 8 * 1024 * 1024
internal const val GARDEN_BACKUP_RECORDS = 5000
internal val gardenJson = Json { encodeDefaults = true }

internal fun validateGardenEntry(entry: OrbisGardenEntry) {
    require(runCatching { UUID.fromString(entry.id).toString() == entry.id }.getOrDefault(false)) { "garden_invalid_entry" }
    require(entry.title.isNotBlank() && entry.title.length <= 120 && entry.title.none(Char::isISOControl)) { "garden_invalid_entry" }
    require(entry.body.isNotBlank() && entry.body.toByteArray(Charsets.UTF_8).size <= GARDEN_BODY_BYTES && '\u0000' !in entry.body) { "garden_invalid_entry" }
    require(listOf(entry.title, entry.body).all { Charsets.UTF_8.newEncoder().canEncode(it) }) { "garden_invalid_entry" }
    require(validOrbisGardenName(entry.author)) { "garden_invalid_entry" }
    require(entry.createdAt >= 0 && entry.updatedAt >= entry.createdAt && entry.revision in 1 until Int.MAX_VALUE) { "garden_invalid_entry" }
    entry.entryDate?.let(::parseGardenEntryDate)
}

private fun OrbisGardenEntry.requiresBackupV2() = entryDate != null || kind == OrbisGardenKind.SONG

internal fun encodeGardenBackup(entries: List<OrbisGardenEntry>): ByteArray {
    require(entries.size <= GARDEN_BACKUP_RECORDS) { "garden_backup_too_large" }
    entries.forEach(::validateGardenEntry)
    require(entries.map { it.id }.distinct().size == entries.size) { "garden_backup_duplicate" }
    val format = if (entries.any { it.requiresBackupV2() }) "orbis-local-garden/2" else "orbis-local-garden/1"
    return gardenJson.encodeToString(OrbisGardenBackup(format = format, entries = entries)).toByteArray(Charsets.UTF_8).also {
        require(it.size <= GARDEN_BACKUP_BYTES) { "garden_backup_too_large" }
    }
}

internal fun decodeGardenBackup(bytes: ByteArray): List<OrbisGardenEntry> {
    require(bytes.size <= GARDEN_BACKUP_BYTES) { "garden_backup_too_large" }
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    val root = gardenJson.parseToJsonElement(text) as? JsonObject ?: error("garden_backup_format")
    require(root.keys == setOf("format", "entries")) { "garden_backup_format" }
    val backup = gardenJson.decodeFromString<OrbisGardenBackup>(text)
    require(backup.format in setOf("orbis-local-garden/1", "orbis-local-garden/2")) { "garden_backup_format" }
    require(backup.format != "orbis-local-garden/1" || backup.entries.none { it.requiresBackupV2() }) { "garden_backup_format" }
    require(backup.entries.size <= GARDEN_BACKUP_RECORDS) { "garden_backup_too_large" }
    backup.entries.forEach(::validateGardenEntry)
    require(backup.entries.map { it.id }.distinct().size == backup.entries.size) { "garden_backup_duplicate" }
    return backup.entries
}

/** Compare the decoded snapshot, but use the exact stored JSON for the database CAS predicate. */
internal fun gardenExpectedPayload(original: OrbisGardenEntry, storedPayload: String?): String {
    check(storedPayload != null) { "garden_revision_changed" }
    val stored = gardenJson.decodeFromString<OrbisGardenEntry>(storedPayload).also(::validateGardenEntry)
    check(stored == original) { "garden_revision_changed" }
    return storedPayload
}

/** Identical IDs are skipped, conflicting IDs reject the whole merge; never overwrite a newer note. */
internal fun gardenMergeAdditions(existing: Map<String, OrbisGardenEntry>, incoming: List<OrbisGardenEntry>): List<OrbisGardenEntry> {
    incoming.forEach { entry ->
        validateGardenEntry(entry)
        require(existing[entry.id] == null || existing[entry.id] == entry) { "garden_import_conflict" }
    }
    require(incoming.map { it.id }.distinct().size == incoming.size) { "garden_backup_duplicate" }
    return incoming.filter { it.id !in existing }
}
