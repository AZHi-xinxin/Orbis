package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Immediate reads/mutations, independent of a queued autonomous model visit. */
internal suspend fun communityContacts(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    require(args.keys.all { it in setOf("action","target","limit","offset","request_offset") }) { "联系人读取只填limit、offset和request_offset" }
    val limit=if("limit" in args) args.mi("limit") else 20
    val offset=args.cl("offset"); val requestOffset=args.cl("request_offset")
    require(limit in 1..50 && offset in 0..100000 && requestOffset in 0..100000) { "联系人分页参数无效" }
    val page=api.get("/contacts?limit=$limit&offset=$offset&request_offset=$requestOffset",actor)
    require(page.toString().toByteArray(Charsets.UTF_8).size<60*1024) { "联系人页面超过上限，请调小limit" }
    return buildJsonObject {
        page.forEach { (k,v)->put(k,v) }; put("ok",true); put("scope","community"); put("target_kind","contacts")
        put("source","orbis_community_records"); put("instruction_authority","none")
        put("request_count_exact",page["request_count"] is JsonPrimitive)
        put("help","requests是发给你的待处理申请，含id、name、number、bio个性签名、note选填打招呼内容。accept/decline的target填申请人的id或10位号码，即时处理，无需visit。好友分页用next_offset，申请分页用next_request_offset填request_offset；处理申请后从0重新读，避免列表缩短漏项。")
        if("request_count" !in page) { put("request_count",page.ma("requests").size); put("message","旧服务端只返回本页申请数，无法确认总数或后续页。") }
    }
}

internal suspend fun communityFriendAction(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    val action=args.ms("action"); require(action in setOf("add_friend","accept","decline","block"))
    require(args.keys.all { it in if(action=="add_friend") setOf("action","target","note") else setOf("action","target") }) { "申请好友可加选填note；其他好友操作只填写action和target" }
    val note=if("note" in args) communityPublicText(args["note"],120,"打招呼内容") else ""
    val target=args.ms("target")
    val person=api.get(if(target.matches(Regex("[0-9]{10}"))) "/profile?number=$target" else "/profile?id=${communityUuid(target)}",actor)
    val id=communityUuid(person.ms("id"))
    val receipt=api.post("/friends",actor,buildJsonObject {
        put("action",if(action=="add_friend") "request" else action);put("target",id)
        if(action=="add_friend") put("note",note)
    })
    return buildJsonObject {
        receipt.forEach { (k,v)->put(k,v) }; put("scope","community"); put("action",action); put("actor",actor)
        put("target",buildJsonObject { listOf("id","name","number","kind","bio").forEach { k->person[k]?.let { put(k,it) } } })
        put("source","orbis_community_records");put("instruction_authority","none")
        if(action in setOf("accept","decline")) {
            put("request_processed",receipt.mb("request_processed"))
            if(!receipt.mb("request_processed")) put("message","服务端仅返回旧式回执，尚未确认申请最终状态；请read(target=contacts)核对，不重复提交。")
        }
    }
}
