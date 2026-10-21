package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KelivoChatArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val db = "SQLite format 3\u0000synthetic-not-a-real-database".toByteArray()
    private fun manifest(checksum: String = MessageDigest.getInstance("SHA-256").digest(db)
        .joinToString("") { "%02x".format(it.toInt() and 255) }, version: Int = 2, schema: Int = 3,
        minimumVersion: Int = version, scope: JsonElement? = if (version == 3)
            buildJsonObject { put("chats", true); put("files", false); put("settings", false) } else null): String =
        buildJsonObject {
            put("format", "kelivo-backup"); put("formatVersion", version); put("minimumReadableFormatVersion", minimumVersion)
            put("payloadKind", "sqlite"); put("includeChats", true); put("secretsIncluded", true)
            scope?.let { put("scope", it) }
            put("database", buildJsonObject { put("entry", "database/kelivo.db"); put("schemaVersion", schema)
                put("minimumReadableSchemaVersion", 2); put("conversationCount", 1); put("messageCount", 2) })
            put("entries", buildJsonObject { put("database/kelivo.db", buildJsonObject {
                put("bytes", db.size); put("sha256", checksum)
            }) })
        }.toString()
    private fun zip(extra: List<Pair<String, ByteArray>> = emptyList(), text: String = manifest()): File =
        temporary.newFile().also { file -> ZipOutputStream(file.outputStream()).use { out ->
            (listOf("manifest.json" to text.toByteArray(), "database/kelivo.db" to db) + extra).forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            }
        } }

    @Test fun extractsOnlyFixedDatabaseNotSettingsFilesSkillsOrMedia() {
        val file = zip(listOf("settings.json" to "NOT VALID JSON must never parse".toByteArray(),
            "skills/run.sh" to "must not run".toByteArray(), "upload/test.jpg" to byteArrayOf(1)))
        val before = KelivoChatArchive.fingerprint(file)
        val destination = temporary.newFolder()
        val extracted = KelivoChatArchive.extract(file, destination)
        assertEquals(1, extracted.conversationCount); assertEquals(2, extracted.messageCount)
        assertArrayEquals(db, extracted.file.readBytes())
        assertEquals(listOf("kelivo-chat-snapshot.db"), destination.listFiles()!!.map { it.name })
        assertEquals(before, KelivoChatArchive.fingerprint(file))
    }
    @Test fun rejectsUnsupportedManifestAndSchemaVersions() {
        listOf(manifest(version = 1), manifest(version = 4), manifest(version = 4, minimumVersion = 3),
            manifest(minimumVersion = 3), manifest(version = 3, minimumVersion = 4), manifest(minimumVersion = 0),
            manifest(schema = 4), manifest(version = 3, schema = 4),
            manifest().replace("kelivo-backup", "orbis-backup")).forEach {
            assertThrows(IllegalArgumentException::class.java) { KelivoChatArchive.extract(zip(text = it), temporary.newFolder()) }
        }
    }
    @Test fun acceptsV3ScopedChatArchiveWithoutReadingOtherCategoriesOrChangingSource() {
        val file = zip(text = manifest(version = 3), extra = listOf(
            "settings.json" to "INVALID_SETTINGS must not parse".toByteArray(),
            "skills/example/SKILL.md" to "must not execute".toByteArray()))
        val before = KelivoChatArchive.fingerprint(file)
        val destination = temporary.newFolder()
        val snapshot = KelivoChatArchive.extract(file, destination)
        assertEquals(1, snapshot.conversationCount)
        assertEquals(2, snapshot.messageCount)
        assertArrayEquals(db, snapshot.file.readBytes())
        assertEquals(listOf("kelivo-chat-snapshot.db"), destination.listFiles()!!.map { it.name })
        assertEquals(before, KelivoChatArchive.fingerprint(file))
    }
    @Test fun v2AndV3ContainerCompatibilityIsIndependentOfSqliteSchemaVersion() {
        for (version in 2..3) {
            for (minimum in 1..version) {
                val snapshot = KelivoChatArchive.extract(zip(text = manifest(version = version,
                    schema = 3, minimumVersion = minimum)), temporary.newFolder())
                assertArrayEquals(db, snapshot.file.readBytes())
            }
        }
    }
    @Test fun rejectsMissingDisabledOrMalformedV3ChatScopeBeforeCreatingOutput() {
        val scopes = listOf(null, JsonPrimitive(true), buildJsonObject { put("files", true) },
            buildJsonObject { put("chats", false) }, buildJsonObject { put("chats", "true") },
            buildJsonObject { put("chats", true); put("settings", "false") })
        for (scope in scopes) {
            val destination = temporary.newFolder()
            assertThrows(Exception::class.java) {
                KelivoChatArchive.extract(zip(text = manifest(version = 3, scope = scope)), destination)
            }
            assertTrue(destination.listFiles()!!.isEmpty())
        }
        val destination = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            KelivoChatArchive.extract(zip(text = manifest(version = 3).replace("\"includeChats\":true",
                "\"includeChats\":\"true\"")), destination)
        }
        assertTrue(destination.listFiles()!!.isEmpty())
    }
    @Test fun rejectsDatabaseChecksumMismatchAndDeletesOnlyNewOutput() {
        val destination = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            KelivoChatArchive.extract(zip(text = manifest("0".repeat(64))), destination)
        }
        assertTrue(destination.listFiles()!!.isEmpty())
    }
    @Test fun rejectsTraversalAbsoluteBackslashControlAndAmbiguousPaths() {
        listOf("../escape", "/absolute", "C:/escape", "a\\b", "a//b", "a/./b", "a/../b", "a. /b", "a./b", "a /b", "a\u0001b").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                KelivoChatArchive.extract(zip(listOf(it to byteArrayOf(1))), temporary.newFolder())
            }
        }
    }
    @Test fun rejectsCaseAndUnicodeCollisionAndFileAsDirectory() {
        listOf(listOf("MANIFEST.JSON" to byteArrayOf(1)),
            listOf("é" to byteArrayOf(1), "e\u0301" to byteArrayOf(1)),
            listOf("folder" to byteArrayOf(1), "folder/file" to byteArrayOf(1))).forEach {
            assertThrows(IllegalArgumentException::class.java) { KelivoChatArchive.extract(zip(it), temporary.newFolder()) }
        }
    }
    @Test fun rejectsWalInsteadOfExecutingOrIgnoringPendingSourceWrites() {
        listOf("database/kelivo.db-wal", "database/kelivo.db-journal", "database/kelivo.db-shm").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                KelivoChatArchive.extract(zip(listOf(it to byteArrayOf(1))), temporary.newFolder())
            }
        }
    }
    @Test fun rejectsHighCompressionEvenForIgnoredSecretEntryWithoutExtractingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            KelivoChatArchive.extract(zip(listOf("settings.json" to ByteArray(4 * 1024 * 1024))), temporary.newFolder())
        }
    }
    @Test fun rejectsNonemptyStagingAndPreservesExistingFile() {
        val destination = temporary.newFolder()
        val keep = File(destination, "existing").apply { writeText("kept") }
        assertThrows(IllegalArgumentException::class.java) { KelivoChatArchive.extract(zip(), destination) }
        assertEquals("kept", keep.readText())
    }
    @Test fun reservesSpaceAndRemovesPartialOutputOnCancellation() {
        val destination = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            KelivoChatArchive.extract(zip(), destination, usableSpace = { 0 })
        }
        assertTrue(destination.listFiles()!!.isEmpty())
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            KelivoChatArchive.extract(zip(), destination, checkCancelled = {
                if (++checks > 4) throw kotlinx.coroutines.CancellationException()
            })
        }
        assertTrue(destination.listFiles()!!.isEmpty())
    }
}
