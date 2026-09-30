package me.rerere.rikkahub.data.orbis

import java.util.UUID
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.model.validOrbisGardenName
import org.junit.Assert.*
import org.junit.Test

class OrbisGardenModelsTest {
    private fun entry() = OrbisGardenEntry(UUID.randomUUID().toString(), OrbisGardenKind.DIARY,
        "自己的标题", "第一行\n引用：https://example.org/book\n<script>not executable</script>", "我", 1, 2)
    private fun rejected(block: () -> Unit) { try { block(); fail("must reject") } catch (_: Exception) { } }

    @Test fun backupPreservesPlainTextAndReferencesWithoutCredentialsOrChatConfiguration() {
        val entries = OrbisGardenKind.entries.map { entry().copy(kind = it) }
        val bytes = encodeGardenBackup(entries)
        assertEquals(entries, decodeGardenBackup(bytes))
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("apiKey")); assertFalse(text.contains("assistants")); assertFalse(text.contains("workspace"))
    }
    @Test fun defaultsAreNeutralAndPreviouslyEnabledWebsitesStayEnabled() {
        val defaults = OrbisCloudHomeConfig()
        assertFalse(defaults.enabled); assertEquals("我", defaults.humanName); assertEquals("伙伴", defaults.companionName)
        val old = gardenJson.decodeFromString<OrbisCloudHomeConfig>("""{"enabled":true,"homeUrl":"https://example.org/"}""")
        assertTrue(old.enabled); assertEquals("https://example.org/", old.homeUrl)
    }
    @Test fun invalidNamesAreNotSilentlyTruncated() {
        listOf("", " ", "x".repeat(33), "line\nbreak", "a\u0000b", "\uD800").forEach { assertFalse(validOrbisGardenName(it)) }
        assertTrue(validOrbisGardenName("自己选择的名字"))
    }
    @Test fun byteBoundUsesUtf8RatherThanCharacterCount() {
        validateGardenEntry(entry().copy(body = "a".repeat(GARDEN_BODY_BYTES)))
        rejected { validateGardenEntry(entry().copy(body = "中".repeat(GARDEN_BODY_BYTES / 3 + 1))) }
        rejected { validateGardenEntry(entry().copy(body = "a\u0000b")) }
        rejected { validateGardenEntry(entry().copy(body = "broken\uD800")) }
    }
    @Test fun malformedRecordsVersionsAndIdsFailClosed() {
        listOf(entry().copy(id = "../../private"), entry().copy(title = ""), entry().copy(author = ""),
            entry().copy(createdAt = 3, updatedAt = 1), entry().copy(revision = 0), entry().copy(title = "x".repeat(121)))
            .forEach { rejected { validateGardenEntry(it) } }
    }
    @Test fun invalidBackupFormatUtf8AndOversizedBytesAreRejected() {
        rejected { decodeGardenBackup(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejected { decodeGardenBackup(ByteArray(GARDEN_BACKUP_BYTES + 1)) }
        rejected { decodeGardenBackup("""{"format":"other/1","entries":[]}""".toByteArray()) }
        rejected { decodeGardenBackup("""{"entries":[]}""".toByteArray()) }
        rejected { decodeGardenBackup("""{"format":"orbis-local-garden/1","entries":[],"token":"synthetic"}""".toByteArray()) }
    }
    @Test fun duplicateIdsAndTooManyRecordsAreRejected() {
        val entry = entry()
        rejected { encodeGardenBackup(listOf(entry, entry)) }
        rejected { decodeGardenBackup(gardenJson.encodeToString(OrbisGardenBackup(entries = listOf(entry, entry))).toByteArray()) }
        rejected { encodeGardenBackup(List(GARDEN_BACKUP_RECORDS + 1) { entry() }) }
    }
    @Test fun importIsAdditiveIdenticalIsIdempotentAndConflictRejectsAll() {
        val old = entry(); val added = entry()
        assertEquals(listOf(added), gardenMergeAdditions(mapOf(old.id to old), listOf(old, added)))
        assertTrue(gardenMergeAdditions(mapOf(old.id to old), listOf(old)).isEmpty())
        rejected { gardenMergeAdditions(mapOf(old.id to old), listOf(added, old.copy(body = "different"))) }
        assertEquals("自己的标题", old.title)
    }

    @Test fun legacyRecordRoundTripsWithoutAddingEntryDate() {
        val payload = """{"id":"00000000-0000-0000-0000-000000000001","kind":"DIARY","title":"legacy","body":"plain text","author":"me","createdAt":1,"updatedAt":2,"revision":1}"""
        val original = gardenJson.decodeFromString<OrbisGardenEntry>(payload)

        validateGardenEntry(original)
        assertNull(original.entryDate)
        assertEquals(OrbisGardenKind.DIARY, original.kind)
        assertEquals(payload, gardenJson.encodeToString(original))
        assertEquals(payload, gardenExpectedPayload(original, payload))
    }

    @Test fun legacyCasPreservesWhitespaceFieldOrderAndMissingRevision() {
        val payload = """
            {
              "body": "plain text",
              "id": "00000000-0000-0000-0000-000000000001",
              "kind": "DIARY", "title": "legacy", "author": "me",
              "updatedAt": 2, "createdAt": 1
            }
        """.trimIndent() + "\n "
        val original = gardenJson.decodeFromString<OrbisGardenEntry>(payload)

        assertEquals(1, original.revision)
        assertNull(original.entryDate)
        assertNotEquals(payload, gardenJson.encodeToString(original))
        assertEquals(payload, gardenExpectedPayload(original, payload))
    }

    @Test fun casRejectsMissingOrChangedSnapshotsIncludingDateAndKind() {
        val original = entry()
        val changedSnapshots = listOf(
            original.copy(body = "changed with the same revision"),
            original.copy(revision = original.revision + 1),
            original.copy(entryDate = "2024-02-29"),
            original.copy(kind = OrbisGardenKind.SONG),
        )
        val payloads: List<String?> = listOf(null) + changedSnapshots.map { gardenJson.encodeToString(it) }

        payloads.forEach { payload ->
            try {
                gardenExpectedPayload(original, payload)
                fail("A missing or changed snapshot must reject the write")
            } catch (error: IllegalStateException) {
                assertEquals("garden_revision_changed", error.message)
            }
        }
    }

    @Test fun explicitDatesRequireRealDaysAndExactlyFourDigitYears() {
        val original = entry()
        listOf("0001-01-01", "2024-02-29", "9999-12-31").forEach { date ->
            validateGardenEntry(original.copy(entryDate = date))
        }
        listOf(
            "", "2025-02-29", "2024-02-30", "2024-13-01", "2024-00-01", "2024-01-00",
            "2024-2-09", "2024-02-9", "999-01-01", "10000-01-01", "+10000-01-01",
            "0000-01-01", "-0001-01-01", " 2024-02-29", "2024-02-29 ", "2024-02-29T00:00:00Z",
        ).forEach { date ->
            rejected { validateGardenEntry(original.copy(entryDate = date)) }
        }
    }

    @Test fun legacyOnlyBackupsKeepVersionOneAndOriginalBytes() {
        val payload = """{"format":"orbis-local-garden/1","entries":[{"id":"00000000-0000-0000-0000-000000000001","kind":"DIARY","title":"legacy","body":"plain text","author":"me","createdAt":1,"updatedAt":2,"revision":1}]}"""
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val decoded = decodeGardenBackup(bytes)

        assertNull(decoded.single().entryDate)
        assertArrayEquals(bytes, encodeGardenBackup(decoded))
        val emptyBackup = gardenJson.decodeFromString<OrbisGardenBackup>(
            encodeGardenBackup(emptyList()).toString(Charsets.UTF_8),
        )
        assertEquals("orbis-local-garden/1", emptyBackup.format)
    }

    @Test fun datedEntriesSongsAndMixedBackupsUseVersionTwoAndRoundTrip() {
        val legacy = entry()
        val dated = entry().copy(entryDate = "2024-02-29")
        val song = entry().copy(kind = OrbisGardenKind.SONG)
        val datedSong = entry().copy(kind = OrbisGardenKind.SONG, entryDate = "2026-09-28")

        listOf(listOf(dated), listOf(song), listOf(datedSong), listOf(legacy, dated, song, datedSong))
            .forEach { entries ->
                val bytes = encodeGardenBackup(entries)
                val backup = gardenJson.decodeFromString<OrbisGardenBackup>(bytes.toString(Charsets.UTF_8))
                assertEquals("orbis-local-garden/2", backup.format)
                assertEquals(entries, decodeGardenBackup(bytes))
                assertArrayEquals(bytes, encodeGardenBackup(decodeGardenBackup(bytes)))
            }
    }

    @Test fun versionTwoCanReadLegacyRecords() {
        val legacy = entry()
        val bytes = gardenJson.encodeToString(
            OrbisGardenBackup(format = "orbis-local-garden/2", entries = listOf(legacy)),
        ).toByteArray(Charsets.UTF_8)

        assertEquals(listOf(legacy), decodeGardenBackup(bytes))
    }

    @Test fun versionOneRejectsDatesAndSongsAndUnknownVersionsAreRejected() {
        val legacy = entry()
        listOf(
            legacy.copy(entryDate = "2024-02-29"),
            legacy.copy(kind = OrbisGardenKind.SONG),
            legacy.copy(kind = OrbisGardenKind.SONG, entryDate = "2024-02-29"),
        ).forEach { newEntry ->
            val bytes = gardenJson.encodeToString(
                OrbisGardenBackup(format = "orbis-local-garden/1", entries = listOf(newEntry)),
            ).toByteArray(Charsets.UTF_8)
            rejected { decodeGardenBackup(bytes) }
        }
        listOf("orbis-local-garden/0", "orbis-local-garden/3", "orbis-local-garden/02").forEach { format ->
            val bytes = gardenJson.encodeToString(
                OrbisGardenBackup(format = format, entries = listOf(legacy)),
            ).toByteArray(Charsets.UTF_8)
            rejected { decodeGardenBackup(bytes) }
        }
    }

    @Test fun invalidDatesAreRejectedAtBackupAndImportBoundaries() {
        val invalid = entry().copy(entryDate = "2025-02-29")
        val bytes = gardenJson.encodeToString(
            OrbisGardenBackup(format = "orbis-local-garden/2", entries = listOf(invalid)),
        ).toByteArray(Charsets.UTF_8)

        rejected { encodeGardenBackup(listOf(invalid)) }
        rejected { decodeGardenBackup(bytes) }
        rejected { gardenMergeAdditions(emptyMap(), listOf(invalid)) }
    }

    @Test fun importRejectsSameIdWithDifferentDateOrKindWithoutChangingExistingRecords() {
        val original = entry().copy(entryDate = "2024-02-29")
        val existing = mapOf(original.id to original)
        val added = entry().copy(kind = OrbisGardenKind.SONG)

        listOf(
            original.copy(entryDate = "2024-03-01"),
            original.copy(entryDate = null),
            original.copy(kind = OrbisGardenKind.SONG),
        ).forEach { collision ->
            try {
                gardenMergeAdditions(existing, listOf(added, collision))
                fail("Conflicting date or kind must reject the complete import")
            } catch (error: IllegalArgumentException) {
                assertEquals("garden_import_conflict", error.message)
            }
            assertEquals(mapOf(original.id to original), existing)
        }
    }

    @Test fun importedDatedSongsAreIdempotentAndDuplicateIdsStillReject() {
        val song = entry().copy(kind = OrbisGardenKind.SONG, entryDate = "2024-02-29")
        val entries = decodeGardenBackup(encodeGardenBackup(listOf(song)))

        assertEquals(entries, gardenMergeAdditions(emptyMap(), entries))
        assertTrue(gardenMergeAdditions(mapOf(song.id to song), entries).isEmpty())
        rejected { gardenMergeAdditions(emptyMap(), listOf(song, song.copy(entryDate = "2024-03-01"))) }
        rejected { encodeGardenBackup(listOf(song, song.copy(kind = OrbisGardenKind.DIARY))) }
        val duplicateBytes = gardenJson.encodeToString(
            OrbisGardenBackup(format = "orbis-local-garden/2", entries = listOf(song, song)),
        ).toByteArray(Charsets.UTF_8)
        rejected { decodeGardenBackup(duplicateBytes) }
    }
}
