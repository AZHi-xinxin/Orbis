package me.rerere.rikkahub.data.orbis.activities

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StudyImportDocument(val subject: String, val plan: String = "", val questions: List<StudyInputQuestion>)
data class StudyImportPreview(val document: StudyImportDocument?, val original: String, val issues: List<String>)

/** Parsing is a preview only. The original remains available; no imports execute instructions or write progress. */
object StudyImport {
    private val json = Json { ignoreUnknownKeys = false }
    fun preview(text: String): StudyImportPreview {
        if (text.toByteArray().size > 1024 * 1024) return StudyImportPreview(null, "", listOf("文件超过 1 MB，请分批导入。"))
        return try {
            val doc = if (text.trimStart().startsWith("{")) json.decodeFromString<StudyImportDocument>(text) else markdown(text)
            require(doc.subject.isNotBlank() && doc.subject.length <= 80 && doc.plan.length <= 8000)
            require(doc.questions.size in 1..100)
            doc.questions.forEach(StudyRepository::validateInput)
            StudyImportPreview(doc, text, emptyList())
        } catch (_: Exception) { StudyImportPreview(null, text, listOf("题目格式尚未识别。原文保留在下方，修改后重新预览；没有写入题库。")) }
    }
    // Deliberately explicit exchange format, not a guess at arbitrary prose or an LLM call.
    private fun markdown(text: String): StudyImportDocument {
        val lines = text.replace("\r\n", "\n").lines()
        val subject = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: error("subject")
        val starts = lines.indices.filter { lines[it].startsWith("## ") }
        require(starts.isNotEmpty())
        val questions = starts.mapIndexed { index, start ->
            val kind = lines[start].removePrefix("## ").trim()
            val block = lines.subList(start + 1, starts.getOrNull(index + 1) ?: lines.size)
            val answerLine = block.singleOrNull { it.startsWith("答案: ") } ?: error("answer")
            val options = block.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
            val explanation = block.filter { it.startsWith("解析: ") }.joinToString("\n") { it.removePrefix("解析: ") }
            val prompt = block.filterNot { it.startsWith("- ") || it.startsWith("答案: ") || it.startsWith("解析: ") }.joinToString("\n").trim()
            StudyInputQuestion(kind, prompt, options, answerLine.removePrefix("答案: ").split(" | "), explanation, source = "手动导入")
        }
        return StudyImportDocument(subject, questions = questions)
    }
    val example = """
        # 数学
        ## single
        1 + 1 等于多少？
        - 1
        - 2
        - 3
        答案: 2
        解析: 两个一相加等于二。

        ## written
        请说明你的计算思路。
        答案: 能清楚说明相加过程即可。
    """.trimIndent()
}
