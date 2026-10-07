package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class ClaudeChatImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var refuse = false
        var afterInsert: (() -> Unit)? = null
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            if (refuse || conversation.id in saved) return false
            saved[conversation.id] = conversation
            afterInsert?.invoke()
            return true
        }
    }
    private fun selections(file: File) = ClaudeChatArchive.inspect(file).conversations.associate { it.sourceId to it.defaultLeafId }
    private suspend fun import(file: File, sink: Sink, selected: Map<String, String> = selections(file),
        progress: (DeepSeekImportProgress) -> Unit = {}) = ClaudeChatImporter(sink).import(file, assistant, selected,
        ClaudeChatImporter.archiveFingerprint(file), progress)

    @Test fun eachCompletePathHasExactRolesOrderTimeAndCoverageWithoutToolsOrConfiguration() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.branch())
        val original = file.readBytes()
        val sink = Sink()
        val result = import(file, sink)
        assertEquals(3, result.imported); assertEquals(5, result.messages)
        val union = sink.saved.values.flatMap { it.currentMessages }.map { message ->
            val metadata = (message.parts.first() as UIMessagePart.Text).metadata!!
            (metadata.getValue("source_message_id") as JsonPrimitive).content
        }.toSet()
        assertEquals(setOf("u", "a", "b", "isolated"), union)
        sink.saved.values.forEach { conversation ->
            assertEquals(assistant, conversation.assistantId)
            assertNull(conversation.customSystemPrompt); assertNull(conversation.workspaceCwd)
            assertTrue(conversation.modeInjectionIds.isEmpty() && conversation.lorebookIds.isEmpty())
            assertTrue(conversation.currentMessages.all { it.getTools().isEmpty() && it.orbisEvent == null && it.modelId == null })
            assertTrue(conversation.currentMessages.all { message -> message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning } })
            assertEquals(MessageRole.USER, conversation.currentMessages.first().role)
            assertEquals(java.time.Instant.parse(ClaudeSynthetic.TIME), conversation.createAt)
        }
        assertArrayEquals(original, file.readBytes())
        assertFalse(file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("orbis-claude-") })
    }

    @Test fun explicitSelectionImportsOnlyThatPathAndReimportIsIdempotentAcrossTargets() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.branch())
        val selected = selections(file).entries.first().let { mapOf(it.toPair()) }
        val sink = Sink()
        assertEquals(1, import(file, sink, selected).imported)
        val saved = sink.saved.values.single()
        val second = ClaudeChatImporter(sink).import(file, Uuid.random(), selected, ClaudeChatImporter.archiveFingerprint(file))
        assertEquals(1, second.skipped); assertEquals(0, second.imported)
        assertSame(saved, sink.saved.values.single())
    }

    @Test fun changedSourceCreatesExplicitlyAdvertisedVersionWithoutOverwritingOldWindow() = runBlocking {
        val first = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = listOf(ClaudeSynthetic.message(text = "old"))))
        val second = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = listOf(ClaudeSynthetic.message(text = "new"))))
        val sink = Sink()
        assertEquals(1, import(first, sink).imported)
        val old = sink.saved.values.single()
        assertTrue(ClaudeChatArchive.inspect(second).warnings.any { "另建版本窗口" in it })
        assertEquals(1, import(second, sink).imported)
        assertEquals(2, sink.saved.size)
        assertSame(old, sink.saved.getValue(old.id))
        assertEquals(1, import(second, sink).skipped)
    }

    @Test fun reasoningToolsAndAttachmentReferencesCannotBecomeLiveActionsOrRemoteMedia() = runBlocking {
        val message = ClaudeSynthetic.message("a", null, "assistant", "mirror", listOf(
            buildJsonObject { put("type", "thinking"); put("thinking", "thought") },
            buildJsonObject { put("type", "tool_result"); put("name", "dangerous_tool"); put("content", "![remote](https://invalid.example/a)") }),
            extra = mapOf("files" to JsonArray(listOf(buildJsonObject { put("file_name", "../../private.txt"); put("file_uuid", "synthetic") }))))
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = listOf(message),
            extra = mapOf("account" to buildJsonObject { put("token", "SYNTHETIC_NEVER_IMPORT") })))
        val sink = Sink()
        val result = import(file, sink)
        assertEquals(1, result.attachmentReferences)
        val parts = sink.saved.values.single().currentMessages.single().parts
        assertTrue(parts.any { it is UIMessagePart.Reasoning })
        assertTrue(parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning })
        assertFalse(parts.joinToString().contains("SYNTHETIC_NEVER_IMPORT"))
        assertTrue(parts.all { part ->
            val metadata = when (part) { is UIMessagePart.Text -> part.metadata; is UIMessagePart.Reasoning -> part.metadata; else -> null }
            (metadata?.get("import_source") as? JsonPrimitive)?.content == "claude_export_v1"
        })
    }

    @Test fun unknownSelectionAndChangedFingerprintPreventEveryWrite() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat())
        val sink = Sink()
        for ((selected, fingerprint) in listOf(
            mapOf("unknown" to ClaudeChatArchive.SELECTED_PATH) to ClaudeChatImporter.archiveFingerprint(file),
            selections(file) to "0".repeat(64),
            selections(file).mapValues { "wrong-path" } to ClaudeChatImporter.archiveFingerprint(file),
        )) {
            try { ClaudeChatImporter(sink).import(file, assistant, selected, fingerprint); fail("must reject") }
            catch (_: DeepSeekImportException) { }
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun malformedLaterConversationPreventsEarlierSelectedPathFromBeingWritten() = runBlocking {
        val good = ClaudeSynthetic.chat("one")
        val bad = ClaudeSynthetic.chat("two", messages = listOf(ClaudeSynthetic.message(sender = "system")))
        val file = ClaudeSynthetic.file(temporary, good, bad)
        val sink = Sink()
        try {
            ClaudeChatImporter(sink).import(file, assistant,
                mapOf(ClaudeChatArchive.selectionKey("one", "a") to ClaudeChatArchive.SELECTED_PATH), ClaudeChatImporter.archiveFingerprint(file))
            fail("must reject")
        } catch (_: DeepSeekImportException) { }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun sourceMutationAfterValidationCannotChangeFrozenBytes() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat())
        val sink = Sink()
        val result = import(file, sink) { progress -> if (progress.completed == 0) file.writeText("{}") }
        assertEquals(1, result.imported)
        assertEquals(2, sink.saved.values.single().currentMessages.size)
    }

    @Test fun oversizedLaterMessageRejectsEverySelectedPathBeforeFirstWriteWithoutTruncation() = runBlocking {
        val file = ClaudeSynthetic.file(temporary,
            ClaudeSynthetic.chat("small"),
            ClaudeSynthetic.chat("large", listOf(ClaudeSynthetic.message(text = "x".repeat(800 * 1024)))))
        val sink = Sink()
        try { import(file, sink); fail("must reject during preflight") }
        catch (failure: DeepSeekImportException) {
            assertEquals(ArchiveFailure.NODE_LIMIT, failure.failureReason)
            assertEquals(0, failure.partialResult.imported)
        }
        assertTrue(sink.saved.isEmpty())
        assertTrue(file.readText().contains("x".repeat(800 * 1024)))
    }

    @Test fun unselectedOversizedPathDoesNotPreventAnExplicitlySelectedSafePath() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat("small"),
            ClaudeSynthetic.chat("large", listOf(ClaudeSynthetic.message(text = "x".repeat(800 * 1024)))))
        val sink = Sink()
        val selected = selections(file).filterKeys { it == ClaudeChatArchive.selectionKey("small", "a") }
        assertEquals(1, import(file, sink, selected).imported)
    }

    @Test fun cancellationBeforeAndAfterCommitReportsOnlyDurablySavedPaths() = runBlocking {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.branch())
        for (afterCommit in listOf(false, true)) {
            val sink = Sink()
            try {
                import(file, sink) { progress ->
                    if (!afterCommit || progress.completed == 1) throw CancellationException()
                }
                fail("must cancel")
            } catch (cancelled: DeepSeekImportCancelledException) {
                assertEquals(if (afterCommit) 1 else 0, cancelled.partialResult.imported)
                assertEquals(cancelled.partialResult.imported, sink.saved.size)
            }
        }
    }

    @Test fun refusedSinkCommitIsSkippedNotReportedAsImported() = runBlocking {
        val sink = Sink().apply { refuse = true }
        val result = import(ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat()), sink)
        assertEquals(0, result.imported); assertEquals(1, result.skipped); assertEquals(0, result.messages)
    }

    @Test fun namespacedIdsCannotCollideByIdentityConcatenation() {
        assertNotEquals(claudeImportId("message", "ab", "c"), claudeImportId("message", "a", "bc"))
        assertNotEquals(claudeImportId("message", "a", "b"), claudeImportId("node", "a", "b"))
        assertNotEquals(claudeImportId("conversation", "a", "b"), deepSeekImportId("conversation", "a", "b"))
    }
}
