package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCheckpointRecoveryDeviceTest {
    private fun isolated(block: suspend (Context) -> Unit) = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val app = inst.targetContext
        assertEquals(Application::class.java, app.applicationContext.javaClass)
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "checkpoint-device-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).also { it.dataDir = root.path }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else
                File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        try { block(context) } finally { root.deleteRecursively() }
    }
    @Test fun missingDatabaseRecoversVerifiedLocalCheckpointWithoutPasswordOrNetwork() = isolated { context ->
        val source = File(context.cacheDir, "source-db")
        val room = AppDatabaseFactory.create(context, source.path)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE checkpoint_probe (value TEXT)")
            db.execSQL("INSERT INTO checkpoint_probe VALUES ('preserved')")
            DatabaseBackup.checkpoint(db)
        } finally { room.close() }
        val archive = File(context.cacheDir, "source.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(DatabaseBackup.ARCHIVE_DATABASE)); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json")); zip.write(JsonInstant.encodeToString(Settings()).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("upload/probe.txt")); zip.write("preserved attachment".toByteArray()); zip.closeEntry()
        }
        VersionedBackup(AutoBackupRuntime.local(context), context.cacheDir).publish(archive, true)
        assertTrue(LocalCheckpointRecovery.needed(context))
        assertTrue(LocalCheckpointRecovery.recover(context))
        val restored = AppDatabaseFactory.create(context)
        try {
            restored.openHelper.readableDatabase.query("SELECT value FROM checkpoint_probe").use {
                assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0))
            }
            assertEquals("preserved attachment", File(context.filesDir, "upload/probe.txt").readText())
            assertFalse(LocalCheckpointRecovery.needed(context))
            assertFalse(LocalCheckpointRecovery.recover(context))
        } finally { restored.close() }
    }
    @Test fun damagedCheckpointNeverCreatesEmptyReplacementOrDeletesOriginalBackup() = isolated { context ->
        val archive = File(context.cacheDir, "original.zip").apply { writeText("synthetic snapshot") }
        val point = VersionedBackup(AutoBackupRuntime.local(context), context.cacheDir).publish(archive, true)
        val stored = File(context.noBackupFilesDir, "automatic-versions/${point.payloadName}")
        stored.writeText("damaged")
        assertTrue(runCatching { LocalCheckpointRecovery.recover(context) }.isFailure)
        assertTrue(stored.exists())
        assertFalse(context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME).exists())
    }
}
