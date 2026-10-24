package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryFormFlowTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun form() = GalleryQuestionnaire(version = 2, pages = listOf(GalleryFormPage("start", "日常"), GalleryFormPage("out", "出发")), questions = listOf(
        GalleryQuestion("choice", "想怎么度过？", "single", listOf("出门", "在家"), true, "start"),
        GalleryQuestion("place", "去哪里？", "single", listOf("海边", "山间"), true, "out", listOf(GalleryCondition("choice", listOf("出门")))),
        GalleryQuestion("note", "还想说什么？", page = "out", visibleWhen = listOf(GalleryCondition("place", listOf("海边"))))))
    @Test fun branchesOnlyFollowEarlierVisibleChoicesAndPruneStaleDescendants() {
        val before = mapOf("choice" to listOf("出门"), "place" to listOf("海边"), "note" to listOf("一起走"))
        assertEquals(3, visibleGalleryQuestions(form(), before).size)
        val after = before + ("choice" to listOf("在家"))
        assertEquals(mapOf("choice" to listOf("在家")), activeGalleryAnswers(form(), after))
        assertEquals(1, galleryVisiblePages(form(), after).size)
        assertEquals("hidden_question", assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), after) }.reason)
        validateGalleryAnswers(form(), activeGalleryAnswers(form(), after))
    }
    @Test fun futureSelfCyclesUnknownOptionsAndOutOfOrderPagesAreRejected() {
        val base = form()
        val invalid = listOf(
            base.copy(questions = base.questions.mapIndexed { i, q -> if (i == 0) q.copy(visibleWhen = listOf(GalleryCondition("place", listOf("海边")))) else q }),
            base.copy(questions = base.questions.map { if (it.id == "place") it.copy(visibleWhen = listOf(GalleryCondition("place", listOf("海边")))) else it }),
            base.copy(questions = base.questions.map { if (it.id == "place") it.copy(visibleWhen = listOf(GalleryCondition("choice", listOf("不存在")))) else it }),
            base.copy(questions = base.questions.reversed()),
            base.copy(style = GalleryFormStyle(background = "javascript:run()")))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { parseGalleryQuestionnaire(galleryJson.encodeToString(it)) } }
    }
    @Test fun nextPageChecksCurrentRequiredFieldsAndFinalSubmitChecksAllVisibleFields() {
        val answers = mapOf("choice" to listOf("出门"))
        validateGalleryAnswers(form(), answers, setOf("choice"))
        assertEquals("place", assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), answers) }.questionId)
    }
    @Test fun v1KeepsItsMeaningAndV2AnswersReopenWithOriginalSnapshotAfterSourceChanges() {
        val legacy = parseGalleryQuestionnaire("""{"questions":[{"id":"a","text":"旧题"}]}""")
        assertEquals(1, legacy.version); assertEquals("a", galleryVisiblePages(legacy, emptyMap()).single().questions.single().id)
        val repo = GalleryRepository(temporary.root.resolve("orbis-gallery/owner"))
        val item = repo.save("合成", "questionnaire", galleryJson.encodeToString(form()))
        val values = mapOf("choice" to listOf("在家"))
        val answer = repo.submit(item.id, 1, "human", values)
        repo.save("合成", "questionnaire", galleryJson.encodeToString(legacy), id = item.id, expectedRevision = 1)
        val reopened = GalleryRepository(temporary.root.resolve("orbis-gallery/owner")).answers(answer.id)
        assertEquals(values, reopened.answers); assertEquals(form().questions, reopened.questions)
        assertEquals(1, reopened.ref.revision)
    }
}
