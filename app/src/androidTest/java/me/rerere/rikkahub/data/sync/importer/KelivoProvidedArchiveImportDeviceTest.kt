package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.MessageNodeBudget
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Hash-pinned private copy, opt-in on the isolated bodylab emulator only. Never real app data,
 * settings, prompts, source credentials, model calls or content in assertions/log output.
 */
@RunWith(AndroidJUnit4::class)
class KelivoProvidedArchiveImportDeviceTest {
    @Test fun wholeArchivePersistsReopensAndDeduplicatesWithoutChangingExistingChat() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("kelivoProvidedImport") == "true")
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is IsolatedGenerationLoopRunner && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = runner.targetContext
        check(base.packageName == "org.orbis.agent.bodylab" && base.applicationContext.javaClass == Application::class.java)
        val name = args.getString("kelivoProvidedArchiveName") ?: error("missing basename")
        val expectedHash = args.getString("kelivoProvidedArchiveSha256") ?: error("missing checksum")
        check(Regex("kelivo-provided-import-[0-9a-f]{16}\\.zip").matches(name))
        check(Regex("[0-9a-f]{64}").matches(expectedHash))
        val input = File(base.cacheDir, name).canonicalFile
        check(input.parentFile == base.cacheDir.canonicalFile && !Files.isSymbolicLink(input.toPath()))
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "kelivo-private-target-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File {
                val file = (if (File(name).isAbsolute) File(name) else File(root, "db/$name")).canonicalFile
                check(file.toPath().startsWith(root.canonicalFile.toPath()))
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                return file
            }
        }
        var db: AppDatabase? = null
        var scope: AppScope? = null
        var phase = "checksum"
        fun openRepository(): ConversationRepository {
            val target = AppDatabaseFactory.create(context).also { db = it }
            val appScope = AppScope().also { scope = it }
            val files = FilesManager(context, FilesRepository(target.managedFileDao()), appScope)
            return ConversationRepository(target.conversationDao(), target.messageNodeDao(), target.favoriteDao(),
                target, files, MessageFtsManager(target))
        }
        try {
            check(KelivoChatArchive.fingerprint(input) == expectedHash)
            var repo = openRepository()
            val owner = Uuid.random()
            val existing = Conversation(assistantId = owner, title = "synthetic existing", messageNodes = emptyList())
            repo.insertConversation(existing)
            val baseline = checkNotNull(repo.getConversationById(existing.id))
            val marker = File(context.filesDir, "settings-marker").apply { writeText("unchanged") }
            phase = "preview"
            var importer = KelivoChatImporter(context, repo)
            val preview = importer.inspect(input)
            check(preview.fingerprint == expectedHash && repo.countConversations() == 1)
            val selected = preview.conversations.map { it.sourceId }.toSet()
            phase = "import"
            val result = importer.importSelected(input, owner, selected, expectedHash)
            check(result.imported == selected.size && result.skipped == 0 && result.failed == 0)
            check(result.messages == preview.conversations.sumOf { it.messageCount })
            check(repo.countConversations() == selected.size + 1)
            phase = "projected content verification"
            val digests = mutableMapOf<Uuid, String>()
            var parts = 0
            var largestNode = 0
            val sourceDir = File(root, "source").apply { mkdirs() }
            val snapshot = KelivoChatArchive.extract(input, sourceDir)
            KelivoChatSnapshotReader.open(snapshot.file, snapshot.conversationCount, snapshot.messageCount).use { source ->
                for (chat in source.conversations()) {
                    val expected = convertKelivoChat(source, chat, owner).conversation
                    val actual = checkNotNull(repo.getConversationById(expected.id))
                    check(actual.assistantId == owner && actual.title == expected.title)
                    check(actual.createAt.toEpochMilli() == expected.createAt.toEpochMilli())
                    check(actual.updateAt.toEpochMilli() == expected.updateAt.toEpochMilli())
                    check(actual.messageNodes == expected.messageNodes)
                    check(actual.customSystemPrompt == null && actual.lorebookIds.isEmpty() && actual.workspaceCwd == null)
                    for (node in actual.messageNodes) {
                        largestNode = maxOf(largestNode, MessageNodeBudget.measureNode(node.messages))
                        for (message in node.messages) {
                            check(message.getTools().isEmpty() && message.parts.all { it is UIMessagePart.Text })
                            parts += message.parts.size
                        }
                    }
                    digests[actual.id] = digest(JsonInstant.encodeToString(Conversation.serializer(), actual))
                }
            }
            check(repo.getConversationById(existing.id) == baseline && marker.readText() == "unchanged")
            phase = "reopen"
            db!!.close(); scope!!.cancel()
            repo = openRepository()
            for ((id, hash) in digests) {
                val actual = checkNotNull(repo.getConversationById(id))
                check(digest(JsonInstant.encodeToString(Conversation.serializer(), actual)) == hash)
            }
            phase = "deduplication"
            importer = KelivoChatImporter(context, repo)
            val again = importer.importSelected(input, owner, selected, expectedHash)
            check(again.imported == 0 && again.skipped == selected.size && repo.countConversations() == selected.size + 1)
            check(repo.getConversationById(existing.id) == baseline && marker.readText() == "unchanged")
            check(KelivoChatArchive.fingerprint(input) == expectedHash)
            runner.sendStatus(0, Bundle().apply {
                putString("kelivo_private_import", "passed")
                putInt("conversations", result.imported)
                putInt("physical_messages", preview.conversations.sumOf { it.totalNodes })
                putInt("selected_messages", result.messages)
                putInt("projected_parts", parts)
                putInt("largest_persisted_node_bytes", largestNode)
                putInt("omitted_attachment_references", preview.omittedAttachmentReferences)
                putInt("duplicate_added", again.imported)
                putString("reopen_and_existing_chat", "passed")
            })
        } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull { it.fileName == "KelivoProvidedArchiveImportDeviceTest.kt" }?.lineNumber
            throw AssertionError("kelivo_private_import_failed: phase=$phase, line=$line; private details suppressed")
        } finally {
            db?.close(); scope?.cancel()
            check(root.deleteRecursively() && input.delete()) { "kelivo_private_cleanup_failed" }
        }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
