package me.rerere.rikkahub.data.files

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The actual ChatPage share-import function, synthetic SAF provider and in-memory file registry.
 * No real database, Koin modules, network, services, Activity launch, or existing phone files. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SystemSharedAttachmentsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Before fun requireIsolatedRunner() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    private inner class Fixture : AutoCloseable {
        val filesRoot = temporary.newFolder().canonicalFile
        val sourcesRoot = temporary.newFolder().canonicalFile
        val provider = SyntheticShareProvider()
        private val resolver = ContentResolver.wrap(provider)
        private val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
            override fun getContentResolver(): ContentResolver = resolver
        }
        private val scope = AppScope()
        val dao = MemoryFilesDao()
        val manager = FilesManager(context, FilesRepository(dao), scope)
        init { provider.attachInfo(context, ProviderInfo().apply { authority = "share.synthetic"; exported = false }) }

        fun source(name: String, mime: String?, bytes: ByteArray): Uri {
            val id = provider.entries.size.toString()
            val source = File(sourcesRoot, id).apply { writeBytes(bytes) }
            provider.entries[id] = SyntheticShareProvider.Entry(source, name, mime)
            return Uri.parse("content://share.synthetic/$id")
        }
        override fun close() { scope.cancel() }
    }

    @Test fun systemSharedZipBecomesManagedDraftDocumentAndMediaKeepsItsOwnType() = runBlocking {
        Fixture().use { f ->
            val original = zip()
            val zip = f.source("bundle.zip", null, original)
            val photo = f.source("photo.png", "image/png", byteArrayOf(1, 2, 3))
            val audio = f.source("sound.mp3", "audio/mpeg", byteArrayOf(4, 5, 6))
            val parts = importSystemSharedAttachments(listOf(zip, photo, audio), f.manager) { fail(it) }
            val document = parts[0] as UIMessagePart.Document
            assertEquals("application/zip", document.mime)
            assertEquals("bundle.zip", document.fileName)
            assertTrue(parts[1] is UIMessagePart.Image); assertTrue(parts[2] is UIMessagePart.Audio)
            val copy = document.url.toUri().toFile()
            assertEquals(File(f.filesRoot, "upload"), copy.parentFile)
            assertArrayEquals(original, copy.readBytes())
            assertEquals(listOf("notes.txt"), ZipAttachmentArchive.inspect(copy).map { it.path })
            assertEquals(3, f.manager.list().size)
            // Once prepared, no stale SAF grant is needed to access the managed copy.
            f.provider.denyRead = true
            assertArrayEquals(original, copy.readBytes())
        }
    }

    @Test fun malformedZipAndUnsupportedFileAreVisibleAndLeaveNoManagedCopies() = runBlocking {
        Fixture().use { f ->
            val broken = f.source("broken.zip", "application/zip", "not a zip".toByteArray())
            val unsupported = f.source("program.bin", "application/octet-stream", byteArrayOf(1))
            val notes = f.source("notes.md", null, "synthetic notes".toByteArray())
            val notices = mutableListOf<String>()
            val parts = importSystemSharedAttachments(listOf(broken, unsupported, notes), f.manager, notices::add)
            assertEquals(2, notices.size)
            assertEquals("notes.md", (parts.single() as UIMessagePart.Document).fileName)
            assertEquals(1, f.manager.list().size)
            assertEquals(1, File(f.filesRoot, "upload").listFiles()!!.size)
            assertEquals(0, f.provider.opens[unsupported.lastPathSegment] ?: 0)
            assertTrue(f.provider.entries.values.all { it.file.isFile })
        }
    }

    @Test fun oversizedSystemSharedZipUsesBoundedCopyAndCleansItsPartialFile() = runBlocking {
        Fixture().use { f ->
            val uri = f.source("too-big.zip", "application/zip", zip())
            val source = f.provider.entries.getValue(uri.lastPathSegment!!).file
            RandomAccessFile(source, "rw").use { it.setLength(ZipAttachmentArchive.MAX_ARCHIVE_BYTES + 1) }
            val notices = mutableListOf<String>()
            val parts = importSystemSharedAttachments(listOf(uri), f.manager, notices::add)
            assertTrue(parts.isEmpty()); assertTrue(f.manager.list().isEmpty())
            assertTrue(File(f.filesRoot, "upload").listFiles().orEmpty().isEmpty())
            assertTrue(notices.single().contains("32 MiB"))
            assertEquals(ZipAttachmentArchive.MAX_ARCHIVE_BYTES + 1, source.length())
        }
    }

    private fun zip(): ByteArray = ByteArrayOutputStream().use { out ->
        val data = "Synthetic ZIP share, UTF-8 合成测试".toByteArray()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("notes.txt").apply {
                method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(data) }.value
            })
            zip.write(data); zip.closeEntry()
        }
        out.toByteArray()
    }

    private class SyntheticShareProvider : ContentProvider() {
        data class Entry(val file: File, val name: String, val mime: String?)
        val entries = mutableMapOf<String, Entry>()
        val opens = mutableMapOf<String, Int>()
        var denyRead = false
        private fun entry(uri: Uri): Entry {
            check(uri.authority == "share.synthetic" && uri.pathSegments.size == 1)
            return entries.getValue(uri.lastPathSegment!!)
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = entry(uri).mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(entry(uri).name)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r" && !denyRead)
            val id = requireNotNull(uri.lastPathSegment)
            opens[id] = (opens[id] ?: 0) + 1
            return ParcelFileDescriptor.open(entry(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("writes forbidden")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("writes forbidden")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("writes forbidden")
    }

    private class MemoryFilesDao : ManagedFileDAO {
        private val rows = MutableStateFlow<List<ManagedFileEntity>>(emptyList())
        private var nextId = 1L
        override suspend fun insert(file: ManagedFileEntity): Long {
            val id = nextId++
            rows.value = rows.value + file.copy(id = id)
            return id
        }
        override suspend fun update(file: ManagedFileEntity) { rows.value = rows.value.map { if (it.id == file.id) file else it } }
        override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun getByPath(relativePath: String) = rows.value.firstOrNull { it.relativePath == relativePath }
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = rows.map { all -> all.filter { it.folder == folder } }
        override suspend fun deleteById(id: Long): Int = remove { it.id == id }
        override suspend fun deleteByPath(relativePath: String): Int = remove { it.relativePath == relativePath }
        override suspend fun deleteByFolder(folder: String): Int = remove { it.folder == folder }
        private fun remove(predicate: (ManagedFileEntity) -> Boolean): Int {
            val before = rows.value
            rows.value = before.filterNot(predicate)
            return before.size - rows.value.size
        }
    }
}
