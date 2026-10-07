package me.rerere.rikkahub.data.orbis.spaces

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One assistant owns one isolated shelf. No model-supplied path or owner is accepted. */
class OrbisCompanionSpacesStore internal constructor(
    root: File,
    private val beforeCommit: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Android may expose the trusted app/cache root through /data/data or /data/user/0.
    // Resolve only that host-owned base. The namespace, owner and files below it must not be links.
    private val root = resolveCompanionOwnerRoot(root)
    private val lock = locks.computeIfAbsent(this.root.path) { Any() }
    private val changes = signals.computeIfAbsent(this.root.path) { MutableStateFlow(0L) }
    val revisions: StateFlow<Long> get() = changes
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Volatile private var mediaCache: Map<String, SpaceMedia> = emptyMap()
    @Volatile private var mediaCacheRevision: Long = -1

    init {
        require(CompanionSpaceLimits.id(root.name) && root.parentFile.name == "orbis-companion-spaces") { "space_owner_invalid" }
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "space_path_invalid" }
        val value = File(root, name)
        require(root.absoluteFile == root.canonicalFile && value.absoluteFile == value.canonicalFile) { "space_path_invalid" }
        return value
    }

    private fun read(): CompanionSpaceSnapshot {
        val index = file("index.json")
        if (!index.exists()) {
            require(!root.exists() || root.listFiles().orEmpty().all { it.name.endsWith(".new") }) { "space_index_missing" }
            return CompanionSpaceSnapshot()
        }
        val bytes = index.inputStream().use { it.spaceReadBounded(CompanionSpaceLimits.INDEX_BYTES) }
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<CompanionSpaceSnapshot>(raw).also { value ->
            validate(value); mediaCache = value.media.associateBy { it.id }; mediaCacheRevision = value.revision
            if (changes.value != value.revision) changes.value = value.revision
        }
    }

    private fun validate(value: CompanionSpaceSnapshot) {
        require(value.version == 1 && value.revision >= 0) { "space_index_invalid" }
        require(value.stories.size <= CompanionSpaceLimits.ITEMS && value.posts.size <= CompanionSpaceLimits.ITEMS && value.photos.size <= CompanionSpaceLimits.ITEMS && value.media.size <= 5_000 && value.videoReceipts.size <= 50_000)
        require(value.wallStyle in CompanionSpaceLimits.walls)
        fun ids(ids: List<String>) { require(ids.distinct().size == ids.size && ids.all(CompanionSpaceLimits::id)) }
        ids(value.stories.map { it.id }); ids(value.posts.map { it.id }); ids(value.photos.map { it.id }); ids(value.media.map { it.id })
        value.stories.forEach { require(it.revision > 0 && it.paper in CompanionSpaceLimits.papers); CompanionSpaceLimits.text(it.title, 120); CompanionSpaceLimits.text(it.prompt, CompanionSpaceLimits.STORY_CHARS); CompanionSpaceLimits.text(it.body, CompanionSpaceLimits.STORY_CHARS) }
        val mediaIds = value.media.map { it.id }.toSet()
        require(value.coverMediaId == null || value.coverMediaId in mediaIds)
        require(value.media.sumOf { it.bytes.toLong() } <= CompanionSpaceLimits.TOTAL_MEDIA_BYTES)
        value.media.forEach { require(it.filename == "${it.id}.image" && it.mime in setOf("image/jpeg", "image/png", "image/webp") && it.bytes in 1..CompanionSpaceLimits.MEDIA_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        value.photos.forEach { require(it.mediaId in mediaIds && it.revision > 0); CompanionSpaceLimits.text(it.note, 10_000) }
        value.posts.forEach { post ->
            require(post.actor in ACTORS && post.likes.all { it in ACTORS } && post.imageIds.size <= 9 && post.imageIds.all { it in mediaIds })
            CompanionSpaceLimits.text(post.text, CompanionSpaceLimits.POST_CHARS)
            require(post.comments.size <= 1_000); ids(post.comments.map { it.id })
            post.comments.forEach { c -> require(c.actor in ACTORS && (c.replyTo == null || post.comments.any { it.id == c.replyTo })); CompanionSpaceLimits.text(c.text, 5_000) }
        }
        require(value.videoReceipts.map { it.callId to it.frameId }.distinct().size == value.videoReceipts.size)
        require(value.videoReceipts.all { it.callId.length in 1..100 && it.frameId.length in 1..100 && '\u0000' !in it.callId && '\u0000' !in it.frameId && CompanionSpaceLimits.id(it.photoId) })
        require(value.videoReceipts.groupBy { it.callId }.values.all { it.size <= 10 })
    }

    private fun durable(name: String, bytes: ByteArray) {
        val existed = root.isDirectory
        require(root.isDirectory || root.mkdirs()) { "space_storage_unavailable" }
        if (!existed) { syncSpaceDirectory(root.parentFile); syncSpaceDirectory(root.parentFile.parentFile) }
        require(root.usableSpace > bytes.size * 2L + 4 * 1024 * 1024) { "space_storage_full" }
        val target = file(name); val temporary = file("$name.new")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncSpaceDirectory(root)
        } catch (failure: Exception) { temporary.delete(); throw failure }
    }

    private fun commit(value: CompanionSpaceSnapshot) {
        val next = value.copy(revision = value.revision + 1)
        validate(next)
        val bytes = json.encodeToString(next).toByteArray()
        require(bytes.size <= CompanionSpaceLimits.INDEX_BYTES) { "space_catalog_full" }
        beforeCommit(); durable("index.json", bytes)
        mediaCache = next.media.associateBy { it.id }; mediaCacheRevision = next.revision
        changes.value = next.revision
    }

    fun snapshot(): CompanionSpaceSnapshot = synchronized(lock) { read() }

    /** Human owns the premise; an AI can only change the separate body. */
    fun saveHumanStory(title: String, prompt: String, paper: String, id: String? = null, expectedRevision: Int? = null): SpaceStory = synchronized(lock) {
        require(title.isNotBlank() && prompt.isNotBlank() && paper in CompanionSpaceLimits.papers) { "space_story_empty" }
        val state = read(); val old = id?.let { ownedStory(state, it, expectedRevision) }
        val item = SpaceStory(old?.id ?: uuid(), title.trim(), prompt, old?.body.orEmpty(), paper, (old?.revision ?: 0) + 1, old?.createdAt ?: now(), now())
        commit(state.copy(stories = state.stories.filterNot { it.id == item.id } + item)); item
    }

    fun writeStoryBody(id: String, body: String, expectedRevision: Int): SpaceStory = synchronized(lock) {
        val state = read(); val old = ownedStory(state, id, expectedRevision)
        val item = old.copy(body = body, revision = old.revision + 1, updatedAt = now())
        commit(state.copy(stories = state.stories.map { if (it.id == id) item else it })); item
    }

    fun deleteStory(id: String, expectedRevision: Int) = synchronized(lock) {
        val state = read(); ownedStory(state, id, expectedRevision)
        commit(state.copy(stories = state.stories.filterNot { it.id == id }))
    }

    private fun ownedStory(state: CompanionSpaceSnapshot, id: String, revision: Int?): SpaceStory {
        val item = state.stories.firstOrNull { it.id == id } ?: error("space_not_found")
        require(revision == item.revision) { "space_revision_changed" }; return item
    }

    fun publishPost(actor: String, text: String, imageIds: List<String> = emptyList()): SpacePost = synchronized(lock) {
        val state = read(); require(actor in ACTORS && (text.isNotBlank() || imageIds.isNotEmpty())) { "space_post_empty" }
        require(imageIds.distinct().size == imageIds.size)
        val post = SpacePost(uuid(), actor, text, imageIds, createdAt = now())
        commit(state.copy(posts = state.posts + post)); post
    }

    fun likePost(id: String, actor: String, liked: Boolean): SpacePost = synchronized(lock) {
        val state = read(); require(actor in ACTORS)
        val old = post(state, id); val next = old.copy(likes = if (liked) old.likes + actor else old.likes - actor)
        commit(state.copy(posts = state.posts.map { if (it.id == id) next else it })); next
    }

    fun commentPost(id: String, actor: String, text: String, replyTo: String? = null): SpaceComment = synchronized(lock) {
        require(actor in ACTORS && text.isNotBlank()) { "space_comment_empty" }
        val state = read(); val old = post(state, id)
        require(replyTo == null || old.comments.any { it.id == replyTo }) { "space_reply_not_found" }
        val comment = SpaceComment(uuid(), actor, text, replyTo, now())
        commit(state.copy(posts = state.posts.map { if (it.id == id) it.copy(comments = it.comments + comment) else it })); comment
    }

    fun deletePost(id: String, actor: String) = synchronized(lock) {
        val state = read(); val old = post(state, id)
        require(actor == "human" || old.actor == actor) { "space_author_mismatch" }
        commit(state.copy(posts = state.posts.filterNot { it.id == id }))
        pruneUnusedMedia(old.imageIds)
    }

    private fun post(state: CompanionSpaceSnapshot, id: String) = state.posts.firstOrNull { it.id == id } ?: error("space_not_found")

    /** Only a host-selected image's bounded bytes are accepted, never a URI or arbitrary path from AI. */
    fun addMedia(bytes: ByteArray, mime: String): SpaceMedia = synchronized(lock) {
        require(bytes.size in 1..CompanionSpaceLimits.MEDIA_BYTES && spaceImageMime(bytes) == mime) { "space_image_invalid" }
        val state = read(); val hash = sha256(bytes)
        state.media.firstOrNull { it.sha256 == hash }?.let { return@synchronized it }
        require(state.media.sumOf { it.bytes.toLong() } + bytes.size <= CompanionSpaceLimits.TOTAL_MEDIA_BYTES) { "space_media_full" }
        if (!file("index.json").exists()) commit(state)
        val id = uuid(); val media = SpaceMedia(id, "$id.image", mime, bytes.size, hash)
        durable(media.filename, bytes)
        try { commit(read().copy(media = state.media + media)) }
        catch (failure: Exception) { file(media.filename).delete(); throw failure }
        media
    }

    fun mediaFile(id: String): File = synchronized(lock) {
        if (mediaCacheRevision != changes.value) read()
        val media = mediaCache[id] ?: error("space_not_found")
        file(media.filename).also { require(it.isFile && it.length() == media.bytes.toLong()) { "space_image_missing" } }
    }

    fun imageBytes(id: String): Pair<String, ByteArray> = synchronized(lock) {
        val media = read().media.firstOrNull { it.id == id } ?: error("space_not_found")
        val bytes = file(media.filename).inputStream().use { it.spaceReadBounded(CompanionSpaceLimits.MEDIA_BYTES) }
        require(sha256(bytes) == media.sha256 && spaceImageMime(bytes) == media.mime) { "space_image_invalid" }; media.mime to bytes
    }

    /** Backup takes a consistent copy under the same lock used by UI and tools. */
    internal fun <T> withExportFiles(checkCancelled: () -> Unit = {}, block: (CompanionSpaceSnapshot, List<File>) -> T): T = synchronized(lock) {
        val state = read()
        if (!file("index.json").exists()) return@synchronized block(state, emptyList())
        val files = listOf(file("index.json")) + state.media.map { media ->
            checkCancelled(); imageBytes(media.id); file(media.filename)
        }
        block(state, files)
    }

    internal fun validateSnapshot(value: CompanionSpaceSnapshot) = validate(value)

    fun addPhoto(mediaId: String, note: String = ""): SpacePhoto = synchronized(lock) {
        val state = read(); require(state.media.any { it.id == mediaId }) { "space_not_found" }
        val photo = SpacePhoto(uuid(), mediaId, note, createdAt = now())
        commit(state.copy(photos = state.photos + photo)); photo
    }

    fun setPhotoNote(id: String, note: String, expectedRevision: Int): SpacePhoto = synchronized(lock) {
        val state = read(); val old = state.photos.firstOrNull { it.id == id } ?: error("space_not_found")
        require(old.revision == expectedRevision) { "space_revision_changed" }
        val item = old.copy(note = note, revision = old.revision + 1)
        commit(state.copy(photos = state.photos.map { if (it.id == id) item else it })); item
    }

    fun movePhoto(id: String, position: Int) = synchronized(lock) {
        val state = read(); val item = state.photos.firstOrNull { it.id == id } ?: error("space_not_found")
        require(position in state.photos.indices) { "space_position_invalid" }
        val photos = state.photos.filterNot { it.id == id }.toMutableList().apply { add(position, item) }
        commit(state.copy(photos = photos))
    }

    fun deletePhoto(id: String) = synchronized(lock) {
        val state = read(); val old = state.photos.firstOrNull { it.id == id } ?: error("space_not_found")
        commit(state.copy(photos = state.photos.filterNot { it.id == id }))
        pruneUnusedMedia(listOf(old.mediaId))
    }

    fun setCover(mediaId: String?) = synchronized(lock) {
        val state = read(); require(mediaId == null || state.media.any { it.id == mediaId }) { "space_not_found" }
        commit(state.copy(coverMediaId = mediaId))
        state.coverMediaId?.takeIf { it != mediaId }?.let { pruneUnusedMedia(listOf(it)) }
    }

    /** Remove only explicitly detached assets, after the catalogue no longer references them. */
    private fun pruneUnusedMedia(candidates: List<String>) {
        val state = read()
        val used = state.photos.map { it.mediaId }.toSet() + state.posts.flatMap { it.imageIds } + listOfNotNull(state.coverMediaId)
        val removed = state.media.filter { it.id in candidates && it.id !in used }
        if (removed.isEmpty()) return
        // Reclaiming media is best-effort. A cleanup failure never makes the completed deletion a failure.
        try {
            commit(state.copy(media = state.media.filterNot { m -> removed.any { it.id == m.id } }))
            removed.forEach { file(it.filename).delete() }
        } catch (_: Exception) { /* Preserve the original files if cleanup cannot commit. */ }
    }

    fun setWallStyle(style: String) = synchronized(lock) {
        require(style in CompanionSpaceLimits.walls) { "space_style_invalid" }; commit(read().copy(wallStyle = style))
    }

    /** Video host verifies ownership/TTL first. This second cap also survives a deletion and app restart. */
    suspend fun importVideoFrame(source: File, note: String, sourceCallId: String, sourceFrameId: String): SpacePhoto = withContext(Dispatchers.IO) {
        synchronized(lock) {
            require(sourceCallId.length in 1..100 && sourceFrameId.length in 1..100 && '\u0000' !in sourceCallId && '\u0000' !in sourceFrameId) { "space_video_id_invalid" }
            val state = read()
            val receipt = state.videoReceipts.firstOrNull { it.callId == sourceCallId && it.frameId == sourceFrameId }
            if (receipt != null) return@synchronized state.photos.firstOrNull { it.id == receipt.photoId } ?: error("space_video_photo_already_deleted")
            require(state.videoReceipts.count { it.callId == sourceCallId } < 10) { "space_video_ten_photo_limit" }
            val bytes = source.inputStream().use { it.spaceReadBounded(CompanionSpaceLimits.MEDIA_BYTES) }
            val media = addMedia(bytes, spaceImageMime(bytes) ?: error("space_image_invalid"))
            val current = read()
            val item = SpacePhoto(uuid(), media.id, note, createdAt = now(), sourceCallId = sourceCallId, sourceFrameId = sourceFrameId)
            commit(current.copy(photos = current.photos + item, videoReceipts = current.videoReceipts + SpaceVideoReceipt(sourceCallId, sourceFrameId, item.id)))
            item
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private val signals = ConcurrentHashMap<String, MutableStateFlow<Long>>()
        private val ACTORS = setOf("human", "ai")
        fun open(context: Context, assistantId: String): OrbisCompanionSpacesStore {
            require(CompanionSpaceLimits.id(assistantId)) { "space_owner_invalid" }
            return OrbisCompanionSpacesStore(File(context.applicationContext.filesDir.canonicalFile, "orbis-companion-spaces/$assistantId"))
        }
        private fun uuid() = UUID.randomUUID().toString()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private fun resolveCompanionOwnerRoot(source: File): File {
    val absolute = source.absoluteFile
    val namespace = requireNotNull(absolute.parentFile) { "space_owner_invalid" }
    require(CompanionSpaceLimits.id(absolute.name) && namespace.name == "orbis-companion-spaces") { "space_owner_invalid" }
    val base = requireNotNull(namespace.parentFile) { "space_path_invalid" }
    require(absolute.toPath().normalize().toFile() == absolute) { "space_path_invalid" }
    val expected = File(base.canonicalFile, "orbis-companion-spaces/${absolute.name}")
    require(namespace.canonicalFile == expected.parentFile && absolute.canonicalFile == expected) { "space_path_invalid" }
    return expected
}

private fun syncSpaceDirectory(directory: File) {
    if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) {
        val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode)) { "space_path_invalid" }
            android.system.Os.fsync(descriptor)
        } finally { android.system.Os.close(descriptor) }
    }
}

internal fun InputStream.spaceReadBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "space_image_too_large" }; output.write(buffer, 0, count) }
    return output.toByteArray()
}

internal fun spaceImageMime(bytes: ByteArray): String? = when {
    bytes.size > 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size > 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() } -> "image/png"
    bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/** Validate a staged backup before restoring it. Does not mutate or open another app's storage. */
fun validateCompanionSpaceBackup(root: File) {
    if (!root.exists()) return
    require(root.name == "orbis-companion-spaces" && root.isDirectory && root.canonicalFile == root.absoluteFile) { "space_backup_invalid" }
    root.listFiles().orEmpty().forEach { owner ->
        require(owner.isDirectory && CompanionSpaceLimits.id(owner.name) && owner.canonicalFile == owner.absoluteFile) { "space_backup_invalid" }
        val store = OrbisCompanionSpacesStore(owner); val state = store.snapshot()
        val allowed = state.media.map { it.filename }.toSet() + "index.json"
        require(owner.listFiles().orEmpty().all { it.isFile && it.canonicalFile == it.absoluteFile && it.name in allowed }) { "space_backup_invalid" }
        state.media.forEach { store.imageBytes(it.id) }
    }
}

fun companionSpaceError(failure: Exception): String = when (failure.message) {
    "space_revision_changed" -> "内容刚刚发生了更新，请重新打开后再保存；你的编辑没有覆盖新内容。"
    "space_not_found", "space_reply_not_found" -> "这条内容已不存在，或不属于当前助手。请刷新后重试。"
    "space_video_ten_photo_limit" -> "本次通话已经保留 10 张照片，删除照片不会重置此额度。"
    "space_video_photo_already_deleted" -> "该抽帧曾经保存后被删除，不会重复导入。"
    "space_storage_full", "space_media_full", "space_catalog_full" -> "存储空间或当前空间容量不足，本次没有覆盖原内容。"
    "space_story_empty", "space_post_empty", "space_comment_empty" -> "请先填写内容；动态也可以只附图片。"
    "space_text_too_long" -> "内容超过长度限制，请分为多篇保存。"
    "space_arguments_invalid", "space_position_invalid" -> "参数不完整或不正确，请用 list 获取当前条目 ID、版本或顺序后再操作。"
    "space_image_invalid", "space_image_too_large" -> "图片无法读取或太大，请选择有效图片后重试。"
    else -> "本次操作未完成，请检查文件、剩余空间或刷新后重试；原有内容不会被清空。"
}
