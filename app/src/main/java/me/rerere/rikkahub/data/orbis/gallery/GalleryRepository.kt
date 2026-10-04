package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A lock per private shelf, shared by every UI/tool instance. Atomic index publishes immutable files last. */
internal class GalleryRepository(
    private val root: File,
    private val beforeCommit: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = locks.getOrPut(root.absolutePath) { Any() }
    init { require(root.name.let(GalleryLimits::id)); require(root.parentFile.name == "orbis-gallery") }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
        val target = File(root, name)
        require(root.canonicalFile == root.absoluteFile && target.canonicalFile == target.absoluteFile) { "gallery_path_invalid" }
        return target
    }
    private fun read(name: String, limit: Int): ByteArray = file(name).inputStream().use { it.galleryReadBounded(limit) }
    private fun durable(name: String, bytes: ByteArray) {
        val existed = root.isDirectory
        check(root.isDirectory || root.mkdirs()) { "gallery_storage_unavailable" }
        if (!existed) { syncGalleryDirectory(root.parentFile); syncGalleryDirectory(root.parentFile.parentFile) }
        require(root.usableSpace > bytes.size * 2L + 4 * 1024 * 1024) { "gallery_space_low" }
        val target = file(name)
        val temporary = file("$name.new")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); syncGalleryDirectory(root) }
        catch (error: Exception) { temporary.delete(); throw error }
    }
    private fun index(): GalleryIndex {
        if (!file("index.json").exists()) {
            require(!root.exists() || root.listFiles().orEmpty().all { it.name.startsWith("draft-") || it.name.endsWith(".new") }) { "gallery_index_missing" }
            return GalleryIndex()
        }
        return galleryJson.decodeFromString<GalleryIndex>(galleryUtf8(read("index.json", GalleryLimits.INDEX_BYTES))).also(::validateIndex)
    }
    private fun validateIndex(value: GalleryIndex) {
            require(value.version == 1 && value.items.size <= GalleryLimits.ITEMS && value.answers.size <= GalleryLimits.ANSWERS) { "gallery_index_invalid" }
            require(value.customName.length <= 40 && value.customName.none(Char::isISOControl) && value.items.map { it.id }.distinct().size == value.items.size)
            value.items.forEach { item ->
                require(GalleryLimits.id(item.id) && GalleryLimits.title(item.title) && item.kind in GalleryLimits.kinds)
                require(item.author in setOf("human", "ai") && item.preview.length <= 240 && item.createdAt >= 0 && item.updatedAt >= item.createdAt)
                require(item.versions.size in 1..GalleryLimits.VERSIONS && item.versions.map { it.revision } == (1..item.versions.size).toList())
                require(item.versions.all { it.bytes in 1..GalleryLimits.BODY_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")) && it.actor in setOf("human", "ai") && it.createdAt >= 0 })
            }
            require(value.answers.map { it.id }.distinct().size == value.answers.size)
            value.answers.forEach { require(GalleryLimits.id(it.id) && value.items.any { item -> item.id == it.itemId && it.revision in 1..item.current.revision } && it.actor in setOf("human", "ai")) }
    }
    private fun commit(index: GalleryIndex) {
        validateIndex(index)
        val bytes = galleryJson.encodeToString(index).toByteArray()
        require(bytes.size <= GalleryLimits.INDEX_BYTES) { "gallery_catalog_full" }
        beforeCommit()
        durable("index.json", bytes)
    }
    fun snapshot(): GalleryIndex = synchronized(lock) { index() }
    fun rename(name: String) = synchronized(lock) {
        require(name.length <= 40 && name.none(Char::isISOControl)); commit(index().copy(customName = name.trim()))
    }
    fun body(id: String, revision: Int? = null): String = synchronized(lock) {
        val item = item(index(), id)
        val version = item.versions.firstOrNull { it.revision == (revision ?: item.current.revision) } ?: error("gallery_not_found")
        val bytes = read("${item.id}-r${version.revision}.txt", GalleryLimits.BODY_BYTES)
        require(bytes.size == version.bytes && galleryHash(bytes) == version.sha256) { "gallery_body_invalid" }
        galleryUtf8(bytes)
    }
    fun readPage(id: String, revision: Int?, offset: Int): Pair<String, Int?> = synchronized(lock) {
        gallerySlice(body(id, revision).toByteArray(), offset)
    }
    fun begin(title: String, kind: String, actor: String, id: String? = null, expectedRevision: Int = 0): GalleryDraft = synchronized(lock) {
        require(GalleryLimits.title(title) && kind in GalleryLimits.kinds && actor in setOf("human", "ai")) { "gallery_invalid_parameters" }
        val snapshot = index()
        require(snapshot.items.size < GalleryLimits.ITEMS || id != null) { "gallery_catalog_full" }
        // Establish a durable empty index BEFORE a draft can produce an immutable body. If the
        // first publication crashes between body and catalogue, reopening stays valid and retryable.
        if (!file("index.json").exists()) commit(snapshot)
        if (id != null) {
            val current = item(snapshot, id)
            require(current.current.revision == expectedRevision && current.kind == kind) { "gallery_revision_changed" }
        } else require(expectedRevision == 0)
        require(root.listFiles().orEmpty().count { it.name.startsWith("draft-") && it.name.endsWith(".json") } < 50) { "gallery_too_many_drafts" }
        val draft = GalleryDraft(UUID.randomUUID().toString(), id ?: UUID.randomUUID().toString(), title, kind, expectedRevision, actor, now())
        durable("draft-${draft.id}.json", galleryJson.encodeToString(draft).toByteArray())
        durable("draft-${draft.id}.txt", byteArrayOf())
        draft
    }
    private fun draft(id: String): GalleryDraft {
        require(GalleryLimits.id(id)); return galleryJson.decodeFromString<GalleryDraft>(galleryUtf8(read("draft-$id.json", 8192))).also { require(it.id == id) }
    }
    /** Retry of an already-written identical byte range succeeds without duplicating text. */
    fun append(id: String, offset: Int, text: String, actor: String): Int = synchronized(lock) {
        require(draft(id).actor == actor) { "gallery_draft_owner" }
        val bytes = text.toByteArray()
        require(bytes.isNotEmpty() && bytes.size <= GalleryLimits.CHUNK_BYTES) { "gallery_chunk_too_large" }
        require(offset >= 0 && offset.toLong() + bytes.size <= GalleryLimits.BODY_BYTES) { "gallery_file_too_large" }
        val target = file("draft-$id.txt")
        RandomAccessFile(target, "rw").use { stream ->
            if (offset.toLong() != stream.length()) {
                require(offset.toLong() + bytes.size <= stream.length()) { "gallery_offset_invalid" }
                val existing = ByteArray(bytes.size); stream.seek(offset.toLong()); stream.readFully(existing)
                require(existing.contentEquals(bytes)) { "gallery_offset_invalid" }
            } else {
                require(root.usableSpace > bytes.size + 4 * 1024 * 1024) { "gallery_space_low" }
                stream.seek(stream.length()); stream.write(bytes); stream.fd.sync()
            }
            stream.length().toInt()
        }
    }
    fun publish(id: String, actor: String): GalleryItem = synchronized(lock) {
        val draft = draft(id); require(draft.actor == actor) { "gallery_draft_owner" }
        require(draft.kind in GalleryLimits.kinds) { "gallery_invalid_parameters" }
        val before = index()
        val old = before.items.firstOrNull { it.id == draft.itemId }
        require(old != null || before.items.size < GalleryLimits.ITEMS) { "gallery_catalog_full" }
        require((old?.current?.revision ?: 0) == draft.expectedRevision && old?.deletedAt == null) { "gallery_revision_changed" }
        require((old?.versions?.size ?: 0) < GalleryLimits.VERSIONS) { "gallery_version_limit" }
        val bytes = read("draft-$id.txt", GalleryLimits.BODY_BYTES)
        require(bytes.isNotEmpty()) { "gallery_body_empty" }
        val content = galleryUtf8(bytes)
        if (draft.kind == "questionnaire") parseGalleryQuestionnaire(content)
        val revision = draft.expectedRevision + 1
        val updated = now()
        val entry = GalleryItem(draft.itemId, draft.title, draft.kind, old?.author ?: actor, old?.createdAt ?: updated, updated,
            old?.versions.orEmpty() + GalleryVersion(revision, bytes.size, galleryHash(bytes), updated, actor), favorite = old?.favorite ?: false,
            preview = galleryPreview(draft.kind, content))
        durable("${entry.id}-r$revision.txt", bytes)
        commit(before.copy(items = before.items.filterNot { it.id == entry.id } + entry))
        // Draft markers remain as a recovery receipt; a later publish must not create another version.
        file("draft-$id.txt").delete(); file("draft-$id.json").delete()
        entry
    }
    fun cancelDraft(id: String, actor: String) = synchronized(lock) {
        require(draft(id).actor == actor); file("draft-$id.txt").delete(); file("draft-$id.json").delete(); Unit
    }
    fun drafts(): List<GalleryDraft> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.name.startsWith("draft-") && it.name.endsWith(".json") }
            .map { draft(it.name.removePrefix("draft-").removeSuffix(".json")) }.sortedByDescending { it.createdAt }
    }
    /** Only the native human UI calls this; AI cancel is separately actor-bound above. */
    fun discardDraftByHuman(id: String) = synchronized(lock) { val pending = draft(id); cancelDraft(pending.id, pending.actor) }
    /** Large AI answers use the same bounded, durable chunk channel as long artwork. */
    fun beginAnswer(id: String, revision: Int): GalleryDraft = synchronized(lock) {
        val entry = item(index(), id)
        require(entry.current.revision == revision && entry.kind == "questionnaire") { "gallery_revision_changed" }
        require(parseGalleryQuestionnaire(body(id, revision)).respondent == "ai") { "gallery_answer_role" }
        require(drafts().size < 50) { "gallery_too_many_drafts" }
        val draft = GalleryDraft(UUID.randomUUID().toString(), id, entry.title, "answers", revision, "ai", now())
        durable("draft-${draft.id}.json", galleryJson.encodeToString(draft).toByteArray())
        durable("draft-${draft.id}.txt", byteArrayOf()); draft
    }
    fun publishAnswer(id: String): GalleryAnswerRef = synchronized(lock) {
        require(GalleryLimits.id(id))
        // A committed answer keeps the draft ID as its receipt. Retrying after a process exit
        // or a lost tool response returns that same submission, never a duplicate answer.
        index().answers.firstOrNull { it.id == id }?.let { existing ->
            require(existing.actor == "ai") { "gallery_answer_role" }
            answers(id)
            file("draft-$id.txt").delete(); file("draft-$id.json").delete()
            return@synchronized existing
        }
        val draft = draft(id)
        require(draft.kind == "answers" && draft.actor == "ai") { "gallery_answer_role" }
        val content = galleryUtf8(read("draft-$id.txt", GalleryLimits.BODY_BYTES))
        val values = galleryJson.decodeFromString<Map<String, List<String>>>(content)
        val ref = submitInternal(draft.itemId, draft.expectedRevision, "ai", values, id)
        file("draft-$id.txt").delete(); file("draft-$id.json").delete(); ref
    }
    fun save(title: String, kind: String, content: String, actor: String = "human", id: String? = null, expectedRevision: Int = 0): GalleryItem = synchronized(lock) {
        require(content.toByteArray().size <= GalleryLimits.BODY_BYTES) { "gallery_file_too_large" }
        if (kind == "questionnaire") parseGalleryQuestionnaire(content)
        val draft = begin(title, kind, actor, id, expectedRevision)
        val bytes = content.toByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val part = gallerySlice(bytes, offset, GalleryLimits.CHUNK_BYTES)
            offset = append(draft.id, offset, part.first, actor)
        }
        publish(draft.id, actor)
    }
    fun delete(id: String, expectedRevision: Int, actor: String) = synchronized(lock) {
        require(actor in setOf("human", "ai")); val before = index(); val target = item(before, id)
        require(target.current.revision == expectedRevision) { "gallery_revision_changed" }
        require(actor != "ai" || target.author == "ai") { "gallery_ai_delete_owner" }
        commit(before.copy(items = before.items.map { if (it.id == id) it.copy(deletedAt = now(), deletedBy = actor) else it }))
    }
    fun favorite(id: String, enabled: Boolean) = synchronized(lock) {
        val before = index(); item(before, id)
        commit(before.copy(items = before.items.map { if (it.id == id) it.copy(favorite = enabled) else it }))
    }
    fun submit(id: String, revision: Int, actor: String, values: Map<String, List<String>>): GalleryAnswerRef = synchronized(lock) {
        submitInternal(id, revision, actor, values, UUID.randomUUID().toString())
    }
    private fun submitInternal(id: String, revision: Int, actor: String, values: Map<String, List<String>>, answerId: String): GalleryAnswerRef {
        val before = index(); val target = item(before, id)
        require(before.answers.size < GalleryLimits.ANSWERS) { "gallery_answers_full" }
        require(target.kind == "questionnaire" && target.current.revision == revision) { "gallery_revision_changed" }
        val form = parseGalleryQuestionnaire(body(id, revision))
        require(actor in setOf("human", "ai") && form.respondent == actor) { "gallery_answer_role" }
        validateGalleryAnswers(form, values)
        val ref = GalleryAnswerRef(answerId, id, revision, actor, now())
        val answer = GalleryAnswers(ref, form.questions, values)
        val bytes = galleryJson.encodeToString(answer).toByteArray()
        require(bytes.size <= GalleryLimits.BODY_BYTES) { "gallery_file_too_large" }
        durable("answer-${ref.id}.json", bytes)
        commit(before.copy(answers = before.answers + ref)); return ref
    }
    fun answers(id: String): GalleryAnswers = synchronized(lock) {
        val before = index(); val ref = before.answers.firstOrNull { it.id == id } ?: error("gallery_not_found")
        item(before, ref.itemId)
        galleryJson.decodeFromString<GalleryAnswers>(galleryUtf8(read("answer-$id.json", GalleryLimits.BODY_BYTES))).also {
            require(it.ref == ref); validateGalleryAnswers(GalleryQuestionnaire(respondent = ref.actor, questions = it.questions), it.answers)
        }
    }
    fun answerPage(id: String, offset: Int): Pair<String, Int?> = gallerySlice(galleryJson.encodeToString(answers(id)).toByteArray(), offset)
    /** Backup holds the same shelf lock through validation and copy; no draft or orphan file is included. */
    fun <T> withExportFiles(checkCancelled: () -> Unit = {}, block: (GalleryIndex, List<File>) -> T): T = synchronized(lock) {
        val snapshot = index()
        if (!file("index.json").exists()) return@synchronized block(snapshot, emptyList())
        val files = mutableListOf(file("index.json"))
        snapshot.items.forEach { entry -> entry.versions.forEach { version ->
            checkCancelled()
            val name = "${entry.id}-r${version.revision}.txt"
            val bytes = read(name, GalleryLimits.BODY_BYTES)
            require(bytes.size == version.bytes && galleryHash(bytes) == version.sha256) { "gallery_body_invalid" }
            val text = galleryUtf8(bytes)
            if (entry.kind == "questionnaire") parseGalleryQuestionnaire(text)
            files += file(name)
        } }
        snapshot.answers.forEach { ref ->
            checkCancelled()
            val name = "answer-${ref.id}.json"
            val answer = galleryJson.decodeFromString<GalleryAnswers>(galleryUtf8(read(name, GalleryLimits.BODY_BYTES)))
            require(answer.ref == ref)
            validateGalleryAnswers(GalleryQuestionnaire(respondent = ref.actor, questions = answer.questions), answer.answers)
            files += file(name)
        }
        block(snapshot, files)
    }
    private fun item(index: GalleryIndex, id: String) = index.items.firstOrNull { it.id == id && it.deletedAt == null } ?: error("gallery_not_found")
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}

/** Android commits directory entries as well as file bytes. JVM tests use their host filesystem. */
internal fun syncGalleryDirectory(directory: File) {
    if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) {
        val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode)) { "gallery_path_invalid" }
            android.system.Os.fsync(descriptor)
        } finally { android.system.Os.close(descriptor) }
    }
}
