package me.rerere.rikkahub.data.sync.importer

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * Explicit local diagnostic only. Normal builds skip this test. No real filename or transcript is
 * committed here, and no fixture/snapshot/database/media file is created. Never calls import().
 * Acceptance and aggregate row expectations are mandatory when opted in; rejection is not success.
 * Accepted archives are converted solely in memory by this generic probe.
 */
class OperitLocalArchiveProbeTest {
    @Test
    fun explicitlyApprovedLocalArchiveUsesOriginalParserWithoutDatabaseOrWrites() {
        val path = System.getenv("ORBIS_OPERIT_PROBE_FILE")
        val expectedHash = System.getenv("ORBIS_OPERIT_PROBE_SHA")
        assumeTrue("Local Operit probe is opt-in; no file will be opened", !path.isNullOrBlank() && !expectedHash.isNullOrBlank())
        checkSafe(expectedHash!!.matches(Regex("[0-9a-f]{64}")), "probe_sha_format")
        val expectedConversations = expectedCount("ORBIS_OPERIT_PROBE_EXPECT_CONVERSATIONS", 1, OperitChatArchive.MAX_CONVERSATIONS)
        val expectedMessages = expectedCount("ORBIS_OPERIT_PROBE_EXPECT_MESSAGES", 0, OperitChatArchive.MAX_MESSAGES)
        val expectedSummaries = expectedCount("ORBIS_OPERIT_PROBE_EXPECT_SKIPPED_SUMMARIES", 0, OperitChatArchive.MAX_MESSAGES)
        val file = File(path!!)
        checkSafe(file.isFile && file.length() in 1..OperitChatArchive.MAX_ARCHIVE_BYTES, "probe_file_bounds")
        val before = fingerprint(file)
        checkSafe(before == expectedHash, "probe_unapproved_file_digest")

        var previewAccepted = false
        var parserAccepted = false
        var parserCode = "not_run"
        var previewConversations = 0
        var previewMessages = 0
        var previewSummaries = 0
        var parsedConversations = 0
        var messages = 0
        var skippedSummaries = 0
        var convertedSummaries = 0
        var convertedConversations = 0
        var previewFailureType = "none"
        var parserFailureType = "none"
        try {
            try {
                val preview = OperitChatArchive.inspect(file)
                previewAccepted = true
                previewConversations = preview.conversations.size
                previewMessages = preview.conversations.sumOf { it.messageCount }
                previewSummaries = preview.conversations.sumOf { it.omittedSummaryCount }
                checkSafe(preview.conversations.all { it.totalNodes == it.messageCount + it.omittedSummaryCount },
                    "probe_preview_source_row_counts")
            } catch (failure: Exception) {
                // Do not let a JUnit failure/stack trace include an arbitrary parser message/path.
                previewFailureType = failure.javaClass.simpleName
            }

            val parsed = try {
                OperitChatArchive.open(file).use { it.conversations().toList() }.also {
                    parserAccepted = true
                    parserCode = "accepted"
                }
            } catch (failure: Exception) {
                parserFailureType = failure.javaClass.simpleName
                parserCode = failure.message?.takeIf { it in SAFE_PARSER_CODES } ?: "unclassified"
                null
            }
            checkSafe(previewAccepted == parserAccepted, "probe_preview_parser_disagreement")
            if (parsed != null) {
                parsedConversations = parsed.size
                skippedSummaries = parsed.sumOf { it.omittedSummaryCount }
                val selectable = parsed.filterNot { it.messages.isEmpty() && it.omittedSummaryCount > 0 }
                checkSafe(selectable.size == previewConversations, "probe_conversation_count_disagreement")
                val syntheticAssistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
                for (source in selectable) {
                    val converted = try {
                        OperitChatImporter.convert(source, syntheticAssistant)
                    } catch (_: Exception) {
                        throw AssertionError("probe_in_memory_conversion_rejected")
                    }
                    checkSafe(converted.assistantId == syntheticAssistant &&
                        converted.messageNodes.size == source.messages.size, "probe_conversion_structure")
                    checkSafe(converted.currentMessages.all { message ->
                        message.parts.all { it is UIMessagePart.Text } && message.getTools().isEmpty()
                    }, "probe_imported_history_not_inert")
                    checkSafe(converted.currentMessages.indices.all { index ->
                        val imported = converted.currentMessages[index]
                        val original = source.messages[index]
                        imported.id == operitImportId("message", source.sourceId, original.sourceIndex.toString()) &&
                            (imported.parts.first() as? UIMessagePart.Text)?.text == original.text
                    }, "probe_selected_text_or_original_index_changed")
                    convertedConversations++
                    messages += source.messages.size
                    convertedSummaries += source.omittedSummaryCount
                }
                checkSafe(messages == previewMessages && convertedSummaries == previewSummaries,
                    "probe_preview_conversion_counts_disagree")
            }
        } finally {
            checkSafe(fingerprint(file) == before, "probe_source_changed")
        }
        // Scalars only: never emit titles, IDs, contents, prompts, source model names or a file path.
        println("ORBIS_OPERIT_LOCAL_PROBE preview_accepted=$previewAccepted parser_accepted=$parserAccepted " +
            "parser_code=$parserCode preview_failure_type=$previewFailureType parser_failure_type=$parserFailureType " +
            "conversations=$parsedConversations messages=$messages converted_in_memory=$convertedConversations " +
            "skipped_summaries=$skippedSummaries source_rows=${messages + skippedSummaries} " +
            "source_sha256_unchanged=true database_opened=false network_called=false files_written=false")
        checkSafe(previewAccepted && parserAccepted, "probe_expected_acceptance")
        checkSafe(parsedConversations == expectedConversations, "probe_expected_conversation_count")
        checkSafe(messages == expectedMessages, "probe_expected_normal_message_count")
        checkSafe(skippedSummaries == expectedSummaries, "probe_expected_skipped_summary_count")
    }

    private fun expectedCount(name: String, minimum: Int, maximum: Int): Int {
        val value = System.getenv(name)?.takeIf { it.matches(Regex("[0-9]{1,6}")) }?.toIntOrNull()
        checkSafe(value != null && value in minimum..maximum, "probe_expected_count_missing_or_invalid")
        return value!!
    }

    private fun fingerprint(file: File): String = try {
        OperitChatImporter.archiveFingerprint(file)
    } catch (_: Exception) {
        throw AssertionError("probe_fingerprint_unreadable")
    }

    private fun checkSafe(condition: Boolean, reason: String) {
        if (!condition) throw AssertionError(reason)
    }

    private companion object {
        val SAFE_PARSER_CODES = setOf(
            "operit_sender", "operit_format_version", "operit_duplicate_or_many_chats", "operit_id",
            "operit_title", "operit_messages_array", "operit_message_limit", "operit_message_object",
            "operit_chat_object", "operit_base_message", "operit_variant_object", "operit_variants_array",
            "operit_variants", "operit_variant_indices", "operit_selected_variant", "operit_missing_selected_variant",
            "operit_empty_archive", "operit_string", "operit_integer", "operit_long", "operit_timestamp",
            "operit_local_timestamp", "operit_size_limit", "archive_header_key", "archive_header_primitive",
            "archive_missing_items", "archive_json_trailing", "archive_root_object", "archive_items_array",
            "archive_json_separator", "deepseek_json_size_or_truncated", "deepseek_json_complexity",
            "deepseek_json_duplicate_key", "deepseek_json_literal_size", "deepseek_json_literal",
            "deepseek_json_string_size", "deepseek_json_surrogate", "deepseek_json_control",
            "deepseek_json_unicode", "deepseek_json_escape", "deepseek_json_string", "deepseek_json_separator",
        )
    }
}
