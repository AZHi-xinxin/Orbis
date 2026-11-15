package me.rerere.rikkahub.data.orbis.market

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException
import me.rerere.rikkahub.data.orbis.gallery.parseGalleryQuestionnaire

/** A fresh editor inherits an explicit shelf selection, never silently chooses a destination. */
internal fun marketEditorCategory(existing: String?, shelf: String): String =
    (existing ?: shelf).takeIf { it in setOf("extra", "grid") }.orEmpty()

internal data class MarketSaveRequest(val text: String, val kind: String)

/** Pure validation only. Rejections here prove that no save was attempted. */
internal fun marketSaveRequest(category: String, raw: JsonElement): MarketSaveRequest {
    val args = raw as? JsonObject
        ?: throw ToolRejectedBeforeExecutionException("保存参数必须是对象；尚未写入作品。")
    if (args.keys.any { it !in setOf("text", "kind") } ||
        (args["text"] as? JsonPrimitive)?.isString != true ||
        ("kind" in args && (args["kind"] as? JsonPrimitive)?.isString != true))
        throw ToolRejectedBeforeExecutionException("请提供字符串 text 和可选的 kind；尚未写入作品。")
    val body = args.getValue("text").jsonPrimitive.content
    val kind = args["kind"]?.jsonPrimitive?.content?.ifBlank { "text" } ?: "text"
    validateMarketBody(category, body, kind)
    return MarketSaveRequest(body, kind)
}

internal fun validateMarketBody(category: String, body: String, kind: String) {
    if (body.isBlank() || body.codePointCount(0, body.length) > 150_000)
        throw ToolRejectedBeforeExecutionException("正文为空或超过15万字；尚未写入作品。")
    if (category !in setOf("extra", "grid") || kind !in setOf("text", "html", "questionnaire"))
        throw ToolRejectedBeforeExecutionException("作品分类或格式无效；尚未写入作品。")
    if (category == "extra" && kind != "text")
        throw ToolRejectedBeforeExecutionException("这份设定被发布为番外，目标是秘密基地，只接受文字；HTML和问卷应属于格子。尚未写入作品，请告知人类核对分类并保留已生成内容，不要把格式改成text强行保存。")
    if (kind == "questionnaire") try { parseGalleryQuestionnaire(body) }
    catch (failure: IllegalArgumentException) {
        throw ToolRejectedBeforeExecutionException("问卷格式不符合格子要求；尚未写入作品，请核对原生问卷格式。", failure)
    }
}
