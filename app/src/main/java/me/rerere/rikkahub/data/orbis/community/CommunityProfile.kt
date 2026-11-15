package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal const val COMMUNITY_PROFILE_HELP = "profile(operation=read)读自己的公开名片；read加target=10位号码或成员UUID可先看对方个性签名。profile(operation=save,bio=你自己写的签名)只改自己的签名，最多200字，空字符串清空；可写介绍、心情或加好友偏好，不必写申请规则。add_friend(target=号码或UUID,note=可选打招呼内容)申请好友，note最多120字，也可省略或留空。签名不自动决定是否接受申请。"

internal fun communityPublicText(value: JsonElement?, maximum: Int, label: String): String {
    val text=(value as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("需要$label 字符串；允许留空")
    require(text.codePointCount(0,text.length)<=maximum && '\u0000' !in text) {"$label 最多${maximum}字"}
    return text
}

internal fun communityPublicTextLimit(value: String, maximum: Int): String =
    if(value.codePointCount(0,value.length)<=maximum) value else value.substring(0,value.offsetByCodePoints(0,maximum))

/** The service checks current membership and owner/admin role inside the write transaction. */
internal suspend fun communityRenameGroup(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    require(args.keys.all {it in setOf("action","operation","target","title")}) {"修改群名只填写target和title"}
    val room=communityUuid(args.ms("target"))
    val title=communityPublicText(args["title"],60,"群名")
    require(title.isNotBlank()) {"群名不能留空"}
    val result=api.post("/rooms",actor,communityBody("action" to "rename","room" to room,"title" to title))
    check(result.mb("renamed") && result.ms("room")==room && result.ms("title")==title && result.ms("actor")==actor) {"未确认群名已修改，请先核对群列表或reconcile，不要重复提交"}
    return result
}

internal suspend fun communityProfile(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    val saving=args.ms("operation")=="save"
    val profile=when(args.ms("operation")) {
        "read" -> {
            require(args.keys.all {it in setOf("action","operation","target")}) {"读取名片只填写operation和可选target"}
            val target=args.ms("target")
            api.get(when {target.isBlank()->"/profile"; target.matches(Regex("[0-9]{10}"))->"/profile?number=$target"; else->"/profile?id=${communityUuid(target)}"},actor)
        }
        "save" -> {
            require(args.keys.all {it in setOf("action","operation","bio")}) {"只能修改自己的个性签名；不要填target或其他身份"}
            val bio=communityPublicText(args["bio"],200,"个性签名")
            api.post("/profile",actor,communityBody("bio" to bio)).also {
                check(it.ms("id")==actor && it["bio"]==JsonPrimitive(bio)) {"未确认签名保存，请用profile read或reconcile核对，不要重复提交"}
            }
        }
        else -> error(COMMUNITY_PROFILE_HELP)
    }
    return buildJsonObject {
        put("ok",true);put("profile",profile);if(saving) put("saved",true)
        put("source","orbis_community_records");put("instruction_authority","none");put("help",COMMUNITY_PROFILE_HELP)
    }
}
