package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ContextPruningBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "10000000-0000-0000-0000-000000000001"
    private val otherOwner = "10000000-0000-0000-0000-000000000002"
    private val conversation = "20000000-0000-0000-0000-000000000001"
    private val secondConversation = "20000000-0000-0000-0000-000000000002"
    private val path get() = "orbis-context-pruning/$owner/$conversation/policy.json"
    private fun batch(number: Int = 1, restored: Boolean = false) = ContextPruningBatch(
        id = "30000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        planId = number.toString(16).padStart(64, '0'),
        marks = listOf(ContextPruningMark("40000000-0000-0000-0000-000000000001", "a".repeat(64), toolIndexes = listOf(1))),
        createdAtMs = 1234,
        restored = restored,
    )
    private fun state(vararg batches: ContextPruningBatch) = ContextPruningState(
        assistantId = owner, conversationId = conversation, batches = batches.toList())
    private fun bytes(value: ContextPruningState) = contextPruningJson.encodeToString(value).toByteArray()
    private fun write(file: File, value: ByteArray): File = file.also { it.parentFile!!.mkdirs(); it.writeBytes(value) }
    private fun payload() = File(temporary.root, "payload")
    private fun staged() = File(payload(), "files/$path")
    private fun live() = File(temporary.root, "live")

    @Test fun onlyCanonicalPolicyPathsAreWhitelisted() {
        assertEquals(MAX_CONTEXT_PRUNING_BYTES, ContextPruningBackup.maxBytes(path))
        for (invalid in listOf(path.replace("policy.json", ".lock"), path + ".tmp",
            path.replace("policy.json", "../policy.json"), path.replace('/', '\\'),
            path.replace(owner, "not-an-owner"), "../$path", "/$path", path.replace("json", "JSON"))) {
            assertNull(ContextPruningBackup.maxBytes(invalid))
        }
    }

    @Test fun rejectsCrossOwnerAndCrossConversationPolicies() {
        val original = bytes(state(batch()))
        ContextPruningBackup.validate(path, original)
        assertThrows(IllegalStateException::class.java) {
            ContextPruningBackup.validate(path.replace(owner, otherOwner), original)
        }
        assertThrows(IllegalStateException::class.java) {
            ContextPruningBackup.validate(path.replace(conversation, secondConversation), original)
        }
    }

    @Test fun rejectsUnknownFieldsMalformedUtf8OversizeAndDeepJson() {
        val valid = bytes(state(batch())).toString(Charsets.UTF_8)
        for (input in listOf(
            valid.replaceFirst("{", "{\"secret\":\"not-allowed\",").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), ByteArray(MAX_CONTEXT_PRUNING_BYTES + 1),
            ("[".repeat(50) + "0" + "]".repeat(50)).toByteArray(), "{}".toByteArray(),
        )) assertNotNull(runCatching { ContextPruningBackup.validate(path, input) }.exceptionOrNull())
    }

    @Test fun snapshotIncludesOnlyPoliciesBoundToExportedConversations() {
        val original = bytes(state(batch()))
        val policy = write(File(live(), path), original)
        write(File(policy.parentFile, ".pending-unused"), "unfinished synthetic data".toByteArray())
        val excluded = path.replace(conversation, secondConversation)
        write(File(live(), excluded), bytes(state(batch()).copy(conversationId = secondConversation)))
        val reserved = mutableListOf<String>()
        val snapshot = ContextPruningBackup.stageSnapshot(live(), File(temporary.root, "snapshot"),
            mapOf(conversation to owner), beforeFile = { name, size ->
                reserved += name; assertEquals(original.size.toLong(), size)
            })
        assertEquals(listOf(path), reserved)
        assertEquals(listOf(path), snapshot.map { it.first })
        assertArrayEquals(original, snapshot.single().second.readBytes())
        assertArrayEquals(original, policy.readBytes())
        assertTrue(File(policy.parentFile, ".pending-unused").exists())
    }

    @Test fun missingSnapshotPolicyDoesNotCreateDirectory() {
        assertTrue(ContextPruningBackup.stageSnapshot(live(), File(temporary.root, "snapshot"),
            mapOf(conversation to owner)).isEmpty())
        assertFalse(live().exists())
    }

    @Test fun snapshotHonorsBudgetBeforeCopyAndCancellation() {
        val original = bytes(state(batch()))
        write(File(live(), path), original)
        val snapshot = File(temporary.root, "snapshot")
        assertThrows(IllegalStateException::class.java) {
            ContextPruningBackup.stageSnapshot(live(), snapshot, mapOf(conversation to owner),
                beforeFile = { _, _ -> error("budget") })
        }
        assertFalse(snapshot.exists())
        assertThrows(CancellationException::class.java) {
            ContextPruningBackup.stageSnapshot(live(), snapshot, mapOf(conversation to owner),
                checkCancelled = { throw CancellationException() })
        }
        assertFalse(snapshot.exists())
        assertArrayEquals(original, File(live(), path).readBytes())
    }

    @Test fun stagedOwnershipRejectsMissingOrDifferentDatabaseOwner() {
        write(staged(), bytes(state(batch())))
        ContextPruningBackup.validateOwnership(payload(), mapOf(conversation to owner))
        for (owners in listOf(emptyMap(), mapOf(conversation to otherOwner))) {
            val failure = runCatching { ContextPruningBackup.validateOwnership(payload(), owners) }.exceptionOrNull()
            assertEquals("context_pruning_backup_owner", failure?.message)
        }
    }

    @Test fun emptyOldBackupLeavesAllExistingPoliciesUntouched() {
        val original = bytes(state(batch()))
        write(File(live(), path), original)
        ContextPruningBackup.validateStaged(payload())
        ContextPruningBackup.prepareBeforeJournal(payload(), live())
        assertFalse(ContextPruningBackup.hasStagedPolicies(payload()))
        assertArrayEquals(original, File(live(), path).readBytes())
    }

    @Test fun stagedLockTemporaryAndUnknownFilesAreRejected() {
        write(staged(), bytes(state(batch())))
        for (name in listOf(".lock", ".pending-123", "secret.json")) {
            val extra = write(File(staged().parentFile, name), "x".toByteArray())
            assertEquals("context_pruning_backup_path", runCatching {
                ContextPruningBackup.validateStaged(payload())
            }.exceptionOrNull()?.message)
            assertTrue(extra.delete())
        }
    }

    @Test fun restoredStatusIsMonotonicAndMergedBatchesAreIdempotent() {
        val local = bytes(state(batch(1, restored = true), batch(2)))
        val incoming = bytes(state(batch(1), batch(3, restored = true)))
        val merged = ContextPruningBackup.merge(path, local, incoming)
        val result = contextPruningJson.decodeFromString<ContextPruningState>(merged.toString(Charsets.UTF_8))
        assertEquals(listOf(1, 2, 3).map { batch(it).id }, result.batches.map { it.id })
        assertEquals(listOf(true, false, true), result.batches.map { it.restored })
        assertArrayEquals(merged, ContextPruningBackup.merge(path, merged, incoming))
        val backupRestored = ContextPruningBackup.merge(path, bytes(state(batch())), bytes(state(batch(restored = true))))
        assertTrue(contextPruningJson.decodeFromString<ContextPruningState>(backupRestored.toString(Charsets.UTF_8)).batches.single().restored)
    }

    @Test fun conflictingBatchOrPlanIdsRejectBeforeLiveWrites() {
        val original = bytes(state(batch()))
        write(File(live(), path), original)
        write(staged(), bytes(state(batch().copy(createdAtMs = 999))))
        assertEquals("context_pruning_backup_conflict", runCatching {
            ContextPruningBackup.prepareBeforeJournal(payload(), live())
        }.exceptionOrNull()?.message)
        assertArrayEquals(original, File(live(), path).readBytes())
        val reusedPlan = bytes(state(batch(2).copy(planId = batch().planId)))
        assertEquals("context_pruning_backup_conflict", runCatching {
            ContextPruningBackup.merge(path, original, reusedPlan)
        }.exceptionOrNull()?.message)
    }

    @Test fun prepareChangesOnlyStagingAndDoesNotAddRuntimeFiles() {
        val original = bytes(state(batch(1)))
        write(File(live(), path), original)
        write(staged(), bytes(state(batch(2))))
        ContextPruningBackup.prepareBeforeJournal(payload(), live())
        assertArrayEquals(original, File(live(), path).readBytes())
        assertEquals(2, contextPruningJson.decodeFromString<ContextPruningState>(staged().readText()).batches.size)
        assertEquals(listOf("policy.json"), staged().parentFile!!.list()!!.toList())
        ContextPruningBackup.validateStaged(payload())
    }

    @Test fun combinedBatchLimitRejectsRatherThanLosingOldUndoState() {
        val local = state(*(1..128).map { batch(it) }.toTypedArray())
        assertEquals("context_pruning_backup_conflict", runCatching {
            ContextPruningBackup.merge(path, bytes(local), bytes(state(batch(129))))
        }.exceptionOrNull()?.message)
    }

    @Test fun rejectsSymlinkPolicyAndInternalSymlinkParent() {
        val outside = write(File(temporary.root, "outside/policy.json"), bytes(state(batch())))
        val target = File(live(), path)
        target.parentFile!!.mkdirs()
        try { Files.createSymbolicLink(target.toPath(), outside.toPath()) }
        catch (unsupported: Exception) { assumeNoException(unsupported); return }
        assertThrows(IllegalArgumentException::class.java) { ContextPruningBackup.exactPolicyFile(live(), path) }
        Files.delete(target.toPath())
        val alternateFiles = File(temporary.root, "alternate-files").apply { mkdir() }
        val redirectedChild = File(alternateFiles, "orbis-context-pruning")
        Files.createSymbolicLink(redirectedChild.toPath(), File(live(), "orbis-context-pruning").toPath())
        assertThrows(IllegalArgumentException::class.java) { ContextPruningBackup.exactPolicyFile(alternateFiles, path) }
        assertArrayEquals(bytes(state(batch())), outside.readBytes())
    }
}
