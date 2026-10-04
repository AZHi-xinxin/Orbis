package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(folder: String = "files", owner: String = "owner"): GalleryRepository =
        GalleryRepository(File(temporary.root, "$folder/orbis-gallery/$owner")) { 42L }
    private fun form(actor: String = "human") = GalleryQuestionnaire(respondent = actor, questions = listOf(
        GalleryQuestion("q1", "今天想要什么？", "single", listOf("拥抱", "安静"), true), GalleryQuestion("q2", "想说的话")))
    @Test fun emptyReadDoesNotCreateFiles() {
        assertTrue(store().snapshot().items.isEmpty()); assertFalse(File(temporary.root, "files").exists())
    }
    @Test fun largeUnicodeArtworkBeyondOld512KiBIsStoredAndPagedExactly() {
        val source = "🌙你好".repeat(100000)
        val repository = store(); val item = repository.save("夜色", "text", source, "ai")
        assertTrue(item.current.bytes > 512 * 1024)
        var offset = 0; val output = StringBuilder()
        do { val (text, next) = repository.readPage(item.id, 1, offset); assertTrue(text.toByteArray().size <= GalleryLimits.READ_BYTES); output.append(text); offset = next ?: -1 } while (offset >= 0)
        assertEquals(source, output.toString()); assertEquals(source, store().body(item.id))
    }
    @Test fun appendRetryIsIdempotentAndDifferentBytesAreRejected() {
        val repository = store(); val draft = repository.begin("标题", "text", "ai")
        assertEquals(6, repository.append(draft.id, 0, "你好", "ai"))
        assertEquals(6, repository.append(draft.id, 0, "你好", "ai"))
        assertThrows(IllegalArgumentException::class.java) { repository.append(draft.id, 0, "再见", "ai") }
        val item = repository.publish(draft.id, "ai"); assertEquals("你好", repository.body(item.id))
    }
    @Test fun independentInstancesShareRevisionConflictProtection() {
        val item = store().save("标题", "text", "first")
        val a = store().begin("标题", "text", "ai", item.id, 1)
        val b = store().begin("标题", "text", "ai", item.id, 1)
        store().append(a.id, 0, "new", "ai"); store().publish(a.id, "ai")
        store().append(b.id, 0, "conflicting", "ai")
        assertThrows(IllegalArgumentException::class.java) { store().publish(b.id, "ai") }
        assertEquals("first", store().body(item.id, 1)); assertEquals("new", store().body(item.id))
    }
    @Test fun updatePreservesVersionsAndOriginalAuthorship() {
        val first = store().save("礼物", "html", "<p>one</p>", "human")
        val second = store().save("新版", "html", "<p>two</p>", "ai", first.id, 1)
        assertEquals("human", second.author); assertEquals(2, second.versions.size)
        assertEquals("<p>one</p>", store().body(first.id, 1))
        assertThrows(IllegalArgumentException::class.java) { store().delete(first.id, 2, "ai") }
    }
    @Test fun deleteLeavesAuditAndBodiesButNoReadableNormalEntry() {
        val repository = store(); val item = repository.save("礼物", "text", "kept", "ai")
        repository.delete(item.id, 1, "ai")
        val deleted = repository.snapshot().items.single()
        assertEquals("ai", deleted.deletedBy); assertEquals(42L, deleted.deletedAt)
        assertThrows(IllegalStateException::class.java) { repository.body(item.id) }
        repository.withExportFiles { _, files -> assertEquals(2, files.size) }
    }
    @Test fun answersRetainQuestionVersionTimeAndTrustworthyActor() {
        val repository = store(); val item = repository.save("问卷", "questionnaire", galleryJson.encodeToString(form()), "ai")
        val answers = mapOf("q1" to listOf("安静"), "q2" to listOf("今天很好"))
        assertThrows(IllegalArgumentException::class.java) { repository.submit(item.id, 1, "ai", answers) }
        val ref = repository.submit(item.id, 1, "human", answers)
        repository.save("问卷", "questionnaire", galleryJson.encodeToString(form().copy(description = "new")), "ai", item.id, 1)
        val saved = repository.answers(ref.id)
        assertEquals("human", saved.ref.actor); assertEquals(42L, saved.ref.createdAt); assertEquals(1, saved.ref.revision)
        assertEquals(answers, saved.answers); assertEquals(form().questions, saved.questions)
    }
    @Test fun aiCanAnswerAiQuestionsButHumanCannotImpersonateAi() {
        val repository = store(); val item = repository.save("问AI", "questionnaire", galleryJson.encodeToString(form("ai")))
        assertThrows(IllegalArgumentException::class.java) { repository.submit(item.id, 1, "human", mapOf("q1" to listOf("安静"))) }
        assertEquals("ai", repository.submit(item.id, 1, "ai", mapOf("q1" to listOf("安静"))).actor)
    }
    @Test fun invalidAnswersDoNotCreateSubmission() {
        val repository = store(); val item = repository.save("问卷", "questionnaire", galleryJson.encodeToString(form()))
        listOf(emptyMap(), mapOf("q1" to listOf("不存在")), mapOf("q1" to listOf("安静", "拥抱")), mapOf("q1" to listOf("安静"), "missing" to listOf("x"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { repository.submit(item.id, 1, "human", it) }
        }
        assertTrue(repository.snapshot().answers.isEmpty())
    }
    @Test fun invalidChunkAndPathDoNotWrite() {
        val repository = store(); val draft = repository.begin("标题", "text", "ai")
        assertThrows(IllegalArgumentException::class.java) { repository.append(draft.id, 0, "x".repeat(GalleryLimits.CHUNK_BYTES + 1), "ai") }
        assertThrows(IllegalArgumentException::class.java) { repository.append("../private", 0, "x", "ai") }
        assertThrows(IllegalArgumentException::class.java) { repository.append(draft.id, 0, "x", "human") }
        assertTrue(repository.snapshot().items.isEmpty())
    }
    @Test fun badQuestionnaireNeverPublishesOverOldVersion() {
        val repository = store(); val first = repository.save("问卷", "questionnaire", galleryJson.encodeToString(form()))
        assertThrows(Exception::class.java) { repository.save("问卷", "questionnaire", "{bad", "ai", first.id, 1) }
        assertEquals(1, repository.snapshot().items.single().current.revision)
    }
    @Test fun missingCatalogueDoesNotBecomeAnEmptyLibrary() {
        store().save("记录", "text", "keep")
        File(temporary.root, "files/orbis-gallery/owner/index.json").delete()
        assertThrows(IllegalArgumentException::class.java) { store().snapshot() }
    }
    @Test fun utf8SlicesRefuseMidCharacterOffsets() {
        assertThrows(IllegalArgumentException::class.java) { gallerySlice("你好".toByteArray(), 1) }
        assertEquals("你", gallerySlice("你好".toByteArray(), 0, 4).first)
    }
    @Test fun firstPublicationFailureAfterBodyWriteKeepsLibraryOpenAndDraftRetryable() {
        val root = File(temporary.root, "files/orbis-gallery/owner")
        var commits = 0
        val repository = GalleryRepository(root, beforeCommit = { if (++commits == 2) error("injected after immutable body") })
        val draft = repository.begin("礼物", "text", "ai")
        repository.append(draft.id, 0, "完整内容", "ai")
        assertThrows(IllegalStateException::class.java) { repository.publish(draft.id, "ai") }
        assertTrue(store().snapshot().items.isEmpty())
        val published = store().publish(draft.id, "ai")
        assertEquals("完整内容", store().body(published.id))
    }
    @Test fun pendingDraftsCannotPublishPastCatalogueLimitAndBreakTheShelf() {
        val first = store().save("seed", "text", "sample", "ai")
        val indexFile = File(temporary.root, "files/orbis-gallery/owner/index.json")
        val index = store().snapshot().copy(items = (1 until GalleryLimits.ITEMS).map { first.copy(id = "item$it") })
        indexFile.writeText(galleryJson.encodeToString(index))
        val a = store().begin("A", "text", "ai"); val b = store().begin("B", "text", "ai")
        store().append(a.id, 0, "a", "ai"); store().append(b.id, 0, "b", "ai")
        store().publish(a.id, "ai")
        assertThrows(IllegalArgumentException::class.java) { store().publish(b.id, "ai") }
        assertEquals(GalleryLimits.ITEMS, store().snapshot().items.size)
        assertTrue(store().drafts().any { it.id == b.id })
    }
    @Test fun humanCanDiscardLostAiDraftWithoutTouchingPublishedWorks() {
        val item = store().save("保存的", "text", "keep")
        val draft = store().begin("遗留草稿", "text", "ai")
        assertEquals(draft, store().drafts().single())
        store().discardDraftByHuman(draft.id)
        assertTrue(store().drafts().isEmpty()); assertEquals("keep", store().body(item.id))
    }
    @Test fun aiLongAnswersCanBeWrittenInChunksAndReadBackWithoutTruncation() {
        val questions = (1..20).map { GalleryQuestion("q$it", "第 $it 题") }
        val form = GalleryQuestionnaire(respondent = "ai", questions = questions)
        val item = store().save("长问卷", "questionnaire", galleryJson.encodeToString(form))
        val values = questions.associate { it.id to listOf("回答".repeat(1000)) }
        val draft = store().beginAnswer(item.id, 1)
        val bytes = galleryJson.encodeToString(values).toByteArray()
        assertTrue(bytes.size > GalleryLimits.CHUNK_BYTES)
        var offset = 0
        while (offset < bytes.size) { val page = gallerySlice(bytes, offset, GalleryLimits.CHUNK_BYTES); offset = store().append(draft.id, offset, page.first, "ai") }
        val answer = store().publishAnswer(draft.id)
        assertEquals(values, store().answers(answer.id).answers); assertEquals("ai", answer.actor)
        assertEquals(answer, store().publishAnswer(draft.id))
        assertEquals(1, store().snapshot().answers.size)
    }
    @Test fun aiAnswerDraftCannotSubmitAgainstChangedQuestionnaireOrAnswerHumanForm() {
        val human = store().save("人类问卷", "questionnaire", galleryJson.encodeToString(form()))
        assertThrows(IllegalArgumentException::class.java) { store().beginAnswer(human.id, 1) }
        val source = galleryJson.encodeToString(form("ai"))
        val item = store().save("AI问卷", "questionnaire", source)
        val draft = store().beginAnswer(item.id, 1)
        store().append(draft.id, 0, "{\"q1\":[\"安静\"]}", "ai")
        store().save("AI问卷", "questionnaire", source, "human", item.id, 1)
        assertThrows(IllegalArgumentException::class.java) { store().publishAnswer(draft.id) }
        assertTrue(store().snapshot().answers.isEmpty())
    }
    @Test fun aiAnswerFailureBeforeIndexCommitCanRetryWithoutDuplicateSubmission() {
        val item = store().save("AI问卷", "questionnaire", galleryJson.encodeToString(form("ai")))
        val draft = store().beginAnswer(item.id, 1)
        store().append(draft.id, 0, "{\"q1\":[\"安静\"]}", "ai")
        val failing = GalleryRepository(File(temporary.root, "files/orbis-gallery/owner"), beforeCommit = { error("injected") })
        assertThrows(IllegalStateException::class.java) { failing.publishAnswer(draft.id) }
        assertTrue(store().snapshot().answers.isEmpty())
        val answer = store().publishAnswer(draft.id)
        assertEquals(draft.id, answer.id)
        assertEquals(answer, store().publishAnswer(draft.id))
        assertEquals(1, store().snapshot().answers.size)
    }
}
