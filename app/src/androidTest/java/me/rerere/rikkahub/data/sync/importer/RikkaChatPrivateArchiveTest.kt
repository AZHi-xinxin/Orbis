package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Opt-in acceptance of the operator-provided COPY at one fixed cache path.
 * No RouteActivity, Koin, SettingsStore, real database, network, model or tool runner.
 * Never log source titles/IDs/messages/paths/tool arguments, including on assertion failure.
 */
@RunWith(AndroidJUnit4::class)
class RikkaChatPrivateArchiveTest {
    private lateinit var root: File
    private lateinit var baseCache: File
    private lateinit var context: Context
    private lateinit var sourceZip: File
    private lateinit var sourceHash: ByteArray
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var importer: RikkaChatImporter
    private lateinit var scope: AppScope
    private val targetAssistant = Uuid.random()
    private val existing = Conversation(
        id = Uuid.random(), assistantId = targetAssistant, title = "existing-marker-chat",
        messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("synthetic-existing-marker")))),
        createAt = Instant.ofEpochMilli(1000), updateAt = Instant.ofEpochMilli(2000),
    )
    private val configMarker = "synthetic-target-config-and-prompt-not-migrated".toByteArray()
    private var phase = "setup"

    @Before fun setup() = runBlocking {
        // The default suite must not even inspect the fixed input path.
        assumeTrue("Private archive acceptance requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("orbisPrivateImportCheck") == "true")
        privateCheck {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner) { "isolated runner required" }
            val base = instrumentation.targetContext
            check(base.applicationContext.javaClass == Application::class.java) { "plain application required" }
            check(base.packageName == "org.orbis.agent.dev") { "isolated target required" }
            baseCache = base.cacheDir.canonicalFile
            val inputDirectory = File(baseCache, "orbis-import-acceptance")
            check(inputDirectory.canonicalFile.parentFile == baseCache) { "fixed input directory required" }
            sourceZip = File(inputDirectory, "input.zip")
            check(!Files.isSymbolicLink(sourceZip.toPath())) { "input must not be a symbolic link" }
            check(sourceZip.isFile && sourceZip.canonicalFile.parentFile == inputDirectory.canonicalFile) {
                "fixed input copy missing"
            }
            sourceHash = digest(sourceZip)
            root = Files.createTempDirectory(baseCache.toPath(), "orbis-private-chat-test-").toFile().canonicalFile
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = ownedDirectory("files")
                override fun getCacheDir(): File = ownedDirectory("cache")
                override fun getDatabasePath(name: String): File {
                    val file = if (File(name).isAbsolute) File(name) else File(root, "db/$name")
                    check(isOwned(file)) { "database outside isolated directory" }
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    return file
                }
            }
            database = AppDatabaseFactory.create(context)
            scope = AppScope()
            val files = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
            repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
                database.favoriteDao(), database, files, MessageFtsManager(database))
            importer = RikkaChatImporter(context, repository, files)
            repository.insertConversation(existing)
            // A fake target settings file, never opened by a settings store, proves no config overwrite.
            val marker = File(context.filesDir, "datastore/settings.preferences_pb")
            check(marker.parentFile!!.mkdirs())
            marker.writeBytes(configMarker)
        }
    }

    @After fun cleanup() {
        var failed = false
        if (::database.isInitialized) runCatching { database.close() }.onFailure { failed = true }
        if (::scope.isInitialized) scope.cancel()
        if (::sourceHash.isInitialized) runCatching {
            check(sourceHash.contentEquals(digest(sourceZip))) { "input copy changed" }
        }.onFailure { failed = true }
        if (::root.isInitialized) runCatching {
            check(root.parentFile == baseCache && root.name.startsWith("orbis-private-chat-test-"))
            check(root != sourceZip.parentFile.canonicalFile)
            // Validate the exact created tree before deleting; never touch the operator's input copy.
            root.walkTopDown().forEach { check(isOwned(it) && !Files.isSymbolicLink(it.toPath())) }
            check(root.deleteRecursively())
        }.onFailure { failed = true }
        assertTrue("Private acceptance cleanup or input-integrity check failed; no broader cleanup attempted", !failed)
    }

    @Test fun privateV25ArchiveAppendsOnceAndPreservesHistoryWithoutMigratingConfiguration() = runBlocking {
        privateCheck {
            phase = "chat-only snapshot"
            val staging = ownedDirectory("expected-snapshot")
            val snapshot = RikkaChatArchive.extract(sourceZip, staging)
            // Extraction is allowlisted. No settings, assistant or credentials entry is opened.
            check(staging.listFiles().orEmpty().all {
                it.name in setOf("rikka_hub.db", "rikka_hub.db-wal", "upload")
            })
            DatabaseBackup.normalize(context, snapshot)
            val configuration = SQLiteConfiguration.configure(context,
                SQLiteDatabaseConfiguration(snapshot.absolutePath, SQLiteDatabase.OPEN_READONLY))
            SQLiteDatabase.openDatabase(configuration, null) { error("private snapshot unavailable") }.use { raw ->
                raw.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst()); assertEquals(26, cursor.getInt(0))
                }
                raw.query("SELECT identity_hash FROM room_master_table WHERE id=42").use { cursor ->
                    check(cursor.moveToFirst())
                    assertTrue("Source schema identity differs from the accepted fixture",
                        cursor.getString(0) == "049f05fd92fc292b92c652743f056693")
                }
            }
            val expected = RikkaChatSnapshotReader.open(context, snapshot).use { reader ->
                reader.conversations().map { chat ->
                    ExpectedChat(chat, reader.nodes(chat.id).mapNotNull { node ->
                        val messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
                        if (messages.isEmpty()) null else ExpectedNode(node, messages)
                    })
                }
            }
            val nodeCount = expected.sumOf { it.nodes.size }
            val uploadCount = File(staging, "upload").listFiles().orEmpty().count { it.isFile }
            assertEquals(1, expected.size)
            assertEquals(2, nodeCount)
            assertEquals(3, uploadCount)
            assertTrue("Fixture role projection differs", expected.flatMap { it.nodes }.flatMap { it.messages }
                .map { it.role } == listOf(MessageRole.USER, MessageRole.ASSISTANT))

            phase = "first import"
            val first = importer.import(sourceZip, targetAssistant)
            assertEquals(expected.size, first.imported)
            assertEquals(0, first.skipped)
            val restoredUploads = mutableMapOf<String, String>()
            var missingReferences = 0
            expected.forEach { source ->
                val imported = repository.getConversationById(rikkaImportId("conversation", source.chat.id))
                check(imported != null) { "imported conversation missing" }
                assertTrue("Target assistant not retained", imported.assistantId == targetAssistant)
                assertTrue("Source title not preserved", imported.title == source.chat.title)
                assertEquals(source.chat.createAt, imported.createAt.toEpochMilli())
                assertEquals(source.chat.updateAt, imported.updateAt.toEpochMilli())
                assertTrue("Source configuration was migrated", imported.customSystemPrompt == null &&
                    imported.orbisPrompt == Conversation(assistantId = targetAssistant, messageNodes = emptyList()).orbisPrompt &&
                    imported.modeInjectionIds.isEmpty() && imported.lorebookIds.isEmpty() &&
                    imported.workspaceCwd == null && imported.folderId == null && imported.chatSuggestions.isEmpty())
                assertEquals(source.nodes.size, imported.messageNodes.size)
                source.nodes.zip(imported.messageNodes).forEach { (oldNode, newNode) ->
                    assertTrue("Node identity changed unexpectedly",
                        newNode.id == rikkaImportId("node", "${source.chat.id}/${oldNode.node.id}"))
                    assertEquals(oldNode.node.selectIndex, newNode.selectIndex)
                    assertEquals(oldNode.messages.size, newNode.messages.size)
                    oldNode.messages.zip(newNode.messages).forEach { (oldMessage, newMessage) ->
                        val system = oldMessage.role == MessageRole.SYSTEM
                        val mappedParts = if (system) listOf(UIMessagePart.Text(SYSTEM_PLACEHOLDER)) else {
                            assertEquals(oldMessage.parts.size, newMessage.parts.size)
                            oldMessage.parts.zip(newMessage.parts).map { (oldPart, newPart) ->
                                verifiedPart(oldPart, newPart, source.chat.id, staging, restoredUploads) { missingReferences++ }
                            }
                        }
                        val expectedMessage = oldMessage.copy(
                            id = rikkaImportId("message", "${source.chat.id}/${oldMessage.id}"),
                            role = if (system) MessageRole.ASSISTANT else oldMessage.role,
                            parts = mappedParts,
                        )
                        // Never use assertEquals on a message: it would print private data on failure.
                        assertTrue("Message role, text, branch or part metadata changed unexpectedly", expectedMessage == newMessage)
                    }
                }
            }
            assertEquals(restoredUploads.size, first.attachments)
            assertEquals(missingReferences, first.missingAttachments)
            assertEquals(restoredUploads.size.toLong(), managedFileCount())
            assertEquals(1 + expected.size, repository.countConversations())
            assertTargetUntouched()
            val firstFiles = fileDigests()
            val firstRows = managedFileCount()

            phase = "duplicate import"
            val second = importer.import(sourceZip, targetAssistant)
            assertEquals(0, second.imported)
            assertEquals(expected.size, second.skipped)
            assertEquals(0, second.attachments)
            assertEquals(0, second.missingAttachments)
            assertEquals(1 + expected.size, repository.countConversations())
            assertEquals(firstRows, managedFileCount())
            assertTrue("Duplicate import changed files", firstFiles == fileDigests())
            assertTargetUntouched()
            assertTrue("Input ZIP changed", sourceHash.contentEquals(digest(sourceZip)))

            // The sole report contains counts, never body text, identifiers, filenames or credentials.
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nPrivate chat import: windows=${first.imported}, nodes=$nodeCount, " +
                    "archiveUploads=$uploadCount, restoredAttachments=${first.attachments}, " +
                    "missingAttachments=${first.missingAttachments}, duplicateAdded=${second.imported}\n")
            })
        }
    }

    private data class ExpectedNode(val node: RikkaChatSnapshotReader.Node, val messages: List<UIMessage>)
    private data class ExpectedChat(val chat: RikkaChatSnapshotReader.Chat, val nodes: List<ExpectedNode>)

    @Suppress("DEPRECATION")
    private fun verifiedPart(source: UIMessagePart, target: UIMessagePart, chatId: String, staging: File,
        uploads: MutableMap<String, String>, missing: () -> Unit): UIMessagePart {
        if (source is UIMessagePart.ToolCall) return UIMessagePart.Text("[历史工具调用 ${source.toolName}；未执行]")
        if (source is UIMessagePart.ToolResult) return UIMessagePart.Text("[历史工具结果 ${source.toolName}]\n${source.content}")
        if (source is UIMessagePart.Tool) {
            check(target is UIMessagePart.Tool) { "historical tool shape changed" }
            assertTrue("Historical tool retained approval", target.approvalState is ToolApprovalState.Denied)
            assertTrue("Historical tool remained executable", !target.canResumeExecution)
            val output = if (source.output.isEmpty()) listOf(UIMessagePart.Text("[导入的历史工具调用；未执行]")) else {
                assertEquals(source.output.size, target.output.size)
                source.output.zip(target.output).map { (old, new) -> verifiedPart(old, new, chatId, staging, uploads, missing) }
            }
            return source.copy(output = output, approvalState = ToolApprovalState.Denied("历史导入不携带工具授权"))
        }
        val oldUrl = mediaUrl(source) ?: return source
        if (oldUrl.startsWith("https://") || oldUrl.startsWith("http://")) return source
        val name = RikkaChatArchive.uploadName(oldUrl)
        val oldFile = name?.let { File(staging, "upload/$it") }?.takeIf { it.isFile }
        if (oldFile == null) {
            missing()
            return UIMessagePart.Text("[原备份未包含可恢复附件]")
        }
        val newUrl = mediaUrl(target)
        check(newUrl != null) { "restored attachment has no URL" }
        val uri = URI(newUrl)
        check(uri.scheme == "file" && uri.authority.isNullOrEmpty()) { "attachment not local" }
        val newFile = File(uri)
        check(isOwned(newFile) && newFile.canonicalFile.parentFile == File(context.filesDir, "upload").canonicalFile)
        assertTrue("Attachment bytes changed", digest(oldFile).contentEquals(digest(newFile)))
        val key = "$chatId/$name"
        val prior = uploads.putIfAbsent(key, newUrl)
        assertTrue("Repeated attachment reference was split", prior == null || prior == newUrl)
        return when (source) {
            is UIMessagePart.Image -> source.copy(url = newUrl)
            is UIMessagePart.Audio -> source.copy(url = newUrl)
            is UIMessagePart.Video -> source.copy(url = newUrl)
            is UIMessagePart.Document -> source.copy(url = newUrl)
            else -> error("unexpected attachment kind")
        }
    }

    private fun mediaUrl(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Document -> part.url
        else -> null
    }

    private suspend fun assertTargetUntouched() {
        assertTrue("Existing synthetic conversation changed", repository.getConversationById(existing.id) == existing)
        assertTrue("Target config marker changed", File(context.filesDir, "datastore/settings.preferences_pb")
            .readBytes().contentEquals(configMarker))
    }

    private fun managedFileCount(): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM managed_files").use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun fileDigests(): Map<String, List<Byte>> = context.filesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(context.filesDir).path to digest(it).toList() }

    private fun ownedDirectory(name: String): File = File(root, name).also {
        check(isOwned(it)); check(it.isDirectory || it.mkdirs())
    }

    private fun isOwned(file: File): Boolean = file.canonicalPath == root.path ||
        file.canonicalPath.startsWith(root.path + File.separator)

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }

    private suspend fun privateCheck(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            // Suppress causes/messages: decoders and database exceptions can embed private input.
            throw AssertionError("Private archive acceptance failed during $phase (${failure.javaClass.simpleName}); private details suppressed")
        }
    }

    private companion object {
        const val SYSTEM_PLACEHOLDER = "[此处为原备份的系统设定；仅聊天导入未包含其正文]"
    }
}
