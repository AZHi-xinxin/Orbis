package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.format.DateTimeParseException

enum class CodexArchiveRole { USER, ASSISTANT }
enum class CodexArchiveMessageSource { RESPONSE_ITEM, EVENT_MSG }

/** Data only: never a prompt, executable tool call, provider configuration, or import command. */
data class CodexArchiveMessage(
    val role: CodexArchiveRole,
    val text: String,
    val timestamp: Instant,
    val sourceLine: Int,
    val sourceKind: CodexArchiveMessageSource,
    val sourceMessageId: String? = null,
    val channel: String? = null,
    /** Image references are not fetched, opened, or retained. The preview must disclose this count. */
    val omittedAttachmentCount: Int = 0,
)

data class CodexChatArchiveData(
    val sourceSessionId: String,
    val createdAt: Instant,
    val messages: List<CodexArchiveMessage>,
    val ignoredRecordCount: Int,
    val discardedFallbackCount: Int,
) {
    val omittedAttachmentCount: Int get() = messages.sumOf { it.omittedAttachmentCount }
}

data class CodexArchiveLimits(
    val maxTotalBytes: Long = 64L * 1024 * 1024,
    val maxLineBytes: Int = 4 * 1024 * 1024,
    val maxMessages: Int = 100_000,
    val maxRecords: Int = 200_000,
) {
    init {
        // Caller may lower limits for a device or preview, but cannot disable the hard bounds.
        require(maxTotalBytes in 1..64L * 1024 * 1024)
        require(maxLineBytes in 1..4 * 1024 * 1024)
        require(maxMessages in 1..100_000 && maxRecords in 1..200_000)
    }
}

enum class CodexArchiveFailure {
    EMPTY_ARCHIVE, UNSUPPORTED_FORMAT, INVALID_JSON, INVALID_UTF8, INVALID_RECORD,
    INVALID_SESSION, MULTIPLE_SESSIONS, INVALID_TIMESTAMP, TRUNCATED_LINE,
    TOTAL_BYTES_LIMIT, LINE_BYTES_LIMIT, MESSAGE_LIMIT, RECORD_LIMIT, NO_CHAT_MESSAGES, READ_FAILED,
}

/** Intentionally excludes parser causes, source text, arbitrary metadata, and local paths. */
class CodexChatArchiveException(val reason: CodexArchiveFailure, val sourceLine: Int? = null) :
    IllegalArgumentException("Codex 聊天文件无法安全读取；未导入或修改聊天（${reason.name}${sourceLine?.let { ":$it" } ?: ""}）")

/**
 * Explicit subset of original Codex rollout JSONL, not `codex exec --json`, history.jsonl,
 * ChatGPT export JSON, Markdown, or a portable/stable OpenAI interchange specification.
 *
 * One session_meta followed by response_item / event_msg / turn_context / compacted records.
 * Only user-facing user/assistant text is returned. Reasoning, system/developer messages,
 * tool execution, context/configuration and compaction summaries never become executable state.
 * `event_msg` is a per-role fallback ONLY when that role has no response_item message anywhere
 * in this file. This deliberately avoids heuristic text matching and duplicate transcript copies.
 *
 * Reads a bounded line at a time; materializes only the bounded chat result. Requires a final
 * newline (as original rollout writers produce) to reject an unfinished last record. Without a
 * source manifest, loss of whole newline-terminated records cannot be detected. Caller owns and
 * closes [InputStream], chooses the file, and performs a separate preview/confirmation/commit.
 */
object CodexChatArchive {
    private val ignoredResponseTypes = setOf(
        "reasoning", "function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output",
        "local_shell_call", "web_search_call", "image_generation_call", "compaction",
    )
    private val privateChannels = setOf("analysis", "summary", "justify", "confidence")
    private val sessionIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun parse(
        input: InputStream,
        limits: CodexArchiveLimits = CodexArchiveLimits(),
        checkCancelled: () -> Unit = {},
    ): CodexChatArchiveData {
        val lines = BoundedLines(input, limits, checkCancelled)
        var sessionId: String? = null
        var createdAt: Instant? = null
        val responses = mutableListOf<CodexArchiveMessage>()
        val fallback = CodexArchiveRole.entries.associateWith { mutableListOf<CodexArchiveMessage>() }
        val responseRoles = mutableSetOf<CodexArchiveRole>()
        var ignored = 0
        var discardedFallback = 0
        var candidates = 0

        fun accept(message: CodexArchiveMessage) {
            if (++candidates > limits.maxMessages) fail(CodexArchiveFailure.MESSAGE_LIMIT, message.sourceLine)
            if (message.sourceKind == CodexArchiveMessageSource.RESPONSE_ITEM) {
                if (responseRoles.add(message.role)) {
                    val duplicateStream = fallback.getValue(message.role)
                    discardedFallback += duplicateStream.size
                    duplicateStream.clear()
                }
                responses += message
            } else if (message.role in responseRoles) {
                discardedFallback++
            } else {
                fallback.getValue(message.role) += message
            }
        }

        try {
            while (true) {
                checkCancelled()
                val line = lines.next() ?: break
                val number = lines.lineNumber
                if (number > limits.maxRecords) fail(CodexArchiveFailure.RECORD_LIMIT, number)
                val record = strictObject(line, number, checkCancelled)
                val type = record.string("type", number)
                if (sessionId == null && type != "session_meta") fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                val payload = record["payload"] as? JsonObject ?: fail(CodexArchiveFailure.INVALID_RECORD, number)
                // Validate time even on ignored records: malformed/truncated input is never a partial import.
                val recordedAt = timestamp(record.string("timestamp", number), number)
                when (type) {
                    "session_meta" -> {
                        if (sessionId != null) fail(CodexArchiveFailure.MULTIPLE_SESSIONS, number)
                        val id = payload.string("id", number)
                        if (!sessionIdPattern.matches(id)) fail(CodexArchiveFailure.INVALID_SESSION, number)
                        sessionId = id
                        createdAt = payload.optionalString("timestamp", number)?.let { timestamp(it, number) } ?: recordedAt
                    }
                    "response_item" -> {
                        val itemType = payload.string("type", number)
                        if (itemType in ignoredResponseTypes) {
                            ignored++
                            continue
                        }
                        if (itemType != "message") fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                        val role = when (payload.string("role", number)) {
                            "user" -> CodexArchiveRole.USER
                            "assistant" -> CodexArchiveRole.ASSISTANT
                            "system", "developer", "tool" -> { ignored++; continue }
                            else -> fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                        }
                        val channel = payload.optionalString("channel", number)
                        val recipient = payload.optionalString("recipient", number)
                        if (channel in privateChannels || (recipient != null && recipient != "all")) {
                            ignored++
                            continue
                        }
                        if (channel != null && channel !in setOf("final", "commentary")) {
                            fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                        }
                        val content = payload["content"] as? JsonArray ?: fail(CodexArchiveFailure.INVALID_RECORD, number)
                        val text = mutableListOf<String>()
                        var images = 0
                        for (rawPart in content) {
                            val part = rawPart as? JsonObject ?: fail(CodexArchiveFailure.INVALID_RECORD, number)
                            when (part.string("type", number)) {
                                "input_text", "output_text" -> text += part.string("text", number)
                                "input_image" -> images++ // No URL/path/base64 is retained or opened.
                                else -> fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                            }
                        }
                        accept(CodexArchiveMessage(role, text.joinToString("\n\n"), recordedAt, number,
                            CodexArchiveMessageSource.RESPONSE_ITEM, payload.optionalIdentity("id", number), channel, images))
                    }
                    "event_msg" -> {
                        val role = when (payload.string("type", number)) {
                            "user_message" -> CodexArchiveRole.USER
                            "agent_message" -> CodexArchiveRole.ASSISTANT
                            else -> { ignored++; continue }
                        }
                        val images = listOf("images", "local_images").sumOf { key ->
                            when (val value = payload[key]) {
                                null, JsonNull -> 0
                                is JsonArray -> value.size
                                else -> fail(CodexArchiveFailure.INVALID_RECORD, number)
                            }
                        }
                        accept(CodexArchiveMessage(role, payload.string("message", number), recordedAt, number,
                            CodexArchiveMessageSource.EVENT_MSG, omittedAttachmentCount = images))
                    }
                    "turn_context", "compacted" -> ignored++
                    else -> fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, number)
                }
            }
            if (sessionId == null) fail(CodexArchiveFailure.EMPTY_ARCHIVE)
            val messages = (responses + fallback.values.flatten()).sortedBy { it.sourceLine }
            if (messages.isEmpty()) fail(CodexArchiveFailure.NO_CHAT_MESSAGES)
            return CodexChatArchiveData(sessionId, checkNotNull(createdAt), messages, ignored, discardedFallback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CodexChatArchiveException) {
            throw failure
        } catch (_: IOException) {
            fail(CodexArchiveFailure.READ_FAILED, lines.lineNumber + 1)
        }
    }

    private fun strictObject(bytes: ByteArray, line: Int, checkCancelled: () -> Unit): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            fail(CodexArchiveFailure.INVALID_UTF8, line)
        }
        return try {
            // Shared pure parser rejects duplicate keys, excessive nesting, malformed escapes and trailing JSON.
            DeepSeekStrictJson.parse(text, checkCancelled) as? JsonObject
                ?: fail(CodexArchiveFailure.UNSUPPORTED_FORMAT, line)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CodexChatArchiveException) {
            throw failure
        } catch (_: RuntimeException) {
            fail(CodexArchiveFailure.INVALID_JSON, line)
        }
    }

    private fun JsonObject.string(key: String, line: Int): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(CodexArchiveFailure.INVALID_RECORD, line)

    private fun JsonObject.optionalString(key: String, line: Int): String? =
        if (get(key) == null || get(key) == JsonNull) null else string(key, line)

    private fun JsonObject.optionalIdentity(key: String, line: Int): String? = optionalString(key, line)?.also {
        if (it.isEmpty() || it.length > 256 || it.any(Char::isISOControl)) fail(CodexArchiveFailure.INVALID_RECORD, line)
    }

    private fun timestamp(value: String, line: Int): Instant = try {
        Instant.parse(value).also {
            if (it.epochSecond !in 0..253402300799L) fail(CodexArchiveFailure.INVALID_TIMESTAMP, line)
        }
    } catch (_: DateTimeParseException) {
        fail(CodexArchiveFailure.INVALID_TIMESTAMP, line)
    }

    private fun fail(reason: CodexArchiveFailure, line: Int? = null): Nothing = throw CodexChatArchiveException(reason, line)

    private class BoundedLines(
        private val input: InputStream,
        private val limits: CodexArchiveLimits,
        private val checkCancelled: () -> Unit,
    ) {
        var lineNumber: Int = 0
            private set
        private val buffer = ByteArray(8192)
        private var position = 0
        private var available = 0
        private var total = 0L

        private fun byte(): Int {
            if (position == available) {
                checkCancelled()
                // Read at most the remaining allowance + 1 sentinel byte; never drain an unbounded source.
                val request = minOf(buffer.size.toLong(), limits.maxTotalBytes - total + 1).toInt()
                available = input.read(buffer, 0, request)
                position = 0
                if (available < 0) return -1
                if (available == 0) fail(CodexArchiveFailure.READ_FAILED, lineNumber + 1)
                total += available
                if (total > limits.maxTotalBytes) fail(CodexArchiveFailure.TOTAL_BYTES_LIMIT, lineNumber + 1)
            }
            return buffer[position++].toInt() and 0xff
        }

        fun next(): ByteArray? {
            val output = ByteArrayOutputStream(minOf(8192, limits.maxLineBytes))
            while (true) {
                val value = byte()
                if (value == -1) {
                    if (output.size() != 0) fail(CodexArchiveFailure.TRUNCATED_LINE, lineNumber + 1)
                    return null
                }
                if (value == '\n'.code) {
                    lineNumber++
                    return output.toByteArray()
                }
                if (output.size() >= limits.maxLineBytes) fail(CodexArchiveFailure.LINE_BYTES_LIMIT, lineNumber + 1)
                output.write(value)
            }
        }
    }
}
