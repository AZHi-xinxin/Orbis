package me.rerere.rikkahub.data.files

import android.app.Application
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.nio.file.Files

/** Synthetic owned files + in-memory DAO. Never opens SettingsStore, Koin or the real database. */
@RunWith(AndroidJUnit4::class)
class FileProtectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    @Before fun requireIsolatedRunner() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    private fun context(root: File) = object : ContextWrapper(instrumentation.targetContext) {
        override fun getFilesDir(): File = root
    }

    @Test fun trustedRootAliasWorksButChildSymlinkAndTraversalNeverBecomeUploadReferences() = runBlocking {
        val root = temporary.newFolder("real").canonicalFile
        val upload = File(root, "upload").apply { mkdir() }
        val image = File(upload, "photo.png").apply { writeText("synthetic") }
        val external = temporary.newFile("outside.txt").apply { writeText("untouched") }
        val alias = File(temporary.root, "alias")
        val childLink = File(upload, "linked.png")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        Files.createSymbolicLink(childLink.toPath(), external.toPath())
        val scope = AppScope()
        try {
            assertEquals("upload/photo.png", FileProtection.relativeUpload(alias, File(alias, "upload/photo.png").toURI().toString()))
            assertEquals("upload/photo.png", FileProtection.relativeUpload(alias, image.path))
            assertNull(FileProtection.relativeUpload(alias, childLink.path))
            assertNull(FileProtection.relativeUpload(alias, File(alias, "upload/../upload/photo.png").path))
            val dao = MemoryFilesDao()
            val manager = FilesManager(context(alias), FilesRepository(dao), scope)
            manager.syncFolder()
            val entry = manager.list().single { it.relativePath == "upload/photo.png" }
            manager.setLocked(entry, true)
            assertFalse(manager.delete(entry.id))
            val reloaded = FilesManager(context(alias), FilesRepository(dao), scope)
            assertTrue("upload/photo.png" in reloaded.lockedPaths())
            reloaded.setLocked(entry, false)
            assertTrue(reloaded.delete(entry.id))
            assertEquals("untouched", external.readText())
        } finally {
            scope.cancel()
            Files.deleteIfExists(childLink.toPath())
            Files.deleteIfExists(alias.toPath())
        }
    }

    @Test fun batchAndAgeCleanupPreserveBothAutomaticAndManualLocksAfterReload() = runBlocking {
        val root = temporary.newFolder("files").canonicalFile
        val dao = MemoryFilesDao()
        val scope = AppScope()
        try {
            val manager = FilesManager(context(root), FilesRepository(dao), scope)
            val automatic = manager.saveUploadFromBytes("avatar".toByteArray(), "avatar.txt")
            val manual = manager.saveUploadFromBytes("locked".toByteArray(), "locked.txt")
            val disposable = manager.saveUploadFromBytes("delete".toByteArray(), "delete.txt")
            FileProtection(File(root, FileProtection.PATH)).rememberAutomatic(setOf(automatic.relativePath))
            manager.setLocked(manual, true)
            assertTrue(manager.deleteOlderThan(cutoffMillis = Long.MAX_VALUE))
            assertTrue(manager.getFile(automatic).isFile); assertTrue(manager.getFile(manual).isFile)
            assertFalse(manager.getFile(disposable).exists())
            val reloaded = FilesManager(context(root), FilesRepository(dao), scope)
            assertTrue(reloaded.deleteAll())
            assertEquals(setOf(automatic.relativePath, manual.relativePath), reloaded.list().map { it.relativePath }.toSet())
            reloaded.setLocked(manual, false)
            assertTrue(reloaded.deleteAll())
            assertTrue(reloaded.getFile(automatic).isFile); assertFalse(reloaded.getFile(manual).exists())
        } finally { scope.cancel() }
    }

    @Test fun corruptedManagedRowCannotRedirectUploadCleanerIntoAnUnrelatedLocalStore() = runBlocking {
        val root = temporary.newFolder("files").canonicalFile
        val sentinel = File(root, "synthetic-store/state.json").apply { parentFile!!.mkdir(); writeText("keep") }
        val dao = MemoryFilesDao()
        val scope = AppScope()
        try {
            dao.insert(ManagedFileEntity(folder = FileFolders.UPLOAD, relativePath = "synthetic-store/state.json",
                displayName = "synthetic bad row", mimeType = "application/json", sizeBytes = sentinel.length(), createdAt = 0, updatedAt = 0))
            val manager = FilesManager(context(root), FilesRepository(dao), scope)
            assertFalse(manager.deleteAll())
            assertEquals("keep", sentinel.readText())
        } finally { scope.cancel() }
    }

    private class MemoryFilesDao : ManagedFileDAO {
        private val rows = MutableStateFlow<List<ManagedFileEntity>>(emptyList())
        private var nextId = 1L
        override suspend fun insert(file: ManagedFileEntity): Long {
            val id = file.id.takeIf { it != 0L } ?: nextId++
            rows.value = rows.value.filterNot { it.id == id || it.relativePath == file.relativePath } + file.copy(id = id)
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
