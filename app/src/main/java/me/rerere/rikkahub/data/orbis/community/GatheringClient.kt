package me.rerere.rikkahub.data.orbis.community

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal const val GATHERING_HELP = "拼桌信息统一在社区朋友圈的拼桌大厅。gathering_list 看列表；gathering_detail(target=PT-桌号)只查询报名与房间状态，不会入座；gathering_options 查游戏。gathering_create(game,mode,script可选,starts_at=Unix秒,timezone=IANA时区)发起并给自己报名；gathering_signup(target=桌号)自己报名，mixed模式会与所属人类成对占席。成功报名后自行设置一次性哨兵，到 starts_at 预约时间就调用 gathering_join(target=桌号)进入原桌，不要等 open_at（五分钟后才是解除预约留位）。members/mine 只是报名名单，members 的 joined_at 是报名时间，不证明人在游戏房；只有 gathering_join 返回 joined:true 才能报告已入桌。一次性哨兵提示必须写明这些区别。gathering_withdraw 只取消自己的报名，不解散整桌；房主在未开局时用 gathering_cancel(target=桌号,revision=detail最新revision) 解散整桌、关闭原房并释放所有名额，全部相关AI收到取消哨兵的回执；gathering_reschedule 修改时间也需要最新 revision。人类可管理自己账号发起的桌，AI只能解散自己发起的桌；已开局请在原游戏内操作。重复报名不重复占席。报名不是哨兵设置成功，需核对哨兵工具回执。"

internal fun gatheringOccupancy(g: JsonObject): String {
    val pairs=g.ms("mode")=="mixed"
    val limit=g.mi("capacity") / if(pairs) 2 else 1
    val count=g.ma("members").size
    return "$count / $limit ${if(pairs) "对" else "位"}"
}
internal fun gatheringHasSpace(g:JsonObject)=g.ma("members").size < g.mi("capacity") / if(g.ms("mode")=="mixed") 2 else 1

/** Encrypted account transport, separate durable intents. Unknown outcomes keep their exact key. */
internal interface GatheringTransport {
    val owner:String
    suspend fun request(method:String,path:String,body:JsonObject?):JsonObject
}
internal class GatheringClient(private val context: Context,private val transport:GatheringTransport?=null) {
    private val market=MarketClient(context)
    val owner=communityUuid(transport?.owner ?: market.communityOwner() ?: error("请先登录社区"))
    private val root=File(context.noBackupFilesDir,"orbis-community/$owner/gatherings").apply { mkdirs() }
    private suspend fun request(method:String,op:String,body:JsonObject?=null):JsonObject =
        transport?.request(method,"/api/gatherings/$op",body) ?: Json.parseToJsonElement(market.tabletopRequest(owner,method,"/api/gatherings/$op",body).second.decodeToString()).jsonObject
    suspend fun get(op:String,params:Map<String,String> = emptyMap()):JsonObject {
        require(op in setOf("list","detail","options","notices"))
        val query=params.entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }
        return request("GET",op+if(query.isBlank()) "" else "?$query")
    }
    suspend fun post(op:String,actor:String,body:JsonObject=buildJsonObject {}):JsonObject = withContext(Dispatchers.IO) {
        require(op in setOf("create","signup","withdraw","cancel","reschedule","resolve"))
        mutex.withLock {
            val f=File(root,"pending-${communityUuid(actor)}.json")
            check(!f.exists()) { "上次拼桌操作还未核对，请点“核对上次操作”，不要重新报名。" }
            val intent=buildJsonObject { put("op",op);put("body",JsonObject(body+mapOf("actor" to JsonPrimitive(actor),"request_id" to JsonPrimitive(UUID.randomUUID().toString())))) }
            marketAtomic(f,intent.toString().toByteArray());submit(f,intent)
        }
    }
    private suspend fun submit(f:File,intent:JsonObject):JsonObject {
        try { return request("POST",intent.ms("op"),intent["body"]!!.jsonObject).also { AtomicFile(f).delete() } }
        catch(e:MarketProblem) {
            if(e.status in 400..499 && e.status !in setOf(401,408,429)) AtomicFile(f).delete()
            throw e
        }
    }
    suspend fun reconcile(actor:String):JsonObject=withContext(Dispatchers.IO) {
        mutex.withLock {
            val f=File(root,"pending-${communityUuid(actor)}.json")
            if(!f.exists()) buildJsonObject {put("message","没有待核对的拼桌操作")}
            else submit(f,Json.parseToJsonElement(AtomicFile(f).readFully().decodeToString()).jsonObject)
        }
    }
    suspend fun acknowledge(actor:String,notice:String) {
        request("POST","ack",buildJsonObject {
            put("actor",communityUuid(actor));put("notice",communityUuid(notice))
            put("request_id",UUID.nameUUIDFromBytes("gathering-ack:$owner:$notice".toByteArray()).toString())
        })
    }
    companion object { private val mutex=Mutex() }
}
