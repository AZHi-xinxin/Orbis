package me.rerere.rikkahub.data.orbis.memory

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic in-memory Room only: no user settings, filesystem memory, model, audio or network. */
@RunWith(AndroidJUnit4::class)
class OrbisMemoryRepositoryDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
        }
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val repo = OrbisMemoryRepository(db)
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        suspend fun execute(action: String, key: String = UUID.randomUUID().toString(), ownerId: String = owner,
            fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = repo.execute(ownerId,
            buildJsonObject { put("action", action); fields() }, key)
        suspend fun store(body: String, state: String = "static", summary: String = ""): String =
            execute("store") { put("body", body); put("state", state); put("summary", summary) }.id()
        suspend fun note(id: String, includeDeleted: Boolean = false): JsonObject =
            execute("read") { put("id", id); put("includeDeleted", includeDeleted) }.getValue("note").jsonObject
        override fun close() = db.close()
    }

    @Test fun legacyAndOtherAssistantsNeverJoinNewMemory() = runBlocking {
        Fixture().use { f ->
            f.db.memoryDao().insertMemory(MemoryEntity(assistantId = f.owner, content = "synthetic legacy original"))
            assertEquals(0, f.repo.stats(f.owner).total)
            f.execute("store", ownerId = f.other) { put("body", "synthetic foreign original") }
            val id = f.store("synthetic new native original")
            assertEquals(1, f.repo.stats(f.owner).total)
            assertEquals(1, f.db.memoryDao().getMemoriesOfAssistant(f.owner).size)
            assertEquals("synthetic new native original", f.note(id).getValue("body").jsonPrimitive.content)
            assertEquals(1, f.repo.stats(f.other).total)
        }
    }

    @Test fun fullOriginalSurvivesWhileInjectionIsBoundedAndStaticNeverInjects() = runBlocking {
        Fixture().use { f ->
            val body = "完整合成原文\n".repeat(2000)
            val id = f.store(body, "conditional")
            assertEquals(body, f.note(id).getValue("body").jsonPrimitive.content)
            assertTrue(f.repo.injectionCandidates(f.owner).isEmpty())
            f.execute("update") { put("id", id); put("summary", "synthetic short summary") }
            val candidate = f.repo.injectionCandidates(f.owner).single()
            assertNull(candidate.body)
            assertEquals("synthetic short summary", candidate.summary)
            assertNull(candidate.pinText)
            f.store("synthetic static note")
            assertEquals(1, f.repo.injectionCandidates(f.owner).size)
            assertEquals(body, f.note(id).getValue("body").jsonPrimitive.content)
        }
    }

    @Test fun metadataProjectionContainsNoOriginalSummaryOrKeywords() = runBlocking {
        Fixture().use { f ->
            f.execute("store") {
                put("body", "synthetic-private-body"); put("summary", "synthetic-private-summary")
                put("keywords", buildJsonArray { add("synthetic-private-keyword") })
                put("tags", buildJsonArray { add("public-test-tag") })
            }
            val encoded = Json.encodeToString(f.repo.metadataSnapshot(f.owner))
            assertFalse(encoded.contains("synthetic-private"))
            assertTrue(encoded.contains("public-test-tag"))
            assertEquals(setOf("id", "state", "tags", "createdAt", "updatedAt", "revision", "deleted"),
                Json.parseToJsonElement(encoded).jsonArray.single().jsonObject.keys)
        }
    }

    @Test fun foreignReadEditDeleteHistoryAndRestoreAllRefuseWithoutLeakingContent() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic isolated secret")
            listOf("read", "update", "delete", "history", "restore", "set_state").forEach { action ->
                val response = f.execute(action, ownerId = f.other) {
                    put("id", id); put("body", "replacement"); put("state", "paused")
                }
                assertFalse(response.ok())
                assertEquals("memory_note_not_found", response.getValue("code").jsonPrimitive.content)
                assertFalse(response.toString().contains("synthetic isolated secret"))
            }
            assertEquals(1, f.note(id).getValue("revision").jsonPrimitive.int)
        }
    }

    @Test fun concurrentIdenticalOperationHasOneOriginalAndOneImmutableReceipt() = runBlocking {
        Fixture().use { f ->
            val request = buildJsonObject { put("action", "store"); put("body", "synthetic same operation") }
            val results = (1..12).map { async(Dispatchers.IO) { f.repo.execute(f.owner, request, "synthetic-operation") } }.awaitAll()
            assertEquals(1, results.map { it.id() }.toSet().size)
            assertEquals(1, results.toSet().size)
            assertEquals(1, f.repo.stats(f.owner).total)
            assertEquals(1, f.db.orbisMemoryDao().history(f.owner, results.first().id(), 50, 0).size)
            val conflict = f.repo.execute(f.owner, buildJsonObject { put("action", "store"); put("body", "different") }, "synthetic-operation")
            assertEquals("memory_idempotency_conflict", conflict.getValue("code").jsonPrimitive.content)
            assertEquals(1, f.repo.stats(f.owner).total)
        }
    }

    @Test fun objectKeyOrderDoesNotChangeOperationIdentityAndRetryReturnsOriginalResult() = runBlocking {
        Fixture().use { f ->
            val first = f.repo.execute(f.owner, buildJsonObject { put("action", "store"); put("body", "first") }, "stable")
            f.execute("update") { put("id", first.id()); put("body", "second") }
            val repeat = f.repo.execute(f.owner, buildJsonObject { put("body", "first"); put("action", "store") }, "stable")
            assertEquals(first, repeat)
            assertEquals(2, f.note(first.id()).getValue("revision").jsonPrimitive.int)
        }
    }

    @Test fun editsSoftDeleteAndRevisionRestorePreserveEveryOriginal() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic version one", "conditional")
            f.execute("update") { put("id", id); put("body", "synthetic version two") }
            f.execute("delete") { put("id", id) }
            assertEquals(1, f.repo.stats(f.owner).deleted)
            assertTrue(f.repo.metadataSnapshot(f.owner).isEmpty())
            assertEquals("synthetic version two", f.note(id, true).getValue("body").jsonPrimitive.content)
            val restored = f.execute("restore") { put("id", id); put("revision", 1) }
            assertTrue(restored.ok()); assertEquals(4, restored.getValue("revision").jsonPrimitive.int)
            assertEquals("synthetic version one", f.note(id).getValue("body").jsonPrimitive.content)
            val history = f.execute("history") { put("id", id) }.getValue("items").jsonArray
            assertEquals(listOf(4, 3, 2, 1), history.map { it.jsonObject.getValue("revision").jsonPrimitive.int })
            assertEquals("synthetic version two", history[1].jsonObject.getValue("body").jsonPrimitive.content)
        }
    }

    @Test fun pauseAndStateChangeImmediatelyExcludeOnlyThatNote() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic conditional", "conditional")
            val other = f.store("synthetic sibling", "conditional")
            f.execute("set_state") { put("id", id); put("state", "paused") }
            assertEquals(setOf(other), f.repo.eligibleInjectionIds(f.owner, listOf(id, other)))
            assertEquals("paused", f.repo.metadataSnapshot(f.owner).first { it.id == id }.state)
            f.execute("set_state") { put("id", id); put("state", "resume") }
            assertEquals(setOf(id, other), f.repo.eligibleInjectionIds(f.owner, listOf(id, other)))
            f.execute("set_state") { put("id", id); put("state", "static") }
            assertEquals(setOf(other), f.repo.eligibleInjectionIds(f.owner, listOf(id, other)))
        }
    }

    @Test fun pinCountAndBudgetFailuresLeaveOriginalAndRevisionUntouched() = runBlocking {
        Fixture().use { f ->
            repeat(7) { f.store("pin $it", "pinned") }
            val id = f.store("original unpinned")
            val rejected = f.execute("set_state") { put("id", id); put("state", "pinned") }
            assertFalse(rejected.ok())
            assertEquals(1, f.note(id).getValue("revision").jsonPrimitive.int)
            assertEquals("static", f.note(id).getValue("state").jsonPrimitive.content)
            val existing = f.repo.injectionCandidates(f.owner).first().id
            val oversized = f.execute("update") { put("id", existing); put("body", "界".repeat(1000)) }
            assertFalse(oversized.ok())
            assertEquals(1, f.note(existing).getValue("revision").jsonPrimitive.int)
            assertEquals(7, f.repo.stats(f.owner).pinned)
        }
    }

    @Test fun summaryCanPinALongOriginalAndOldPinsRemainFirstAcrossPages() = runBlocking {
        Fixture().use { f ->
            val body = "synthetic complete original ".repeat(500)
            val id = f.store(body, "pinned", "short pin")
            repeat(55) { f.store("new conditional $it", "conditional") }
            assertEquals(id, f.repo.injectionCandidates(f.owner, limit = 5).first().id)
            assertEquals("short pin", f.repo.injectionCandidates(f.owner).first().pinText)
            assertEquals(body, f.note(id).getValue("body").jsonPrimitive.content)
            assertEquals(6, f.repo.injectionCandidates(f.owner, limit = 50, offset = 50).size)
        }
    }

    @Test fun characterLimitUsesCodepointsAndCannotBeBypassedWithEmbeddedNull() = runBlocking {
        Fixture().use { f ->
            val id = f.store("😀".repeat(300), "conditional")
            f.store("😀".repeat(301), "conditional")
            f.store("x\u0000" + "long".repeat(100), "conditional")
            val candidates = f.repo.injectionCandidates(f.owner)
            assertEquals(listOf(id), candidates.map { it.id })
            assertEquals(300, OrbisMemoryBudget.codepoints(candidates.single().body!!))
        }
    }

    @Test fun summariesAreNotLimitedTo300CharactersAndOversizedProjectionNeverTruncates() = runBlocking {
        Fixture().use { f ->
            val body = "complete original ".repeat(100)
            val summary = "s".repeat(1000)
            val id = f.store(body, "conditional", summary)
            assertEquals(summary, f.repo.injectionCandidates(f.owner).single().summary)
            val oversizedSummary = "long summary ".repeat(200)
            val skipped = f.execute("store") {
                put("body", body); put("state", "conditional"); put("summary", oversizedSummary)
            }
            assertTrue(skipped.ok())
            assertEquals("memory_short_summary_required_for_injection", skipped.getValue("injectionNotice").jsonPrimitive.content)
            assertEquals(listOf(id), f.repo.injectionCandidates(f.owner).map { it.id })
            assertEquals(oversizedSummary, f.note(skipped.id()).getValue("summary").jsonPrimitive.content)
            val shortBody = f.store("short original fallback", "conditional", oversizedSummary)
            val candidate = f.repo.injectionCandidates(f.owner).first { it.id == shortBody }
            assertEquals("", candidate.summary)
            assertEquals("short original fallback", candidate.body)
        }
    }

    @Test fun failedNewPinPromotionPreservesOriginalAsStaticAndRetriesDoNotPromoteLater() = runBlocking {
        Fixture().use { f ->
            val body = "a".repeat(301) // Token budget fits; the raw-original 300-codepoint rule does not.
            val request = buildJsonObject { put("action", "store"); put("body", body); put("state", "pinned") }
            val result = f.repo.execute(f.owner, request, "synthetic-failed-promotion")
            assertTrue(result.ok()); assertTrue(result.getValue("saved").jsonPrimitive.boolean)
            assertFalse(result.getValue("promotionSucceeded").jsonPrimitive.boolean)
            assertEquals("memory_pin_summary_required", result.getValue("promotionCode").jsonPrimitive.content)
            assertEquals("static", result.getValue("state").jsonPrimitive.content)
            assertEquals(body, f.note(result.id()).getValue("body").jsonPrimitive.content)
            assertTrue(f.repo.injectionCandidates(f.owner).isEmpty())
            assertEquals(result, f.repo.execute(f.owner, request, "synthetic-failed-promotion"))
            assertEquals(1, f.repo.stats(f.owner).total)
            assertEquals(1, f.db.orbisMemoryDao().history(f.owner, result.id(), 50, 0).size)
        }
    }

    @Test fun countAndTokenPromotionFailuresSaveStaticOriginalWithoutCreatingAnEighthPin() = runBlocking {
        Fixture().use { f ->
            repeat(7) { f.store("short pin $it", "pinned") }
            val extra = f.execute("store") { put("body", "eighth original"); put("state", "pinned") }
            assertTrue(extra.ok()); assertFalse(extra.getValue("promotionSucceeded").jsonPrimitive.boolean)
            assertEquals("memory_pin_budget_exceeded", extra.getValue("promotionCode").jsonPrimitive.content)
            assertEquals("eighth original", f.note(extra.id()).getValue("body").jsonPrimitive.content)
            assertEquals(7, f.repo.stats(f.owner).pinned)
            assertEquals(8, f.repo.stats(f.owner).total)
        }
        Fixture().use { f ->
            val body = "complete large original ".repeat(50)
            val result = f.execute("store") {
                put("body", body); put("summary", "界".repeat(400)); put("state", "pinned")
            }
            assertTrue(result.ok()); assertFalse(result.getValue("promotionSucceeded").jsonPrimitive.boolean)
            assertEquals("memory_pin_budget_exceeded", result.getValue("promotionCode").jsonPrimitive.content)
            assertEquals(body, f.note(result.id()).getValue("body").jsonPrimitive.content)
            assertEquals("界".repeat(400), f.note(result.id()).getValue("summary").jsonPrimitive.content)
            assertEquals(0, f.repo.stats(f.owner).pinned)
        }
    }

    @Test fun longSummaryWithinTokenBudgetPinsWithoutBeingCutTo300Characters() = runBlocking {
        Fixture().use { f ->
            val summary = "s".repeat(1000)
            val id = f.store("complete long original ".repeat(100), "pinned", summary)
            assertEquals("pinned", f.note(id).getValue("state").jsonPrimitive.content)
            assertEquals(summary, f.repo.injectionCandidates(f.owner).single().pinText)
            assertEquals(summary, f.repo.injectionCandidates(f.owner).single().summary)
            assertTrue(OrbisMemoryBudget.pinnedTokens(listOf(summary)) <= 350)
        }
    }

    @Test fun concurrentPinPromotionsNeverExceedSevenAndAllOriginalsAreSaved() = runBlocking {
        Fixture().use { f ->
            val results = (1..12).map { number -> async(Dispatchers.IO) {
                f.execute("store") { put("body", "synthetic pin $number"); put("state", "pinned") }
            } }.awaitAll()
            assertTrue(results.all { it.ok() && it.getValue("saved").jsonPrimitive.boolean })
            assertEquals(7, results.count { it.getValue("promotionSucceeded").jsonPrimitive.boolean })
            assertEquals(7, f.repo.stats(f.owner).pinned)
            assertEquals(12, f.repo.stats(f.owner).total)
        }
    }

    @Test fun restorePausedNoteResumesPreviousStateButFullPinSetRefusesWithoutMutation() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic paused pin", "pinned")
            f.execute("set_state") { put("id", id); put("state", "paused") }
            val restored = f.execute("restore") { put("id", id) }
            assertTrue(restored.ok()); assertEquals("pinned", restored.getValue("state").jsonPrimitive.content)
            f.execute("set_state") { put("id", id); put("state", "paused") }
            repeat(7) { f.store("replacement pin $it", "pinned") }
            val before = f.note(id)
            val refused = f.execute("restore") { put("id", id) }
            assertFalse(refused.ok())
            assertEquals("memory_pin_budget_exceeded", refused.getValue("code").jsonPrimitive.content)
            assertEquals(before, f.note(id))
        }
    }

    @Test fun fieldErrorsAreActionableAndNeverEchoTheInvalidValue() = runBlocking {
        Fixture().use { f ->
            val marker = "synthetic-private-invalid-field"
            val response = f.execute("store") { put("body", "intact"); put("important", marker) }
            assertFalse(response.ok())
            assertEquals("important", response.getValue("field").jsonPrimitive.content)
            assertTrue(response.getValue("message").jsonPrimitive.content.isNotBlank())
            assertFalse(response.toString().contains(marker))
            assertEquals(0, f.repo.stats(f.owner).total)
        }
    }

    @Test fun searchPagesHaveNoDuplicateRowsAndDeletedRequiresExplicitInclusion() = runBlocking {
        Fixture().use { f ->
            val ids = (1..5).map { f.store("synthetic searchable $it") }
            f.execute("delete") { put("id", ids.first()) }
            val one = f.execute("read") { put("query", "searchable"); put("limit", 2) }
            val two = f.execute("read") { put("query", "searchable"); put("limit", 2); put("offset", one.getValue("nextOffset")) }
            val visible = (one.getValue("items").jsonArray + two.getValue("items").jsonArray).map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(4, visible.toSet().size)
            assertFalse(visible.contains(ids.first()))
            assertEquals(5, f.execute("read") { put("includeDeleted", true) }.getValue("items").jsonArray.size)
        }
    }

    @Test fun exportIncludesCompleteOriginalsAndHistoryOnlyForTheBoundAssistantWithoutClosingStream() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic export original")
            f.execute("update") { put("id", id); put("body", "synthetic export revision") }
            f.execute("delete") { put("id", id) }
            f.execute("store", ownerId = f.other) { put("body", "foreign-must-not-export") }
            var closed = false
            val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
            f.repo.export(f.owner, output)
            assertFalse(closed)
            val text = output.toString("UTF-8")
            assertFalse(text.contains("foreign-must-not-export"))
            val exported = Json.parseToJsonElement(text).jsonObject
            assertEquals(1, exported.getValue("notes").jsonArray.size)
            assertEquals(3, exported.getValue("revisions").jsonArray.size)
            assertEquals("synthetic export original", exported.getValue("revisions").jsonArray.first().jsonObject.getValue("body").jsonPrimitive.content)
        }
    }

    @Test fun exportFailurePropagatesAndDoesNotChangeOriginal() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic export failure original")
            val output = object : OutputStream() { override fun write(value: Int) { throw IOException("synthetic output failure") } }
            assertTrue(runCatching { f.repo.export(f.owner, output) }.exceptionOrNull() is IOException)
            assertEquals("synthetic export failure original", f.note(id).getValue("body").jsonPrimitive.content)
            assertEquals(1, f.repo.stats(f.owner).total)
        }
    }

    @Test fun exportKeysetPaginationIncludesEveryNoteAndRevisionExactlyOnce() = runBlocking {
        Fixture().use { f ->
            val expected = (1..9).map { index ->
                val id = f.store("synthetic page original $index")
                f.execute("update") { put("id", id); put("body", "synthetic page revision $index") }
                id
            }.toSet()
            val output = ByteArrayOutputStream()
            f.repo.export(f.owner, output)
            val data = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
            val notes = data.getValue("notes").jsonArray
            val revisions = data.getValue("revisions").jsonArray
            assertEquals(9, notes.size)
            assertEquals(expected, notes.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
            assertEquals(18, revisions.size)
            assertEquals(18, revisions.map { it.jsonObject.let { row ->
                row.getValue("id").jsonPrimitive.content to row.getValue("revision").jsonPrimitive.int
            } }.toSet().size)
        }
    }

    @Test fun pagedExportIsOneTransactionSnapshotWhileAnotherWriterWaits() = runBlocking {
        Fixture().use { f ->
            repeat(9) { f.store("synthetic snapshot note $it") }
            val started = CompletableDeferred<Unit>()
            val continueExport = CountDownLatch(1)
            val firstWrite = AtomicBoolean(true)
            val output = object : ByteArrayOutputStream() {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    super.write(buffer, offset, length)
                    if (firstWrite.compareAndSet(true, false)) {
                        started.complete(Unit)
                        check(continueExport.await(10, TimeUnit.SECONDS)) { "synthetic export gate timed out" }
                    }
                }
            }
            val exporter = async(Dispatchers.IO) { f.repo.export(f.owner, output) }
            try {
                withTimeout(10_000) { started.await() }
                val writer = async(start = CoroutineStart.UNDISPATCHED) { f.store("synthetic after snapshot") }
                assertFalse(writer.isCompleted)
                continueExport.countDown()
                exporter.await()
                writer.await()
                val text = output.toString("UTF-8")
                assertFalse(text.contains("synthetic after snapshot"))
                assertEquals(9, Json.parseToJsonElement(text).jsonObject.getValue("notes").jsonArray.size)
                assertEquals(10, f.repo.stats(f.owner).total)
            } finally { continueExport.countDown() }
        }
    }

    @Test fun cancellationOfSurroundingTransactionRollsBackNoteVersionAndReceipt() = runBlocking {
        Fixture().use { f ->
            val request = buildJsonObject { put("action", "store"); put("body", "synthetic cancellation") }
            val failure = runCatching { f.db.withTransaction {
                f.repo.execute(f.owner, request, "synthetic-cancelled-operation")
                throw CancellationException("synthetic transaction cancellation")
            } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, f.repo.stats(f.owner).total)
            assertTrue(f.db.orbisMemoryDao().exportRevisions(f.owner, "", 0, 4).isEmpty())
            assertTrue(f.repo.execute(f.owner, request, "synthetic-cancelled-operation").ok())
            assertEquals(1, f.repo.stats(f.owner).total)
        }
    }

    @Test fun failingOperationReceiptRollsBackCurrentAndRevisionTogether() = runBlocking {
        Fixture().use { f ->
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_memory_receipt_failure BEFORE INSERT ON orbis_memory_operation BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END")
            assertTrue(runCatching { f.store("synthetic rollback") }.isFailure)
            assertEquals(0, f.repo.stats(f.owner).total)
            assertTrue(f.db.orbisMemoryDao().exportRevisions(f.owner, "", 0, 4).isEmpty())
        }
    }

    @Test fun compactionOriginalIsStaticIdempotentAndParticipatesInOuterTransaction() = runBlocking {
        Fixture().use { f ->
            val body = "完整合成压缩总结\n".repeat(2000)
            val first = f.repo.storeCompaction(f.owner, "synthetic-event", body)
            assertTrue(first.ok())
            assertEquals(first, f.repo.storeCompaction(f.owner, "synthetic-event", body))
            assertEquals(body, f.note(first.id()).getValue("body").jsonPrimitive.content)
            assertTrue(f.repo.injectionCandidates(f.owner).isEmpty())
            assertTrue(runCatching { f.db.withTransaction {
                f.repo.storeCompaction(f.owner, "synthetic-rollback-event", "uncommitted summary")
                error("synthetic surrounding compaction rollback")
            } }.isFailure)
            assertEquals(1, f.repo.stats(f.owner).total)
            val tooLarge = "x".repeat(OrbisMemoryBudget.MAX_BODY_BYTES + 1)
            assertEquals("memory_capacity_exceeded", f.repo.compactionCapacityError(tooLarge))
            assertFalse(f.repo.storeCompaction(f.owner, "oversized", tooLarge).ok())
            assertEquals(1, f.repo.stats(f.owner).total)
        }
    }

    @Test fun expectedRevisionAndBodyCapacityRefuseWithoutTruncationOrNewVersion() = runBlocking {
        Fixture().use { f ->
            val id = f.store("synthetic intact")
            val stale = f.execute("update") { put("id", id); put("expectedRevision", 99); put("body", "replacement") }
            assertEquals("memory_revision_conflict", stale.getValue("code").jsonPrimitive.content)
            val oversized = f.execute("update") { put("id", id); put("body", "x".repeat(OrbisMemoryBudget.MAX_BODY_BYTES + 1)) }
            assertEquals("memory_capacity_exceeded", oversized.getValue("code").jsonPrimitive.content)
            assertEquals("synthetic intact", f.note(id).getValue("body").jsonPrimitive.content)
            assertEquals(1, f.note(id).getValue("revision").jsonPrimitive.int)
        }
    }

    @Test fun lexicalCandidatesNeverAutomaticallyMergeOrReturnForeignOriginals() = runBlocking {
        Fixture().use { f ->
            val first = f.store("synthetic common lexical original")
            val second = f.execute("store") { put("body", "synthetic common lexical original with more details") }
            assertEquals(first, second.getValue("similar").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertFalse(second.getValue("merged").jsonPrimitive.boolean)
            assertEquals(2, f.repo.stats(f.owner).total)
            assertEquals("synthetic common lexical original", f.note(first).getValue("body").jsonPrimitive.content)
        }
    }

    companion object {
        private fun JsonObject.ok(): Boolean = getValue("ok").jsonPrimitive.boolean
        private fun JsonObject.id(): String { assertTrue(toString(), ok()); return getValue("id").jsonPrimitive.content }
    }
}
