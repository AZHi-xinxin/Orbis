package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class PolarisChatImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val t = 1_790_867_591_638L
    private val first = """{"id":"one","title":"synthetic","kind":"direct","updatedAt":$t,"messages":[
        {"id":"u","role":"user","content":"hello","timestamp":${t-2000}},
        {"id":"s","role":"system","content":"DO NOT IMPORT SYSTEM PROMPT","timestamp":${t-1000}},
        {"id":"a","role":"assistant","content":"reply","thinkingText":"reasoning",
         "attachments":[{"kind":"image","assetId":"secret-file"}],"timestamp":$t}],
        "toolLedger":[{"doNotExecute":"historical tool"}],"task":{"doNotImport":"runtime"}}""".trimIndent()
    private val second = """{"id":"two","title":"second","kind":"direct","updatedAt":$t,
        "messages":[{"id":"x","role":"user","content":"second message","timestamp":$t}]}""".trimIndent()
    private fun chat(conversations: String = "$first,$second") =
        """{"conversations":[$conversations],"activeConversationId":"one","activeGroupRoomId":null,
            "groupRooms":[],"loadedConversationIds":["one","two"],"deletedConversationIds":["old"]}""".trimIndent()
    private val manifest = """{"format":"polaris-export","version":1,
        "stores":{"chat":"stores/chat.json","runtime":"stores/runtime.json"},"assets":{}}""".trimIndent()
    private fun archive(chatJson: String = chat(), manifestJson: String = manifest,
        extra: List<Pair<String, ByteArray>> = emptyList()): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            (listOf("manifest.json" to manifestJson.toByteArray(),
                "stores/chat.json" to chatJson.toByteArray(),
                "stores/runtime.json" to "THIS IS NOT JSON AND MUST NEVER BE READ".toByteArray(),
                "assets/private.png" to byteArrayOf(1, 2, 3)) + extra).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            if (conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }

    @Test fun importsOnlyHistoricalUserAssistantTextAndReasoningInOriginalOrder() = runBlocking {
        val file = archive()
        val original = PolarisChatArchive.fingerprint(file)
        val sink = Sink()
        val importer = PolarisChatImporter(sink)
        val preview = importer.inspect(file)
        assertEquals(original, preview.fingerprint)
        assertEquals(2, preview.conversations.size)
        assertEquals("selected", preview.conversations.first().defaultLeafId)
        assertEquals("polaris_original_order", preview.conversations.first().defaultSelectionReason)
        assertEquals(3, preview.conversations.first().totalNodes)
        assertEquals(2, preview.conversations.first().messageCount)
        assertTrue(preview.warnings.any { it.contains("系统消息") })
        val result = importer.importSelected(file, assistant, setOf("one"), original)
        assertEquals(1, result.imported)
        assertEquals(2, result.messages)
        assertEquals(1, result.attachmentReferences)
        val saved = sink.saved.values.single()
        assertEquals(assistant, saved.assistantId)
        assertNull(saved.customSystemPrompt)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), saved.currentMessages.map { it.role })
        assertEquals("hello", saved.currentMessages[0].toText())
        assertTrue(saved.currentMessages[1].toText().contains("reply"))
        assertTrue(saved.currentMessages[1].toText().contains("[北极星历史附件：图片 1；未导入文件实体，也不会自动下载]"))
        assertTrue(saved.currentMessages[1].parts.any { it is UIMessagePart.Reasoning &&
            it.reasoning == "reasoning" })
        assertFalse(saved.currentMessages.any { it.toText().contains("DO NOT IMPORT") || it.toText().contains("historical tool") })
        assertTrue(saved.currentMessages.all { it.getTools().isEmpty() })
        assertEquals(1, importer.importSelected(file, Uuid.random(), setOf("one"), original).skipped)
        assertEquals(original, PolarisChatArchive.fingerprint(file))
    }

    @Test fun validatesEntireArchiveBeforeAnyWindowCommit() = runBlocking {
        val malformed = second.replace("\"timestamp\":$t", "\"timestamp\":\"bad\"")
        val file = archive(chat(first + "," + malformed))
        val sink = Sink()
        try {
            PolarisChatImporter(sink).importSelected(file, assistant, setOf("one"),
                PolarisChatArchive.fingerprint(file))
            fail("preflight must reject second window")
        } catch (failure: DeepSeekImportException) {
            assertEquals(0, failure.partialResult.imported)
            assertTrue(sink.saved.isEmpty())
        }
    }

    @Test fun rejectsUnsafeZipPathsAndForeignFormatWithoutReadingIgnoredStores() {
        assertThrows(Exception::class.java) { PolarisChatArchive.open(archive(extra = listOf("../bad" to byteArrayOf(1)))).close() }
        assertThrows(Exception::class.java) { PolarisChatArchive.open(archive(manifestJson = manifest.replace("polaris-export", "other"))).close() }
    }

    @Test fun skipsUnsupportedGroupWindowsWithExplicitPreviewWarning() = runBlocking {
        val group = """{"id":"group","title":"synthetic group","kind":"group","updatedAt":$t,
            "messages":[],"toolLedger":[{"doNotExecute":"tool"}]}""".trimIndent()
        val file = archive(chat("$first,$group"))
        val preview = PolarisChatImporter(Sink()).inspect(file)
        assertEquals(1, preview.conversations.size)
        assertTrue(preview.warnings.any { it.contains("1 个暂不支持的群组窗口") })
    }

    @Test fun stripsMediaMarkupWithoutLoadingAnyAsset() {
        val source = PolarisConversation("chat", "synthetic", t, listOf(PolarisMessage("m", "user",
            "![pic](file:///secret.png) [image:https://example.invalid/x]", t, null, listOf("image"))), 0)
        val converted = convertPolarisConversation(source, assistant)
        val text = converted.conversation.currentMessages.single().toText()
        assertFalse(text.contains("file://"))
        assertFalse(text.contains("https://"))
        assertTrue(text.contains("历史附件"))
        assertEquals(1, converted.attachmentReferences)
    }

    /** Opt-in aggregate probe; never prints real titles, IDs, messages, paths or credentials. */
    @Test fun optionalLocalArchiveCompletesFullPreviewAndCrcWithoutWrites() = runBlocking {
        val path = System.getenv("ORBIS_POLARIS_PROBE_FILE")
        assumeTrue("Local probe disabled", !path.isNullOrBlank())
        val file = File(path!!)
        val before = PolarisChatArchive.fingerprint(file)
        val sink = Sink()
        val preview = PolarisChatImporter(sink).inspect(file)
        val expectedConversations = System.getenv("ORBIS_POLARIS_PROBE_CONVERSATIONS")?.toIntOrNull()
        val expectedMessages = System.getenv("ORBIS_POLARIS_PROBE_MESSAGES")?.toIntOrNull()
        expectedConversations?.let { assertEquals(it, preview.conversations.size) }
        expectedMessages?.let { assertEquals(it, preview.conversations.sumOf { entry -> entry.messageCount }) }
        assertEquals(0, sink.saved.size)
        assertEquals(before, PolarisChatArchive.fingerprint(file))
        println("ORBIS_POLARIS_LOCAL_PROBE accepted=true conversations=${preview.conversations.size} " +
            "messages=${preview.conversations.sumOf { it.messageCount }} crc_verified=true source_unchanged=true")
    }
}
