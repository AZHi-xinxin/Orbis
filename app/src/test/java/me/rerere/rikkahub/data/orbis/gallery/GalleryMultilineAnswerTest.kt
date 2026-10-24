package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryMultilineAnswerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun shelf() = temporary.root.resolve("orbis-gallery/owner").also { it.mkdirs() }
    private fun form() = GalleryQuestionnaire(respondent = "ai", questions = listOf(
        GalleryQuestion("q7", "描述", required = true), GalleryQuestion("q9", "描述二"),
        GalleryQuestion("q10", "选项", "single", listOf("甲", "乙"))))
    @Test fun actualLfCrLfUnicodeAndLiteralBackslashSurviveSubmitAndReopenExactly() {
        val root = shelf()
        val repo = GalleryRepository(root, now = { 42 })
        val item = repo.save("合成问卷", "questionnaire", galleryJson.encodeToString(form()), "ai")
        val text = "第一行\n第二行\r\nemoji 🌙 与字面 \\n，\"引号\""
        val answers = mapOf("q7" to listOf(text), "q9" to listOf("段落\n".repeat(1800)), "q10" to listOf("乙"))
        val ref = repo.submit(item.id, 1, "ai", answers)
        assertEquals(answers, GalleryRepository(root).answers(ref.id).answers)
    }
    @Test fun textArrayShapeErrorNamesKnownQuestionWithoutDroppingParagraphs() {
        val error = assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), mapOf("q7" to listOf("第一段", "第二段"))) }
        assertEquals("q7", error.questionId); assertEquals("text_requires_one_string", error.reason)
        assertTrue(galleryErrorText(error).contains("换行"))
    }
    @Test fun blankRequiredInvalidOptionsAndUnknownKeysAreDistinct() {
        val required = assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), emptyMap()) }
        assertEquals("required", required.reason)
        val option = assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), mapOf("q7" to listOf("有"), "q10" to listOf("丙"))) }
        assertEquals("q10", option.questionId); assertEquals("option_not_in_question", option.reason)
        val unknown = assertThrows(GalleryAnswerProblem::class.java) { validateGalleryAnswers(form(), mapOf("do not echo private text" to listOf("有"))) }
        assertEquals("", unknown.questionId); assertFalse(galleryErrorText(unknown).contains("private"))
    }
    @Test fun overBudgetRefusesWholeSubmissionAndRetainsEarlierAnswer() {
        val repo = GalleryRepository(shelf())
        val item = repo.save("合成问卷", "questionnaire", galleryJson.encodeToString(form()), "ai")
        val ref = repo.submit(item.id, 1, "ai", mapOf("q7" to listOf("保留")))
        val bad = assertThrows(GalleryAnswerProblem::class.java) { repo.submit(item.id, 1, "ai", mapOf("q7" to listOf("文".repeat(30000)))) }
        assertEquals("text_byte_limit", bad.reason)
        assertEquals(1, repo.snapshot().answers.size); assertEquals(listOf("保留"), repo.answers(ref.id).answers["q7"])
    }
}
