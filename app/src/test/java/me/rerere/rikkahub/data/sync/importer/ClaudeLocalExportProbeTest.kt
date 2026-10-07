package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/** Explicit opt-in, read-only private source. Only aggregate counts reach logs; no private fixture/copy/database. */
class ClaudeLocalExportProbeTest {
    @Test fun allLocalPathsCoverEveryOriginalMessageWithoutLiveToolsOrMedia() = runBlocking {
        val path = System.getenv("ORBIS_CLAUDE_LOCAL_PROBE_JSON")
        assumeTrue("Private archive probe is disabled by default", !path.isNullOrBlank())
        val file = File(checkNotNull(path))
        val preview = ClaudeChatArchive.inspect(file)
        val sink = object : DeepSeekImportSink {
            val saved = hashSetOf<Uuid>()
            override suspend fun exists(id: Uuid) = id in saved
            override suspend fun insert(conversation: Conversation): Boolean {
                assertTrue("No source configuration may be imported", conversation.customSystemPrompt == null &&
                    conversation.workspaceCwd == null && conversation.modeInjectionIds.isEmpty() && conversation.lorebookIds.isEmpty())
                assertTrue("Historical messages must stay inert", conversation.currentMessages.all { message ->
                    message.getTools().isEmpty() && message.orbisEvent == null && message.modelId == null &&
                        message.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning }
                })
                return saved.add(conversation.id)
            }
        }
        var conversations = 0
        var paths = 0
        var unique = 0
        var expanded = 0L
        var missing = 0
        var references = 0
        ClaudeChatArchive.open(file).use { archive ->
            for (source in archive.conversations()) {
                conversations++
                val covered = hashSetOf<String>()
                missing += source.messages.values.count { it.parentId != null && it.parentId !in source.messages }
                references += source.messages.values.sumOf { it.attachmentReferences }
                source.paths.forEachIndexed { index, branch ->
                    val converted = ClaudeChatImporter.convert(source, branch, index,
                        Uuid.parse("11111111-1111-4111-8111-111111111111")).conversation
                    converted.currentMessages.forEach { message ->
                        val metadata = when (val part = message.parts.first()) {
                            is UIMessagePart.Text -> part.metadata
                            is UIMessagePart.Reasoning -> part.metadata
                            else -> null
                        }
                        val id = (metadata?.get("source_message_id") as? JsonPrimitive)?.content
                        assertTrue("Every imported message must retain its source identity", id != null)
                        covered += checkNotNull(id)
                    }
                    assertTrue("Every path has a unique destination identity", sink.insert(converted))
                    expanded += converted.currentMessages.size
                    paths++
                }
                assertTrue("All source messages must be represented by selected paths", covered == source.messages.keys)
                unique += covered.size
            }
        }
        assertTrue("Preview path count must match converted paths", preview.conversations.size == paths)
        System.getenv("ORBIS_CLAUDE_LOCAL_EXPECT_PATHS")?.toIntOrNull()?.let {
            assertTrue("Unexpected aggregate path count", paths == it)
        }
        System.getenv("ORBIS_CLAUDE_LOCAL_EXPECT_UNIQUE")?.toIntOrNull()?.let {
            assertTrue("Unexpected aggregate unique-message count", unique == it)
        }
        println("CLAUDE_PRIVATE_PROBE conversations=$conversations paths=$paths unique_messages=$unique " +
            "expanded_messages=$expanded missing_parents=$missing attachment_references=$references " +
            "coverage_complete=true live_tools_or_media=0 contents_and_identifiers_not_logged=true")
    }
}
