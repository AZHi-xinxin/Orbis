package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal val galleryJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** Bodies live in separate immutable files, never in the chat database or the catalogue. */
@Serializable internal data class GalleryVersion(val revision: Int, val bytes: Int, val sha256: String, val createdAt: Long, val actor: String)
@Serializable internal data class GalleryItem(
    val id: String, val title: String, val kind: String, val author: String, val createdAt: Long,
    val updatedAt: Long, val versions: List<GalleryVersion>, val deletedAt: Long? = null,
    val deletedBy: String? = null, val favorite: Boolean = false, val preview: String = "",
) { val current get() = versions.last() }
@Serializable internal data class GalleryAnswerRef(val id: String, val itemId: String, val revision: Int, val actor: String, val createdAt: Long)
@Serializable internal data class GalleryIndex(
    val version: Int = 1, val customName: String = "", val items: List<GalleryItem> = emptyList(),
    val answers: List<GalleryAnswerRef> = emptyList(),
)
@Serializable internal data class GalleryQuestion(val id: String, val text: String, val type: String = "text", val options: List<String> = emptyList(), val required: Boolean = false)
@Serializable internal data class GalleryQuestionnaire(val version: Int = 1, val respondent: String = "human", val description: String = "", val questions: List<GalleryQuestion>)
@Serializable internal data class GalleryAnswers(val ref: GalleryAnswerRef, val questions: List<GalleryQuestion>, val answers: Map<String, List<String>>)
@Serializable internal data class GalleryDraft(val id: String, val itemId: String, val title: String, val kind: String, val expectedRevision: Int, val actor: String, val createdAt: Long)

internal object GalleryLimits {
    const val BODY_BYTES = 16 * 1024 * 1024
    const val CHUNK_BYTES = 32 * 1024
    const val READ_BYTES = 16 * 1024
    const val INDEX_BYTES = 8 * 1024 * 1024
    const val QUESTIONNAIRE_BYTES = 1024 * 1024
    const val ITEMS = 2000
    const val VERSIONS = 200
    const val ANSWERS = 10000
    val kinds = setOf("html", "text", "questionnaire")
    fun id(value: String) = value.matches(Regex("[a-zA-Z0-9_-]{1,80}"))
    fun title(value: String) = value.isNotBlank() && value.length <= 120 && value.none(Char::isISOControl)
}

internal fun galleryUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

/** Bound native metadata fields in UTF-16 units without cutting an emoji surrogate pair. */
internal fun galleryTextPrefix(text: String, maxUnits: Int): String {
    require(maxUnits >= 0)
    var end = minOf(text.length, maxUnits)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end)
}

internal fun galleryHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun InputStream.galleryReadBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 65536))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
        if (count == -1) break
        require(out.size() + count <= limit) { "gallery_file_too_large" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

/** Offsets are UTF-8 bytes; reject a split code point instead of replacing it silently. */
internal fun gallerySlice(bytes: ByteArray, offset: Int, budget: Int = GalleryLimits.READ_BYTES): Pair<String, Int?> {
    require(offset in 0..bytes.size && (offset == bytes.size || bytes[offset].toInt() and 0xc0 != 0x80)) { "gallery_offset_invalid" }
    var end = minOf(bytes.size, offset + budget)
    while (end > offset && end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end--
    return galleryUtf8(bytes.copyOfRange(offset, end)) to end.takeIf { it < bytes.size }
}

internal fun parseGalleryQuestionnaire(text: String): GalleryQuestionnaire {
    require(text.toByteArray().size <= GalleryLimits.QUESTIONNAIRE_BYTES) { "gallery_questionnaire_too_large" }
    return galleryJson.decodeFromString<GalleryQuestionnaire>(text).also { form ->
        require(form.version == 1 && form.respondent in setOf("human", "ai")) { "gallery_questionnaire_invalid" }
        require(form.description.length <= 8000 && form.questions.size in 1..200) { "gallery_questionnaire_invalid" }
        require(form.questions.map { it.id }.distinct().size == form.questions.size) { "gallery_questionnaire_invalid" }
        form.questions.forEach { q ->
            require(GalleryLimits.id(q.id) && q.text.isNotBlank() && q.text.length <= 4000 && q.type in setOf("text", "single", "multiple")) { "gallery_questionnaire_invalid" }
            require(q.options.size <= 50 && q.options.distinct().size == q.options.size && q.options.all { it.isNotBlank() && it.length <= 500 }) { "gallery_questionnaire_invalid" }
            require(if (q.type == "text") q.options.isEmpty() else q.options.size >= 2) { "gallery_questionnaire_invalid" }
        }
    }
}

internal fun validateGalleryAnswers(form: GalleryQuestionnaire, answers: Map<String, List<String>>) {
    require(answers.keys.all { id -> form.questions.any { it.id == id } }) { "gallery_answers_invalid" }
    form.questions.forEach { q ->
        val values = answers[q.id].orEmpty()
        require(values.size <= 50 && values.distinct().size == values.size && values.all { it.length <= 4000 }) { "gallery_answers_invalid" }
        require(!q.required || values.any { it.isNotBlank() }) { "gallery_required_answer" }
        require(if (q.type == "text") values.size <= 1 else values.all { it in q.options } && (q.type != "single" || values.size <= 1)) { "gallery_answers_invalid" }
    }
}

internal fun galleryErrorText(error: Throwable): String = when (error.message) {
    "gallery_file_too_large" -> "单件作品最多 16 MiB。长作品可分块写入；原有作品未被覆盖。"
    "gallery_chunk_too_large" -> "单次工具写入最多 32 KiB，请拆分为多块；不是整件作品的上限。"
    "gallery_revision_changed" -> "作品已被修改，请重新读取版本后再保存。"
    "gallery_required_answer" -> "请填写所有标为必填的题目。"
    "gallery_answer_role" -> "不能代填另一方的答案。人类答案只能在原生表单中由本人提交。"
    "gallery_questionnaire_invalid", "gallery_questionnaire_too_large", "gallery_answers_invalid" -> "问卷或答案格式不符合要求，请核对题目、选项与填写对象。"
    "gallery_ai_delete_owner" -> "AI 只能删除自己创建的作品；人类导入的内容请由人类在格子中删除。"
    "gallery_space_low" -> "存储空间不足，未覆盖原作品。请释放空间后再试。"
    "gallery_too_many_drafts" -> "未发布草稿已满，请在格子中打开「管理未发布草稿」核对并丢弃不需要的草稿。已发布作品不受影响。"
    "gallery_catalog_full" -> "格子已达作品或索引保护上限，旧内容未改动。请先导出备份后整理；已删除作品仍保留版本与留痕，不能靠重复删除绕过上限。"
    "gallery_not_found" -> "作品不存在或已删除，请刷新目录。"
    "gallery_url_blocked" -> "只支持公开 HTTPS 问卷地址，不允许账号凭据、内网地址或重定向。可改为导入 HTML 文件。"
    "gallery_no_questions" -> "未找到可识别的题目。可导入结构化问卷 JSON、带输入项的 HTML，或编号 / 问号结尾的纯文本题目。动态网页未执行。"
    else -> "本次操作未确认完成。原记录不会重置；请刷新核对后再试。"
}
