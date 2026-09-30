package me.rerere.rikkahub.data.orbis.group

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.AndroidStickerRepository
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** Only synthetic cache files plus an unregistered SAF provider, never production media/settings. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class OrbisGroupAttachmentDeviceTest {
    private class Fixture(aliasFiles: Boolean = false) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val parent = instrumentation.targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(parent.toPath(), "orbis-group-attachment-test-").toFile()
        val appFiles = File(root, "files").also { check(it.mkdir()) }
        private val alias = if (aliasFiles) File(root, "files-alias").also {
            Files.createSymbolicLink(it.toPath(), appFiles.toPath())
        } else null
        val provider = SelectedFileProvider()
        private val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = alias ?: appFiles
            override fun getCacheDir(): File = root
            override fun getNoBackupFilesDir(): File = error("private storage forbidden")
            override fun getDir(name: String, mode: Int): File = error("private storage forbidden")
            override fun getContentResolver(): ContentResolver = resolver
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("preferences forbidden")
            override fun getDatabasePath(name: String): File {
                check(name == "group-fixture.db")
                return File(root, name).also { check(it.canonicalFile == it) }
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            override fun startService(service: Intent): ComponentName? = error("service forbidden")
            override fun startForegroundService(service: Intent): ComponentName? = error("service forbidden")
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = error("service forbidden")
            override fun sendBroadcast(intent: Intent) { error("broadcast forbidden") }
            override fun startActivity(intent: Intent) { error("activity forbidden") }
        }
        init { provider.attachInfo(context, ProviderInfo().apply { authority = "group.synthetic"; exported = false }) }
        val library = AndroidStickerRepository(context)
        val store = OrbisGroupAttachments(context, library::imageForSend)
        val database = AndroidOrbisGroupStorage(context, "group-fixture.db")
        fun selected(bytes: ByteArray = png(), mime: String? = "image/png", name: String = "synthetic.png"): Uri {
            val id = Uuid.random().toString()
            val source = File(root, "selected-$id").also { it.writeBytes(bytes) }
            provider.entries[id] = SelectedFileProvider.Entry(source, mime, name)
            return Uri.parse("content://group.synthetic/$id")
        }
        fun source(uri: Uri): File = provider.entries.getValue(uri.lastPathSegment!!).file
        override fun close() {
            runBlocking { database.close() }
            check(root.parentFile == parent && root.canonicalFile == root && root.name.startsWith("orbis-group-attachment-test-"))
            alias?.let { check(Files.isSymbolicLink(it.toPath()) && it.canonicalFile == appFiles); check(it.delete()) }
            fun removeOwned(directory: File, depth: Int) {
                check(depth <= 4 && directory.canonicalFile == directory && !Files.isSymbolicLink(directory.toPath()))
                directory.listFiles().orEmpty().forEach { child ->
                    check(child.canonicalFile == child && child.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(child.toPath()))
                    if (child.isDirectory) removeOwned(child, depth + 1) else check(child.delete())
                }
                check(directory.delete())
            }
            removeOwned(root, 0)
        }
    }
    private class SelectedFileProvider : ContentProvider() {
        data class Entry(val file: File, val mime: String?, val name: String)
        val entries = mutableMapOf<String, Entry>()
        var metadataMode = "normal"
        var denyRead = false
        private fun entry(uri: Uri): Entry {
            check(uri.authority == "group.synthetic" && uri.pathSegments.size == 1)
            return entries.getValue(uri.lastPathSegment!!)
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = if (metadataMode == "throw") error("synthetic missing metadata") else entry(uri).mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (metadataMode == "throw") error("synthetic query not supported")
            return if (metadataMode == "missing") MatrixCursor(arrayOf("other_column")).apply { addRow(arrayOf("not a name")) }
            else MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(entry(uri).name)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r" && !denyRead)
            return ParcelFileDescriptor.open(entry(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("write forbidden")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("write forbidden")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("write forbidden")
    }

    @Test fun stickerIdUsesLibraryValidationAndCopiesIndependentGroupImage() = runBlocking<Unit> {
        Fixture().use { f ->
            val uri = f.selected(); val bytes = f.source(uri).readBytes()
            val sticker = f.library.importImage(uri, listOf("synthetic"))
            val result = f.store.importSticker(sticker.id)
            assertTrue(result.image); assertEquals("image/png", result.mime)
            assertArrayEquals(bytes, f.source(uri).readBytes())
            assertArrayEquals(bytes, f.store.file(result).readBytes())
            assertEquals(1, f.library.readSnapshot().stickers.size)
            val url = Uri.fromFile(f.store.file(result)).toString()
            assertTrue(UIMessagePart.Image(url).encodeBase64().getOrThrow().base64.startsWith("data:image/"))
            assertEquals(f.store.file(result), OrbisGroupAttachments(f.context).file(result))
        }
    }
    @Test fun unlistedStickerAndArbitraryFileUriAreRejected() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = f.source(f.selected())
            try { f.store.import(Uri.fromFile(source)); fail("file URI must reject") } catch (_: OrbisGroupException) {}
            for (id in listOf("../private", "file:///data/private", "st000001")) {
                try { f.store.importSticker(id); fail("unlisted or invalid ID") } catch (_: Exception) {}
            }
            assertTrue(source.exists())
        }
    }
    @Test fun invalidImageDoesNotLeavePartialGroupCopyOrDeleteOriginal() = runBlocking<Unit> {
        Fixture().use { f ->
            val uri = f.selected("not an image".toByteArray())
            try { f.store.import(uri, imageRequested = true); fail("invalid image") }
            catch (error: OrbisGroupException) { assertEquals("invalid_attachment", error.code) }
            assertTrue(f.source(uri).exists())
            assertTrue(File(f.appFiles, "orbis-group-attachments").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun missingOrMutatedAttachmentNeverResolvesToAnotherFile() = runBlocking<Unit> {
        Fixture().use { f ->
            val result = f.store.import(f.selected(), imageRequested = true)
            f.store.file(result).appendText("changed")
            assertThrows(OrbisGroupException::class.java) { f.store.file(result) }
            assertThrows(OrbisGroupException::class.java) { f.store.file(result.copy(id = "../private")) }
        }
    }
    @Test fun pngHeaderWithoutImagePixelsIsRejectedBeforeAnyRequest() = runBlocking<Unit> {
        Fixture().use { f ->
            // Complete PNG signature and IHDR, but no IDAT pixel stream.
            val uri = f.selected(png().copyOf(33))
            try { f.store.import(uri, imageRequested = true); fail("missing pixels") }
            catch (error: OrbisGroupException) { assertEquals("invalid_attachment", error.code) }
            assertTrue(f.source(uri).exists())
            assertTrue(File(f.appFiles, "orbis-group-attachments").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun platformFilesAliasWorksButNamespaceSymlinkRemainsBlocked() = runBlocking<Unit> {
        Fixture(aliasFiles = true).use { f ->
            val image = f.store.import(f.selected(), imageRequested = true)
            assertTrue(f.store.file(image).canonicalPath.startsWith(f.appFiles.canonicalPath))
        }
        Fixture().use { f ->
            val owned = File(f.appFiles, "orbis-group-attachments")
            val redirected = File(f.root, "redirected").also { check(it.mkdir()) }
            Files.createSymbolicLink(owned.toPath(), redirected.toPath())
            try {
                try { f.store.import(f.selected()); fail("namespace symlink") } catch (_: OrbisGroupException) {}
                assertTrue(redirected.listFiles().orEmpty().isEmpty())
            } finally { check(owned.delete()) }
        }
    }
    @Test fun missingOrThrowingSafMetadataStillImportsAndSniffsActualImage() = runBlocking<Unit> {
        Fixture().use { f ->
            for (mode in listOf("missing", "throw")) {
                f.provider.metadataMode = mode
                val result = f.store.import(f.selected(mime = null), imageRequested = false)
                assertTrue(result.image); assertEquals("image/png", result.mime); assertEquals("附件", result.name)
            }
        }
    }
    @Test fun textFileAndImageSelectedFromFilesRemainReadableAndExportable() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = "synthetic UTF-8 文件".toByteArray()
            val result = f.store.import(f.selected(source, "text/plain; charset=utf-8", "notes.txt"))
            assertEquals(source.toString(Charsets.UTF_8), result.extractedText)
            val out = ByteArrayOutputStream()
            f.store.file(result).inputStream().use { copyGroupAttachment(it, out) }
            assertArrayEquals(source, out.toByteArray())
            assertTrue(f.store.import(f.selected(mime = "application/octet-stream")).image)
        }
    }
    @Test fun revokedGrantAndOversizeFileLeaveNoImportedCopy() = runBlocking<Unit> {
        Fixture().use { f ->
            val uri = f.selected()
            f.provider.denyRead = true
            try { f.store.import(uri); fail("revoked grant") } catch (_: Exception) {}
            f.provider.denyRead = false
            try { f.store.import(f.selected(ByteArray(GROUP_ATTACHMENT_BYTES + 1), "application/octet-stream", "too-big.bin")); fail("oversize") }
            catch (_: OrbisGroupException) {}
            assertTrue(File(f.appFiles, "orbis-group-attachments").listFiles().orEmpty().isEmpty())
            assertTrue(f.source(uri).exists())
        }
    }
    @Test fun safPhotoStickerAndFileReachSQLiteRealProviderWireAndReopenWithoutOriginalGrant() = runBlocking<Unit> {
        Fixture(aliasFiles = true).use { f ->
            val photo = f.store.import(f.selected(), imageRequested = true)
            val sticker = f.library.importImage(f.selected(), listOf("synthetic"))
            val stickerCopy = f.store.importSticker(sticker.id)
            val file = f.store.import(f.selected("FILE_CONTENT_MARKER".toByteArray(), "text/plain", "note.txt"))
            f.provider.denyRead = true // Transport/render/export must use the committed copies, not SAF.
            assertPipeline(f, listOf(photo, stickerCopy, file), imageCount = 2, expectedText = "FILE_CONTENT_MARKER")
        }
    }
    @Test fun fileOnlyMessageTraversesRealProviderAndFailedRequestDoesNotRetryOrLoseAttachment() = runBlocking<Unit> {
        Fixture().use { f ->
            val file = f.store.import(f.selected("FILE_ONLY_MARKER".toByteArray(), "application/json", "data.json"))
            assertPipeline(f, listOf(file), imageCount = 0, expectedText = "FILE_ONLY_MARKER", status = 500)
        }
    }
    /** Real SAF copies -> group coordinator -> SQLite -> GenerationLoop/OpenAI encoder -> intercepted
     * HTTP body -> persisted reply. Interceptor NEVER proceeds: there is no socket/DNS/model call. */
    private suspend fun assertPipeline(f: Fixture, items: List<OrbisGroupAttachment>, imageCount: Int,
        expectedText: String, status: Int = 200) {
        val requests = AtomicInteger()
        val captured = AtomicReference<JsonObject>()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .dns { throw AssertionError("Network forbidden") }
            .addInterceptor { chain ->
                check(requests.incrementAndGet() == 1)
                val request = chain.request()
                check(request.url.host == "group-attachment.invalid" && request.method == "POST")
                val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
                captured.set(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
                val body = if (status == 200) """{"id":"synthetic","choices":[{"index":0,"message":{"role":"assistant","content":"SYNTHETIC_REPLY"},"finish_reason":"stop"}]}"""
                    else """{"error":{"code":"server_error","message":"synthetic failure"}}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
        val job = SupervisorJob()
        try {
            val model = Model(modelId = "synthetic-only", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
            val assistant = Assistant(name = "Synthetic", chatModelId = model.id, streamOutput = false, maxTokens = 128)
            val provider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "", baseUrl = "https://group-attachment.invalid/v1")
            val settings = Settings(assistants = listOf(assistant), providers = listOf(provider),
                networkSetting = NetworkSetting(enableAutoRetry = false))
            val chats = OrbisGroupChats(f.database, { settings },
                GenerationLoopGroupResponder(GenerationLoop(f.context, ProviderManager(client, f.context), Json)),
                CoroutineScope(job + Dispatchers.Default), f.store)
            chats.reload()
            val room = chats.createRoom("Synthetic attachments")
            chats.addMember(room, assistant.id, model.id)
            chats.send(room, "", attachments = items)
            withTimeout(15_000) { while (chats.state.value.runningRoomIds.isNotEmpty()) delay(10) }
            assertEquals(1, requests.get())
            val wire = requireNotNull(captured.get())
            val images = wire.getValue("messages").jsonArray.flatMap { message ->
                (message.jsonObject["content"] as? JsonArray).orEmpty()
            }.mapNotNull { part -> part.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content }
            assertEquals(imageCount, images.size)
            assertTrue(images.all { it.startsWith("data:image/") && !it.contains("file://") })
            assertTrue(wire.toString().contains(expectedText))
            assertFalse(wire.toString().contains("content://group.synthetic"))
            assertFalse(wire.containsKey("tools"))
            f.database.close()
            val reopened = AndroidOrbisGroupStorage(f.context, "group-fixture.db")
            try {
                val rows = reopened.messages(room, 200)
                assertEquals(2, rows.size)
                assertEquals(items, rows.first().attachments)
                assertEquals(if (status == 200) OrbisGroupMessageStatus.COMPLETE else OrbisGroupMessageStatus.FAILED, rows.last().status)
                for (item in rows.first().attachments) {
                    val original = f.store.file(item)
                    if (item.image) assertNotNull(android.graphics.BitmapFactory.decodeFile(original.path)?.also { it.recycle() })
                    val exported = ByteArrayOutputStream()
                    original.inputStream().use { copyGroupAttachment(it, exported) }
                    assertArrayEquals(original.readBytes(), exported.toByteArray())
                }
            } finally { reopened.close() }
        } finally {
            job.cancelAndJoin()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
    companion object {
        private fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            return try { ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() } }
            finally { bitmap.recycle() }
        }
    }
}
