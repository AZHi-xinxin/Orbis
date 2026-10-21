package me.rerere.rikkahub.data.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.checkpoint.AndroidGenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Independent native SQLite connections plus AtomicFile, with synthetic files only. */
@RunWith(AndroidJUnit4::class)
class ConversationBackupConsistencyTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val cache = context.cacheDir.canonicalFile
        val root = Files.createTempDirectory(cache.toPath(), "backup-consistency-test-").toFile()
        val live = File(root, "synthetic.db")
        val snapshot = File(root, "snapshot.db")
        val staging = File(root, "recovery-copy")
        val database = open(live, SQLiteDatabase.CREATE_IF_NECESSARY).apply {
            enableWriteAheadLogging()
            execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT)")
            execSQL("INSERT INTO messages(text) VALUES ('old Room page')")
        }
        val base = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("synthetic old input").toMessageNode()))
        val continued = base.copy(messageNodes = base.messageNodes + UIMessage.assistant("synthetic durable tail").toMessageNode())
        val runId = Uuid.random()
        val journal = HumanContinuationJournal(AndroidGenerationCheckpointStore.createHumanContinuation(root)).also {
            it.checkpoint(runId, base, continued)
        }
        val owners = mapOf(base.id.toString() to base.assistantId.toString())
        fun stage() = ConversationRecoveryBackup.stageSnapshot(root, staging, owners)
        fun count(file: File) = open(file, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.query("SELECT count(*) FROM messages").use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        }
        private fun open(file: File, flags: Int) = SQLiteDatabase.openDatabase(
            SQLiteConfiguration.configure(context, SQLiteDatabaseConfiguration(file.absolutePath, flags)), null, null)
        override fun close() {
            database.close()
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("backup-consistency-test-"))
            val files = root.walkBottomUp().toList()
            files.forEach { check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(root.toPath())) }
            files.forEach { check(it.delete()) }
        }
    }

    @Test fun oldDatabasePlusRetiredJournalIsRefusedWhileLiveWriteStillCompletes() {
        Fixture().use { f ->
            val failure = assertThrows(IllegalStateException::class.java) {
                ConversationBackupConsistency.capture(f.context, f.live) {
                    DatabaseBackup.createSnapshot(f.database, f.snapshot)
                    // Separate writer commits successfully while the observer remains open.
                    f.database.execSQL("INSERT INTO messages(text) VALUES ('committed continuation')")
                    assertTrue(f.journal.clearCommitted(f.runId, f.continued))
                    assertTrue(f.stage().isEmpty())
                }
            }
            assertEquals(ConversationRecoveryBackup.CHANGED, failure.message)
            assertEquals(1, f.count(f.snapshot))
            assertEquals(2, f.count(f.live))
            assertNull(HumanContinuationJournal(AndroidGenerationCheckpointStore.createHumanContinuation(f.root)).recover(f.continued))
        }
    }

    @Test fun copiedJournalFollowedByNewerRoomCommitCannotProduceMixedSuccessfulBackup() {
        Fixture().use { f ->
            val original = ConversationRecoveryBackup.readCommitted(f.root, ConversationRecoveryBackup.CONTINUATION, f.base.id.toString())
            val failure = assertThrows(IllegalStateException::class.java) {
                ConversationBackupConsistency.capture(f.context, f.live) {
                    DatabaseBackup.createSnapshot(f.database, f.snapshot)
                    assertEquals(1, f.stage().size)
                    f.database.execSQL("INSERT INTO messages(text) VALUES ('newer Room revision')")
                }
            }
            assertEquals(ConversationRecoveryBackup.CHANGED, failure.message)
            assertArrayEquals(original, ConversationRecoveryBackup.readCommitted(f.root,
                ConversationRecoveryBackup.CONTINUATION, f.base.id.toString()))
            assertEquals(2, f.count(f.live))
        }
    }

    @Test fun stableWalSnapshotAndJournalRoundTripWithoutChangingLiveJournalMode() {
        Fixture().use { f ->
            val result = ConversationBackupConsistency.capture(f.context, f.live) {
                DatabaseBackup.createSnapshot(f.database, f.snapshot)
                f.stage()
            }
            assertEquals(1, result.size)
            assertEquals(1, f.count(f.snapshot))
            assertTrue(f.database.isWriteAheadLoggingEnabled)
            f.database.query("PRAGMA journal_mode").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("wal", cursor.getString(0).lowercase())
            }
            val restored = HumanContinuationJournal(AndroidGenerationCheckpointStore.createHumanContinuation(f.staging)).recover(f.base)!!
            assertEquals(f.continued.messageNodes, restored.snapshot.messageNodes)
            assertEquals(1, f.count(f.live))
        }
    }

    @Test fun trustedParentAliasWorksButJournalAndNamespaceLinksCannotReachOutsideTheirRoot() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        check(instrument is IsolatedGenerationLoopRunner)
        check(instrument.targetContext.applicationContext.javaClass == Application::class.java)
        val cache = instrument.targetContext.cacheDir.canonicalFile
        val directory = Files.createTempDirectory(cache.toPath(), "backup-link-boundary-test-").toFile().canonicalFile
        val links = mutableListOf<File>()
        fun folder(parent: File, name: String) = File(parent, name).also { check(it.mkdir()) }
        fun write(file: File, text: String) = file.also { check(it.parentFile!!.isDirectory || it.parentFile!!.mkdirs()); it.writeText(text) }
        fun link(path: File, target: File) {
            check(path.parentFile!!.canonicalFile.toPath().startsWith(directory.toPath()))
            check(target.canonicalFile.toPath().startsWith(directory.toPath()))
            Files.createSymbolicLink(path.toPath(), target.canonicalFile.toPath())
            links += path
        }
        try {
            val actual = folder(directory, "actual")
            val alias = File(directory, "trusted-alias")
            link(alias, actual)
            val root = folder(alias, "live-no-backup")
            assertNotEquals(root.absoluteFile, root.canonicalFile)
            val id = Uuid.random().toString()
            val namespace = ConversationRecoveryBackup.GENERATION
            val valid = write(ConversationRecoveryBackup.exactFile(root.canonicalFile, "$namespace/$id.json"), "synthetic committed bytes")
            assertEquals(valid, ConversationRecoveryBackup.exactFile(root, "$namespace/$id.json"))
            assertArrayEquals(valid.readBytes(), ConversationRecoveryBackup.readCommitted(root, namespace, id))

            // The target is outside the allowed journal root but still wholly owned by this test.
            val external = write(File(directory, "external-file"), "untouched leaf target")
            val linkedId = Uuid.random().toString()
            val leaf = File(root.canonicalFile, "$namespace/$linkedId.json")
            link(leaf, external)
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.exactFile(root, "$namespace/$linkedId.json") }
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.readCommitted(root, namespace, linkedId) }

            val externalFolder = folder(directory, "external-folder")
            val externalJournal = write(File(externalFolder, "$id.json"), "untouched namespace target")
            val unsafeRoot = folder(directory, "unsafe-root")
            val humanNamespace = ConversationRecoveryBackup.CONTINUATION
            link(File(unsafeRoot, humanNamespace), externalFolder)
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.exactFile(unsafeRoot, "$humanNamespace/$id.json") }
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.readCommitted(unsafeRoot, humanNamespace, id) }

            val payload = folder(directory, "unsafe-payload")
            link(File(payload, "no-backup"), root.canonicalFile)
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.hasStaged(payload) }
            assertEquals("synthetic committed bytes", valid.readText())
            assertEquals("untouched leaf target", external.readText())
            assertEquals("untouched namespace target", externalJournal.readText())
            assertEquals(listOf("$id.json"), externalFolder.listFiles()!!.map { it.name })
        } finally {
            // Unlink only the leaves created above. Never traverse any alias during cleanup.
            check(directory.parentFile == cache && directory.name.startsWith("backup-link-boundary-test-") &&
                directory.canonicalFile == directory && !Files.isSymbolicLink(directory.toPath()))
            links.asReversed().forEach { path ->
                check(path.parentFile!!.canonicalFile.toPath().startsWith(directory.toPath()))
                if (Files.isSymbolicLink(path.toPath())) Files.delete(path.toPath())
                else check(!Files.exists(path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
            val entries = Files.walk(directory.toPath()).use {
                it.sorted(Comparator.reverseOrder()).iterator().asSequence().toList()
            }
            entries.forEach { path ->
                check(path.startsWith(directory.toPath()) && !Files.isSymbolicLink(path) && path.toFile().canonicalFile.toPath() == path)
            }
            entries.forEach { Files.delete(it) }
        }
    }
}
