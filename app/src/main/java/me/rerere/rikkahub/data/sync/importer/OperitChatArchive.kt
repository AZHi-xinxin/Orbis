package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal data class OperitMessage(
    val sender: String, val text: String, val timestamp: Instant, val sourceIndex: Int,
    val selectedVariant: Int, val variantCount: Int, val roleName: String,
    val sourceModel: String, val attachmentReferences: Int,
)

internal data class OperitConversation(
    val sourceId: String, val title: String, val messages: List<OperitMessage>,
    val createdAt: Instant, val updatedAt: Instant, val sourceCreatedAt: String,
    val sourceUpdatedAt: String, val assumedTimeZone: String,
    val omittedSummaryCount: Int = 0,
)

/** Only the explicit Operit 1.12.2 JSON-v2 export, not raw snapshots, CSV, memory JSON or legacy lists. */
object OperitChatArchive {
    const val MAX_ARCHIVE_BYTES = ArchiveCapacity.MAX_STREAM_JSON_BYTES
    const val SELECTED_PATH = "selected"
    /** Explicit human choice; never silently replaces an earlier plain-text import. */
    const val CORRECTED_COPY_PATH = "selected-corrected-copy-v1"
    internal const val MAX_CONVERSATIONS = 10_000
    internal const val MAX_MESSAGES = 1_000_000
    private val attachments = Regex("<attachment\\b|<image\\b|!\\[", RegexOption.IGNORE_CASE)

    fun inspect(file: File, checkCancelled: () -> Unit = {}): DeepSeekArchivePreview = safe {
        open(file, checkCancelled).use { archive ->
            var omittedSummaries = 0
            var summaryOnlyConversations = 0
            var reasoningMessages = 0
            var ambiguousReasoning = 0
            val conversations = archive.conversations().mapNotNull { source ->
                source.messages.forEach { message ->
                    val split = splitOperitReasoningContent(message.text, message.sender, checkCancelled)
                    if (split.slices.any { it.reasoning }) reasoningMessages++
                    if (split.ambiguous) ambiguousReasoning++
                }
                omittedSummaries += source.omittedSummaryCount
                if (source.messages.isEmpty() && source.omittedSummaryCount > 0) {
                    summaryOnlyConversations++
                    return@mapNotNull null
                }
                DeepSeekConversationPreview(source.sourceId, source.title, source.createdAt, source.updatedAt,
                    source.messages.size + source.omittedSummaryCount, source.messages.size,
                    source.messages.count { it.variantCount > 1 },
                    listOf(DeepSeekBranchPreview(SELECTED_PATH, source.messages.size, source.updatedAt, true)),
                    SELECTED_PATH, "operit_selected_variants_local_timezone",
                    omittedSummaryCount = source.omittedSummaryCount)
            }.toList()
            DeepSeekArchivePreview(conversations, warnings = buildList {
                if (omittedSummaries > 0) add("将跳过 $omittedSummaries 条 Operit 内部摘要，不作为聊天、系统提示或工具导入；原始文件不变。")
                if (summaryOnlyConversations > 0) add("已排除 $summaryOnlyConversations 个只有内部摘要、没有普通消息的会话。")
                if (reasoningMessages > 0) add("$reasoningMessages 条 AI 消息包含明确的思考标签，将分离为可折叠思考；正文与原文件保留，不执行历史工具。")
                if (ambiguousReasoning > 0) add("$ambiguousReasoning 条消息的思考标签无法安全分离，将完整保留原文；不会猜测或删去正文。")
            })
        }
    }.also { preview ->
        require(preview.conversations.isNotEmpty()) { "Operit 文件只有内部摘要，没有可导入的普通聊天；原始文件不变。" }
    }

    internal fun open(file: File, checkCancelled: () -> Unit = {},
        timeZone: ZoneId = ZoneId.systemDefault()): ArchiveReader {
        require(file.isFile) { "operit_missing_file" }
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        return ArchiveReader(file, checkCancelled, timeZone)
    }

    internal class ArchiveReader(file: File, private val checkCancelled: () -> Unit,
        private val timeZone: ZoneId) : Closeable {
        private var consumed = false
        private var bytes = 0L
        private val stream = object : FilterInputStream(file.inputStream()) {
            override fun read(): Int {
                checkCancelled()
                return `in`.read().also { if (it >= 0) account(1) }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                return `in`.read(buffer, offset, minOf(length.toLong(), MAX_ARCHIVE_BYTES - bytes + 1).toInt())
                    .also { if (it > 0) account(it) }
            }
            private fun account(count: Int) {
                bytes += count
                ArchiveCapacity.requireSize(bytes, MAX_ARCHIVE_BYTES)
            }
        }
        private val reader = PushbackReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(32 * 1024), 1)

        fun conversations(): Sequence<OperitConversation> = sequence {
            check(!consumed); consumed = true
            val first = reader.read()
            if (first != -1 && first != '\uFEFF'.code) reader.unread(first)
            val ids = hashSetOf<String>()
            var totalMessages = 0
            val items = DeepSeekStrictJson.objectArrayItems(reader, "chats",
                setOf("archiveType", "formatVersion", "exportedAt"), checkCancelled) { header ->
                require(header.string("archiveType") == "operit_chat_archive" && header.integer("formatVersion") == 2) {
                    "operit_format_version"
                }
                header["exportedAt"]?.let { timestamp(header.long("exportedAt")) }
            }
            for (item in items) {
                checkCancelled()
                val chat = item as? JsonObject ?: error("operit_chat_object")
                val id = chat.string("id").also {
                    require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "operit_id" }
                }
                require(ids.add(id) && ids.size <= MAX_CONVERSATIONS) { "operit_duplicate_or_many_chats" }
                val title = chat.string("title").also { require(it.length <= 512) { "operit_title" } }
                val rawMessages = chat["messages"] as? JsonArray ?: error("operit_messages_array")
                totalMessages += rawMessages.size
                require(totalMessages <= MAX_MESSAGES) { "operit_message_limit" }
                var omittedSummaries = 0
                val messages = rawMessages.mapIndexedNotNull { index, raw ->
                    checkCancelled()
                    val message = raw as? JsonObject ?: error("operit_message_object")
                    val base = message["baseMessage"] as? JsonObject ?: error("operit_base_message")
                    val sender = base.string("sender")
                    // Summary is an internal compression record, never a chat role or a prompt.
                    // Do not interpret its payload/variants; retaining the original array index below
                    // keeps all ordinary message IDs stable across this omission. It still counts
                    // against the source-row limit above, and the entire JSON remains validated.
                    if (sender == "summary") {
                        omittedSummaries++
                        return@mapIndexedNotNull null
                    }
                    require(sender == "user" || sender == "ai") { "operit_sender" }
                    val variants = when (val value = message["variants"]) {
                        null -> emptyList()
                        is JsonArray -> value.map { it as? JsonObject ?: error("operit_variant_object") }
                        else -> error("operit_variants_array")
                    }
                    require(variants.size <= 100 && (sender == "ai" || variants.isEmpty())) { "operit_variants" }
                    val indexed = variants.associateBy { it.integer("variantIndex") }
                    require(indexed.size == variants.size && indexed.keys.all { it > 0 }) { "operit_variant_indices" }
                    // Validate unselected variants too; never silently choose a different answer for malformed input.
                    variants.forEach { it.string("content") }
                    val selected = base.optionalInteger("selectedVariantIndex") ?: 0
                    require(selected >= 0 && (sender == "ai" || selected == 0)) { "operit_selected_variant" }
                    val chosen = if (selected == 0) base else indexed[selected] ?: error("operit_missing_selected_variant")
                    val text = chosen.optionalString("content") ?: ""
                    OperitMessage(sender, text, timestamp(base.long("timestamp")), index, selected,
                        variants.size + 1, chosen.optionalString("roleName") ?: base.optionalString("roleName") ?: "",
                        chosen.optionalString("modelName") ?: "", attachments.findAll(text).count())
                }
                // Operit serializes these as ISO_LOCAL_DATE_TIME without an offset. Do not label them UTC.
                val created = chat.string("createdAt")
                val updated = chat.string("updatedAt")
                val createdInstant = localTimestamp(created, timeZone)
                val updatedInstant = localTimestamp(updated, timeZone)
                yield(OperitConversation(id, title, messages, createdInstant, updatedInstant,
                    created, updated, timeZone.id, omittedSummaryCount = omittedSummaries))
            }
            require(ids.isNotEmpty()) { "operit_empty_archive" }
        }

        override fun close() = reader.close()
    }

    internal inline fun <T> safe(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ArchiveReadException) { throw failure }
    catch (_: java.nio.charset.CharacterCodingException) { throw ArchiveReadException(ArchiveFailure.INVALID_UTF8) }
    catch (_: java.io.IOException) { throw ArchiveReadException(ArchiveFailure.READ_WRITE) }
    catch (_: Exception) {
        throw IllegalArgumentException("请选择 Operit 的聊天 JSON v2 导出；文件不兼容、损坏或超限，现有聊天未覆盖")
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("operit_string")
    private fun JsonObject.optionalString(key: String): String? =
        if (get(key) == null || get(key) == JsonNull) null else string(key)
    private fun JsonObject.integer(key: String): Int =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: error("operit_integer")
    private fun JsonObject.optionalInteger(key: String): Int? = if (get(key) == null) null else integer(key)
    private fun JsonObject.long(key: String): Long =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("operit_long")
    private fun timestamp(milliseconds: Long): Instant {
        require(milliseconds in 0..253402300799999L) { "operit_timestamp" }
        return Instant.ofEpochMilli(milliseconds)
    }
    private fun localTimestamp(value: String, zone: ZoneId): Instant =
        LocalDateTime.parse(value).atZone(zone).toInstant().also {
            require(it.epochSecond in 0..253402300799L) { "operit_local_timestamp" }
        }
}
