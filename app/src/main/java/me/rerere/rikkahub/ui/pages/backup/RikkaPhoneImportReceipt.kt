package me.rerere.rikkahub.ui.pages.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.importer.RikkaChatImportCancelledException
import me.rerere.rikkahub.data.sync.importer.RikkaChatImportResult
import me.rerere.rikkahub.data.sync.importer.RikkaPartialImportException
import java.util.UUID

@Serializable
enum class RikkaPhoneImportStatus { RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }

/** Deliberately excludes source URI/path, conversation IDs, text, settings and parser errors. */
@Serializable
data class RikkaPhoneImportReceipt(
    val operationId: String,
    val status: RikkaPhoneImportStatus,
    val result: RikkaChatImportResult,
    val updatedAt: Long,
) {
    fun summary(): String = buildString {
        append(when (status) {
            RikkaPhoneImportStatus.RUNNING -> "正在导入；以下是已确认完成的数量。"
            RikkaPhoneImportStatus.COMPLETED -> "本次导入已完成。"
            RikkaPhoneImportStatus.CANCELLED -> "本次导入已停止；已完成的窗口保留，未完成部分没有继续执行。"
            RikkaPhoneImportStatus.FAILED -> "本次导入未全部完成；已完成的窗口保留。"
            RikkaPhoneImportStatus.INTERRUPTED -> "上次导入未正常收尾；以下仅为最后保存的计数，实际完成数可能更多。"
        })
        append("\n已导入 ${result.imported} 个窗口，跳过 ${result.skipped} 个已有窗口；恢复 ${result.attachments} 个附件，${result.missingAttachments} 处附件不在原备份中。")
        append("\n可重新选择同一原包继续，已有窗口会跳过，不会覆盖。没有导入模型、密钥、提示词设置、技能或工具授权。")
    }
}

data class RikkaPhoneImportState(val receipt: RikkaPhoneImportReceipt? = null,
    val busy: Boolean = false, val storageWarning: Boolean = false)

@Serializable
private data class RikkaPhoneReceiptEnvelope(val version: Int = 1, val receipt: RikkaPhoneImportReceipt?)

/** Process-owned, with an atomic private-file adapter. Page/VM disposal does not discard receipts. */
class RikkaPhoneImportReceiptOwner(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { encodeDefaults = true }
    private val mutex = Mutex()
    @Volatile private var activeOperation: String? = null
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    private fun load(): RikkaPhoneImportState = try {
        val raw = read()
        if (raw == null) RikkaPhoneImportState() else {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val saved = json.decodeFromString<RikkaPhoneReceiptEnvelope>(raw)
            require(saved.version == 1)
            saved.receipt?.let(::validate)
            // A new process must not show a permanently running operation or claim exact totals
            // after process death between the SQLite commit and the separate receipt write.
            RikkaPhoneImportState(saved.receipt?.let {
                if (it.status == RikkaPhoneImportStatus.RUNNING) it.copy(status = RikkaPhoneImportStatus.INTERRUPTED) else it
            })
        }
    } catch (_: Exception) { RikkaPhoneImportState(storageWarning = true) }

    suspend fun import(operation: suspend ((RikkaChatImportResult) -> Unit) -> RikkaChatImportResult): RikkaChatImportResult {
        currentCoroutineContext().ensureActive()
        val id = newId()
        val initial = receipt(id, RikkaPhoneImportStatus.RUNNING, RikkaChatImportResult())
        check(mutex.tryLock()) { "Rikka import already active" }
        var latest = RikkaChatImportResult()
        var completed = false
        activeOperation = id
        mutableState.value = RikkaPhoneImportState(initial, busy = true)
        try {
            return withContext(dispatcher) {
                publish(id, RikkaPhoneImportStatus.RUNNING, latest)
                val result = operation { progress ->
                    // A callback from an older operation may never overwrite a newer receipt.
                    if (activeOperation == id) {
                        latest = progress
                        publish(id, RikkaPhoneImportStatus.RUNNING, progress)
                    }
                }
                latest = result
                // The import itself is already complete. A receipt-only disk failure must not
                // relabel committed data as a failed import; retain success and show the warning.
                publish(id, RikkaPhoneImportStatus.COMPLETED, result, stopOnStorageFailure = false)
                completed = true
                result
            }
        } catch (cancelled: CancellationException) {
            if (!completed) {
                latest = (cancelled as? RikkaChatImportCancelledException)?.partialResult ?: latest
                finish(id, RikkaPhoneImportStatus.CANCELLED, latest)
            }
            throw cancelled
        } catch (failure: Exception) {
            latest = (failure as? RikkaPartialImportException)?.partialResult ?: latest
            finish(id, RikkaPhoneImportStatus.FAILED, latest)
            throw failure
        } finally {
            if (activeOperation == id) {
                activeOperation = null
                mutableState.value = mutableState.value.copy(busy = false)
            }
            mutex.unlock()
        }
    }

    /** Only the small receipt write is non-cancellable, never archive reading/conversion. */
    private suspend fun finish(id: String, status: RikkaPhoneImportStatus, result: RikkaChatImportResult) =
        withContext(NonCancellable + dispatcher) {
            try { publish(id, status, result) }
            catch (_: Exception) { /* Exact in-process counts remain visible with a storage warning. */ }
        }

    private fun publish(id: String, status: RikkaPhoneImportStatus, result: RikkaChatImportResult,
        stopOnStorageFailure: Boolean = true) {
        if (activeOperation != id) return
        val receipt = receipt(id, status, result)
        mutableState.value = RikkaPhoneImportState(receipt, busy = true)
        try { persist(receipt) }
        catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(storageWarning = true)
            if (stopOnStorageFailure) throw failure
        }
    }

    suspend fun dismiss() {
        if (!mutex.tryLock()) return
        try {
            withContext(dispatcher) {
                persist(null)
                mutableState.value = RikkaPhoneImportState()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { mutableState.value = mutableState.value.copy(storageWarning = true) }
        finally { mutex.unlock() }
    }

    private fun receipt(id: String, status: RikkaPhoneImportStatus, result: RikkaChatImportResult) =
        RikkaPhoneImportReceipt(id, status, result, now()).also(::validate)

    private fun validate(receipt: RikkaPhoneImportReceipt) {
        require(UUID.fromString(receipt.operationId).toString() == receipt.operationId)
        require(receipt.updatedAt >= 0)
        require(with(receipt.result) { imported >= 0 && skipped >= 0 && attachments >= 0 && missingAttachments >= 0 })
    }

    private fun persist(receipt: RikkaPhoneImportReceipt?) {
        receipt?.let(::validate)
        val raw = json.encodeToString(RikkaPhoneReceiptEnvelope(receipt = receipt))
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        write(raw)
        check(read() == raw) { "Rikka receipt write not confirmed" }
    }

    companion object { const val MAX_BYTES = 8192 }
}
