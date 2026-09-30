package me.rerere.rikkahub.web

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.importer.DeepSeekArchive
import me.rerere.rikkahub.data.sync.importer.deepSeekImportId
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.WebImportCommitRequest
import me.rerere.rikkahub.web.dto.WebImportSelection
import me.rerere.rikkahub.web.dto.WebImportStatusDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * Opt-in device tests for the actual staging/preview/import manager, not an HTTP server.
 * Only synthetic ZIPs and an in-memory Room database are used. No Koin, settings, network,
 * service, broadcast, real conversation, or application-owned persistent store is accessed.
 */
@RunWith(AndroidJUnit4::class)
class WebDeepSeekImportsTest {
    private companion object {
        const val OWNER = "synthetic-owner"
        const val OTHER_OWNER = "synthetic-other-owner"
        const val PREFIX = "deepseek-web-synthetic-"
        const val STAMP = "2025-01-02T03:04:05.123456+00:00"
        const val FIRST_SOURCE = "synthetic-web-first"
        const val SECOND_SOURCE = "synthetic-web-second"
        const val DEADLINE_MS = 10_000L
    }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner) { "Isolated runner required" }
            check(it.targetContext.applicationContext.javaClass == Application::class.java) { "Plain Application required" }
            check(!LcExternalRecoveryGate.isAllowed()) { "External recovery must be disabled" }
        }
        private val testCache = instrumentation.targetContext.cacheDir.canonicalFile.also {
            check(it.isDirectory || it.mkdirs())
            check(it.isDirectory && it.canWrite())
        }
        val directory = Files.createTempDirectory(testCache.toPath(), PREFIX).toFile().also {
            check(it.canonicalFile == it && it.parentFile == testCache && it.name.startsWith(PREFIX) &&
                !Files.isSymbolicLink(it.toPath())) { "Fixture must be a direct, non-linked random cache child" }
            check(it.isDirectory && it.canWrite())
        }
        private val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(directory, "no_backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = throw AssertionError("Persistent databases forbidden")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw AssertionError("Preferences forbidden")
            override fun startService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun startForegroundService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
                throw AssertionError("Services forbidden")
            override fun sendBroadcast(intent: Intent) { throw AssertionError("Broadcasts forbidden") }
            override fun sendBroadcast(intent: Intent, receiverPermission: String?) { throw AssertionError("Broadcasts forbidden") }
            override fun startActivity(intent: Intent) { throw AssertionError("Activities forbidden") }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
                }
            }).build()
        private val filesScope = AppScope()
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database,
            FilesManager(context, FilesRepository(database.managedFileDao()), filesScope), MessageFtsManager(database))
        val importParentJob = SupervisorJob()
        private val importScope = CoroutineScope(importParentJob + Dispatchers.IO)
        // The parent is our random fixture, never the production orbis-web-imports directory.
        val staging = File(directory, "isolated-staging")
        val imports = WebDeepSeekImports(staging, repository, importScope, dispatcher)
        val assistantId = Uuid.random()
        fun stagedFile(id: String) = File(staging, "$id.zip")

        suspend fun close() {
            try {
                imports.close() // Join upload/import/sweeper before closing the in-memory database.
            } finally {
                try {
                    importParentJob.cancelAndJoin()
                    filesScope.cancel()
                    database.close()
                } finally {
                    check(testCache.canonicalFile == testCache && directory.canonicalFile == directory &&
                        directory.parentFile?.canonicalFile == testCache && directory.name.startsWith(PREFIX) &&
                        !Files.isSymbolicLink(directory.toPath())) { "Refusing cleanup outside exact fixture child" }
                    check(directory.deleteRecursively()) { "Synthetic fixture cleanup failed" }
                }
            }
        }
    }

    private suspend fun withFixture(dispatcher: CoroutineDispatcher = Dispatchers.IO, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(dispatcher)
        try {
            withTimeout(DEADLINE_MS) { block(fixture) }
        } finally {
            withContext(NonCancellable) { withTimeout(DEADLINE_MS) { fixture.close() } }
        }
    }

    /** Deterministically keeps the commit coroutine from entering its body until cancellation. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private var holding = true
        private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val runNow = synchronized(lock) {
                if (holding) { pending += context to block; false } else true
            }
            if (runNow) Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            val tasks = synchronized(lock) {
                holding = false
                pending.toList().also { pending.clear() }
            }
            tasks.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
        }
    }

    private fun node(id: String, parent: String?, children: List<String>, type: String? = null, text: String = "") =
        buildJsonObject {
            put("id", id)
            put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("children", JsonArray(children.map(::JsonPrimitive)))
            put("message", if (type == null) JsonNull else buildJsonObject {
                put("model", "deepseek-synthetic")
                put("inserted_at", STAMP)
                put("fragments", JsonArray(listOf(buildJsonObject { put("type", type); put("content", text) })))
            })
        }

    private fun sourceConversation(id: String, title: String, branch: Boolean) = buildJsonObject {
        put("id", id); put("title", title); put("inserted_at", STAMP); put("updated_at", STAMP)
        put("current_node", "default")
        put("mapping", buildJsonObject {
            put("root", node("root", null, listOf("user")))
            put("user", node("user", "root", if (branch) listOf("default", "alternate") else listOf("default"),
                "REQUEST", "$id user"))
            put("default", node("default", "user", emptyList(), "RESPONSE", "$id default"))
            if (branch) put("alternate", node("alternate", "user", emptyList(), "RESPONSE", "$id alternate"))
        })
    }

    private fun archive(fixture: Fixture): File = File(fixture.directory, "synthetic-source.zip").also { file ->
        val body = JsonArray(listOf(sourceConversation(FIRST_SOURCE, "synthetic first", true),
            sourceConversation(SECOND_SOURCE, "synthetic second", false))).toString().toByteArray(Charsets.UTF_8)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("conversations.json")); zip.write(body); zip.closeEntry()
        }
    }

    private fun hash(file: File): List<Byte> = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    private suspend fun ready(fixture: Fixture, source: File): WebImportStatusDto {
        val created = fixture.imports.create(OWNER, fixture.assistantId)
        assertEquals("created", created.state)
        assertEquals(DeepSeekArchive.MAX_ARCHIVE_BYTES, created.maxArchiveBytes)
        val ready = fixture.imports.upload(OWNER, created.id, ByteReadChannel(source.readBytes()))
        assertEquals("ready", ready.state)
        assertEquals(source.length(), ready.uploadedBytes)
        assertEquals(2, ready.conversationCount)
        assertNotNull(ready.reviewToken)
        assertTrue(fixture.stagedFile(ready.id).isFile)
        return ready
    }

    private suspend fun terminal(fixture: Fixture, id: String): WebImportStatusDto = withTimeout(DEADLINE_MS) {
        var status = fixture.imports.status(OWNER, id)
        while (status.state !in setOf("complete", "failed", "cancelled", "expired")) {
            delay(5)
            status = fixture.imports.status(OWNER, id)
        }
        status
    }

    private inline fun <reified T : Throwable> expectFailure(message: String, block: () -> Unit) {
        val failure = try { block(); null } catch (error: Throwable) { error }
        assertTrue("Expected ${T::class.simpleName}, got $failure", failure is T)
        assertEquals(message, failure?.message)
    }

    private fun firstSelection(fixture: Fixture, ready: WebImportStatusDto): WebImportSelection {
        val row = fixture.imports.conversations(OWNER, ready.id, 0, 1).items.single()
        return WebImportSelection(row.index, row.defaultBranch)
    }

    @Test fun actualUploadPagedPreviewAndExplicitBranchCommitPreserveExistingWindowAndSource() = runBlocking {
        withFixture { fixture ->
            val old = Conversation(assistantId = fixture.assistantId, title = "synthetic untouched",
                messageNodes = listOf(UIMessage.user("synthetic existing message").toMessageNode()))
            fixture.repository.insertConversation(old)
            val oldBefore = JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(old.id)))
            assertEquals(":memory:", fixture.database.openHelper.writableDatabase.path)
            val source = archive(fixture)
            val sourceBefore = hash(source)
            val ready = ready(fixture, source)
            val page1 = fixture.imports.conversations(OWNER, ready.id, 0, 1)
            assertEquals(0, page1.items.single().index); assertEquals("synthetic first", page1.items.single().title)
            assertEquals(2, page1.items.single().branchCount); assertEquals(1, page1.items.single().branchPointCount)
            assertEquals(1, page1.nextOffset); assertTrue(page1.hasMore)
            val page2 = fixture.imports.conversations(OWNER, ready.id, checkNotNull(page1.nextOffset), 1)
            assertEquals(1, page2.items.single().index); assertEquals("synthetic second", page2.items.single().title)
            assertNull(page2.nextOffset); assertFalse(page2.hasMore)
            assertTrue(fixture.imports.conversations(OWNER, ready.id, 2, 1).items.isEmpty())
            val branch1 = fixture.imports.branches(OWNER, ready.id, 0, 0, 1)
            val branch2 = fixture.imports.branches(OWNER, ready.id, 0, checkNotNull(branch1.nextOffset), 1)
            assertNull(branch2.nextOffset)
            val branches = branch1.items + branch2.items
            assertEquals(listOf(0, 1), branches.map { it.index })
            assertTrue(branches.all { it.messageCount == 2 })
            assertEquals(page1.items.single().defaultBranch, branches.single { it.isDefault }.index)
            val alternate = branches.single { !it.isDefault }.index
            assertEquals(1, fixture.repository.countConversations()) // Preview does not import anything.
            fixture.imports.commit(OWNER, ready.id, WebImportCommitRequest(checkNotNull(ready.reviewToken),
                listOf(WebImportSelection(1, page2.items.single().defaultBranch), WebImportSelection(0, alternate)), true))
            val done = terminal(fixture, ready.id)
            assertEquals("complete", done.state); assertEquals(2, done.total); assertEquals(2, done.completed)
            assertEquals(2, done.imported); assertEquals(0, done.skipped); assertEquals(0, done.failed)
            assertEquals(4, done.messages); assertNull(done.reviewToken)
            assertEquals(listOf(0, 1), done.rows.map { it.conversation })
            assertTrue(done.rows.all { it.state == "imported" })
            assertFalse(fixture.stagedFile(ready.id).exists())
            val selected = checkNotNull(fixture.repository.getConversationById(deepSeekImportId("conversation", FIRST_SOURCE, "alternate")))
            assertEquals(fixture.assistantId, selected.assistantId)
            assertEquals(listOf("$FIRST_SOURCE user", "$FIRST_SOURCE alternate"), selected.messageNodes.map { it.currentMessage.toText() })
            assertFalse(fixture.repository.existsConversationById(deepSeekImportId("conversation", FIRST_SOURCE, "default")))
            assertEquals(3, fixture.repository.countConversations())
            assertEquals(oldBefore, JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(old.id))))
            assertEquals(sourceBefore, hash(source))
        }
    }

    @Test fun repeatedUploadOfSameSelectedBranchSkipsWithoutChangingStoredMessages() = runBlocking {
        withFixture { fixture ->
            val source = archive(fixture)
            val first = ready(fixture, source)
            fixture.imports.commit(OWNER, first.id, WebImportCommitRequest(checkNotNull(first.reviewToken),
                listOf(firstSelection(fixture, first)), true))
            assertEquals(1, terminal(fixture, first.id).imported)
            val id = deepSeekImportId("conversation", FIRST_SOURCE, "default")
            val before = JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(id)))
            val second = ready(fixture, source)
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.reviewToken, second.reviewToken)
            fixture.imports.commit(OWNER, second.id, WebImportCommitRequest(checkNotNull(second.reviewToken),
                listOf(firstSelection(fixture, second)), true))
            val done = terminal(fixture, second.id)
            assertEquals("complete", done.state); assertEquals(0, done.imported); assertEquals(1, done.skipped)
            assertEquals(0, done.messages); assertEquals("skipped", done.rows.single().state)
            assertEquals(1, fixture.repository.countConversations())
            assertEquals(before, JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(id))))
            assertFalse(fixture.stagedFile(first.id).exists()); assertFalse(fixture.stagedFile(second.id).exists())
        }
    }

    @Test fun everyManagerEntryRejectsAnotherOwnerWithoutExposingOrMutatingStaging() = runBlocking {
        withFixture { fixture ->
            val source = archive(fixture)
            val created = fixture.imports.create(OWNER, fixture.assistantId)
            expectFailure<NotFoundException>("import_not_found") {
                fixture.imports.upload(OTHER_OWNER, created.id, ByteReadChannel(source.readBytes()))
            }
            assertEquals("created", fixture.imports.status(OWNER, created.id).state)
            assertFalse(fixture.stagedFile(created.id).exists())
            val ready = fixture.imports.upload(OWNER, created.id, ByteReadChannel(source.readBytes()))
            val request = WebImportCommitRequest(checkNotNull(ready.reviewToken), listOf(firstSelection(fixture, ready)), true)
            expectFailure<NotFoundException>("import_not_found") { fixture.imports.status(OTHER_OWNER, ready.id) }
            expectFailure<NotFoundException>("import_not_found") { fixture.imports.conversations(OTHER_OWNER, ready.id, 0, 1) }
            expectFailure<NotFoundException>("import_not_found") { fixture.imports.branches(OTHER_OWNER, ready.id, 0, 0, 1) }
            expectFailure<NotFoundException>("import_not_found") { fixture.imports.commit(OTHER_OWNER, ready.id, request) }
            expectFailure<NotFoundException>("import_not_found") { fixture.imports.cancel(OTHER_OWNER, ready.id) }
            assertEquals(ready, fixture.imports.status(OWNER, ready.id))
            assertTrue(fixture.stagedFile(ready.id).isFile)
            assertEquals(0, fixture.repository.countConversations())
        }
    }

    @Test fun confirmationAndJobSpecificReviewTokenAreRequiredAndCommitIsOneShot() = runBlocking {
        withFixture { fixture ->
            val source = archive(fixture)
            val first = ready(fixture, source)
            fixture.imports.cancel(OWNER, first.id)
            val current = ready(fixture, source)
            val selected = listOf(firstSelection(fixture, current))
            expectFailure<BadRequestException>("confirmation_required") {
                fixture.imports.commit(OWNER, current.id, WebImportCommitRequest(checkNotNull(current.reviewToken), selected))
            }
            expectFailure<ConflictException>("preview_changed") {
                fixture.imports.commit(OWNER, current.id, WebImportCommitRequest(checkNotNull(first.reviewToken), selected, true))
            }
            expectFailure<BadRequestException>("invalid_selection") {
                fixture.imports.commit(OWNER, current.id, WebImportCommitRequest(checkNotNull(current.reviewToken), selected + selected, true))
            }
            assertEquals("ready", fixture.imports.status(OWNER, current.id).state)
            assertEquals(0, fixture.repository.countConversations())
            val confirmed = WebImportCommitRequest(checkNotNull(current.reviewToken), selected, true)
            fixture.imports.commit(OWNER, current.id, confirmed)
            expectFailure<ConflictException>("import_not_ready") { fixture.imports.commit(OWNER, current.id, confirmed) }
            assertEquals(1, terminal(fixture, current.id).imported)
            expectFailure<ConflictException>("import_not_ready") { fixture.imports.commit(OWNER, current.id, confirmed) }
            assertEquals(1, fixture.repository.countConversations())
        }
    }

    @Test fun invalidZipFailsWithoutRowsAndDeletesOnlyItsTemporaryUpload() = runBlocking {
        withFixture { fixture ->
            val source = archive(fixture)
            val before = hash(source)
            val created = fixture.imports.create(OWNER, fixture.assistantId)
            val failed = fixture.imports.upload(OWNER, created.id, ByteReadChannel("synthetic-not-a-zip".toByteArray()))
            assertEquals("failed", failed.state); assertEquals("invalid_archive", failed.error)
            assertNull(failed.reviewToken); assertEquals(0, failed.imported)
            assertEquals(0, fixture.repository.countConversations())
            assertFalse(fixture.stagedFile(created.id).exists())
            expectFailure<ConflictException>("import_not_ready") { fixture.imports.conversations(OWNER, created.id, 0, 1) }
            assertEquals(before, hash(source))
        }
    }

    @Test fun streamingOversizeUploadIsBoundedAndLeavesNoRowsOrStagedBytes() = runBlocking {
        withFixture { fixture ->
            val created = fixture.imports.create(OWNER, fixture.assistantId)
            val channel = ByteChannel(autoFlush = true)
            coroutineScope {
                // One reusable block: no 80 MiB array or real source archive is ever allocated.
                val producer = launch(Dispatchers.IO) {
                    try {
                        val block = ByteArray(32 * 1024)
                        repeat((DeepSeekArchive.MAX_ARCHIVE_BYTES / block.size).toInt() + 1) { channel.writeFully(block) }
                    } finally { channel.close() }
                }
                try {
                    val failed = fixture.imports.upload(OWNER, created.id, channel)
                    assertEquals("failed", failed.state); assertEquals("archive_too_large", failed.error)
                    assertTrue(failed.uploadedBytes in 1..DeepSeekArchive.MAX_ARCHIVE_BYTES)
                    assertNull(failed.reviewToken)
                    assertFalse(fixture.stagedFile(created.id).exists())
                    assertEquals(0, fixture.repository.countConversations())
                } finally { producer.cancelAndJoin() }
            }
        }
    }

    @Test fun cancellingReadyReviewDeletesTemporaryFileButLeavesSourceAndExistingChatUnchanged() = runBlocking {
        withFixture { fixture ->
            val old = Conversation(assistantId = fixture.assistantId, title = "synthetic old",
                messageNodes = listOf(UIMessage.user("synthetic retained").toMessageNode()))
            fixture.repository.insertConversation(old)
            val oldBefore = JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(old.id)))
            val source = archive(fixture)
            val before = hash(source)
            val ready = ready(fixture, source)
            val cancelled = fixture.imports.cancel(OWNER, ready.id)
            assertEquals("cancelled", cancelled.state); assertEquals(0, cancelled.imported)
            assertNull(cancelled.reviewToken); assertEquals(0, cancelled.conversationCount)
            assertFalse(fixture.stagedFile(ready.id).exists())
            assertEquals(cancelled, fixture.imports.cancel(OWNER, ready.id))
            expectFailure<ConflictException>("import_not_ready") {
                fixture.imports.commit(OWNER, ready.id, WebImportCommitRequest(checkNotNull(ready.reviewToken),
                    listOf(WebImportSelection(0, 0)), true))
            }
            assertEquals(1, fixture.repository.countConversations())
            assertEquals(oldBefore, JsonInstant.encodeToString(checkNotNull(fixture.repository.getConversationById(old.id))))
            assertEquals(before, hash(source))
        }
    }

    @Test fun closeJoinsItsChildScopeAndCleansOwnedReadyUploadButNotSiblingFiles() = runBlocking {
        withFixture { fixture ->
            val source = archive(fixture)
            val before = hash(source)
            val ready = ready(fixture, source)
            assertTrue(fixture.importParentJob.children.any())
            fixture.imports.close()
            assertFalse(fixture.importParentJob.children.any())
            assertFalse(fixture.stagedFile(ready.id).exists())
            assertFalse(fixture.staging.exists())
            assertEquals(before, hash(source))
            assertEquals(0, fixture.repository.countConversations())
        }
    }

    @Test fun cancelImmediatelyAfterCommitBeforeWorkerBodyStartsReachesTerminalAndReleasesCapacity() = runBlocking {
        val dispatcher = HoldingDispatcher()
        withFixture(dispatcher) { fixture ->
            try {
                val source = archive(fixture)
                val before = hash(source)
                val ready = ready(fixture, source)
                val started = fixture.imports.commit(OWNER, ready.id, WebImportCommitRequest(
                    checkNotNull(ready.reviewToken), listOf(firstSelection(fixture, ready)), true))
                assertEquals("importing", started.state)
                assertEquals(0, fixture.repository.countConversations())
                val cancelling = fixture.imports.cancel(OWNER, ready.id)
                assertTrue(cancelling.state in setOf("cancelling", "cancelled"))
                dispatcher.release()
                val cancelled = terminal(fixture, ready.id)
                assertEquals("cancelled", cancelled.state)
                assertEquals(0, cancelled.imported); assertEquals(0, cancelled.completed)
                assertTrue(cancelled.rows.isEmpty()); assertNull(cancelled.reviewToken)
                assertFalse(fixture.stagedFile(ready.id).exists())
                assertEquals(0, fixture.repository.countConversations())
                // No ghost active worker may block a subsequent synthetic upload after cancellation.
                val next = ready(fixture, source)
                assertEquals("ready", next.state)
                fixture.imports.cancel(OWNER, next.id)
                assertEquals(before, hash(source))
            } finally { dispatcher.release() }
        }
    }
}
