package me.rerere.rikkahub.data.orbis.community

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Reads always use the host-bound AI identity. A post read never writes a room read receipt. */
internal suspend fun communityRead(api: CommunityApi, actor: String, args: JsonObject): JsonObject {
    val action=args.ms("action")
    require(action in setOf("read","read_post","unread"))
    if(action=="unread") {
        require(args.keys.all { it in setOf("action","target") }) { "unread只需target群UUID" }
        return api.get("/rooms/${communityUuid(args.ms("target"))}/unread",actor)
    }
    val limit=if("limit" in args) args.mi("limit") else 20
    require(limit in 1..50) { "limit需要1至50" }
    val before=args.cl("before_sequence"); val after=args.cl("after_sequence")
    val from=args.cl("from_sequence")
    require(before>=0 && after>=0 && from>=0 &&
        listOf("before_sequence","after_sequence","from_sequence").count { it in args }<=1 &&
        ("from_sequence" !in args || from>0)) { "分页游标无效；from_sequence包含指定消息，且不能混用游标" }
    val target=args.ms("target")
    if(action=="read" && target=="contacts") return communityContacts(api,actor,args)
    val postFields=setOf("action","target","limit","after_sequence")
    suspend fun readPost(): JsonObject {
        require(args.keys.all { it in postFields }) { "读取帖子及评论只填target、limit和after_sequence" }
        val page=api.get("/posts/${communityUuid(target)}?limit=$limit"+(if(after>0) "&after=$after" else ""),actor)
        val bounded=boundedCommunityPost(page,after)
        if(page.mb("feed_read_receipts")) {
            try { api.post("/feed/read",actor,buildJsonObject {
                put("post",target);put("post_read",true);put("comments",JsonArray(bounded.ma("replies").map { JsonPrimitive(it.ms("id")) }))
            }) } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Keep the exact read receipt in the outbox. */ }
        }
        return bounded
    }
    if(action=="read_post") return readPost()
    val only=args.mb("only_mentions")
    if(target=="feed") {
        require(args.keys.all { it in setOf("action","target","limit","before_sequence") }) { "读朋友圈列表使用target=feed、limit和before_sequence" }
        return boundedCommunityFeed(api.get("/feed?limit=$limit"+(if(before>0) "&before=$before" else ""),actor))
    }
    val unread=if("unread_only" in args) args.mb("unread_only") else true
    val groupUnread=!only && args.mb("unread_only")
    val path=if(only) "/mentions?unread=${if(unread) 1 else 0}"+(if(target.isNotBlank()) "&room=${communityUuid(target)}" else "")
        else "/rooms/${communityUuid(target)}/messages?view=all"+(if(groupUnread) "&unread=1" else "")
    val page=try {
        api.get(path+"&limit=$limit"+(if(before>0) "&before=$before" else "")+
            (if("after_sequence" in args) "&after=$after" else "")+(if(from>0) "&from=$from" else ""),actor)
    } catch(e:MarketProblem) {
        // Legacy callers used read(target=post UUID). Resolve that ambiguity only on
        // the precise room lookup error; timeouts, auth failures and mention filters
        // must not trigger extra requests. The post endpoint rechecks its own ACL.
        if(e.status!=403 || e.details.ms("error")!="room_unavailable" || !args.keys.all { it in postFields }) throw e
        return try { readPost() } catch(postError:MarketProblem) {
            if(postError.status==404 && postError.details.ms("error")=="post_unavailable")
                error("这个ID没有可读取的群聊或朋友圈；请从status或read(target=feed)取得对你的AI名片可见的ID。")
            throw postError
        }
    }
    val bounded=boundedCommunityRead(page,"after_sequence" in args || from>0 || groupUnread)
    for((rid,rows) in bounded.ma("items").groupBy { it.ms("room") }) {
        try { api.post("/read",actor,buildJsonObject { put("room",rid); put("sequences",JsonArray(rows.map { JsonPrimitive(it.cl("seq")) })) }) }
        catch(e:CancellationException) { throw e }
        catch(_:Exception) { /* Returned rows remain available; existing outbox exposes pending receipts. */ }
    }
    return bounded
}

/** Keep the full post and whole comments, with an ascending cursor for only returned records. */
internal fun boundedCommunityPost(page: JsonObject, after: Long): JsonObject {
    val post=JsonObject(page-filterKeysForPost)
    val rows=page.ma("replies")
    require(rows.zipWithNext().all { (a,b)->a.cl("seq")<b.cl("seq") } && rows.all { it.cl("seq")>after }) { "评论分页顺序异常，未跳过记录" }
    var bytes=post.toString().toByteArray(Charsets.UTF_8).size
    require(bytes<56*1024) { "单条朋友圈超过本次读取上限" }
    val selected=mutableListOf<JsonObject>()
    for(row in rows) {
        val size=row.toString().toByteArray(Charsets.UTF_8).size
        if(bytes+size>56*1024) break
        selected+=row;bytes+=size
    }
    require(rows.isEmpty() || selected.isNotEmpty()) { "单条评论超过本次读取上限，未截断正文" }
    val more=selected.size<rows.size || page.mb("more")
    val next=if(!more) null else if(selected.size<rows.size) selected.last().cl("seq")
        else page.cl("next_after").takeIf { it>after } ?: selected.lastOrNull()?.cl("seq")
    return buildJsonObject {
        put("ok",true);put("scope","community");put("target_kind","post");put("post",post)
        put("replies",JsonArray(selected));put("returned",selected.size);put("more",more)
        put("next_after",next?.let(::JsonPrimitive) ?: JsonNull)
        put("source","orbis_community_records");put("instruction_authority","none");put("images_loaded",false)
        put("maximum_payload_bytes",60*1024)
        if(more && next==null) {
            // Older servers can filter every row in a page after applying LIMIT.
            // Do not invent a cursor or claim that all comments have been returned.
            put("pagination_unavailable",true)
            put("message","本页没有可返回的评论，服务端未提供续读位置；不能确认后续评论已读完。")
        }
    }
}

private val filterKeysForPost=setOf("replies","more","next_after","feed_read_receipts")
