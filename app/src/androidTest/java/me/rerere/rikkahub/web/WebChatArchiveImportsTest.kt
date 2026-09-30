package me.rerere.rikkahub.web

import android.app.Application
import android.content.*
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.lover.connect.LcExternalRecoveryGate
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.importer.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.*
import me.rerere.rikkahub.web.routes.chatArchiveImportRoutes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Synthetic inputs, random cache child, in-memory target Room, isolated runner and loopback only. */
@RunWith(AndroidJUnit4::class)
class WebChatArchiveImportsTest {
    private companion object {
        const val OWNER = "synthetic-archive-owner"
        const val OTHER = "synthetic-other-owner"
        const val PREFIX = "web-archive-synthetic-"
        const val DEADLINE = 15_000L
        const val SOURCE_ID = "019dc55a-6700-7000-8000-000000000001"
        const val STAMP = "2026-09-26T14:31:36Z"
    }

    private class Fixture(formats: ((Fixture) -> Map<String, WebChatArchiveFormat>)? = null) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(!LcExternalRecoveryGate.isAllowed())
            assertTrue("Synthetic Rikka inspection requires the target APK SQLite extension",
                File(it.targetContext.applicationInfo.nativeLibraryDir, "libsimple.so").isFile)
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(cache.toPath(), PREFIX).toFile().also {
            check(it.canonicalFile == it && it.parentFile == cache && !Files.isSymbolicLink(it.toPath()))
        }
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            // Only native-library metadata comes from the target APK. All mutable storage and
            // component operations remain redirected or rejected by this isolated wrapper.
            override fun getApplicationInfo(): ApplicationInfo = instrumentation.targetContext.applicationInfo
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(directory, "no_backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = throw AssertionError("Persistent app DB forbidden")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw AssertionError("Preferences forbidden")
            override fun startService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun startForegroundService(service: Intent): ComponentName? = throw AssertionError("Services forbidden")
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = throw AssertionError("Services forbidden")
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
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), filesScope)
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database))
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.IO)
        val staging = File(directory, "isolated-staging")
        val assistant = Uuid.random()
        val imports = WebDeepSeekImports(staging, repository, scope, additionalFormats = formats?.invoke(this) ?: mapOf(
            "rikkahub" to WebRikkaArchiveFormat(RikkaChatImporter(context, repository, files)),
            "codex" to WebCodexArchiveFormat(repository)))
        fun staged(id: String) = File(staging, "$id.zip")
        suspend fun close() {
            try { imports.close() } finally {
                parent.cancelAndJoin(); filesScope.cancel(); database.close()
                check(directory.canonicalFile == directory && directory.parentFile?.canonicalFile == cache &&
                    directory.name.startsWith(PREFIX) && !Files.isSymbolicLink(directory.toPath()))
                check(directory.deleteRecursively())
            }
        }
    }

    private suspend fun fixture(formats: ((Fixture) -> Map<String, WebChatArchiveFormat>)? = null, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(formats)
        try { withTimeout(DEADLINE) { block(fixture) } }
        finally { withContext(NonCancellable) { withTimeout(DEADLINE) { fixture.close() } } }
    }

    private fun codexBytes(): ByteArray = ("""
        {"type":"session_meta","timestamp":"$STAMP","payload":{"id":"$SOURCE_ID","timestamp":"$STAMP"}}
        {"type":"response_item","timestamp":"$STAMP","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"synthetic hello"}]}}
        {"type":"response_item","timestamp":"$STAMP","payload":{"type":"message","role":"assistant","channel":"final","content":[{"type":"output_text","text":"synthetic answer"}]}}
    """.trimIndent() + "\n").toByteArray()

    private fun rikkaBytes(f: Fixture): ByteArray {
        val db = File(f.directory, "synthetic-source.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use { source ->
            source.version = 26
            source.execSQL("CREATE TABLE ConversationEntity (id TEXT, title TEXT, create_at INTEGER, update_at INTEGER)")
            source.execSQL("CREATE TABLE message_node (id TEXT, conversation_id TEXT, node_index INTEGER, messages TEXT, select_index INTEGER)")
            source.execSQL("INSERT INTO ConversationEntity VALUES (?, ?, ?, ?)", arrayOf<Any>(SOURCE_ID, "synthetic Rikka", 1L, 2L))
            source.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(Uuid.random().toString(), SOURCE_ID, 0,
                JsonInstant.encodeToString(listOf(UIMessage.user("synthetic first branch"), UIMessage.user("synthetic alternate branch"))), 1))
        }
        val zip = File(f.directory, "synthetic-rikka.zip")
        ZipOutputStream(zip.outputStream()).use { archive ->
            archive.putNextEntry(ZipEntry("rikka_hub.db")); db.inputStream().use { it.copyTo(archive) }; archive.closeEntry()
            archive.putNextEntry(ZipEntry("settings.json")); archive.write("invalid and must never be decoded".toByteArray()); archive.closeEntry()
        }
        return zip.readBytes()
    }

    private suspend fun ready(f: Fixture, source: String, bytes: ByteArray): WebImportStatusDto {
        val created = f.imports.create(OWNER, f.assistant, source)
        assertEquals(created.maxArchiveBytes, f.imports.requireSource(OWNER, created.id, source))
        return f.imports.upload(OWNER, created.id, ByteReadChannel(bytes)).also {
            assertEquals(importDiagnostic(source, it.error), "ready", it.state)
        }
    }

    /** Diagnostics contain only fixed source/error labels, never response bodies or archive data. */
    private fun importDiagnostic(source: String, error: String?): String {
        val safeSource = source.takeIf { it in setOf("deepseek", "rikkahub", "codex") } ?: "unexpected_source"
        val safeError = when (error) {
            null -> "none"
            "invalid_archive", "archive_too_large", "insufficient_storage", "upload_timeout",
            "archive_content_type_required", "invalid_status_shape" -> error
            else -> "unexpected_error"
        }
        return "synthetic_import source=$safeSource error=$safeError"
    }

    private fun request(f: Fixture, ready: WebImportStatusDto): WebImportCommitRequest = WebImportCommitRequest(
        checkNotNull(ready.reviewToken), f.imports.conversations(OWNER, ready.id, 0, 100).items.map {
            WebImportSelection(it.index, it.defaultBranch)
        }, true)

    private suspend fun terminal(f: Fixture, id: String): WebImportStatusDto = withTimeout(DEADLINE) {
        var value = f.imports.status(OWNER, id)
        while (value.state !in setOf("complete", "failed", "cancelled", "expired")) {
            delay(5); value = f.imports.status(OWNER, id)
        }
        value
    }

    private inline fun <reified T : Throwable> fails(message: String, body: () -> Unit) {
        val error = try { body(); null } catch (failure: Throwable) { failure }
        assertTrue("Expected ${T::class.simpleName}, got $error", error is T)
        assertEquals(message, error?.message)
    }

    @Test fun actualRikkaFormatPreservesAllBranchesAndNeverReadsSettings() = runBlocking {
        fixture { f ->
            val ready = ready(f, "rikkahub", rikkaBytes(f))
            assertEquals(512L * 1024 * 1024, ready.maxArchiveBytes)
            val row = f.imports.conversations(OWNER, ready.id, 0, 10).items.single()
            assertEquals("all_branches_preserved", row.defaultSelectionReason); assertEquals(1, row.branchCount)
            assertEquals(0, f.repository.countConversations())
            f.imports.commit(OWNER, ready.id, request(f, ready))
            assertEquals(1, terminal(f, ready.id).imported)
            val imported = checkNotNull(f.repository.getConversationById(rikkaImportId("conversation", SOURCE_ID)))
            assertEquals(f.assistant, imported.assistantId)
            assertEquals(2, imported.messageNodes.single().messages.size)
            assertEquals(1, imported.messageNodes.single().selectIndex)
            assertEquals("synthetic alternate branch", imported.messageNodes.single().currentMessage.toText())
            assertFalse(f.staged(ready.id).exists())
        }
    }

    @Test fun actualCodexFormatImportsVisibleTextOnceAndBindsReceiver() = runBlocking {
        fixture { f ->
            repeat(2) { index ->
                val ready = ready(f, "codex", codexBytes())
                assertEquals(64L * 1024 * 1024, ready.maxArchiveBytes)
                assertEquals("codex_text_only", f.imports.conversations(OWNER, ready.id, 0, 1).items.single().defaultSelectionReason)
                f.imports.commit(OWNER, ready.id, request(f, ready))
                val done = terminal(f, ready.id)
                assertEquals("complete", done.state); assertEquals(if (index == 0) 1 else 0, done.imported)
                assertEquals(if (index == 0) 0 else 1, done.skipped)
            }
            val imported = checkNotNull(f.repository.getConversationById(codexImportId("conversation", SOURCE_ID)))
            assertEquals(f.assistant, imported.assistantId)
            assertEquals(listOf("synthetic hello", "synthetic answer"), imported.messageNodes.map { it.currentMessage.toText() })
            assertEquals(1, f.repository.countConversations())
        }
    }

    @Test fun bothSourcesRejectWrongOwnerAndWrongSourceWithoutMutation() = runBlocking {
        fixture { f ->
            for ((source, bytes) in listOf("rikkahub" to rikkaBytes(f), "codex" to codexBytes())) {
                val ready = ready(f, source, bytes)
                fails<NotFoundException>("import_not_found") { f.imports.requireSource(OTHER, ready.id, source) }
                fails<NotFoundException>("import_not_found") { f.imports.requireSource(OWNER, ready.id, if (source == "codex") "rikkahub" else "codex") }
                fails<NotFoundException>("import_not_found") { f.imports.status(OTHER, ready.id) }
                fails<NotFoundException>("import_not_found") { f.imports.conversations(OTHER, ready.id, 0, 1) }
                fails<NotFoundException>("import_not_found") { f.imports.branches(OTHER, ready.id, 0, 0, 1) }
                fails<NotFoundException>("import_not_found") { f.imports.commit(OTHER, ready.id, request(f, ready)) }
                fails<NotFoundException>("import_not_found") { f.imports.cancel(OTHER, ready.id) }
                assertEquals("ready", f.imports.status(OWNER, ready.id).state)
                assertEquals(0, f.repository.countConversations())
                f.imports.cancel(OWNER, ready.id)
            }
        }
    }

    @Test fun bothSourcesRejectSameLengthStagingTamperAfterPreview() = runBlocking {
        fixture { f ->
            for ((source, bytes) in listOf("rikkahub" to rikkaBytes(f), "codex" to codexBytes())) {
                val ready = ready(f, source, bytes)
                val selected = request(f, ready)
                RandomAccessFile(f.staged(ready.id), "rw").use { file -> val first = file.read(); file.seek(0); file.write(first xor 1) }
                assertEquals(ready.uploadedBytes, f.staged(ready.id).length())
                f.imports.commit(OWNER, ready.id, selected)
                val done = terminal(f, ready.id)
                assertEquals("failed", done.state); assertEquals(0, done.imported); assertEquals(0, done.completed)
                assertTrue(done.rows.isEmpty()); assertEquals(0, f.repository.countConversations()); assertFalse(f.staged(ready.id).exists())
            }
        }
    }

    @Test fun oneUploadBudgetIsSharedAcrossRikkaAndCodex() = runBlocking {
        fixture { f ->
            val rikka = f.imports.create(OWNER, f.assistant, "rikkahub")
            val codex = f.imports.create(OWNER, f.assistant, "codex")
            val channel = ByteChannel(autoFlush = true)
            coroutineScope {
                val uploading = async(Dispatchers.IO) { f.imports.upload(OWNER, rikka.id, channel) }
                try {
                    while (f.imports.status(OWNER, rikka.id).state != "uploading") delay(5)
                    fails<ConflictException>("import_busy") { f.imports.upload(OWNER, codex.id, ByteReadChannel(codexBytes())) }
                    assertEquals("created", f.imports.status(OWNER, codex.id).state)
                    f.imports.cancel(OWNER, rikka.id)
                    try { uploading.await() } catch (_: CancellationException) { }
                    assertEquals("cancelled", terminal(f, rikka.id).state)
                    assertEquals("ready", f.imports.upload(OWNER, codex.id, ByteReadChannel(codexBytes())).state)
                    f.imports.cancel(OWNER, codex.id)
                } finally { channel.close(); uploading.cancelAndJoin() }
            }
        }
    }

    /** Deliberate boundary fixture: one real atomic Room insert, then suspend before/after progress. */
    private class PartialFormat(private val repository: ConversationRepository, private val progressBeforePause: Boolean) : WebChatArchiveFormat {
        override val maxArchiveBytes = 1024L
        val committed = CompletableDeferred<Unit>()
        override suspend fun inspect(file: File, checkCancelled: () -> Unit): DeepSeekArchivePreview {
            checkCancelled()
            return DeepSeekArchivePreview((0..1).map { index -> DeepSeekConversationPreview("synthetic-$index", "synthetic $index",
                Instant.EPOCH, Instant.EPOCH, 1, 1, 0, listOf(DeepSeekBranchPreview("all", 1, Instant.EPOCH, true)), "all", "all_branches_preserved") })
        }
        override suspend fun import(file: File, assistantId: Uuid, selections: Map<String, String>, expectedFingerprint: String,
            onProgress: (DeepSeekImportProgress) -> Unit): DeepSeekImportResult {
            val result = DeepSeekImportResult(imported = 1, messages = 1)
            withContext(NonCancellable) {
                repository.insertImportedConversations(listOf(Conversation(assistantId = assistantId, title = "synthetic committed boundary",
                    messageNodes = listOf(UIMessage.user("synthetic retained after cancellation").toMessageNode()))))
            }
            if (progressBeforePause) onProgress(DeepSeekImportProgress(2, 1, result))
            committed.complete(Unit)
            try { awaitCancellation() } catch (_: CancellationException) { throw DeepSeekImportCancelledException(result) }
        }
    }

    private suspend fun partialBoundary(progressBeforePause: Boolean) {
        lateinit var partial: PartialFormat
        fixture(formats = { f -> partial = PartialFormat(f.repository, progressBeforePause)
            mapOf("rikkahub" to partial, "codex" to WebCodexArchiveFormat(f.repository)) }) { f ->
            val rikka = ready(f, "rikkahub", "synthetic gate".toByteArray())
            val codex = ready(f, "codex", codexBytes())
            f.imports.commit(OWNER, rikka.id, request(f, rikka))
            partial.committed.await()
            fails<ConflictException>("import_busy") { f.imports.commit(OWNER, codex.id, request(f, codex)) }
            f.imports.cancel(OWNER, rikka.id)
            val done = terminal(f, rikka.id)
            assertEquals("cancelled", done.state); assertEquals(2, done.total); assertEquals(1, done.completed)
            assertEquals(1, done.imported); assertEquals(1, done.messages); assertEquals(1, done.rows.size)
            assertEquals(0, done.rows.single().conversation); assertEquals("imported", done.rows.single().state)
            assertEquals(1, f.repository.countConversations()); assertFalse(f.staged(rikka.id).exists())
            f.imports.commit(OWNER, codex.id, request(f, codex))
            assertEquals(1, terminal(f, codex.id).imported)
            assertEquals(2, f.repository.countConversations())
        }
    }

    @Test fun cancelKeepsPartialReceiptAfterProgressAndReleasesGlobalBudget() = runBlocking { partialBoundary(true) }
    @Test fun cancelAccountsCommittedRowEvenBeforeProgressCallback() = runBlocking { partialBoundary(false) }

    @Test fun actualRoutesEnforcePerSourceContentTypeOwnerAndSourceOnLoopback() = runBlocking {
        fixture { f ->
            // Cancel BEFORE construction: the SettingsStore flow never collects DataStore or enters Koin.
            val dormantScope = AppScope().also { it.cancel() }
            val settings = SettingsStore(f.context, dormantScope).also { store ->
                store.settingsFlow.value = Settings(assistants = listOf(Assistant(id = f.assistant)), assistantId = f.assistant,
                    webServerJwtEnabled = true, webServerAccessPassword = "synthetic-fixture-only")
            }
            val algorithm = Algorithm.HMAC256("synthetic-fixture-key-not-a-credential")
            val token = JWT.create().withSubject("synthetic-browser").sign(algorithm)
            val otherToken = JWT.create().withSubject("synthetic-other-browser").sign(algorithm)
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                install(ContentNegotiation) { json(JsonInstant) }
                install(StatusPages) { exception<ApiException> { call, error -> call.respond(error.status, ErrorResponse(error.message, error.status.value)) } }
                install(Authentication) { jwt("fixture") { verifier(JWT.require(algorithm).build()); validate { JWTPrincipal(it.payload) } } }
                routing { route("/api") { authenticate("fixture") {
                    chatArchiveImportRoutes(f.imports, settings, "deepseek", "application/zip")
                    chatArchiveImportRoutes(f.imports, settings, "rikkahub", "application/zip")
                    chatArchiveImportRoutes(f.imports, settings, "codex", "application/x-ndjson")
                } } }
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            val origin = "http://127.0.0.1:$port"
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS)
                .dns { name -> if (name != "127.0.0.1") throw IOException("Non-loopback forbidden"); listOf(InetAddress.getByName("127.0.0.1")) }
                .addInterceptor { chain -> val url = chain.request().url
                    check(url.scheme == "http" && url.host == "127.0.0.1" && url.port == port && url.encodedPath.startsWith("/api/imports/"))
                    chain.proceed(chain.request()) }.build()
            fun call(path: String, method: String, body: ByteArray? = null, type: String = "application/json", bearer: String = token): Pair<Int, String> {
                val request = Request.Builder().url("$origin/api/$path").header("Authorization", "Bearer $bearer")
                    .header(WEB_ORIGIN_HEADER, origin).method(method, body?.toRequestBody(type.toMediaType())).build()
                return client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }
            try {
                for (source in listOf("deepseek", "rikkahub", "codex")) {
                    val createdResponse = call("imports/$source", "POST", "{\"assistantId\":\"${f.assistant}\"}".toByteArray())
                    assertEquals(201, createdResponse.first)
                    val created = JsonInstant.decodeFromString<WebImportStatusDto>(createdResponse.second)
                    val otherSource = if (source == "codex") "rikkahub" else "codex"
                    assertEquals(404, call("imports/$otherSource/${created.id}", "GET").first)
                    assertEquals(404, call("imports/$source/${created.id}", "GET", bearer = otherToken).first)
                    val wrongType = if (source == "codex") "application/zip" else "application/x-ndjson"
                    val wrong = call("imports/$source/${created.id}/archive", "PUT", "synthetic".toByteArray(), wrongType)
                    assertEquals(400, wrong.first); assertTrue(wrong.second.contains("archive_content_type_required"))
                    val retained = JsonInstant.decodeFromString<WebImportStatusDto>(call("imports/$source/${created.id}", "GET").second)
                    assertEquals("created", retained.state); assertEquals(0L, retained.uploadedBytes)
                    val rightType = if (source == "codex") "application/x-ndjson" else "application/zip"
                    val bytes = when (source) { "codex" -> codexBytes(); "rikkahub" -> rikkaBytes(f); else -> "not-an-archive".toByteArray() }
                    val accepted = call("imports/$source/${created.id}/archive", "PUT", bytes, rightType)
                    val acceptedStatus = runCatching { JsonInstant.decodeFromString<WebImportStatusDto>(accepted.second) }.getOrNull()
                    val diagnostic = importDiagnostic(source,
                        if (acceptedStatus == null) "invalid_status_shape" else acceptedStatus.error)
                    assertFalse(diagnostic, accepted.second.contains("archive_content_type_required"))
                    assertEquals(diagnostic, if (source == "deepseek") 400 else 200, accepted.first)
                    assertEquals(200, call("imports/$source/${created.id}", "DELETE").first)
                }
                assertEquals(0, f.repository.countConversations())
                assertFalse(File(f.context.filesDir, "datastore").exists())
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
                server.stop(0, 1_000); dormantScope.cancel()
            }
        }
    }
}
