package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.checkpoint.AndroidGenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.data.sync.importer.RikkaChatImporter
import me.rerere.rikkahub.data.sync.importer.rikkaImportId
import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/** Disk Room + AtomicFile + real managed bytes, with no model/tool execution.
 * Private fixtures require an explicit opt-in on an isolated emulator; they never enter source.
 */
@RunWith(AndroidJUnit4::class)
class InlineImageRecoveryDeviceTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(cache.toPath(), "image-recovery-test-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").also { check(it.isDirectory || it.mkdirs()) }
        }
        private val scope = AppScope()
        var db = open()
        val files get() = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
        val repo get() = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            files, MessageFtsManager(db))
        val store = AndroidGenerationCheckpointStore.createHumanContinuation(root)
        private fun open() = Room.databaseBuilder(context, AppDatabase::class.java,
            File(root, "history.db").absolutePath).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
            }
        }).build()
        fun reopen() { db.close(); db = open() }
        override fun close() {
            scope.cancel(); db.close()
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("image-recovery-test-"))
            root.walkBottomUp().toList().forEach {
                check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(root.toPath()))
                check(it.delete())
            }
        }
    }

    @Test fun oversizedToolImagesRecoverCommitReopenDeleteAndCompact() = runBlocking<Unit> {
        Fixture().use { f ->
            val baseline = Conversation(assistantId = Uuid.random(), messageNodes =
                listOf(UIMessage.user("before image").toMessageNode()))
            val image = UIMessagePart.Image("data:image/png;base64," +
                Base64.getEncoder().encodeToString(ByteArray(700_000) { (it % 251).toByte() }))
            val result = UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Tool("completed", "synthetic_photo", "{}",
                    output = listOf(UIMessagePart.Text("preserve receipt"), image, image)))).toMessageNode()
            val snapshot = baseline.copy(messageNodes = baseline.messageNodes + result +
                List(224) { UIMessage.user("later $it").toMessageNode() })
            f.repo.insertConversation(baseline)
            HumanContinuationJournal(f.store).checkpoint(Uuid.random(), baseline, snapshot)
            verifyRecovery(f, baseline)
        }
    }

    @Test fun explicitlyProvidedArchiveRecoversWithoutChangingMessagesOrImageBytes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("axuProvidedImageRecovery") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") ||
            android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        Fixture().use { f ->
            val input = File(f.instrumentation.targetContext.filesDir, "axu-provided-image-recovery")
            val baseline = JsonInstant.decodeFromString<Conversation>(File(input, "baseline.json").readText())
            f.repo.insertConversation(baseline)
            f.store.writeAtomic(baseline.id, File(input, "journal.json").readBytes())
            // Optional private handoff stays in the operator-provided directory, never in source.
            // Export the native, settled recovery before any test-only delete/compaction.
            if (InstrumentationRegistry.getArguments().getString("axuExportSettledRecovery") == "true") {
                val snapshot = checkNotNull(HumanContinuationJournal(f.store).recover(baseline)).snapshot
                File(input, "settled-recovery.json").writeText(JsonInstant.encodeToString(snapshot))
            }
            val count = verifyRecovery(f, baseline)
            File(input, "result.json").writeText("""{"passed":true,"before_nodes":${baseline.messageNodes.size},"after_nodes":$count,"images_byte_equal":true,"messages_preserved":true,"disk_reopen":true,"delete_compact_rollback":true,"tool_replay":false}""")
        }
    }

    @Test fun repairedAdditiveArchiveUsesExistingImportAndPreservesTheBlockedOriginal() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("axuRepairedChatImport") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        Fixture().use { f ->
            val input = File(f.instrumentation.targetContext.filesDir, "axu-provided-image-recovery")
            val baseline = JsonInstant.decodeFromString<Conversation>(File(input, "baseline.json").readText())
            val expected = JsonInstant.decodeFromString<Conversation>(File(input, "settled-recovery.json").readText())
            val journalBytes = File(input, "journal.json").readBytes()
            val archive = File(input, "repaired-chat.zip")
            f.repo.insertConversation(baseline)
            f.store.writeAtomic(baseline.id, journalBytes)
            val importer = RikkaChatImporter(f.context, f.repo, f.files)
            val preview = importer.inspect(archive)
            check(preview.conversations.size == 1) { "unexpected_window_count" }
            val first = importer.import(archive, baseline.assistantId)
            check(first.imported == 1 && first.skipped == 0 && first.missingAttachments == 2) { "import_failed" }
            val importedId = rikkaImportId("conversation", preview.conversations.single().sourceId)
            val imported = checkNotNull(f.repo.getConversationById(importedId))
            check(imported.id != baseline.id && imported.assistantId == baseline.assistantId)
            check(imported.messageNodes.size == expected.messageNodes.size) { "node_count_changed" }
            var verifiedMedia = 0
            var confirmedMissingMedia = 0
            ZipFile(archive).use { zip ->
                fun nestedMedia(parts: List<UIMessagePart>): List<UIMessagePart> = parts.flatMap {
                    when (it) {
                        is UIMessagePart.Tool -> nestedMedia(it.output)
                        is UIMessagePart.Image, is UIMessagePart.Audio,
                        is UIMessagePart.Video, is UIMessagePart.Document -> listOf(it)
                        else -> emptyList()
                    }
                }
                fun mediaUrl(part: UIMessagePart): String = when (part) {
                    is UIMessagePart.Image -> part.url
                    is UIMessagePart.Audio -> part.url
                    is UIMessagePart.Video -> part.url
                    is UIMessagePart.Document -> part.url
                    else -> error("not_media")
                }
                fun verifyMedia(old: UIMessagePart, next: UIMessagePart) {
                    val source = mediaUrl(old)
                    val entry = if (source.startsWith("data:image/")) null
                        else zip.getEntry("upload/" + Uri.parse(source).lastPathSegment)
                    if (!source.startsWith("data:image/") && entry == null) {
                        check(old is UIMessagePart.Audio && next is UIMessagePart.Text &&
                            next.text == "[原备份未包含可恢复附件]") { "missing_media_not_disclosed" }
                        confirmedMissingMedia++
                        return
                    }
                    check(old.javaClass == next.javaClass) { "media_type_changed" }
                    val bytes = if (source.startsWith("data:image/"))
                        Base64.getDecoder().decode(source.substringAfter("base64,"))
                    else zip.getInputStream(checkNotNull(entry)).use { it.readBytes() }
                    val actual = Uri.parse(mediaUrl(next))
                    check(actual.scheme == "file") { "media_not_local" }
                    check(File(checkNotNull(actual.path)).readBytes().contentEquals(bytes)) { "media_bytes_changed" }
                    verifiedMedia++
                }
                fun verifyToolText(tool: UIMessagePart.Tool, actual: UIMessagePart) {
                    check(actual is UIMessagePart.Text) { "tool_not_inert" }
                    check(actual.text.contains(tool.toolName) && actual.text.contains(tool.input)) { "tool_input_changed" }
                    fun output(parts: List<UIMessagePart>) { parts.forEach {
                        when (it) {
                            is UIMessagePart.Text -> check(actual.text.contains(it.text)) { "tool_output_changed" }
                            is UIMessagePart.Reasoning -> check(actual.text.contains(it.reasoning)) { "tool_reasoning_changed" }
                            else -> Unit
                        }
                    } }
                    output(tool.output)
                }
                expected.messageNodes.zip(imported.messageNodes).forEach { (a, b) ->
                    check(a.selectIndex == b.selectIndex && a.messages.size == b.messages.size) { "branch_changed" }
                    a.messages.zip(b.messages).forEach { (old, next) ->
                        check(old.role == next.role && old.createdAt == next.createdAt) { "message_identity_changed" }
                        var index = 0
                        old.parts.forEach { part ->
                            val mapped = next.parts[index++]
                            when (part) {
                                is UIMessagePart.Text -> check(mapped is UIMessagePart.Text && mapped.text == part.text) { "text_changed" }
                                is UIMessagePart.Reasoning -> check(mapped is UIMessagePart.Reasoning && mapped.reasoning == part.reasoning) { "reasoning_changed" }
                                is UIMessagePart.Tool -> {
                                    verifyToolText(part, mapped)
                                    nestedMedia(part.output).forEach { verifyMedia(it, next.parts[index++]) }
                                }
                                is UIMessagePart.Image, is UIMessagePart.Audio,
                                is UIMessagePart.Video, is UIMessagePart.Document -> verifyMedia(part, mapped)
                                else -> error("unexpected_source_part")
                            }
                        }
                        check(index == next.parts.size) { "unexpected_parts" }
                    }
                }
            }
            check(verifiedMedia == 3 && confirmedMissingMedia == 2)
            check(imported.messageNodes.flatMap { it.messages }.none { it.getTools().isNotEmpty() })
            val duplicate = importer.import(archive, baseline.assistantId)
            check(duplicate.imported == 0 && duplicate.skipped == 1) { "duplicate_import" }
            val appended = imported.copy(messageNodes = imported.messageNodes + UIMessage.user("isolated continuation").toMessageNode())
            f.repo.updateConversation(appended)
            f.reopen()
            check(f.repo.getConversationById(imported.id)?.messageNodes == appended.messageNodes) { "continuation_reopen_failed" }
            val deleted = appended.copy(messageNodes = appended.messageNodes.dropLast(2))
            f.repo.updateConversation(deleted)
            val before = checkNotNull(f.repo.getConversationById(imported.id))
            check(before.messageNodes == deleted.messageNodes) { "imported_delete_blocked" }
            val compactor = OrbisCompactionRepository(f.db, MessageFtsManager(f.db))
            val summary = UIMessage.assistant("isolated repaired-import compaction").toMessageNode()
            val compact = compactor.commit(before, listOf(summary) + before.messageNodes.takeLast(2),
                OrbisCompactionMetadata(summary.currentMessage.id, "isolated repaired-import compaction", 2,
                    350_000, 2_000, "test estimate", "test estimate", retainOriginals = true, preserveBranch = true))
            check(compact.preservedBranchId != null)
            check(compactor.rollbackLatest(compact.conversation, 0, { 1000 }).conversation.messageNodes == before.messageNodes)
            check(f.repo.getConversationById(baseline.id)?.messageNodes == baseline.messageNodes) { "original_chat_changed" }
            check(f.store.read(baseline.id)?.contentEquals(journalBytes) == true) { "original_recovery_changed" }
            File(input, "repair-import-result.json").writeText("""{"passed":true,"nodes":${imported.messageNodes.size},"verified_image_references":$verifiedMedia,"original_missing_audio_references":$confirmedMissingMedia,"original_chat_preserved":true,"original_journal_preserved":true,"duplicate_skipped":true,"append_reopen_delete_compact_rollback":true,"tool_execution":false}""")
        }
    }

    private suspend fun verifyRecovery(f: Fixture, baseline: Conversation): Int {
        val journal = HumanContinuationJournal(f.store)
        val persisted = checkNotNull(f.repo.getConversationById(baseline.id))
        val recovery = checkNotNull(journal.recover(persisted))
        val originals = mutableMapOf<String, ByteArray>()
        fun collect(parts: List<UIMessagePart>) { parts.forEach {
            when (it) {
                is UIMessagePart.Image -> if (it.url.startsWith("data:image/")) {
                    originals[it.url] = Base64.getDecoder().decode(it.url.substringAfter("base64,"))
                }
                is UIMessagePart.Tool -> collect(it.output)
                else -> Unit
            }
        } }
        recovery.snapshot.messageNodes.forEach { n -> n.messages.forEach { collect(it.parts) } }
        check(originals.isNotEmpty())
        val reverse = mutableMapOf<String, String>()
        val localized = localizeUnstoredContinuationImages(recovery, persisted) { parts ->
            val result = f.files.localizeInlineImages(parts)
            fun verify(old: List<UIMessagePart>, next: List<UIMessagePart>) {
                check(old.size == next.size) { "part_count_changed" }
                old.zip(next).forEach { (a, b) ->
                    when {
                        a is UIMessagePart.Image && b is UIMessagePart.Image && a.url.startsWith("data:image/") -> {
                            check(b.url.startsWith("file://")) { "image_not_local" }
                            check(File(checkNotNull(Uri.parse(b.url).path)).readBytes().contentEquals(originals[a.url])) { "image_bytes_changed" }
                            check(a.copy(url = b.url) == b) { "image_metadata_changed" }
                            reverse[b.url] = a.url
                        }
                        a is UIMessagePart.Tool && b is UIMessagePart.Tool -> {
                            check(a.copy(output = b.output) == b) { "tool_receipt_changed" }
                            verify(a.output, b.output)
                        }
                        else -> check(a == b) { "non_image_content_changed" }
                    }
                }
            }
            verify(parts, result); result
        }
        fun restore(parts: List<UIMessagePart>): List<UIMessagePart> = parts.map {
            when (it) {
                is UIMessagePart.Image -> it.copy(url = reverse[it.url] ?: it.url)
                is UIMessagePart.Tool -> it.copy(output = restore(it.output))
                else -> it
            }
        }
        val restored = localized.snapshot.messageNodes.map { node -> node.copy(messages =
            node.messages.map { it.copy(parts = restore(it.parts)) }) }
        check(restored == recovery.snapshot.messageNodes) { "message_tree_changed" }
        journal.checkpoint(localized.runId, localized.baseline, localized.snapshot)
        val saved = checkNotNull(f.repo.commitHumanContinuation(persisted, localized.snapshot))
        check(saved.messageNodes == localized.snapshot.messageNodes) { "room_save_changed" }
        check(journal.clearCommitted(localized.runId, saved))
        f.reopen()
        val reopened = checkNotNull(f.repo.getConversationById(saved.id))
        check(reopened.messageNodes == saved.messageNodes) { "disk_reopen_changed" }
        check(HumanContinuationJournal(f.store).recover(reopened) == null) { "recovery_repeated" }
        reverse.forEach { (url, original) ->
            check(File(checkNotNull(Uri.parse(url).path)).readBytes().contentEquals(originals[original])) { "reopen_image_changed" }
        }
        // Exercise normal history edits after recovery; only the isolated fixture is edited.
        val deleted = reopened.copy(messageNodes = reopened.messageNodes.dropLast(1))
        f.repo.updateConversation(deleted)
        val before = checkNotNull(f.repo.getConversationById(saved.id))
        check(before.messageNodes == deleted.messageNodes) { "delete_blocked" }
        val compactor = OrbisCompactionRepository(f.db, MessageFtsManager(f.db))
        val summary = UIMessage.assistant("isolated compaction check").toMessageNode()
        val compact = compactor.commit(before, listOf(summary) + before.messageNodes.takeLast(2),
            OrbisCompactionMetadata(summary.currentMessage.id, "isolated compaction check", 2,
                350_000, 2_000, "test estimate", "test estimate", retainOriginals = true, preserveBranch = true))
        check(compact.preservedBranchId != null) { "branch_missing" }
        val rolledBack = compactor.rollbackLatest(compact.conversation, 0, { 1000 }).conversation
        check(rolledBack.messageNodes == before.messageNodes) { "compaction_rollback_changed" }
        return reopened.messageNodes.size
    }
}
