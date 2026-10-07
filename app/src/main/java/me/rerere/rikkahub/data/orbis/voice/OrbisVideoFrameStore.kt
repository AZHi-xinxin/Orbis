package me.rerere.rikkahub.data.orbis.voice

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val VIDEO_FRAME_TTL_MS = 10 * 60_000L
const val VIDEO_FRAME_MAX_BYTES = 192 * 1024
const val VIDEO_CALL_MAX_FRAMES = 360
const val VIDEO_CALL_MAX_RETAINED = 10
private const val VIDEO_TOTAL_MAX_BYTES = 96L * 1024 * 1024
class VideoFrameStorageLimitException(message: String) : IllegalStateException(message)

@Serializable
data class OrbisVideoFrame(val id: String, val capturedAtMs: Long, val bytes: Int,
    val photoId: String? = null)

@Serializable
data class OrbisVideoFrameCall(val id: String, val assistantId: String, val conversationId: String,
    val startedAtMs: Long, val endedAtMs: Long? = null, val frames: List<OrbisVideoFrame> = emptyList())

/** Closed UUID handles, never model-provided filesystem paths. JPEGs live outside backups. */
class OrbisVideoFrameStore(root: File) {
    // The trusted root itself may be relocated by the OS (for example Windows TEMP or
    // Android /data/user aliases). Resolve it once; child links remain disallowed.
    private val root = root.canonicalFile
    private val mutex = locks.computeIfAbsent(root.canonicalPath) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun begin(owner: String, conversationId: String, callId: String, now: Long) = locked {
        id(owner); id(conversationId); id(callId)
        val old = read(callId)
        if (old != null) {
            require(old.assistantId == owner && old.conversationId == conversationId && old.endedAtMs == null)
            old
        } else save(OrbisVideoFrameCall(callId, owner, conversationId, now))
    }

    suspend fun end(owner: String, callId: String, now: Long) = locked {
        val call = owned(owner, callId)
        if (call.endedAtMs == null) save(call.copy(endedAtMs = now.coerceAtLeast(call.startedAtMs))) else call
    }

    suspend fun add(owner: String, callId: String, jpeg: ByteArray, now: Long): OrbisVideoFrame = locked {
        val call = owned(owner, callId)
        check(call.endedAtMs == null) { "视频通话已结束，未保存新画面。" }
        require(jpeg.size in 4..VIDEO_FRAME_MAX_BYTES && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        if (call.frames.size >= VIDEO_CALL_MAX_FRAMES) throw VideoFrameStorageLimitException("本次通话临时画面已达 360 张，已停止新抽帧，原画面仍保留。")
        if (totalBytes() + jpeg.size > VIDEO_TOTAL_MAX_BYTES) throw VideoFrameStorageLimitException("临时画面已达 96 MiB，已停止新抽帧，原画面仍保留。")
        if (root.usableSpace <= 64L * 1024 * 1024 + jpeg.size) throw VideoFrameStorageLimitException("手机可用空间不足，已暂停抽帧。")
        val frame = OrbisVideoFrame(UUID.randomUUID().toString(), now, jpeg.size)
        val target = imageFile(call.id, frame.id)
        target.parentFile!!.mkdirs()
        target.outputStream().use { it.write(jpeg); it.flush() }
        try { save(call.copy(frames = call.frames + frame)) }
        catch (e: Exception) { target.delete(); throw e }
        frame
    }

    suspend fun list(owner: String, callId: String? = null, now: Long): List<OrbisVideoFrameCall> = locked {
        id(owner)
        (if (callId == null) records().filter { it.assistantId == owner } else listOf(owned(owner, callId)))
            .filter { !expired(it, now) }.sortedByDescending { it.startedAtMs }.take(20)
    }

    suspend fun readJpeg(owner: String, callId: String, frameId: String, now: Long): ByteArray = locked {
        val call = owned(owner, callId)
        require(!expired(call, now)) { "画面已超过通话结束后 10 分钟的临时保留期。" }
        val frame = call.frames.firstOrNull { it.id == frameId } ?: error("画面不存在或不属于本次通话。")
        val file = imageFile(call.id, frame.id)
        require(file.length() == frame.bytes.toLong() && frame.bytes in 4..VIDEO_FRAME_MAX_BYTES)
        file.readBytes()
    }

    /** Import copies the image before receipt publication; the photo store is also idempotent. */
    suspend fun retain(owner: String, callId: String, frameId: String, now: Long,
        import: suspend (File) -> String): String = locked {
        val call = owned(owner, callId)
        val frame = call.frames.firstOrNull { it.id == frameId } ?: error("画面不存在或不属于本次通话。")
        frame.photoId?.let { return@locked it }
        require(!expired(call, now)) { "临时画面已过期。" }
        check(call.frames.count { it.photoId != null } < VIDEO_CALL_MAX_RETAINED) { "每次通话最多保留 10 张照片。" }
        val source = imageFile(call.id, frame.id)
        require(source.length() == frame.bytes.toLong())
        val photoId = import(source)
        save(call.copy(frames = call.frames.map { if (it.id == frame.id) it.copy(photoId = photoId) else it }))
        photoId
    }

    /** Recovery never reopens a camera. Interrupted sessions expire from their last known capture. */
    suspend fun cleanup(now: Long, recoverInterrupted: Boolean = false): Int = locked {
        var removed = 0
        val known = records()
        known.forEach { original ->
            val call = if (recoverInterrupted && original.endedAtMs == null) save(original.copy(
                endedAtMs = original.frames.lastOrNull()?.capturedAtMs ?: original.startedAtMs)) else original
            if (expired(call, now)) call.frames.forEach { frame ->
                val file = imageFile(call.id, frame.id)
                if (file.exists() && file.delete()) removed++
            }
            // A crash between JPEG creation and manifest publication leaves only this closed,
            // app-owned directory orphan. Never follow an arbitrary path supplied by a tool.
            File(root, call.id).listFiles().orEmpty().filter { file ->
                file.isFile && file.extension == "jpg" &&
                    file.nameWithoutExtension.matches(Regex("[0-9a-fA-F-]{36}")) &&
                    call.frames.none { it.id == file.nameWithoutExtension } &&
                    (expired(call, now) || now - file.lastModified() >= VIDEO_FRAME_TTL_MS)
            }.forEach { if (it.delete()) removed++ }
        }
        // A missing/corrupt manifest is not a permanent retention exception. Only clean
        // closed cache names under this store; never follow a child symlink or outside path.
        val knownIds = known.map { it.id }.toSet()
        root.listFiles().orEmpty().filter { directory ->
            directory.isDirectory && directory.name.matches(Regex("[0-9a-fA-F-]{36}")) &&
                directory.name !in knownIds && directory.canonicalFile == directory.absoluteFile
        }.forEach { directory ->
            directory.listFiles().orEmpty().filter { file ->
                file.isFile && file.canonicalFile == file.absoluteFile && file.extension == "jpg" &&
                    file.nameWithoutExtension.matches(Regex("[0-9a-fA-F-]{36}")) &&
                    (now < file.lastModified() || now - file.lastModified() >= VIDEO_FRAME_TTL_MS)
            }.forEach { if (it.delete()) removed++ }
        }
        removed
    }

    private fun expired(call: OrbisVideoFrameCall, now: Long) = call.endedAtMs?.let {
        // A backwards clock cannot extend retention indefinitely.
        now < it || now - it >= VIDEO_FRAME_TTL_MS
    } ?: false
    private fun owned(owner: String, callId: String) = checkNotNull(read(callId)) { "视频通话不存在。" }
        .also { require(it.assistantId == owner) { "该视频通话不属于当前助手。" } }
    private fun id(value: String) { require(value.matches(Regex("[0-9a-fA-F-]{36}"))) { "无效的通话或画面标识。" } }
    private fun metadata(callId: String): File { id(callId); return File(root, "$callId.json") }
    private fun imageFile(callId: String, frameId: String): File {
        id(callId); id(frameId); return File(File(root, callId), "$frameId.jpg")
    }
    private fun records() = root.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
        .mapNotNull { runCatching { read(it.nameWithoutExtension) }.getOrNull() }
    private fun read(callId: String): OrbisVideoFrameCall? {
        val file = metadata(callId)
        if (!file.exists()) return null
        require(file.length() <= 256 * 1024)
        return json.decodeFromString<OrbisVideoFrameCall>(file.readText()).also { call ->
            require(call.id == callId); id(call.assistantId); id(call.conversationId)
            require(call.frames.size <= VIDEO_CALL_MAX_FRAMES)
            call.frames.forEach { id(it.id); require(it.bytes in 4..VIDEO_FRAME_MAX_BYTES) }
        }
    }
    private fun save(call: OrbisVideoFrameCall): OrbisVideoFrameCall {
        root.mkdirs()
        val file = metadata(call.id)
        val pending = File(root, "${call.id}.pending")
        pending.outputStream().use { it.write(json.encodeToString(call).toByteArray()); it.flush() }
        java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return call
    }
    private fun totalBytes(): Long = root.listFiles().orEmpty().filter { it.isDirectory }
        .sumOf { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "jpg" }.sumOf { it.length() } }
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
