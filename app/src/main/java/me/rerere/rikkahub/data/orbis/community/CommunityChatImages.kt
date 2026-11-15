package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ChatImageReadScope
import me.rerere.rikkahub.data.ai.tools.chatImageCode
import me.rerere.rikkahub.data.model.Conversation

/** Explicit selections from human attachments in this generation's owning chat branch. */
internal class CommunityChatImages private constructor(
    private val scope: ChatImageReadScope,
    private val entries: List<JsonObject>,
    private val read: suspend () -> Conversation?,
    private val active: () -> Unit,
) {
    private val codes=entries.map { it["image_code"]!!.jsonPrimitive.content }.toSet()

    suspend fun resolve(selected: List<String>): List<UIMessagePart.Image> {
        active()
        val current=read()
        require(current != null && current.id==scope.conversationId && current.assistantId==scope.owner &&
            current.compactionEpoch==scope.epoch && current.currentMessages.lastOrNull { it.role==MessageRole.USER }?.id==scope.tailUser) {
            "当前聊天已变化，请在新一轮重新选择图片"
        }
        return selected.map { code ->
            require(code in codes) { "图片码不属于本轮当前窗口的人类图片，请先chat_images选图" }
            require(current.currentMessages.any { message ->
                message.role==MessageRole.USER && !message.isSynthetic && message.orbisEvent==null &&
                    message.parts.filterIsInstance<UIMessagePart.Image>().any {
                        chatImageCode(current.assistantId,current.id,message.id,it.url)==code
                    }
            }) { "原人类图片已变化，请重新选图" }
            val image=scope.resolve(code,current)
            require(image.url.startsWith("file:") || image.url.startsWith("content:")) { "这张图片还不是可上传的本地附件" }
            image
        }
    }

    suspend fun page(args: JsonObject): JsonObject {
        require(args.keys.all { it in setOf("action","limit","offset","images") }) { "chat_images只需limit、offset和可选images" }
        fun number(key: String, default: Int): Int = if(key !in args) default else
            (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("${key}需要整数")
        val limit=number("limit",20);val offset=number("offset",0)
        require(limit in 1..50 && offset>=0)
        val rows=entries.drop(offset).take(limit)
        resolve(rows.map { it["image_code"]!!.jsonPrimitive.content })
        return buildJsonObject {
            put("items",JsonArray(rows));put("total",entries.size)
            put("next_offset",if(entries.size-offset>rows.size) JsonPrimitive(offset+rows.size) else JsonNull)
            put("scope","current_chat_human_images");put("images_loaded",false)
            put("help","按最新消息在前列出当前聊天的人类图片；image_index是该消息内图片次序。publish传image_codes最多9张，send_message最多4张；不填则纯文字。images=true可预览本页前两张。")
        }
    }

    suspend fun upload(selected: List<String>, send: suspend (UIMessagePart.Image)->String): List<String> {
        resolve(selected) // Validate the complete selection before uploading anything.
        val media=selected.map { code ->
            val image=resolve(listOf(code)).single()
            communityUuid(send(image))
        }
        resolve(selected) // Upload can suspend; never publish into a changed branch.
        require(media.distinct().size==media.size) { "图片上传回执重复，请核对后重试" }
        return media
    }

    companion object {
        fun capture(current: Conversation, read: suspend () -> Conversation?, active: () -> Unit): CommunityChatImages {
            val human=current.currentMessages.filter {
                it.role==MessageRole.USER && !it.isSynthetic && it.orbisEvent==null && it.parts.any { p -> p is UIMessagePart.Image }
            }
            val scope=ChatImageReadScope.capture(current,human.map { it.id }.toSet())
            val entries=human.asReversed().flatMap { message ->
                message.parts.filterIsInstance<UIMessagePart.Image>().mapIndexed { index,image -> buildJsonObject {
                    put("image_code",chatImageCode(current.assistantId,current.id,message.id,image.url))
                    put("message_id",message.id.toString());put("created_at",message.createdAt.toString());put("image_index",index+1)
                } }
            }.distinctBy { it["image_code"] }
            return CommunityChatImages(scope,entries,read,active)
        }
    }
}
