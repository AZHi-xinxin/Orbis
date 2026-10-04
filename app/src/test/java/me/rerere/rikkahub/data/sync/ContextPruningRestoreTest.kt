package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningBackup
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningBatch
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningMark
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningState
import me.rerere.rikkahub.data.ai.contextpruning.contextPruningJson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ContextPruningRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "10000000-0000-0000-0000-000000000001"
    private val conversation = "20000000-0000-0000-0000-000000000001"
    private val path get() = "orbis-context-pruning/$owner/$conversation/policy.json"
    private val files get() = File(temporary.root, "files")
    private val database get() = File(temporary.root, "databases/rikka_hub")
    private fun value(number: Int) = contextPruningJson.encodeToString(ContextPruningState(
        assistantId = owner, conversationId = conversation,
        batches = listOf(ContextPruningBatch("30000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
            number.toString(16).padStart(64, '0'), listOf(ContextPruningMark(
                "40000000-0000-0000-0000-000000000001", "a".repeat(64), toolIndexes = listOf(1))), 100))))
    private fun write(file: File, text: String) { file.parentFile!!.mkdirs(); file.writeText(text) }
    private fun restore(owners: Map<String, String> = mapOf(conversation to owner)) = PendingRestore(
        File(temporary.root, "restore"), database, files,
        validateBeforeJournal = { ContextPruningBackup.validateOwnership(it, owners) },
    )
    private fun stage(restore: PendingRestore) {
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/files/$path"), value(2))
        write(File(staging, "settings.json"), "synthetic settings")
        restore.publish(staging)
    }

    @Test fun restoreInstallsMergedPolicyWithoutTouchingOriginalChatDatabase() = runBlocking {
        write(database, "original synthetic chat plus tools")
        write(File(files, path), value(1))
        val restore = restore()
        stage(restore)
        assertTrue(restore.apply { })
        assertEquals("original synthetic chat plus tools", database.readText())
        assertEquals(2, contextPruningJson.decodeFromString<ContextPruningState>(File(files, path).readText()).batches.size)
        assertFalse(restore.apply { error("must not replay") })
    }

    @Test fun failedSettingsRestoresOriginalPolicyAndKeepsChatData() = runBlocking {
        write(database, "original synthetic history")
        write(File(files, path), value(1))
        val restore = restore()
        stage(restore)
        try { restore.apply { throw IOException("synthetic disk failure") }; fail("Expected rollback") }
        catch (_: RestoreFailedException) { }
        assertEquals(value(1), File(files, path).readText())
        assertEquals("original synthetic history", database.readText())
    }

    @Test fun ownershipChangeBeforeRestartRejectsWholeRestoreBeforeLiveMutation() = runBlocking {
        write(database, "original synthetic history")
        write(File(files, path), value(1))
        stage(restore())
        try { restore(emptyMap()).apply { fail("must not apply settings") }; fail("Expected ownership failure") }
        catch (failure: RestoreFailedException) {
            assertEquals("context_pruning_backup_owner", failure.cause?.message)
        }
        assertEquals(value(1), File(files, path).readText())
        assertEquals("original synthetic history", database.readText())
    }

    @Test fun processDeathAfterMovesResumesWithoutMergingTwice() = runBlocking {
        write(database, "original synthetic history")
        write(File(files, path), value(1))
        stage(restore())
        try { restore().apply { throw SimulatedProcessDeath() }; fail("Expected interruption") }
        catch (_: SimulatedProcessDeath) { }
        val installed = File(files, path).readText()
        // Binding was validated before the durable journal; replay needs no DB/model/tool work.
        assertTrue(restore(emptyMap()).apply { })
        assertEquals(installed, File(files, path).readText())
        assertEquals(2, contextPruningJson.decodeFromString<ContextPruningState>(installed).batches.size)
        assertEquals("original synthetic history", database.readText())
    }

    private class SimulatedProcessDeath : Error()
}
