package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekArchiveLayoutTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = """[{"id":"synthetic","title":"fixture","inserted_at":"2025-01-01T00:00:00Z","updated_at":"2025-01-01T00:00:00Z","mapping":{
        "root":{"id":"root","parent":null,"children":["u"],"message":null},
        "u":{"id":"u","parent":"root","children":["a"],"message":{"model":"fixture","inserted_at":"2025-01-01T00:00:00Z","fragments":[{"type":"REQUEST","content":"exact user"}]}},
        "a":{"id":"a","parent":"u","children":[],"message":{"model":"fixture","inserted_at":"2025-01-01T00:00:01Z","fragments":[{"type":"RESPONSE","content":"exact answer"}]}}
    }}]""".toByteArray()

    private fun zip(entries: Map<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }
    private fun wrapped() = linkedMapOf(
        "export/" to byteArrayOf(), "export/conversations.json" to json,
        // Deliberately invalid JSON and UTF-8: account metadata must never be parsed.
        "export/user.json" to byteArrayOf(0xFF.toByte()),
        "__MACOSX/._export" to byteArrayOf(1),
        "__MACOSX/export/._conversations.json" to byteArrayOf(1),
        "__MACOSX/export/._user.json" to byteArrayOf(1),
    )
    private fun rejects(entries: Map<String, ByteArray>) {
        try { DeepSeekArchive.inspect(zip(entries)); fail("accepted an ambiguous or unsafe synthetic layout") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsFinderRezipWithoutChangingPreviewOrSource() {
        val file = zip(wrapped()); val before = file.readBytes()
        val flat = DeepSeekArchive.inspect(zip(mapOf("conversations.json" to json)))
        assertEquals(flat, DeepSeekArchive.inspect(file))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun acceptsWrapperWithoutExplicitDirectoryEntriesAndRootFinderMetadata() {
        assertEquals(1, DeepSeekArchive.inspect(zip(mapOf("导出/conversations.json" to json))).conversations.size)
        assertEquals(1, DeepSeekArchive.inspect(zip(mapOf("conversations.json" to json,
            "__MACOSX/" to byteArrayOf(), "__MACOSX/._conversations.json" to byteArrayOf(1),
            ".DS_Store" to byteArrayOf(2)))).conversations.size)
    }

    @Test fun wrappedAndFlatImportsHaveSameIdentityAndNeverOverwrite() = runBlocking {
        val saved = linkedMapOf<Uuid, Conversation>()
        val importer = DeepSeekChatImporter(object : DeepSeekImportSink {
            override suspend fun exists(id: Uuid) = id in saved
            override suspend fun insert(conversation: Conversation): Boolean {
                check(conversation.id !in saved); saved[conversation.id] = conversation; return true
            }
        })
        val owner = Uuid.parse("11111111-1111-4111-8111-111111111111")
        assertEquals(1, importer.import(zip(wrapped()), owner, mapOf("synthetic" to "a")).imported)
        val before = saved.values.single()
        assertEquals(listOf("exact user", "exact answer"), before.currentMessages.map { it.toText() })
        assertTrue(before.currentMessages.all { it.getTools().isEmpty() })
        assertEquals(1, importer.import(zip(mapOf("conversations.json" to json)), owner,
            mapOf("synthetic" to "a")).skipped)
        assertEquals(before, saved.values.single())
    }

    @Test fun rejectsMultipleExportsAndMismatchedProfileRoots() {
        rejects(mapOf("conversations.json" to json, "export/conversations.json" to json))
        rejects(mapOf("first/conversations.json" to json, "second/conversations.json" to json))
        rejects(mapOf("export/conversations.json" to json, "user.json" to byteArrayOf()))
        rejects(mapOf("first/conversations.json" to json, "second/user.json" to byteArrayOf()))
    }

    @Test fun rejectsTraversalAbsolutePathsAndExtraNestedExports() {
        for (path in listOf("../conversations.json", "/conversations.json", "C:/conversations.json",
            "export\\conversations.json", "./conversations.json", "export//conversations.json",
            "a/b/conversations.json", "__MACOSX/conversations.json")) rejects(mapOf(path to json))
        rejects(wrapped() + ("__MACOSX/export/../._user.json" to byteArrayOf()))
    }

    @Test fun ignoresOnlyBoundedKnownMetadataNotArbitraryFiles() {
        for (path in listOf("notes.txt", "export/plugin.json", "__MACOSX/executable.json", "__MACOSX/export/._unknown"))
            rejects(wrapped() + (path to byteArrayOf(1)))
        rejects(wrapped() + ("__MACOSX/export/._user.json" to ByteArray(1024 * 1024 + 1)))
        rejects(wrapped() + ("empty-unrelated/" to byteArrayOf()))
        rejects(wrapped() + ("export/" to byteArrayOf(1)))
    }

    @Test fun rejectsTooManyEntriesBeforeReadingData() {
        val entries = wrapped()
        repeat(33) { entries["__MACOSX/extra$it"] = byteArrayOf() }
        rejects(entries)
    }

    @Test fun malformedConversationIsStillRejectedInWrapper() {
        rejects(wrapped() + ("export/conversations.json" to "[{\"mapping\":{},\"mapping\":{}}]".toByteArray()))
        rejects(wrapped() + ("export/conversations.json" to byteArrayOf(0xFF.toByte())))
    }
}
