package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.ZipAttachmentException
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Real managed-file/projection/tool path, synthetic files and in-memory DAO only; no app startup. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class OrbisZipAttachmentDeviceTest {
    private class MemoryFileDao : ManagedFileDAO {
        val rows = linkedMapOf<Long, ManagedFileEntity>()
        private var next = 1L
        override suspend fun insert(file: ManagedFileEntity): Long = (next++).also { rows[it] = file.copy(id = it) }
        override suspend fun update(file: ManagedFileEntity) { rows[file.id] = file }
        override suspend fun getById(id: Long) = rows[id]
        override suspend fun getByPath(relativePath: String) = rows.values.singleOrNull { it.relativePath == relativePath }
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = flowOf(rows.values.filter { it.folder == folder })
        override suspend fun deleteById(id: Long): Int = if (rows.remove(id) != null) 1 else 0
        override suspend fun deleteByPath(relativePath: String): Int = getByPath(relativePath)?.let { deleteById(it.id) } ?: 0
        override suspend fun deleteByFolder(folder: String): Int = rows.values.filter { it.folder == folder }.map { it.id }.sumOf { deleteById(it) }
    }
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(it.targetContext.packageName == "org.orbis.agent.dev")
            check(it.targetContext.applicationInfo.uid == Process.myUid())
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val parent = instrumentation.targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(parent.toPath(), "orbis-zip-input-test-").toFile()
        val appFiles = File(root, "files").also { check(it.mkdir()) }
        val context = object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = appFiles
            override fun getCacheDir(): File = root
            override fun getNoBackupFilesDir(): File = error("private storage forbidden")
            override fun getDir(name: String, mode: Int): File = error("private storage forbidden")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("preferences forbidden")
            override fun getDatabasePath(name: String): File = error("database forbidden")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = error("database forbidden")
            override fun startService(service: Intent): ComponentName? = error("service forbidden")
            override fun startForegroundService(service: Intent): ComponentName? = error("service forbidden")
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = error("service forbidden")
            override fun sendBroadcast(intent: Intent) { error("broadcast forbidden") }
            override fun startActivity(intent: Intent) { error("activity forbidden") }
        }
        val dao = MemoryFileDao()
        val scope = AppScope()
        val files = FilesManager(context, FilesRepository(dao), scope)
        val upload get() = File(appFiles, "upload")
        fun selected(bytes: ByteArray): File = File(root, "source-${Uuid.random()}.zip").also { it.writeBytes(bytes) }
        fun document(entity: ManagedFileEntity) = UIMessagePart.Document(Uri.fromFile(files.getFile(entity)).toString(), entity.displayName, entity.mimeType)
        fun transformerContext() = TransformerContext(context, Model(), Assistant(), Settings())
        override fun close() {
            scope.cancel()
            check(root.parentFile == parent && root.canonicalFile == root && root.name.startsWith("orbis-zip-input-test-"))
            fun removeOwned(file: File) {
                check(file.canonicalFile == file && file.toPath().startsWith(root.toPath()) && !Files.isSymbolicLink(file.toPath()))
                if (file.isDirectory) file.listFiles().orEmpty().forEach(::removeOwned)
                check(file.delete())
            }
            removeOwned(root)
        }
    }
    private fun archive(path: String = "资料/说明.md", text: String = "这只是包内参考内容") = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry(path)); it.write(text.toByteArray()); it.closeEntry() }
    }.toByteArray()

    @Test fun selectedZipPersistsAsOneManagedFileAndToolReadsOnlyRequestedText() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = archive(); val source = f.selected(bytes)
            val saved = f.files.saveManagedZipFromUri(Uri.fromFile(source), "中文资料.zip")
            assertEquals("application/zip", saved.mimeType)
            assertEquals(bytes.size.toLong(), saved.sizeBytes)
            assertEquals(1, f.dao.rows.size); assertEquals(1, f.upload.listFiles()!!.size)
            val document = f.document(saved)
            assertArrayEquals(bytes, readManagedZipAttachment(document, f.upload, f.files).readBytes())
            val user = UIMessage.user("看这个包").copy(parts = listOf(document))
            val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user.toMessageNode()))
            val tool = createOrbisZipAttachmentTools(conversation.assistantId, conversation.id, setOf(user.id), { conversation },
                { readManagedZipAttachment(it, f.upload, f.files) }).single()
            val response = tool.execute(buildJsonObject {
                put("action", "read_text"); put("archive_ref", zipAttachmentReference(user.id, 0)); put("entry_path", "资料/说明.md")
            })
            val result = Json.parseToJsonElement((response.single() as UIMessagePart.Text).text).jsonObject
            assertTrue(result["ok"]!!.jsonPrimitive.boolean); assertTrue(result["crc_verified"]!!.jsonPrimitive.boolean)
            assertEquals("这只是包内参考内容", result["text"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes, source.readBytes()); assertEquals(1, f.upload.listFiles()!!.size)
        }
    }

    @Test fun invalidOrTraversalArchiveRollsBackOnlyNewCopyAndKeepsSource() = runBlocking<Unit> {
        Fixture().use { f ->
            for (bytes in listOf("not a zip".toByteArray(), archive("../private.txt"))) {
                val source = f.selected(bytes)
                try { f.files.saveManagedZipFromUri(Uri.fromFile(source), "bad.zip"); fail("must reject") }
                catch (_: ZipAttachmentException) { }
                assertArrayEquals(bytes, source.readBytes())
                assertTrue(f.dao.rows.isEmpty()); assertTrue(f.upload.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test fun unmanagedOutsideUploadAndSymlinkCannotBeReadEvenWithZipMetadata() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = f.selected(archive()); check(f.upload.mkdirs())
            val unmanaged = File(f.upload, "unmanaged.zip").also { it.writeBytes(source.readBytes()) }
            for (file in listOf(source, unmanaged)) {
                try { readManagedZipAttachment(UIMessagePart.Document(Uri.fromFile(file).toString(), "a.zip", "application/zip"), f.upload, f.files); fail("must reject") }
                catch (_: IllegalArgumentException) { }
            }
            val entity = f.files.saveManagedZipFromUri(Uri.fromFile(source), "real.zip")
            val managed = f.files.getFile(entity); check(managed.delete())
            Files.createSymbolicLink(managed.toPath(), source.toPath())
            try {
                try { readManagedZipAttachment(f.document(entity), f.upload, f.files); fail("link must reject") }
                catch (_: IllegalArgumentException) { }
            } finally { check(managed.delete()) }
            assertTrue(source.exists())
        }
    }

    @Test fun providerProjectionContainsReferenceNotCompressedBytesAndLeavesUiAttachmentIntact() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = f.selected(archive())
            val entity = f.files.saveManagedZipFromUri(Uri.fromFile(source), "中文.zip")
            val document = f.document(entity)
            val user = UIMessage.user("查看").copy(parts = listOf(UIMessagePart.Text("查看"), document))
            val projected = DocumentAsPromptTransformer.transform(f.transformerContext(), listOf(user)).single()
            assertEquals(document, user.parts.filterIsInstance<UIMessagePart.Document>().single())
            assertTrue(projected.parts.none { it is UIMessagePart.Document })
            val text = projected.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
            assertTrue(text.contains(zipAttachmentReference(user.id, 1)))
            assertTrue(text.contains("\"content_loaded\":false")); assertFalse(text.contains("这只是包内参考内容"))
            assertFalse(text.contains("PK\u0003\u0004"))
        }
    }

    @Test fun mislabelledZipNeverFallsBackToBinaryReadText() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = f.selected(archive())
            for (mime in listOf("text/plain", "application/octet-stream")) {
                val document = UIMessagePart.Document(Uri.fromFile(source).toString(), "renamed.txt", mime)
                val projected = DocumentAsPromptTransformer.transform(f.transformerContext(), listOf(UIMessage.user("").copy(parts = listOf(document)))).single()
                val text = projected.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
                assertTrue(text.contains("ZIP binary not loaded")); assertFalse(text.contains("PK\u0003\u0004"))
                assertFalse(text.contains("这只是包内参考内容"))
            }
        }
    }

    @Test fun trustedFilesDirectoryAliasAcceptsItsCanonicalAndAliasAttachments() = runBlocking<Unit> {
        Fixture().use { f ->
            val saved = f.files.saveManagedZipFromUri(Uri.fromFile(f.selected(archive())), "test.zip")
            val alias = File(f.root, "files-alias")
            Files.createSymbolicLink(alias.toPath(), f.appFiles.toPath())
            try {
                val canonical = f.files.getFile(saved).canonicalFile
                val aliasFile = File(alias, saved.relativePath)
                assertEquals(canonical, readManagedZipAttachment(f.document(saved), File(alias, "upload"), f.files))
                val aliased = f.document(saved).copy(url = Uri.fromFile(aliasFile).toString())
                assertEquals(canonical, readManagedZipAttachment(aliased, File(alias, "upload"), f.files))
                assertFalse(File(alias, "upload").absoluteFile == File(alias, "upload").canonicalFile)
            } finally { check(alias.delete()) }
        }
    }

    @Test fun externallyProvidedRegressionZipIsReadOnlyAndNeverBundledInApk() = runBlocking<Unit> {
        val supplied = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "orbis-user-zip-fixture.zip")
        org.junit.Assume.assumeTrue("Optional explicitly provided local regression ZIP", supplied.isFile)
        Fixture().use { f ->
            val before = supplied.readBytes()
            assertTrue(before.size < 64 * 1024)
            val saved = f.files.saveManagedZipFromUri(Uri.fromFile(supplied), "regression.zip")
            val document = f.document(saved)
            val message = UIMessage.user("").copy(parts = listOf(document))
            val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(message.toMessageNode()))
            val tool = createOrbisZipAttachmentTools(conversation.assistantId, conversation.id, setOf(message.id), { conversation },
                { readManagedZipAttachment(it, f.upload, f.files) }).single()
            val directory = Json.parseToJsonElement((tool.execute(buildJsonObject {
                put("action", "list_entries"); put("archive_ref", zipAttachmentReference(message.id, 0))
            }).single() as UIMessagePart.Text).text).jsonObject
            assertTrue(directory["ok"]!!.jsonPrimitive.boolean)
            assertEquals(5, directory["total_entries"]!!.jsonPrimitive.int)
            for (entry in directory["entries"]!!.jsonArray) {
                val response = Json.parseToJsonElement((tool.execute(buildJsonObject {
                    put("action", "read_text"); put("archive_ref", zipAttachmentReference(message.id, 0))
                    put("entry_path", entry.jsonObject["path"]!!.jsonPrimitive.content); put("limit", 8000)
                }).single() as UIMessagePart.Text).text).jsonObject
                assertTrue(response["ok"]!!.jsonPrimitive.boolean)
                assertTrue(response["crc_verified"]!!.jsonPrimitive.boolean)
                assertTrue(response["text"]!!.jsonPrimitive.content.isNotBlank())
            }
            assertArrayEquals(before, supplied.readBytes())
        }
    }

    @Test fun uploadLeafAliasAndTraversalRemainRejected() = runBlocking<Unit> {
        Fixture().use { f ->
            val saved = f.files.saveManagedZipFromUri(Uri.fromFile(f.selected(archive())), "test.zip")
            val uploadAlias = File(f.root, "upload-alias")
            Files.createSymbolicLink(uploadAlias.toPath(), f.upload.toPath())
            try {
                val url = Uri.fromFile(File(uploadAlias, f.files.getFile(saved).name)).toString()
                try { resolveManagedZipFile(url, uploadAlias); fail("upload leaf link must reject") }
                catch (e: ZipAttachmentException) { assertEquals("zip_attachment_link_not_supported", e.code) }
                val traversal = Uri.fromFile(File(f.upload, "../upload/${f.files.getFile(saved).name}")).toString()
                try { resolveManagedZipFile(traversal, f.upload); fail("traversal must reject") }
                catch (e: ZipAttachmentException) { assertEquals("zip_attachment_outside_upload", e.code) }
            } finally { check(uploadAlias.delete()) }
        }
    }
}
