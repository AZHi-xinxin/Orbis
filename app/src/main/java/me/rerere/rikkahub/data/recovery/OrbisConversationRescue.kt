package me.rerere.rikkahub.data.recovery

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.checkpoint.AndroidGenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.encodeMessageNodeMessages
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

data class RescueWindow(val id: Uuid, val title: String)
data class RescueInspection internal constructor(
    val conversationId: Uuid,
    val nodeCount: Int,
    val damagedCount: Int,
    val repairAvailable: Boolean,
    val message: String,
    val copy: File,
    internal val fingerprint: String,
    internal val journalHash: String?,
    internal val target: String?,
    internal val replacement: String?,
)

/** No model, network, tool execution, settings writes or broad history saves. All artifacts stay
 * in private no-backup storage until the human explicitly exports them via the document picker. */
class OrbisConversationRescue(
    context: Context,
    private val db: AppDatabase,
    private val conversations: ConversationRepository,
    private val heapHeadroom: () -> Long = RescueAdmission::currentHeapHeadroom,
) {
    private val directory = File(context.noBackupFilesDir, "orbis-conversation-rescue-v1")
    private val store = AndroidGenerationCheckpointStore.create(context.noBackupFilesDir)
    private val journal = GenerationCheckpointJournal(store)
    private val checkpointDirectory = File(context.noBackupFilesDir, "orbis-generation-journal-v1")

    suspend fun windows(query: String = "", offset: Int = 0, limit: Int = 50): List<RescueWindow> = withContext(Dispatchers.IO) {
        require(query.length <= 200 && offset >= 0 && limit in 1..100)
        db.conversationDao().getRescueWindows(query, limit, offset).mapNotNull { row ->
            runCatching { RescueWindow(Uuid.parse(row.id), row.title) }.getOrNull()
        }
    }

    private data class Snapshot(val header: ConversationEntity, val rows: List<MessageNodeEntity>)
    private suspend fun snapshot(id: Uuid): Snapshot {
        // Callers hold the same Room transaction for admission and reading, including confirmation.
        // COUNT/byte lengths do not decode JSON or put the raw cells into a CursorWindow.
        val headerBytes = checkNotNull(db.conversationDao().getRescueHeaderBytes(id.toString()))
        val raw = db.messageNodeDao().getRescueRawSize(id.toString())
        val checkpointBytes = listOf(File(checkpointDirectory, "$id.json"), File(checkpointDirectory, "$id.json.bak"))
            .maxOf { it.length() }
        RescueAdmission.requireSafe(raw.nodeCount, raw.totalBytes, raw.largestRowBytes,
            headerBytes, checkpointBytes, heapHeadroom())
        val header = checkNotNull(db.conversationDao().getConversationById(id.toString()))
        check(header.consultationBinding.isEmpty()) { "consultation_out_of_scope" }
        // Raw strings: a bad message JSON cannot block this entry or silently disappear.
        val rows = mutableListOf<MessageNodeEntity>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = db.messageNodeDao().getNodesOfConversationPaged(id.toString(), 32, offset)
            if (page.isEmpty()) break
            rows += page
            offset += page.size
        }
        return Snapshot(header, rows)
    }

    suspend fun inspect(id: Uuid): RescueInspection = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = snapshot(id)
            val bytes = try { store.read(id) } catch (_: Exception) { null }
            // Copy the ORIGINAL raw cells first, even if JSON decoding subsequently fails.
            val copy = preserve(current, bytes, "before")
            diagnose(current, bytes, copy)
        }
    }

    private fun diagnose(current: Snapshot, checkpoint: ByteArray?, copy: File): RescueInspection {
        val id = Uuid.parse(current.header.id)
        val damaged = mutableListOf<MessageNodeEntity>()
        val nodes = current.rows.map { row ->
            val decoded = runCatching {
                val messages = JsonInstant.decodeFromString<List<UIMessage>>(row.messages)
                check(messages.isNotEmpty() && row.selectIndex in messages.indices &&
                    messages.map { it.id }.distinct().size == messages.size)
                MessageNode(Uuid.parse(row.id), messages, row.selectIndex)
            }.getOrNull()
            if (decoded == null) damaged += row
            decoded
        }
        var candidate: String? = null
        if (damaged.size == 1 && checkpoint != null && current.rows.map { it.id }.distinct().size == current.rows.size &&
            current.rows.withIndex().all { (index, row) -> row.nodeIndex == index && row.conversationId == current.header.id }) {
            candidate = runCatching {
                val broken = damaged.single()
                // Refuse semantic/schema/branch errors; only a syntactically broken cell qualifies.
                check(runCatching { JsonInstant.parseToJsonElement(broken.messages) }.isFailure)
                val placeholder = MessageNode(Uuid.parse(broken.id), emptyList(), broken.selectIndex)
                // The normal mapper intentionally filters empty nodes. Restore this private
                // placeholder AFTER mapping so indexes/hashes cannot shift during diagnosis.
                val base = conversations.conversationEntityToConversation(current.header, emptyList())
                    .copy(messageNodes = nodes.map { it ?: placeholder })
                val restored = journal.previewDamagedNode(base, placeholder.id)
                val encoded = encodeMessageNodeMessages(restored.messages)
                check(isKnownQuoteEncodingDamage(broken.messages, encoded))
                encoded
            }.getOrNull()
        }
        val readable = damaged.isEmpty() && runCatching {
            conversations.conversationEntityToConversation(current.header, nodes.filterNotNull())
        }.isSuccess
        return RescueInspection(id, current.rows.size, damaged.size, candidate != null,
            when {
                candidate != null -> "找到与当前窗口、历史分支和已完成工具记录一致的完整副本。可只修复 1 条编码损坏的消息；不会回退其他聊天。"
                readable -> "消息与窗口信息可完整读取，未发现可由此入口修复的格式损坏。如仍打不开，请导出诊断副本；队列暂停与网络报错不是聊天被删除。"
                else -> "发现无法完整读取的记录，但没有足够证据安全修复。原记录未改动；请导出诊断副本并寻求协助，不要清除数据或卸载。"
            }, copy, fingerprint(current), checkpoint?.let(::sha), damaged.singleOrNull()?.id, candidate)
    }

    /** Caller MUST hold ChatService.withEmergencyRecoveryLock. Revalidate ALL current rows and
     * checkpoint at confirmation, then update one column and fully decode before commit. */
    suspend fun repair(preview: RescueInspection): File = withContext(Dispatchers.IO) {
        check(preview.repairAvailable && preview.target != null && preview.replacement != null)
        db.withTransaction {
            val before = snapshot(preview.conversationId)
            check(fingerprint(before) == preview.fingerprint) { "rescue_snapshot_changed" }
            val checkpoint = store.read(preview.conversationId)
            check(checkpoint?.let(::sha) == preview.journalHash) { "rescue_journal_changed" }
            val preserved = preserve(before, checkpoint, "confirmed-before")
            val fresh = diagnose(before, checkpoint, preserved)
            check(fresh.repairAvailable && fresh.target == preview.target && fresh.replacement == preview.replacement)
            val target = before.rows.single { it.id == preview.target }
            currentCoroutineContext().ensureActive()
            check(db.messageNodeDao().updateMessagesIfUnchanged(target.conversationId, target.id,
                target.messages, preview.replacement) == 1) { "rescue_compare_failed" }
            val after = snapshot(preview.conversationId)
            check(after.header == before.header && after.rows == before.rows.map {
                if (it.id == target.id) it.copy(messages = preview.replacement) else it
            }) { "rescue_readback_failed" }
            checkNotNull(conversations.getConversationById(preview.conversationId))
            check(store.read(preview.conversationId)?.let(::sha) == preview.journalHash)
            // A failed copy/readback/cancellation rolls the database transaction back. The
            // checkpoint is intentionally retained; opening the page settles it without replay.
            val result = preserve(after, checkpoint, "after-verified")
            currentCoroutineContext().ensureActive()
            result
        }
    }

    private fun headerJson(h: ConversationEntity) = buildJsonObject {
        put("id", h.id); put("assistantId", h.assistantId); put("title", h.title)
        put("nodes", h.nodes); put("createAt", h.createAt); put("updateAt", h.updateAt)
        put("chatSuggestions", h.chatSuggestions); put("isPinned", h.isPinned)
        put("customSystemPrompt", h.customSystemPrompt); put("orbisPrompt", h.orbisPrompt)
        put("modeInjectionIds", h.modeInjectionIds); put("lorebookIds", h.lorebookIds)
        put("workspaceCwd", h.workspaceCwd); put("folderId", h.folderId)
        put("compactionEpoch", h.compactionEpoch); put("consultationBinding", h.consultationBinding)
    }
    private fun rowJson(r: MessageNodeEntity) = buildJsonObject {
        put("id", r.id); put("conversationId", r.conversationId); put("nodeIndex", r.nodeIndex)
        put("selectIndex", r.selectIndex); put("rawMessages", r.messages)
    }
    private fun fingerprint(snapshot: Snapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: JsonObject) { digest.update(exactRescueJsonBytes(value.toString())); digest.update(0.toByte()) }
        add(headerJson(snapshot.header)); snapshot.rows.forEach { add(rowJson(it)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun preserve(snapshot: Snapshot, checkpoint: ByteArray?, stage: String): File {
        check(directory.isDirectory || directory.mkdirs())
        syncDirectory(checkNotNull(directory.parentFile))
        val file = File(directory, "${System.currentTimeMillis()}-${Uuid.random()}-$stage.zip")
        check(file.createNewFile())
        try {
            FileOutputStream(file).use { output ->
                val zip = ZipOutputStream(output)
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                entry("conversation.json", exactRescueJsonBytes(headerJson(snapshot.header).toString()))
                zip.putNextEntry(ZipEntry("raw-nodes.jsonl"))
                snapshot.rows.forEach { zip.write(exactRescueJsonBytes(rowJson(it).toString())); zip.write(10) }
                zip.closeEntry()
                checkpoint?.let { entry("generation-checkpoint.json", it) }
                entry("README.txt", ("Orbis 私密诊断副本 / $stage\n包含此窗口的聊天、思考和工具记录，可能包含敏感信息。" +
                    "不是一键整库恢复包，不要公开上传。原始字段按原样保存。\nSHA256: ${fingerprint(snapshot)}\n").toByteArray())
                zip.finish(); zip.flush(); output.fd.sync()
            }
            // CRC verification catches incomplete/private-storage writes before any repair.
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().forEach { item ->
                    val crc = java.util.zip.CRC32()
                    zip.getInputStream(item).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
                    }
                    check(crc.value == item.crc)
                }
            }
            syncDirectory(directory)
            return file
        } catch (e: Exception) {
            // Preserve even a partial file for support. Never delete the only original copy.
            throw IllegalStateException("诊断副本未能完整保存，未执行修复。请检查本机剩余空间。")
        }
    }
    private fun syncDirectory(folder: File) {
        val descriptor = Os.open(folder.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
