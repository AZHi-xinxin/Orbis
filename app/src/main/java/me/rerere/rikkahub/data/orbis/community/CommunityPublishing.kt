package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Explicit AI-authored community operations. Only these arguments cross the network;
 * Only selected human image attachments may be uploaded; no other chat material is copied. */
internal suspend fun communityPublication(api: CommunityApi, actor: String, args: JsonObject,
    chatImages: CommunityChatImages? = null,
    upload: suspend (me.rerere.ai.ui.UIMessagePart.Image)->String = { error("当前窗口不可上传图片") },
): JsonObject {
    val action=args.ms("action")
    val allowed=when(action) {
        "publish" -> setOf("action","text","visibility","mentions","image_codes")
        "comment" -> setOf("action","target","text","mentions","reply_to")
        "like" -> setOf("action","target","liked")
        "delete_post" -> setOf("action","target")
        "delete_comment" -> setOf("action","target")
        "send_message" -> setOf("action","target","text","mentions","reply_to","image_codes")
        "recall_message" -> setOf("action","target","message_id")
        else -> error("未知社区发表操作")
    }
    require(args.keys.all { it in allowed }) { "只填写当前动作的字段；身份由宿主绑定，不能指定其他人或本地空间" }
    val imageCodes=if("image_codes" in args) {
        val values=args["image_codes"] as? JsonArray ?: error("image_codes需要图片码数组")
        val maximum=if(action=="send_message") 4 else 9
        require(values.size<=maximum) { "本动作最多${maximum}张图片" }
        values.map { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.matches(Regex("orbisimg_[0-9a-f]{64}")) }?.content
                ?: error("请用chat_images返回的图片码，不接受文件路径、网址或社区媒体ID")
        }.also { require(it.distinct().size==it.size) { "不要重复选择同一图片码" } }
    } else emptyList()
    fun content(max: Int): String {
        if("text" !in args && imageCodes.isNotEmpty()) return ""
        val text=(args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("需要 text 正文")
        require((text.isNotBlank() || imageCodes.isNotEmpty()) && text.codePointCount(0,text.length)<=max) { "正文不能为空，最多${max}字；图片帖可不填正文" }
        return text
    }
    var visibility: String?=null
    val body=when(action) {
        "publish" -> {
            visibility=(args["visibility"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(visibility in setOf("public","friends")) { "发社区朋友圈必须选择 visibility=public（公开）或 friends（仅好友）" }
            communityBody("text" to content(4000),"visibility" to visibility!!)
        }
        "comment" -> communityBody("post" to communityUuid(args.ms("target")),"text" to content(1000))
        "send_message" -> buildJsonObject {
            put("room",communityUuid(args.ms("target"))); put("text",content(4000))
            if("reply_to" in args) {
                put("reply_to",communityUuid(args.ms("reply_to")))
            }
            if("mentions" in args) {
                val mentions=args["mentions"] as? JsonArray ?: error("mentions需要成员UUID数组")
                require(mentions.size<=20)
                put("mentions",JsonArray(mentions.map { value ->
                    val id=(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("艾特成员必须是UUID")
                    JsonPrimitive(communityUuid(id))
                }.distinct()))
            }
        }
        "recall_message" -> communityBody("action" to "remove","room" to communityUuid(args.ms("target")),"id" to communityUuid(args.ms("message_id")))
        "like" -> buildJsonObject {
            put("post",communityUuid(args.ms("target")))
            put("liked",(args["liked"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: error("需要 liked=true 或 false"))
        }
        else -> communityBody("action" to "remove","id" to communityUuid(args.ms("target")))
    }
    val outgoing=if(action in setOf("publish","comment")) JsonObject(body+buildJsonObject {
        if("mentions" in args) {
            val mentioned=args["mentions"] as? JsonArray ?: error("mentions需要成员UUID数组")
            require(mentioned.size<=20)
            put("mentions",JsonArray(mentioned.map { JsonPrimitive(communityUuid(it.jsonPrimitive.content)) }.distinct()))
        }
        if(action=="comment" && "reply_to" in args) put("reply_to",communityUuid(args.ms("reply_to")))
    }) else body
    val media=if(imageCodes.isEmpty()) emptyList() else
        (chatImages ?: error("当前没有可用聊天图片，请先chat_images查询")).upload(imageCodes,upload)
    val submitted=if(media.isEmpty()) outgoing else JsonObject(outgoing+mapOf("media" to JsonArray(media.map(::JsonPrimitive))))
    if(media.isNotEmpty()) chatImages!!.resolve(imageCodes)
    val result=api.post(when(action) { "comment","delete_comment"->"/comments"; "send_message","recall_message"->"/messages"; "like"->"/likes"; else->"/posts" },actor,submitted)
    if(action in setOf("publish","comment","send_message")) communityUuid(result.ms("id"))
    if(action=="send_message") require(result.cl("seq")>0) { "群发送回执缺少消息序号，请用reconcile核对" }
    if(media.isNotEmpty()) {
        val id=result.ms("id")
        try {
            val saved=if(action=="publish") api.get("/posts/$id",actor) else
                api.get("/rooms/${args.ms("target")}/messages?from=${result.cl("seq")}&limit=1",actor).ma("items").single {it.ms("id")==id}
            require(saved.ms("id")==id && saved.ms("actor")==actor && saved.cs("media")==media)
            require(if(action=="publish") saved.ms("visibility")==visibility else saved.ms("room")==args.ms("target") && saved.cl("seq")==result.cl("seq"))
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(_:Exception) {
            val help=if(action=="publish") "read_post(target=$id)" else "read(target=${args.ms("target")},from_sequence=${result.cl("seq")},limit=1)"
            error("${id}已收到提交回执，但图片读回未完成；请$help 核对，不要重复$action")
        }
    }
    if(action=="send_message" && "reply_to" in args) require(result.ms("reply_to")==args.ms("reply_to") && result["quote_notifies"]==JsonPrimitive(false)) { "引用回执未确认，请核对结果" }
    if(action=="recall_message") require(result.mb("recalled") && result.ms("id")==args.ms("message_id") && result.ms("room")==args.ms("target") && result.ms("actor")==actor) { "服务端未确认撤回自己的这条群消息，请核对回执" }
    if(action=="delete_comment") require(result.mb("deleted") && result.ms("id")==args.ms("target")) { "服务端未确认删除这条评论，请核对回执" }
    return buildJsonObject {
        result.forEach { (key,value)->put(key,value) }
        put("ok",true); put("scope","community"); put("action",action); put("actor",actor)
        visibility?.let { put("visibility",it); put("published",true) }
        if(media.isNotEmpty()) {put("media",JsonArray(media.map(::JsonPrimitive)));put("image_count",media.size);put("images_verified",true)}
        if(action=="send_message") { put("sent",true); put("room",args.ms("target")) }
    }
}

/** Keep complete posts, a single mixed feed, and a cursor for the last returned row. */
internal fun boundedCommunityFeed(page: JsonObject): JsonObject {
    val rows=page.ma("items"); val selected=mutableListOf<JsonObject>(); var bytes=0
    for(row in rows) {
        val size=row.toString().toByteArray(Charsets.UTF_8).size
        if(bytes+size>56*1024) break
        selected+=row; bytes+=size
    }
    check(rows.isEmpty() || selected.isNotEmpty()) { "单条朋友圈超过本次读取上限" }
    return buildJsonObject {
        put("items",JsonArray(selected))
        put("next_before",if(selected.size<rows.size) JsonPrimitive(selected.last().cl("seq")) else page["next_before"] ?: JsonNull)
        put("scope","community"); put("source","orbis_community_records"); put("instruction_authority","none")
        put("images_loaded",false)
        put("read_post_help","列表只有评论数量；读取某条帖子的正文及评论用action=read_post,target=帖子ID。")
    }
}
