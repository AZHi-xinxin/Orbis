package me.rerere.rikkahub.data.orbis.gallery

/** Only earlier choice answers may control visibility. No executable expressions or cycles. */
internal fun validateGalleryFormLayout(form: GalleryQuestionnaire) {
    fun valid(ok: Boolean) = require(ok) { "gallery_questionnaire_invalid" }
    valid(form.pages.size <= 30 && form.pages.map { it.id }.distinct().size == form.pages.size)
    form.pages.forEach { valid(GalleryLimits.id(it.id) && it.title.isNotBlank() && it.title.length <= 120 && it.description.length <= 2000) }
    valid(form.style.palette in setOf("ocean", "paper", "night") && form.style.background in setOf("waves", "stars", "plain"))
    valid(form.style.heading in setOf("serif", "sans") && form.style.cornerRadius in 12..32)
    var previousPage = -1
    form.questions.forEachIndexed { index, question ->
        if (form.pages.isEmpty()) valid(question.page.isEmpty())
        else {
            val pageIndex = form.pages.indexOfFirst { it.id == question.page }
            valid(pageIndex >= previousPage && pageIndex >= 0); previousPage = pageIndex
        }
        valid(question.visibleWhen.size <= 8)
        question.visibleWhen.forEach { condition ->
            val source = form.questions.take(index).firstOrNull { it.id == condition.questionId }
            valid(source != null && source.type in setOf("single", "multiple"))
            valid(condition.anyOf.isNotEmpty() && condition.anyOf.distinct().size == condition.anyOf.size && condition.anyOf.all { it in source!!.options })
        }
    }
    valid(form.pages.all { p -> form.questions.any { it.page == p.id } })
}

internal fun visibleGalleryQuestions(form: GalleryQuestionnaire, answers: Map<String, List<String>>): List<GalleryQuestion> {
    val visible = mutableSetOf<String>()
    return form.questions.filter { q ->
        q.visibleWhen.all { c -> c.questionId in visible && answers[c.questionId].orEmpty().any { it in c.anyOf } }
            .also { if (it) visible += q.id }
    }
}

internal fun activeGalleryAnswers(form: GalleryQuestionnaire, answers: Map<String, List<String>>): Map<String, List<String>> {
    val ids = visibleGalleryQuestions(form, answers).map { it.id }.toSet()
    return answers.filterKeys { it in ids }
}

internal data class GalleryVisiblePage(val id: String, val title: String, val description: String, val questions: List<GalleryQuestion>)
internal fun galleryVisiblePages(form: GalleryQuestionnaire, answers: Map<String, List<String>>): List<GalleryVisiblePage> {
    val visible = visibleGalleryQuestions(form, answers)
    if (form.pages.isEmpty()) return visible.chunked(4).mapIndexed { index, questions -> GalleryVisiblePage("legacy-$index", "第 ${index + 1} 页", "", questions) }
    return form.pages.mapNotNull { page ->
        visible.filter { it.page == page.id }.takeIf { it.isNotEmpty() }?.let { GalleryVisiblePage(page.id, page.title, page.description, it) }
    }
}
