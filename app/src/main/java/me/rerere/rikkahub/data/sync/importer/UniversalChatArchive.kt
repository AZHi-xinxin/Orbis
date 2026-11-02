package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/** Only fixed field paths and expected types enter the public error, never source contents. */
class UniversalChatFormatException(path: String, expected: String) :
    IllegalArgumentException("通用格式：$path 需要$expected。请按格式说明修正后重新选择文件。")

internal data class UniversalMessage(val role: String, val content: String, val reasoning: String, val timestamp: Instant?)
internal data class UniversalConversation(val id: String, val title: String, val messages: List<UniversalMessage>, val location: String) {
    // Length framing keeps adjacent fields distinct. Formatting/key order and missing optional
    // values do not create another copy; a changed title/body/order/time does.
    val contentVersion: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes)
        }
        field(id); field(title)
        messages.forEach { field(it.role); field(it.content); field(it.reasoning); field(it.timestamp?.toString().orEmpty()) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

object UniversalChatArchive {
    const val MAX_ARCHIVE_BYTES = ArchiveCapacity.MAX_STREAM_JSON_BYTES
    const val PATH = "ordered"
    const val FORMAT = "orbis_chat"
    const val VERSION = 1

    fun inspect(file: File, checkCancelled: () -> Unit = {}, fallbackTime: Instant = Instant.now()): DeepSeekArchivePreview {
        var missingTimes = 0
        val previews = open(file, checkCancelled).use { archive ->
            archive.conversations().map { chat ->
                missingTimes += chat.messages.count { it.timestamp == null }
                // Preflight actual serialized rows too, before preview permits any import.
                UniversalChatImporter.convert(chat, Uuid.parse("00000000-0000-0000-0000-000000000000"), fallbackTime, checkCancelled)
                val created = chat.messages.mapNotNull { it.timestamp }.minOrNull() ?: fallbackTime
                val updated = chat.messages.mapNotNull { it.timestamp }.maxOrNull() ?: fallbackTime
                DeepSeekConversationPreview(chat.id, chat.title, created, updated,
                    chat.messages.size, chat.messages.size, 0,
                    listOf(DeepSeekBranchPreview(PATH, chat.messages.size, updated, true)), PATH, "universal_array_order")
            }.toList()
        }
        if (previews.isEmpty()) throw UniversalChatFormatException("conversations", "至少一个会话")
        return DeepSeekArchivePreview(previews, buildList {
            add("按 messages 数组顺序追加到当前选择的助手。相同会话ID与内容跳过；内容变化另建窗口。")
            add("本入口保存文字和AI思考。图片、音频与文件请在正文保留来源说明；媒体文件需另行保存。")
            if (missingTimes > 0) add("$missingTimes 条消息未提供原时间，将以本次导入时间显示，并记录原时间缺失。")
        })
    }

    internal fun open(file: File, checkCancelled: () -> Unit = {}): Reader {
        require(file.isFile)
        ArchiveCapacity.requireSize(file.length(), MAX_ARCHIVE_BYTES)
        return Reader(file, checkCancelled)
    }

    internal class Reader(file: File, private val checkCancelled: () -> Unit) : Closeable {
        private var consumed = false
        private var bytes = 0L
        private val stream = object : FilterInputStream(file.inputStream()) {
            override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, minOf(length.toLong(), MAX_ARCHIVE_BYTES - bytes + 1).toInt()).also { if (it > 0) account(it) }
            private fun account(count: Int) { checkCancelled(); bytes += count; ArchiveCapacity.requireSize(bytes, MAX_ARCHIVE_BYTES) }
        }
        private val reader = PushbackReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered(32768), 1)

        fun conversations(): Sequence<UniversalConversation> = sequence {
            check(!consumed); consumed = true
            try {
                val first = reader.read()
                if (first >= 0 && first != '\uFEFF'.code) reader.unread(first)
                val ids = hashSetOf<String>()
                var totalMessages = 0
                val values = DeepSeekStrictJson.objectArrayItems(reader, "conversations", setOf("format", "version"), checkCancelled) { header ->
                    if (header["format"] != JsonPrimitive(FORMAT)) throw UniversalChatFormatException("format", "字符串 orbis_chat")
                    if (header["version"] != JsonPrimitive(VERSION)) throw UniversalChatFormatException("version", "整数 1")
                }
                for ((i, value) in values.withIndex()) {
                    checkCancelled()
                    if (i >= 10000) throw UniversalChatFormatException("conversations", "最多10000个会话，更多记录请分文件")
                    val path = "conversations[$i]"
                    val raw = value as? JsonObject ?: throw UniversalChatFormatException(path, "对象")
                    val chat = parse(raw, path)
                    if (!ids.add(chat.id)) throw UniversalChatFormatException("$path.id", "在本文件中唯一的会话ID")
                    totalMessages += chat.messages.size
                    if (totalMessages > 1000000) throw UniversalChatFormatException("conversations", "每文件最多1000000条消息")
                    yield(chat)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: UniversalChatFormatException) { throw e }
            catch (e: ArchiveReadException) { throw e }
            catch (e: java.io.IOException) { throw e }
            catch (_: RuntimeException) { throw UniversalChatFormatException("文件", "完整JSON对象，字段不重复，且只包含说明中列出的字段") }
        }
        override fun close() = reader.close()
    }

    private fun parse(raw: JsonObject, path: String): UniversalConversation {
        keys(raw, setOf("id", "title", "messages"), path)
        val id = text(raw, "id", path)
        if (id.isBlank() || id.length > 256 || id.any(Char::isISOControl)) throw UniversalChatFormatException("$path.id", "1至256字的稳定ID")
        val title = text(raw, "title", path)
        if (title.length > 512) throw UniversalChatFormatException("$path.title", "最多512字")
        val messages = raw["messages"] as? JsonArray ?: throw UniversalChatFormatException("$path.messages", "数组")
        if (messages.size !in 1..50000) throw UniversalChatFormatException("$path.messages", "1至50000条消息，长会话请按顺序分段并使用不同会话ID")
        return UniversalConversation(id, title, messages.mapIndexed { i, value ->
            val p = "$path.messages[$i]"
            val msg = value as? JsonObject ?: throw UniversalChatFormatException(p, "对象")
            keys(msg, setOf("role", "content", "reasoning", "timestamp"), p)
            val role = text(msg, "role", p)
            if (role !in setOf("user", "assistant")) throw UniversalChatFormatException("$p.role", "user 或 assistant")
            val content = text(msg, "content", p)
            val reasoning = optionalText(msg, "reasoning", p).orEmpty()
            if (role == "user" && reasoning.isNotEmpty()) throw UniversalChatFormatException("$p.reasoning", "仅用于assistant思考；人类的备注请原样放在content中")
            val stamp = optionalText(msg, "timestamp", p)?.let {
                try { OffsetDateTime.parse(it).also { t -> require(t.year in 1..9999) }.toInstant() }
                catch (_: Exception) { throw UniversalChatFormatException("$p.timestamp", "带时区的ISO 8601时间，例如2026-01-01T12:00:00+08:00，未知可留null") }
            }
            UniversalMessage(role, content, reasoning, stamp)
        }, location = path)
    }

    private fun keys(raw: JsonObject, allowed: Set<String>, path: String) {
        if (!allowed.containsAll(raw.keys)) throw UniversalChatFormatException(path, "仅包含 ${allowed.joinToString()} 字段")
    }
    private fun text(raw: JsonObject, key: String, path: String): String =
        (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw UniversalChatFormatException("$path.$key", "字符串")
    private fun optionalText(raw: JsonObject, key: String, path: String): String? =
        if (raw[key] == null || raw[key] == JsonNull) null else text(raw, key, path)
}
