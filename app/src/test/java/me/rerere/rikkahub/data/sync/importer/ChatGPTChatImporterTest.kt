package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class ChatGPTChatImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var refuse = false
        var failAt = Int.MAX_VALUE
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            if (saved.size == failAt) error("synthetic atomic insert failure")
            if (refuse || conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }
    private fun selections(file: File) = ChatGPTChatArchive.inspect(file).conversations.associate { it.sourceId to it.defaultLeafId }
    private suspend fun import(file: File, sink: Sink, selected: Map<String, String> = selections(file),
        progress: (DeepSeekImportProgress) -> Unit = {}) = ChatGPTChatImporter(sink).import(file, assistant,
        selected, ChatGPTChatImporter.archiveFingerprint(file), progress)

    @Test fun allBranchesCreateSeparateAdditiveWindowsWithExactTextRolesAndTime() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch())
        val original = file.readBytes()
        val sink = Sink()
        val result = import(file, sink)
        assertEquals(2, result.imported); assertEquals(4, result.messages)
        assertEquals(setOf("A", "B"), sink.saved.values.map { (it.currentMessages.last().parts.single() as UIMessagePart.Text).text }.toSet())
        sink.saved.values.forEach { conversation ->
            assertEquals(assistant, conversation.assistantId)
            assertNull(conversation.customSystemPrompt); assertNull(conversation.workspaceCwd)
            assertTrue(conversation.modeInjectionIds.isEmpty() && conversation.lorebookIds.isEmpty())
            assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), conversation.currentMessages.map { it.role })
            assertEquals("  synthetic 正文🙂\r\n  ", (conversation.currentMessages.first().parts.single() as UIMessagePart.Text).text)
            assertEquals(java.time.Instant.parse("2023-11-14T22:13:20.125Z"), conversation.createAt)
            assertTrue(conversation.currentMessages.all { it.getTools().isEmpty() && it.orbisEvent == null && it.modelId == null })
        }
        assertArrayEquals(original, file.readBytes())
        assertFalse(file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("orbis-chatgpt-") })
    }

    @Test fun selectionAndReimportSkipWithoutOverwritingEvenWithAnotherTarget() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch())
        val selected = selections(file).entries.first().let { mapOf(it.toPair()) }
        val sink = Sink()
        assertEquals(1, import(file, sink, selected).imported)
        val old = sink.saved.values.single()
        val next = ChatGPTChatImporter(sink).import(file, Uuid.random(), selected, ChatGPTChatImporter.archiveFingerprint(file))
        assertEquals(1, next.skipped); assertEquals(0, next.imported); assertSame(old, sink.saved.values.single())
    }

    @Test fun changingOnlyActiveBranchAndImportingSeparateJsonShardsRemainIdempotent() = runBlocking {
        val first = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch())
        val changedCurrent = ChatGPTSynthetic.file(temporary, JsonObject(ChatGPTSynthetic.branch() + ("current_node" to JsonPrimitive("b"))))
        val secondShard = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat("other"))
        val sink = Sink()
        assertEquals(2, import(first, sink).imported)
        assertEquals(2, import(changedCurrent, sink).skipped)
        assertEquals(1, import(secondShard, sink).imported)
        assertEquals(1, import(secondShard, sink).skipped)
        assertEquals(3, sink.saved.size)
    }

    @Test fun editedContentCreatesNewVersionAndKeepsPreviousWindow() = runBlocking {
        fun file(text: String) = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat(nodes = listOf(
            ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("a", content = ChatGPTSynthetic.content(text))))))
        val sink = Sink()
        assertEquals(1, import(file("old"), sink).imported)
        val old = sink.saved.values.single()
        assertEquals(1, import(file("new"), sink).imported)
        assertEquals(2, sink.saved.size); assertSame(old, sink.saved.getValue(old.id))
        assertEquals(1, import(file("new"), sink).skipped)
    }

    @Test fun systemConfigAndMediaNeverBecomeInstructionsLiveToolsOrDownloadParts() = runBlocking {
        val raw = ChatGPTSynthetic.chat(nodes = listOf(
            ChatGPTSynthetic.node("s", message = ChatGPTSynthetic.message("s", "system", ChatGPTSynthetic.content("SYNTHETIC-SYSTEM-NOT-IMPORTED"))),
            ChatGPTSynthetic.node("a", "s", ChatGPTSynthetic.message("a", "tool", buildJsonObject {
                put("content_type", "multimodal_text"); put("parts", JsonArray(listOf(JsonPrimitive("![remote](https://invalid.example/media)"),
                    buildJsonObject { put("content_type", "image_asset_pointer"); put("asset_pointer", "file://SYNTHETIC-PATH") })))
            }))
        ), extra = mapOf("account" to JsonPrimitive("SYNTHETIC-ACCOUNT-NOT-IMPORTED")))
        val file = ChatGPTSynthetic.file(temporary, raw)
        val sink = Sink()
        val result = import(file, sink)
        assertEquals(1, result.attachmentReferences)
        val message = sink.saved.values.single().currentMessages.single()
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertTrue(message.parts.all { it is UIMessagePart.Text })
        val part = message.parts.single() as UIMessagePart.Text
        assertEquals("chatgpt_export_v1", (part.metadata!!.getValue("import_source") as JsonPrimitive).content)
        assertTrue(part.text.contains("历史工具记录"))
        assertFalse(part.text.contains("SYNTHETIC-SYSTEM") || part.text.contains("SYNTHETIC-ACCOUNT") || part.text.contains("SYNTHETIC-PATH"))
    }

    @Test fun unknownSelectionOrChangedFingerprintRejectBeforeAnyWrite() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat())
        val sink = Sink()
        for ((selected, fingerprint) in listOf(mapOf("unknown" to ChatGPTChatArchive.SELECTED_PATH) to ChatGPTChatImporter.archiveFingerprint(file),
            selections(file) to "0".repeat(64), selections(file).mapValues { "wrong" } to ChatGPTChatImporter.archiveFingerprint(file))) {
            try { ChatGPTChatImporter(sink).import(file, assistant, selected, fingerprint); fail("reject") }
            catch (_: DeepSeekImportException) { }
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun invalidLaterConversationPreventsAllEarlierSelectedWrites() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat("good"), ChatGPTSynthetic.chat("bad", current = "absent"))
        val sink = Sink()
        try { ChatGPTChatImporter(sink).import(file, assistant,
            mapOf(ChatGPTChatArchive.selectionKey("good", "a") to ChatGPTChatArchive.SELECTED_PATH), ChatGPTChatImporter.archiveFingerprint(file)); fail("reject") }
        catch (_: DeepSeekImportException) { }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun conversionSizePreflightStopsBeforeAnyWindowAndKeepsFullSource() = runBlocking {
        val oversized = ChatGPTSynthetic.chat("large", nodes = listOf(ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("a",
            content = ChatGPTSynthetic.content("x".repeat(800 * 1024))))))
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat("small"), oversized)
        val sink = Sink()
        try { import(file, sink); fail("reject") } catch (failure: DeepSeekImportException) {
            assertEquals(ArchiveFailure.NODE_LIMIT, failure.failureReason); assertEquals(0, failure.partialResult.imported)
        }
        assertTrue(sink.saved.isEmpty())
        assertTrue(file.readText().contains("x".repeat(800 * 1024)))
        assertEquals(1, import(file, sink, selections(file).filterKeys { it == ChatGPTChatArchive.selectionKey("small", "a") }).imported)
    }

    @Test fun sourceMutationAfterValidationCannotChangePrivateSnapshot() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat())
        val sink = Sink()
        assertEquals(1, import(file, sink) { if (it.completed == 0) file.writeText("{}") }.imported)
        assertEquals(2, sink.saved.values.single().currentMessages.size)
    }

    @Test fun cancellationHasAccurateCommittedCountNoHalfWindowAndRetrySkipsCompleted() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch())
        for (afterCommit in listOf(false, true)) {
            val sink = Sink()
            try { import(file, sink) { if (!afterCommit || it.completed == 1) throw CancellationException() }; fail("cancel") }
            catch (cancelled: DeepSeekImportCancelledException) {
                assertEquals(if (afterCommit) 1 else 0, cancelled.partialResult.imported)
                assertEquals(cancelled.partialResult.imported, sink.saved.size)
            }
            val retried = import(file, sink)
            assertEquals(if (afterCommit) 1 else 0, retried.skipped)
            assertEquals(2, sink.saved.size)
        }
    }

    @Test fun sinkFailureRetainsOnlyCompletedPathsAndNeverOverwritesThem() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch())
        val sink = Sink().apply { failAt = 1 }
        try { import(file, sink); fail("atomic insert fails") } catch (failure: DeepSeekImportException) {
            assertEquals(1, failure.partialResult.imported)
        }
        assertEquals(1, sink.saved.size)
        val old = sink.saved.values.single()
        sink.failAt = Int.MAX_VALUE
        val retry = import(file, sink)
        assertEquals(1, retry.skipped); assertEquals(1, retry.imported); assertSame(old, sink.saved.getValue(old.id))
    }

    @Test fun refusedAtomicInsertIsNotCountedAsImportedAndIdsAreNamespaced() = runBlocking {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat())
        val result = import(file, Sink().apply { refuse = true })
        assertEquals(0, result.imported); assertEquals(1, result.skipped)
        assertNotEquals(chatGptImportId("conversation", "a", "b"), claudeImportId("conversation", "a", "b"))
        assertNotEquals(chatGptImportId("message", "ab", "c"), chatGptImportId("message", "a", "bc"))
    }
}
