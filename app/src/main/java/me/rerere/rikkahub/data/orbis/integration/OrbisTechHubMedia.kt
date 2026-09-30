package me.rerere.rikkahub.data.orbis.integration

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal const val HUB_FILE_LIMIT = 30L * 1024 * 1024
internal const val HUB_IMAGE_LIMIT = 10L * 1024 * 1024
internal const val HUB_TEXT_PREVIEW_LIMIT = 128 * 1024
internal const val HUB_AUTO_PREVIEW_LIMIT = 2L * 1024 * 1024

/** No URL, filename sniffing, or unknown MIME can opt into automatic network reads. */
internal fun hubMayAutoPreview(attachment: HubAttachment): Boolean = attachment.kind == "image" &&
    attachment.size in 1..HUB_AUTO_PREVIEW_LIMIT &&
    attachment.mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp") &&
    Regex("[a-f0-9]{32}").matches(attachment.id)

/** One attempt per attachment in a connection-scoped page lifetime, even after
 * LazyColumn disposes a card. The bounded set never evicts to create retries. */
internal fun claimHubAutoPreview(attempts: MutableSet<String>, attachment: HubAttachment): Boolean =
    hubMayAutoPreview(attachment) && attachment.id !in attempts && attempts.size < 1000 && attempts.add(attachment.id)

internal data class HubCachedPreview(val attachment: HubAttachment, val file: File, val mime: String)

/** Owns inactive cards' verified files, never credentials or remote URLs. Taking
 * transfers ownership to a card; putting transfers it back. Eviction is final:
 * a successful evicted ID needs a manual click until the next foreground epoch. */
internal class HubPreviewPageCache {
    private val entries = LinkedHashMap<String, HubCachedPreview>()
    private val attempts = mutableSetOf<String>()
    private val succeeded = mutableSetOf<String>()
    private var active = true
    private var closed = false
    @Synchronized fun claim(attachment: HubAttachment): Boolean =
        active && !closed && claimHubAutoPreview(attempts, attachment)
    @Synchronized fun succeeded(attachment: HubAttachment) {
        if (!closed && hubMayAutoPreview(attachment) && succeeded.size < 1000) succeeded += attachment.id
    }
    @Synchronized fun contains(attachment: HubAttachment): Boolean = active && !closed &&
        entries[attachment.id]?.let { it.attachment == attachment && it.file.isFile && it.file.length() == attachment.size } == true
    @Synchronized fun take(attachment: HubAttachment): HubCachedPreview? {
        if (!active || closed) return null
        val item = entries.remove(attachment.id) ?: return null
        if (item.attachment != attachment || !item.file.isFile || item.file.length() != attachment.size) {
            item.file.delete(); return null
        }
        return item
    }
    @Synchronized fun put(item: HubCachedPreview) {
        if (!active || closed || !hubMayAutoPreview(item.attachment) ||
            !item.file.isFile || item.file.length() != item.attachment.size) {
            item.file.delete(); return
        }
        entries.remove(item.attachment.id)?.let { if (it.file != item.file) it.file.delete() }
        entries[item.attachment.id] = item
        while (entries.size > 8 || entries.values.sumOf { it.attachment.size } > 16L * 1024 * 1024) {
            val first = entries.entries.first(); entries.remove(first.key); first.value.file.delete()
        }
    }
    @Synchronized fun setActive(value: Boolean) {
        active = value
        if (!value) {
            entries.values.forEach { it.file.delete() }; entries.clear()
            // Re-entering foreground may re-read prior successes, not failures.
            attempts.removeAll(succeeded); succeeded.clear()
        }
    }
    @Synchronized fun close() {
        closed = true; entries.values.forEach { it.file.delete() }; entries.clear()
        attempts.clear(); succeeded.clear()
    }
}

/** Context.cacheDir is trusted but may use Android's /data/user/0 alias. Resolve
 * that parent once, then forbid links/escapes in the directory and file we own. */
internal fun createHubPreviewFile(cacheDirectory: File): File {
    val base = cacheDirectory.canonicalFile
    if (!base.isDirectory) throw OrbisTechHubException("media_storage")
    val directory = File(base, "orbis-techhub-preview")
    if (!directory.exists() && !directory.mkdir() && !directory.isDirectory)
        throw OrbisTechHubException("media_storage")
    if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile || directory.parentFile != base)
        throw OrbisTechHubException("media_storage")
    return File.createTempFile("hub-", ".bin", directory).also {
        if (!it.isFile || it.length() != 0L || it.canonicalFile != it.absoluteFile || it.parentFile != directory)
            throw OrbisTechHubException("media_storage")
    }
}

/** Null means download/save remains allowed, but no image decoder is invoked. */
internal fun hubPreviewImageSample(mime: String, width: Int, height: Int): Int? {
    if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")) return null
    if (width !in 1..65536 || height !in 1..65536 || width.toLong() * height > 100_000_000L) return null
    var sample = 1
    while ((width + sample - 1) / sample > 1600 || (height + sample - 1) / sample > 1600) sample *= 2
    return sample
}

internal enum class HubAttachmentReadStage { CREDENTIAL, CACHE, TRANSFER, PREVIEW }

/** Only fixed categories enter the UI; never stringify a URL, bearer or response. */
internal fun hubAttachmentReadFailure(stage: HubAttachmentReadStage, failure: Exception): String {
    val reason = (failure as? OrbisTechHubException)?.reason
    return when (reason) {
        "unauthorized" -> "附件授权未通过，请检查 TechHub 专用连接。[授权]"
        "not_found" -> "服务器未找到这个附件，请确认附件仍存在。[附件不存在]"
        "redirect_refused" -> "附件地址返回了跳转，已停止读取，未向其它地址发送授权。[跳转已拦截]"
        "configuration_changed" -> "TechHub 连接已变更，请在当前连接下重新查看。[连接已变更]"
        "media_size_mismatch", "media_too_large", "empty_media" -> "附件长度与消息记录不一致，已丢弃不完整缓存。[长度校验]"
        "invalid_response" -> "附件响应或本机缓存写入未通过校验，请稍后重试。[读取校验]"
        else -> when (stage) {
            HubAttachmentReadStage.CREDENTIAL -> "TechHub 连接暂不可用，请检查专用连接设置。[读取连接]"
            HubAttachmentReadStage.CACHE -> "无法建立本机附件缓存，请检查存储空间。[本机缓存]"
            HubAttachmentReadStage.TRANSFER -> "附件传输未完成，请检查网络后重试。[附件传输]"
            HubAttachmentReadStage.PREVIEW -> "附件已读取，但无法生成预览；可尝试下载保存原文件。[生成预览]"
        }
    }
}

/** Only an app-owned UUID refers to a file; never a server filename or URI. */
internal data class HubUploadAttachment(val payloadId: String, val filename: String,
    val mediaType: String, val size: Long, val sha256: String)
internal data class HubUploadReceipt(val seq: Long, val attachment: HubAttachment)

/** Draft ownership is independent of Compose recomposition. A claim and screen
 * disposal race has exactly one winner; never delete a payload after outbox owns it. */
internal class HubMediaDraftOwner(private val discard: (HubUploadAttachment) -> Unit) {
    private val selected = AtomicReference<HubUploadAttachment?>(null)
    fun replace(next: HubUploadAttachment) { selected.getAndSet(next)?.let(discard) }
    fun claim(expected: HubUploadAttachment) {
        val current = selected.get()
        check(current == expected && selected.compareAndSet(current, null)) { "media_draft_no_longer_owned" }
    }
    fun clear() { selected.getAndSet(null)?.let(discard) }
}

/** Owns only the fresh private preview file supplied by the downloader. UI
 * publication and disposal never depend on a later Compose recomposition. */
internal class HubPreviewFileOwner {
    private val owned = AtomicReference<File?>(null)
    fun publish(file: File) { owned.getAndSet(file)?.takeUnless { it == file }?.delete() }
    fun take(): File? = owned.getAndSet(null)
    fun clear() { owned.getAndSet(null)?.delete() }
}

internal fun requireHubFilename(name: String): String {
    require(name.isNotBlank() && name == name.trim() && name.length <= 200 && name !in setOf(".", "..")) { "invalid_filename" }
    require(name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 ||
        it.code in 0x202a..0x202e || it.code in 0x2066..0x2069 }) { "invalid_filename" }
    return name
}

/** Matches the real Hub's documented filename normalization before sending. */
internal fun hubUploadFilename(name: String): String {
    requireHubFilename(name)
    return name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff()（） ]"), "_").take(200).ifBlank { "file" }
}

internal fun hubMediaType(value: String): String {
    val clean = value.substringBefore(';').trim().lowercase()
    require(clean.length <= 120 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(clean)) { "invalid_media_type" }
    return clean
}

internal fun validateHubUpload(value: HubUploadAttachment) {
    val id = UUID.fromString(value.payloadId)
    require(id.version() == 4 && id.variant() == 2 && id.toString() == value.payloadId) { "invalid_payload" }
    require(hubUploadFilename(value.filename) == value.filename) { "invalid_filename" }
    require(hubMediaType(value.mediaType) == value.mediaType) { "invalid_media_type" }
    val limit = if (value.mediaType.startsWith("image/")) HUB_IMAGE_LIMIT else HUB_FILE_LIMIT
    require(value.size in 1..limit && Regex("[a-f0-9]{64}").matches(value.sha256)) { "invalid_payload" }
}

internal fun hubHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

/** Streaming, bounded copy. The caller owns both streams and partial-file cleanup. */
internal fun copyHubMedia(input: InputStream, output: OutputStream, limit: Long,
    checkActive: () -> Unit = {}): Pair<Long, String> {
    require(limit in 1..HUB_FILE_LIMIT)
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    var size = 0L
    val deadline = System.nanoTime() + 120_000_000_000L
    while (true) {
        checkActive()
        if (System.nanoTime() > deadline) throw OrbisTechHubException("media_timeout")
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read > limit - size) throw OrbisTechHubException("media_too_large")
        output.write(buffer, 0, read); hash.update(buffer, 0, read); size += read
    }
    if (size == 0L) throw OrbisTechHubException("empty_media")
    return size to hubHex(hash.digest())
}

internal fun copyHubMediaExact(input: InputStream, output: OutputStream, expected: Long,
    checkActive: () -> Unit = {}): Pair<Long, String> = copyHubMedia(input, output, expected, checkActive).also {
    if (it.first != expected) throw OrbisTechHubException("media_size_mismatch")
}

/** Plain text only, strict UTF-8; nothing is executed as HTML/Markdown/JS. */
internal fun hubPreviewText(file: File, mime: String): String? {
    val type = hubMediaType(mime)
    if (!(type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/javascript"))) return null
    if (file.length() > HUB_TEXT_PREVIEW_LIMIT) return null
    return runCatching {
        val bytes = file.inputStream().use { it.cloudReadBounded(HUB_TEXT_PREVIEW_LIMIT) }
        if (bytes.size > HUB_TEXT_PREVIEW_LIMIT || bytes.any { it == 0.toByte() }) return null
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
}

/** Durable upload bytes live outside ordinary exports; no raw credential is stored. */
internal class HubMediaFiles(private val privateRoot: File) {
    private fun directory(): File {
        val base = privateRoot.canonicalFile
        val root = File(base, "orbis-techhub-uploads")
        if (!root.exists() && !root.mkdir()) throw OrbisTechHubException("media_storage")
        if (!root.isDirectory || root.canonicalFile != root.absoluteFile || root.parentFile != base)
            throw OrbisTechHubException("media_storage")
        return root
    }

    private fun path(id: String): File {
        val uuid = UUID.fromString(id)
        require(uuid.version() == 4 && uuid.variant() == 2 && uuid.toString() == id)
        val dir = directory()
        return File(dir, "$id.bin").also {
            if (it.canonicalFile != it.absoluteFile || it.parentFile != dir) throw OrbisTechHubException("media_storage")
        }
    }

    suspend fun stage(name: String, mime: String, openInput: () -> InputStream): HubUploadAttachment = withContext(Dispatchers.IO) {
        val filename = hubUploadFilename(name)
        val mediaType = hubMediaType(mime)
        val id = UUID.randomUUID().toString()
        val file = path(id)
        if (!file.createNewFile()) throw OrbisTechHubException("media_storage")
        try {
            val coroutine = currentCoroutineContext()
            val (size, hash) = openInput().use { input -> file.outputStream().use { output ->
                copyHubMedia(input, output, if (mediaType.startsWith("image/")) HUB_IMAGE_LIMIT else HUB_FILE_LIMIT) {
                    coroutine.ensureActive()
                }.also { output.fd.sync() }
            } }
            HubUploadAttachment(id, filename, mediaType, size, hash).also(::validateHubUpload)
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    suspend fun verified(value: HubUploadAttachment): File = withContext(Dispatchers.IO) {
        validateHubUpload(value)
        val file = path(value.payloadId)
        if (!file.isFile || file.length() != value.size) throw OrbisTechHubException("media_changed")
        val coroutine = currentCoroutineContext()
        val (size, hash) = file.inputStream().use { input ->
            copyHubMedia(input, object : OutputStream() { override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit }, value.size) { coroutine.ensureActive() }
        }
        if (size != value.size || hash != value.sha256) throw OrbisTechHubException("media_changed")
        file
    }

    /** Deletes precisely our UUID payload, never a directory or caller-supplied path. */
    fun discard(value: HubUploadAttachment) {
        validateHubUpload(value)
        val file = path(value.payloadId)
        if (file.exists() && (!file.isFile || !file.delete())) throw OrbisTechHubException("media_storage")
    }
}

internal fun hubUploadBody(file: File, attachment: HubUploadAttachment): RequestBody {
    validateHubUpload(attachment)
    require(file.isFile && file.length() == attachment.size)
    return object : RequestBody() {
        override fun contentType() = attachment.mediaType.toMediaType()
        override fun contentLength() = attachment.size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            file.inputStream().use { input ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (n > attachment.size - written) throw OrbisTechHubException("media_changed")
                    sink.write(bytes, 0, n); written += n
                }
            }
            if (written != attachment.size) throw OrbisTechHubException("media_changed")
        }
    }
}
