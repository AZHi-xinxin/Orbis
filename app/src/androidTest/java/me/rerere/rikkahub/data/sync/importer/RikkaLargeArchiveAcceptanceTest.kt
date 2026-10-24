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
import me.rerere.ai.core.MessageRole
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
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Explicitly opted-in, hash-pinned local archive COPY, only on an isolated emulator.
 * Production text/branch decoding has separate synthetic tests; this checks complete real-file
 * admission, bounded persistence, every projected part, reopen and duplicate accounting.
 * No normal app startup, model calls, source settings, tools or private assertions in output.
 */
@RunWith(AndroidJUnit4::class)
class RikkaLargeArchiveAcceptanceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var importer: RikkaChatImporter
    private lateinit var scope: AppScope
    private var phase = "setup"
    private var successful = false

    private fun openTarget() {
        db = AppDatabaseFactory.create(context)
        scope = AppScope()
        val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
        repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db, files, MessageFtsManager(db))
        importer = RikkaChatImporter(context, repo, files)
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.cancel()
        // Failed isolated DBs are retained for diagnosis. Never remove the operator's input.
        if (successful && ::root.isInitialized) check(root.deleteRecursively())
    }

    @Test fun localLargeArchiveImportsWithoutDroppingPartsAndReopens() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("orbisLargeArchiveCheck") == "true")
        try {
            val runner = InstrumentationRegistry.getInstrumentation()
            check(runner is IsolatedGenerationLoopRunner && Build.HARDWARE in setOf("ranchu", "goldfish"))
            val base = runner.targetContext
            check(base.applicationContext.javaClass == Application::class.java && base.packageName == "org.orbis.agent.dev")
            val expectedHash = args.getString("orbisLargeArchiveSha256") ?: error("hash required")
            check(expectedHash.matches(Regex("[0-9a-f]{64}")))
            val inputDirectory = File(base.cacheDir, "rikka-large-acceptance")
            check(!Files.isSymbolicLink(inputDirectory.toPath()))
            val input = File(inputDirectory, "input.zip")
            check(!Files.isSymbolicLink(input.toPath()) && hash(input) == expectedHash)
            root = Files.createTempDirectory(base.cacheDir.toPath(), "rikka-large-target-").toFile().canonicalFile
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
                override fun getDatabasePath(name: String): File {
                    val file = (if (File(name).isAbsolute) File(name) else File(root, "db/$name")).canonicalFile
                    check(file.toPath().startsWith(root.toPath()))
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    return file
                }
            }
            openTarget()
            val owner = Uuid.random()
            val existing = Conversation(assistantId = owner, title = "synthetic existing", messageNodes = emptyList())
            repo.insertConversation(existing)
            // Room stores header timestamps in milliseconds; compare against the durable
            // pre-import baseline, not Instant.now()'s extra nanoseconds in the constructor.
            val existingStored = checkNotNull(repo.getConversationById(existing.id))
            val marker = File(context.filesDir, "settings-marker").apply { writeText("unchanged") }
            phase = "preview"
            val preview = importer.inspect(input)
            check(preview.fingerprint == expectedHash && repo.countConversations() == 1)
            val sourceNodes = preview.conversations.sumOf { it.totalNodes }
            val sourceMessages = preview.conversations.sumOf { it.messageCount }
            phase = "whole import"
            val first = importer.import(input, owner)
            check(first.imported == preview.conversations.size && first.skipped == 0)
            check(repo.countConversations() == first.imported + 1)

            phase = "complete projected content"
            val staging = File(root, "source-projection").apply { mkdirs() }
            val snapshot = RikkaChatArchive.extract(input, staging)
            DatabaseBackup.normalize(context, snapshot)
            var targetNodes = 0
            var historicalParts = 0
            val durableDigests = mutableMapOf<Uuid, String>()
            RikkaChatSnapshotReader.open(context, snapshot).use { source ->
                for (chat in source.conversations()) {
                    val target = checkNotNull(repo.getConversationById(rikkaImportId("conversation", chat.id)))
                    check(target.assistantId == owner && target.title == chat.title)
                    check(target.createAt.toEpochMilli() == chat.createAt && target.updateAt.toEpochMilli() == chat.updateAt)
                    check(target.customSystemPrompt == null && target.lorebookIds.isEmpty() && target.modeInjectionIds.isEmpty())
                    var position = 0
                    for (node in source.nodes(chat.id)) {
                        val decoded = RikkaChatContentDecoder.decode(node.id, node.messages)
                        if (decoded.isEmpty()) continue
                        val group = mutableListOf(target.messageNodes[position++])
                        check(group[0].id == rikkaImportId("node", "${chat.id}/${node.id}"))
                        while (position < target.messageNodes.size && target.messageNodes[position].id ==
                            rikkaImportId("node-segment", "${chat.id}/${node.id}/${group.size}")) {
                            group += target.messageNodes[position++]
                        }
                        group.forEach { check(MessageNodeBudget.measureNode(it.messages) <= MessageNodeBudget.MAX_NODE_BYTES) }
                        if (group.size > 1) check(decoded.size == 1 && group.all { it.messages.size == 1 && it.selectIndex == 0 })
                        else check(group[0].selectIndex == node.selectIndex && group[0].messages.size == decoded.size)
                        decoded.forEachIndexed { branch, expected ->
                            val actual = if (group.size == 1) listOf(group[0].messages[branch]) else group.map { it.messages.single() }
                            check(actual[0].id == rikkaImportId("message", "${chat.id}/${expected.id}"))
                            check(actual.all { it.role == (if (expected.role == MessageRole.SYSTEM) MessageRole.ASSISTANT else expected.role) &&
                                it.createdAt == expected.createdAt && it.finishedAt == expected.finishedAt && it.getTools().isEmpty() })
                            val expectedParts = expected.parts.map { part ->
                                val url = when (part) {
                                    is UIMessagePart.Image -> part.url
                                    is UIMessagePart.Audio -> part.url
                                    is UIMessagePart.Video -> part.url
                                    is UIMessagePart.Document -> part.url
                                    else -> null
                                }
                                if (url == null) part else {
                                    check(RikkaChatArchive.uploadName(url)?.let { File(staging, "upload/$it").isFile } != true)
                                    UIMessagePart.Text("[原备份未包含可恢复附件]", part.metadata)
                                }
                            }
                            check(actual.flatMap { it.parts } == expectedParts)
                            historicalParts += expectedParts.size
                        }
                    }
                    check(position == target.messageNodes.size)
                    targetNodes += position
                    durableDigests[target.id] = digest(JsonInstant.encodeToString(Conversation.serializer(), target).toByteArray())
                }
            }
            check(repo.getConversationById(existing.id) == existingStored && marker.readText() == "unchanged")
            phase = "reopen database"
            db.close(); scope.cancel(); openTarget()
            for ((id, expected) in durableDigests) {
                val restored = checkNotNull(repo.getConversationById(id))
                check(digest(JsonInstant.encodeToString(Conversation.serializer(), restored).toByteArray()) == expected)
            }
            phase = "duplicate import"
            val second = importer.import(input, owner)
            check(second.imported == 0 && second.skipped == first.imported && repo.countConversations() == first.imported + 1)
            check(marker.readText() == "unchanged" && repo.getConversationById(existing.id) == existingStored && hash(input) == expectedHash)
            runner.sendStatus(0, Bundle().apply { putString("stream", "\nLarge archive verified: windows=${first.imported}, " +
                "sourceNodes=$sourceNodes, sourceMessages=$sourceMessages, targetNodes=$targetNodes, projectedParts=$historicalParts, " +
                "missingAttachments=${first.missingAttachments}, duplicateAdded=${second.imported}, reopen=passed\n") })
            successful = true
        } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull { it.fileName == "RikkaLargeArchiveAcceptanceTest.kt" }?.lineNumber
            throw AssertionError("Large archive failed at $phase (${failure.javaClass.simpleName}, check line=$line); private details suppressed")
        }
    }

    private fun hash(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(65536)
            while (true) { val count = source.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
