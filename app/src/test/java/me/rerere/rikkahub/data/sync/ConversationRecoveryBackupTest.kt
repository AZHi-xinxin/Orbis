package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** No real database, account, history, tool execution, or network is used in these fixtures. */
class ConversationRecoveryBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source get() = File(temporary.root, "source")
    private val payload get() = File(temporary.root, "payload")
    private val live get() = File(temporary.root, "live")
    private val namespaces = listOf(ConversationRecoveryBackup.GENERATION, ConversationRecoveryBackup.CONTINUATION)
    private fun path(namespace: String, id: Uuid) = "$namespace/$id.json"
    private fun write(file: File, bytes: ByteArray): File = file.apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    private fun file(root: File, namespace: String, id: Uuid) = ConversationRecoveryBackup.exactFile(root, path(namespace, id))
    private class Store(private val root: File, private val namespace: String) : GenerationCheckpointStore {
        private fun file(id: Uuid) = ConversationRecoveryBackup.exactFile(root, "$namespace/$id.json")
        override fun read(conversationId: Uuid) = file(conversationId).takeIf { it.isFile }?.readBytes()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            file(conversationId).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        }
        override fun delete(conversationId: Uuid) { check(file(conversationId).delete()) }
    }
    private data class Sample(val base: Conversation, val continued: Conversation) {
        val owners get() = mapOf(base.id.toString() to base.assistantId.toString())
    }
    private fun sample(): Sample {
        val base = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("synthetic old input").toMessageNode(), UIMessage.assistant("synthetic old reply").toMessageNode()))
        val tool = UIMessagePart.Tool("synthetic-call", "synthetic_never_execute", "{}")
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)).toMessageNode()
        val journal = GenerationCheckpointJournal(Store(source, namespaces[0]))
        val handle = journal.begin(base)
        journal.checkpoint(handle, base.assistantId, base.compactionEpoch, base.messageNodes.takeLast(1) + pending,
            GenerationToolTransition(tool.toolCallId, tool.toolName, GenerationToolStatus.STARTED))
        val continued = base.copy(messageNodes = base.messageNodes + listOf(
            UIMessage.user("synthetic continued input").toMessageNode(), UIMessage.assistant("synthetic continued answer").toMessageNode()))
        val humanJournal = HumanContinuationJournal(Store(source, namespaces[1]))
        val humanRun = Uuid.random()
        humanJournal.checkpoint(humanRun, base, continued)
        humanJournal.prepareRoomCommit(humanRun, base, base, continued)
        return Sample(base, continued)
    }
    private fun snapshot(sample: Sample, target: File = payload) = ConversationRecoveryBackup.stageSnapshot(
        source, File(target, "no-backup"), sample.owners)
    private fun validate(sample: Sample) = ConversationRecoveryBackup.validateStaged(payload, sample.owners)

    @Test fun databaseCommitRetiringJournalDuringCaptureCannotReturnSuccessfulBackup() {
        val sample = sample()
        var version = 1L
        val failure = assertThrows(IllegalStateException::class.java) {
            ConversationRecoveryBackup.requireStableDatabase(readVersion = { version }) {
                // The synthetic Room snapshot is already old. A live commit now retires its tail.
                version++
                check(file(source, namespaces[1], sample.base.id).delete())
                snapshot(sample)
            }
        }
        assertEquals(ConversationRecoveryBackup.CHANGED, failure.message)
        assertTrue(file(source, namespaces[0], sample.base.id).isFile)
    }

    @Test fun stableDatabaseCaptureReturnsExactCopiesAndDoesNotNeedToLockWriters() {
        val sample = sample()
        val result = ConversationRecoveryBackup.requireStableDatabase(readVersion = { 7L }) { snapshot(sample) }
        assertEquals(2, result.size)
        validate(sample)
        result.forEach { (name, staged) -> assertArrayEquals(File(source, name).readBytes(), staged.readBytes()) }
    }

    @Test fun nativeRecordsRoundTripAsTwoIndependentCopiesWithoutGrantingToolExecution() {
        val sample = sample()
        val copies = snapshot(sample)
        assertEquals(2, copies.size)
        validate(sample)
        ConversationRecoveryBackup.requireNoConflicts(payload, live)
        copies.forEach { (name, staged) ->
            assertArrayEquals(File(source, name).readBytes(), staged.readBytes())
            write(ConversationRecoveryBackup.exactFile(live, name), staged.readBytes())
        }
        // Native recovery remains the authority. Neither import nor these previews merge into Room.
        val generation = GenerationCheckpointJournal(Store(live, namespaces[0])).recover(sample.base)!!
        assertEquals(setOf("synthetic-call"), generation.unknownToolIds)
        assertTrue(generation.conversation.currentMessages.last().getTools().single().isExecuted)
        val human = HumanContinuationJournal(Store(live, namespaces[1])).recover(sample.base)!!
        assertEquals(sample.continued, human.snapshot)
        assertEquals(2, sample.base.messageNodes.size)
        assertTrue(file(live, namespaces[0], sample.base.id).exists())
        assertTrue(file(live, namespaces[1], sample.base.id).exists())
    }

    @Test fun unchangedLocalCopiesAndLegacyAtomicBackupAreIdempotent() {
        val sample = sample(); val copies = snapshot(sample); validate(sample)
        copies.forEach { (name, staged) ->
            val target = write(ConversationRecoveryBackup.exactFile(live, name), staged.readBytes())
            write(File(target.path + ".bak"), staged.readBytes())
        }
        repeat(2) { ConversationRecoveryBackup.requireNoConflicts(payload, live) }
        copies.forEach { (name, staged) -> assertArrayEquals(staged.readBytes(), File(live, name).readBytes()) }
    }

    @Test fun versionOneHumanJournalWithoutNewWriteAheadHashesRemainsCompatible() {
        val sample = sample()
        val target = file(source, namespaces[1], sample.base.id)
        val record = Json.parseToJsonElement(target.readText()).jsonObject.getValue("record").jsonObject
            .toMutableMap().apply { put("version", JsonPrimitive(1)); remove("roomSnapshotHashes") }
            .let(::JsonObject)
        val hash = MessageDigest.getInstance("SHA-256").digest(record.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        target.writeText(buildJsonObject { put("record", record); put("sha256", hash) }.toString())
        snapshot(sample); validate(sample)
        assertEquals(sample.continued, HumanContinuationJournal(Store(source, namespaces[1])).recover(sample.base)!!.snapshot)
    }

    @Test fun differingCopyAndInFlightAtomicWriteAreNeverOverwritten() {
        val sample = sample(); val copies = snapshot(sample)
        val target = write(file(live, namespaces[1], sample.base.id), "synthetic newer local copy".toByteArray())
        val before = target.readBytes()
        assertEquals(ConversationRecoveryBackup.CONFLICT, assertThrows(IllegalArgumentException::class.java) {
            ConversationRecoveryBackup.requireNoConflicts(payload, live)
        }.message)
        assertArrayEquals(before, target.readBytes())
        assertTrue(copies.all { it.second.isFile })
        target.writeBytes(file(File(payload, "no-backup"), namespaces[1], sample.base.id).readBytes())
        val inFlight = write(File(target.path + ".new"), "not committed".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.requireNoConflicts(payload, live) }
        assertEquals("not committed", inFlight.readText())
    }

    @Test fun damagedOriginalBytesAreExportedWithoutPretendingTheyAreRestorable() {
        val sample = sample()
        val damaged = byteArrayOf('{'.code.toByte(), 0xc3.toByte(), 0x28)
        write(file(source, namespaces[1], sample.base.id), damaged)
        val copies = snapshot(sample)
        assertEquals(2, copies.size)
        assertArrayEquals(damaged, file(File(payload, "no-backup"), namespaces[1], sample.base.id).readBytes())
        assertEquals(ConversationRecoveryBackup.INVALID,
            assertThrows(IllegalArgumentException::class.java) { validate(sample) }.message)
        assertArrayEquals(damaged, file(source, namespaces[1], sample.base.id).readBytes())
        assertTrue(copies.all { it.second.isFile })
    }

    @Test fun wrongOwnerAndChangedChecksumRejectImport() {
        val sample = sample(); snapshot(sample)
        assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.validateStaged(payload,
            mapOf(sample.base.id.toString() to Uuid.random().toString())) }
        val target = file(File(payload, "no-backup"), namespaces[1], sample.base.id)
        target.writeText(target.readText().replace("synthetic continued answer", "modified unverified answer"))
        assertThrows(IllegalArgumentException::class.java) { validate(sample) }
    }

    @Test fun exportUsesCommittedBackupAndNeverExportsUncommittedNewFile() {
        val sample = sample()
        val target = file(source, namespaces[1], sample.base.id)
        val committed = target.readBytes()
        val backup = write(File(target.path + ".bak"), committed)
        target.writeText("incomplete legacy write")
        write(File(target.path + ".new"), "uncommitted newer write".toByteArray())
        val other = Uuid.random()
        write(File(file(source, namespaces[1], other).path + ".new"), "only new".toByteArray())
        val copies = ConversationRecoveryBackup.stageSnapshot(source, File(payload, "no-backup"),
            sample.owners + (other.toString() to sample.base.assistantId.toString()))
        assertEquals(2, copies.size)
        assertArrayEquals(committed, file(File(payload, "no-backup"), namespaces[1], sample.base.id).readBytes())
        assertTrue(backup.isFile); assertEquals("incomplete legacy write", target.readText())
        validate(sample)
    }

    @Test fun excludedConversationsAndUnrelatedLocalJournalsStayOutsideTheImport() {
        val sample = sample(); val other = Uuid.random()
        write(file(source, namespaces[0], other), "excluded private bytes".toByteArray())
        val localOther = write(file(live, namespaces[0], other), "unrelated local bytes".toByteArray())
        assertEquals(2, snapshot(sample).size)
        ConversationRecoveryBackup.requireNoConflicts(payload, live)
        assertFalse(file(File(payload, "no-backup"), namespaces[0], other).exists())
        assertEquals("unrelated local bytes", localOther.readText())
    }

    @Test fun oldArchiveWithoutRecoveryCopiesLeavesAllCurrentCopiesAlone() = runBlocking {
        val sample = sample()
        val local = write(file(live, namespaces[1], sample.base.id), "keep local copy".toByteArray())
        val database = File(temporary.root, "database/rikka_hub")
        val restore = PendingRestore(File(temporary.root, "restore"), database,
            File(temporary.root, "files"), noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/database/rikka_hub"), "legacy database".toByteArray())
        restore.publish(staging)
        assertTrue(restore.apply { error("no settings") })
        assertEquals("legacy database", database.readText())
        assertEquals("keep local copy", local.readText())
    }

    @Test fun firstInstallAppliesBothCopiesAndDoesNotReapplyOnLaterLaunch() = runBlocking {
        val sample = sample()
        val database = File(temporary.root, "database/rikka_hub")
        val restore = PendingRestore(File(temporary.root, "restore"), database, File(temporary.root, "files"),
            validateBeforeJournal = { ConversationRecoveryBackup.validateStaged(it, sample.owners) }, noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        snapshot(sample, File(staging, "payload"))
        write(File(staging, "payload/database/rikka_hub"), "synthetic restored database".toByteArray())
        restore.publish(staging)
        assertTrue(restore.apply { error("no settings") })
        assertEquals("synthetic restored database", database.readText())
        namespaces.forEach { namespace -> assertArrayEquals(file(source, namespace, sample.base.id).readBytes(),
            file(live, namespace, sample.base.id).readBytes()) }
        assertFalse(restore.apply { error("must not replay") })
    }

    @Test fun conflictAtStartupRejectsEntireRestoreBeforeDatabaseOrAnyCopyMoves() = runBlocking {
        val sample = sample()
        val database = write(File(temporary.root, "database/rikka_hub"), "current database".toByteArray())
        val local = write(file(live, namespaces[1], sample.base.id), "new human tail".toByteArray())
        val restoreRoot = File(temporary.root, "restore")
        val restore = PendingRestore(restoreRoot, database, File(temporary.root, "files"),
            validateBeforeJournal = { ConversationRecoveryBackup.validateStaged(it, sample.owners) }, noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        snapshot(sample, File(staging, "payload"))
        write(File(staging, "payload/database/rikka_hub"), "older imported database".toByteArray())
        restore.publish(staging)
        val failure = runCatching { restore.apply { error("must not change settings") } }.exceptionOrNull()
        assertTrue(failure is RestoreFailedException)
        assertEquals(ConversationRecoveryBackup.CONFLICT, failure!!.message)
        assertEquals(ConversationRecoveryBackup.CONFLICT, failure.cause!!.message)
        assertEquals("current database", database.readText()); assertEquals("new human tail", local.readText())
        assertFalse(file(live, namespaces[0], sample.base.id).exists())
        val failed = restoreRoot.listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("older imported database", File(failed, "payload/database/rikka_hub").readText())
        namespaces.forEach { assertTrue(file(File(failed, "payload/no-backup"), it, sample.base.id).isFile) }
        assertFalse(restore.apply { error("failed restore must not retry") })
    }

    @Test fun corruptCopyRejectsEntireRestoreAndPreservesPayloadForDiagnosis() = runBlocking {
        val sample = sample()
        val database = write(File(temporary.root, "database/rikka_hub"), "current database".toByteArray())
        val restoreRoot = File(temporary.root, "restore")
        val restore = PendingRestore(restoreRoot, database, File(temporary.root, "files"),
            validateBeforeJournal = { ConversationRecoveryBackup.validateStaged(it, sample.owners) }, noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        snapshot(sample, File(staging, "payload"))
        write(file(File(staging, "payload/no-backup"), namespaces[1], sample.base.id), "damaged original".toByteArray())
        write(File(staging, "payload/database/rikka_hub"), "imported database".toByteArray())
        restore.publish(staging)
        val failure = runCatching { restore.apply { error("must not change settings") } }.exceptionOrNull()
        assertTrue(failure is RestoreFailedException)
        assertEquals(ConversationRecoveryBackup.INVALID, failure!!.message)
        assertEquals(ConversationRecoveryBackup.INVALID, failure.cause!!.message)
        assertEquals("current database", database.readText())
        val failed = restoreRoot.listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("damaged original", file(File(failed, "payload/no-backup"), namespaces[1], sample.base.id).readText())
        assertFalse(live.exists())
    }

    @Test fun uuidTraversalExtraFilesAndOversizedCopiesAreRejected() {
        val sample = sample()
        val invalidNames = listOf("${namespaces[0]}/../secret", "${namespaces[0]}/not-a-uuid.json",
            "${namespaces[0]}/${sample.base.id}.json/new", "${namespaces[0]}/${sample.base.id}.json.bak",
            "${namespaces[0]}\\${sample.base.id}.json", "other/${sample.base.id}.json")
        invalidNames.forEach { name ->
            assertNull(ConversationRecoveryBackup.maxBytes(name))
            assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.exactFile(live, name) }
        }
        val target = file(source, namespaces[1], sample.base.id)
        RandomAccessFile(target, "rw").use { it.setLength(ConversationRecoveryBackup.MAX_BYTES.toLong() + 1) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(sample) }
        assertEquals(ConversationRecoveryBackup.MAX_BYTES.toLong() + 1, target.length())
        write(File(payload, "no-backup/${namespaces[1]}/unexpected.json"), "{}".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.hasStaged(payload) }
    }

    @Test fun untrustedLeafLinksAreRejectedButTrustedRootParentAliasesRemainValid() {
        val actual = temporary.newFolder("actual")
        val alias = File(temporary.root, "trusted-alias")
        try { Files.createSymbolicLink(alias.toPath(), actual.toPath()) }
        catch (e: Exception) { assumeNoException("host cannot create symbolic links", e) }
        val id = Uuid.random()
        val valid = write(file(actual, namespaces[0], id), "committed".toByteArray())
        assertEquals(valid.canonicalFile, file(alias, namespaces[0], id))
        val external = write(File(temporary.root, "external"), "not owned".toByteArray())
        val malicious = file(actual, namespaces[0], Uuid.random())
        Files.createSymbolicLink(malicious.toPath(), external.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            ConversationRecoveryBackup.exactFile(actual, "${namespaces[0]}/${malicious.name}")
        }
        val unsafeRoot = temporary.newFolder("unsafe-root")
        Files.createSymbolicLink(File(unsafeRoot, namespaces[1]).toPath(), actual.toPath())
        assertThrows(IllegalArgumentException::class.java) { file(unsafeRoot, namespaces[1], id) }
        val unsafePayload = temporary.newFolder("unsafe-payload")
        Files.createSymbolicLink(File(unsafePayload, "no-backup").toPath(), actual.toPath())
        assertThrows(IllegalArgumentException::class.java) { ConversationRecoveryBackup.hasStaged(unsafePayload) }
        assertEquals("not owned", external.readText())
    }
}
