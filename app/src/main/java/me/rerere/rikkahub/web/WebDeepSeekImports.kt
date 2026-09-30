package me.rerere.rikkahub.web

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.*
import me.rerere.rikkahub.web.dto.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Server-owned, short-lived staging. Never registers uploaded ZIPs as downloadable chat files. */
internal class WebDeepSeekImports(
    private val directory: File,
    private val repository: ConversationRepository,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    additionalFormats: Map<String, WebChatArchiveFormat> = emptyMap(),
) {
    private val formats = additionalFormats + ("deepseek" to WebDeepSeekArchiveFormat(repository))
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)
    private val lock = Any()
    private val jobs = linkedMapOf<String, ImportJob>()
    private val lifetimeMillis = 30L * 60 * 1000
    private val terminal = setOf("complete", "cancelled", "failed", "expired")
    private val active = setOf("uploading", "checking", "importing", "cancelling")

    private class ImportJob(val id: String, val owner: String, val assistantId: Uuid, val file: File, val expiresAt: Long,
        val formatKey: String, val format: WebChatArchiveFormat) {
        var state = "created"
        var uploadedBytes = 0L
        var preview: DeepSeekArchivePreview? = null
        var reviewToken: String? = null
        var work: Job? = null
        var result = DeepSeekImportResult()
        var total = 0
        var completed = 0
        var rows = emptyList<WebImportRowResult>()
        var selectedIndices = emptyList<Int>()
        var error: String? = null
        var archiveDigest: ByteArray? = null
    }

    init {
        check(directory.mkdirs() || directory.isDirectory)
        check(directory.canonicalFile == directory.absoluteFile) { "invalid_import_cache" }
        // One owner directory per server instance; delete only the exact files this instance creates.
        scope.launch {
            while (isActive) {
                delay(60_000)
                synchronized(lock) { expireIdleLocked() }
                pruneStaleStaging()
            }
        }
    }

    fun create(owner: String, assistantId: Uuid, source: String = "deepseek"): WebImportStatusDto = synchronized(lock) {
        expireIdleLocked()
        if (jobs.values.count { it.state !in terminal } >= 2 || jobs.size >= 16) {
            throw ConflictException("import_capacity_reached")
        }
        val id = UUID.randomUUID().toString()
        val format = formats[source] ?: throw BadRequestException("unsupported_import_source")
        val job = ImportJob(id, owner, assistantId, File(directory, "$id.zip"), System.currentTimeMillis() + lifetimeMillis,
            source, format)
        jobs[id] = job
        statusLocked(job)
    }

    fun requireSource(owner: String, id: String, source: String): Long = synchronized(lock) {
        val job = ownedLocked(owner, id)
        if (job.formatKey != source) throw NotFoundException("import_not_found")
        job.format.maxArchiveBytes
    }

    suspend fun upload(owner: String, id: String, channel: ByteReadChannel): WebImportStatusDto {
        val job = synchronized(lock) {
            val item = ownedLocked(owner, id)
            if (item.state != "created" || jobs.values.any { it.state in active }) throw ConflictException("import_busy")
            item.state = "uploading"
            item
        }
        try {
            val requestJob = currentCoroutineContext()[Job]
            synchronized(lock) {
                job.work = requestJob
                if (job.state == "cancelling") requestJob?.cancel()
            }
            withTimeout(180_000) {
                withContext(Dispatchers.IO) {
                    check(job.file.createNewFile()) { "import_staging_exists" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    job.file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var size = 0L
                        while (true) {
                            ensureActive()
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) continue
                            size += count
                            if (size > job.format.maxArchiveBytes) throw ImportTooLarge()
                            if (directory.usableSpace < 64L * 1024 * 1024 + count) throw ImportStorageLow()
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            synchronized(lock) { job.uploadedBytes = size }
                        }
                    }
                    synchronized(lock) { job.state = "checking" }
                    val coroutine = currentCoroutineContext()
                    val preview = job.format.inspect(job.file) { coroutine.ensureActive() }
                    synchronized(lock) {
                        coroutine.ensureActive()
                        job.preview = preview
                        job.archiveDigest = digest.digest()
                        job.reviewToken = UUID.randomUUID().toString()
                        job.state = "ready"
                    }
                }
            }
        } catch (_: ImportTooLarge) {
            finish(job, "failed", DeepSeekImportResult(), "archive_too_large")
        } catch (_: ImportStorageLow) {
            finish(job, "failed", DeepSeekImportResult(), "insufficient_storage")
        } catch (_: TimeoutCancellationException) {
            finish(job, "failed", DeepSeekImportResult(), "upload_timeout")
        } catch (cancel: CancellationException) {
            finish(job, "cancelled", DeepSeekImportResult(), null)
            throw cancel
        } catch (_: Exception) {
            finish(job, "failed", DeepSeekImportResult(), "invalid_archive")
        } finally {
            synchronized(lock) { job.work = null }
        }
        return synchronized(lock) { statusLocked(job) }
    }

    fun status(owner: String, id: String): WebImportStatusDto = synchronized(lock) {
        statusLocked(ownedLocked(owner, id))
    }

    fun conversations(owner: String, id: String, offset: Int, limit: Int): PagedResult<WebImportConversationDto> = synchronized(lock) {
        val preview = readyLocked(ownedLocked(owner, id))
        val items = preview.conversations.drop(offset).take(limit).mapIndexed { index, c ->
            WebImportConversationDto(offset + index, c.title, c.totalNodes, c.messageCount, c.branchPointCount,
                c.branches.size, c.branches.indexOfFirst { it.leafId == c.defaultLeafId }, c.defaultSelectionReason)
        }
        PagedResult(items, (offset + items.size).takeIf { it < preview.conversations.size })
    }

    fun branches(owner: String, id: String, conversation: Int, offset: Int, limit: Int): PagedResult<WebImportBranchDto> = synchronized(lock) {
        val row = readyLocked(ownedLocked(owner, id)).conversations.getOrNull(conversation)
            ?: throw BadRequestException("invalid_conversation_selection")
        val items = row.branches.drop(offset).take(limit).mapIndexed { index, b ->
            WebImportBranchDto(offset + index, b.messageCount, b.updatedAt.toString(), b.isDefault)
        }
        PagedResult(items, (offset + items.size).takeIf { it < row.branches.size })
    }

    fun commit(owner: String, id: String, request: WebImportCommitRequest): WebImportStatusDto = synchronized(lock) {
        val job = ownedLocked(owner, id)
        if (!request.confirmed) throw BadRequestException("confirmation_required")
        if (job.state != "ready") throw ConflictException("import_not_ready")
        if (request.reviewToken != job.reviewToken) throw ConflictException("preview_changed")
        if (jobs.values.any { it.state in active }) throw ConflictException("import_busy")
        val preview = readyLocked(job)
        if (request.selections.isEmpty() || request.selections.size > preview.conversations.size ||
            request.selections.map { it.conversation }.toSet().size != request.selections.size) {
            throw BadRequestException("invalid_selection")
        }
        val ordered = request.selections.sortedBy { it.conversation }
        val selections = ordered.associate { selection ->
            val conversation = preview.conversations.getOrNull(selection.conversation)
                ?: throw BadRequestException("invalid_selection")
            val branch = conversation.branches.getOrNull(selection.branch)
                ?: throw BadRequestException("invalid_selection")
            conversation.sourceId to branch.leafId
        }
        job.state = "importing"
        job.total = selections.size
        job.selectedIndices = ordered.map { it.conversation }
        job.reviewToken = null // one shot; a lost response must be reconciled with GET, never resubmitted.
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                check(job.file.length() == job.uploadedBytes && job.uploadedBytes in 1..job.format.maxArchiveBytes)
                job.file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    var count = input.read(buffer)
                    while (count >= 0) {
                        ensureActive()
                        total += count
                        check(total <= job.format.maxArchiveBytes)
                        digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                check(MessageDigest.isEqual(checkNotNull(job.archiveDigest), digest.digest())) { "preview_changed" }
                val fingerprint = checkNotNull(job.archiveDigest).joinToString("") { "%02x".format(it.toInt() and 255) }
                val result = job.format.import(job.file, job.assistantId, selections, fingerprint) { progress ->
                    synchronized(lock) {
                        if (progress.completed > job.completed) {
                            val selected = ordered.getOrNull(progress.completed - 1)
                            val state = when {
                                progress.result.imported > job.result.imported -> "imported"
                                progress.result.skipped > job.result.skipped -> "skipped"
                                else -> "failed"
                            }
                            if (selected != null) job.rows = job.rows + WebImportRowResult(selected.conversation, state,
                                if (state == "failed") "message_too_large" else null)
                        }
                        job.completed = progress.completed
                        job.result = progress.result
                    }
                }
                finish(job, "complete", result, null)
            } catch (cancelled: DeepSeekImportCancelledException) {
                finish(job, "cancelled", cancelled.partialResult, null)
            } catch (failure: DeepSeekImportException) {
                finish(job, "failed", failure.partialResult, "import_incomplete")
            } catch (_: CancellationException) {
                finish(job, "cancelled", job.result, null)
            } catch (_: Exception) {
                finish(job, "failed", job.result, "import_incomplete")
            }
        }
        job.work = work
        // Cancellation before dispatcher entry never enters the body's try/catch/finally.
        // Register this fence before start; preserve any atomic-commit partial receipt already recorded.
        work.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (job.work === work) {
                    if (job.state in active) {
                        finish(job, if (cause is CancellationException) "cancelled" else "failed", job.result,
                            if (cause is CancellationException) null else "import_incomplete")
                    }
                    job.work = null
                }
            }
        }
        work.start()
        statusLocked(job)
    }

    fun cancel(owner: String, id: String): WebImportStatusDto = synchronized(lock) {
        val job = ownedLocked(owner, id)
        if (job.state in terminal) return@synchronized statusLocked(job)
        if (job.state in active) {
            job.state = "cancelling"
            job.work?.cancel()
        } else {
            job.state = "cancelled"
            job.reviewToken = null
            job.preview = null
            deleteOwned(job)
        }
        statusLocked(job)
    }

    suspend fun close() {
        val requests = synchronized(lock) { jobs.values.mapNotNull { it.work }.toList() }
        requests.forEach { it.cancel() }
        requests.joinAll()
        scope.coroutineContext[Job]?.cancelAndJoin()
        synchronized(lock) {
            jobs.values.forEach(::deleteOwned)
            directory.delete() // empty directory only; never recurse.
        }
    }

    private fun finish(job: ImportJob, state: String, result: DeepSeekImportResult, error: String?) = synchronized(lock) {
        // Native cancellation can arrive after the atomic commit but before its progress callback.
        // Account that final committed row from the native partial result, never claim it was absent.
        var imported = job.rows.count { it.state == "imported" }
        var skipped = job.rows.count { it.state == "skipped" }
        var failed = job.rows.count { it.state == "failed" }
        while (job.rows.size < result.imported + result.skipped + result.failed) {
            val index = job.selectedIndices.getOrNull(job.rows.size) ?: break
            val rowState = when {
                imported < result.imported -> { imported++; "imported" }
                skipped < result.skipped -> { skipped++; "skipped" }
                failed < result.failed -> { failed++; "failed" }
                else -> break
            }
            job.rows = job.rows + WebImportRowResult(index, rowState, if (rowState == "failed") "message_too_large" else null)
        }
        job.state = state
        job.result = result
        job.completed = result.imported + result.skipped + result.failed
        job.reviewToken = null
        job.preview = null
        job.error = error
        deleteOwned(job)
    }

    private fun readyLocked(job: ImportJob): DeepSeekArchivePreview {
        if (job.state != "ready") throw ConflictException("import_not_ready")
        return job.preview ?: throw ConflictException("preview_missing")
    }

    private fun ownedLocked(owner: String, id: String): ImportJob {
        expireIdleLocked()
        return jobs[id]?.takeIf { it.owner == owner } ?: throw NotFoundException("import_not_found")
    }

    private fun expireIdleLocked() {
        val now = System.currentTimeMillis()
        jobs.values.filter { it.expiresAt <= now && it.state !in active }.forEach { job ->
            deleteOwned(job)
            job.preview = null
            job.reviewToken = null
            if (job.state !in terminal) job.state = "expired"
        }
        jobs.entries.removeAll { it.value.expiresAt + lifetimeMillis < now && it.value.state in terminal }
    }

    private fun deleteOwned(job: ImportJob) {
        // The upload filename and parent are generated here, not supplied by a request.
        if (job.file.canonicalFile == job.file.absoluteFile && job.file.parentFile == directory) job.file.delete()
    }

    private fun pruneStaleStaging() {
        val parent = directory.parentFile ?: return
        if (parent.name != "orbis-web-imports" || parent.canonicalFile != parent.absoluteFile) return
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        parent.listFiles().orEmpty().filter { candidate ->
            candidate != directory && candidate.isDirectory && candidate.canonicalFile == candidate.absoluteFile &&
                runCatching { UUID.fromString(candidate.name).toString() == candidate.name }.getOrDefault(false) &&
                candidate.lastModified() < cutoff
        }.forEach { candidate ->
            candidate.listFiles().orEmpty().filter { file ->
                file.isFile && file.canonicalFile == file.absoluteFile && file.extension == "zip" &&
                    runCatching { UUID.fromString(file.nameWithoutExtension).toString() == file.nameWithoutExtension }.getOrDefault(false)
            }.forEach { it.delete() }
            candidate.delete() // unknown files/subdirectories are never traversed or deleted.
        }
    }

    private fun statusLocked(job: ImportJob) = WebImportStatusDto(
        job.id, job.state, job.assistantId.toString(), job.expiresAt, job.uploadedBytes,
        job.format.maxArchiveBytes, job.preview?.conversations?.size ?: 0, job.reviewToken,
        job.total, job.completed, job.result.imported, job.result.skipped, job.result.failed,
        job.result.messages, job.result.attachmentReferences, job.rows, job.error,
    )

    private class ImportTooLarge : IllegalArgumentException()
    private class ImportStorageLow : IllegalStateException()
}
