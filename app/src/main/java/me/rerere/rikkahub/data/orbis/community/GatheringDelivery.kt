package me.rerere.rikkahub.data.orbis.community

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.*
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.tabletop.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Only signup receipts, never community posts or group timelines. No due-time scheduler. */
internal object GatheringDelivery {
    private var polling:Job?=null
    private val mutex=Mutex()
    private fun root(context:Context)=File(context.noBackupFilesDir,"orbis-gathering-deliveries").apply {mkdirs()}
    private fun file(context:Context,id:String)=File(root(context),"${communityUuid(id)}.json")
    private fun input(value:JsonObject)=OrbisIncomingEvent(value.ms("id"),"gathering."+value.ms("id"),value.ms("text"),
        occurred_at=value.cl("created_ms"))
    fun accepts(context:Context,event:OrbisInboxEvent)=accepts(context,OrbisIncomingEvent(event.eventId,event.source,event.text,event.wake,event.occurredAt,event.localImage,event.workshopImages),event.assistantId,event.conversationId)
    fun accepts(context:Context,event:OrbisIncomingEvent,assistant:String,conversation:String):Boolean=runCatching {
        val v=Json.parseToJsonElement(AtomicFile(file(context,event.event_id)).readFully().decodeToString()).jsonObject
        v.ms("assistant")==assistant && v.ms("conversation")==conversation && input(v)==event &&
            MarketClient(context).communityOwner()==v.ms("owner") && CommunityRuntime.enabled(assistant)
    }.getOrDefault(false)

    @Synchronized fun start(context:Context,scope:CoroutineScope,chat:ChatService) {
        if(polling?.isActive==true)return
        polling=scope.launch(Dispatchers.IO) {
            while(isActive) {
                try {receive(context,chat)} catch(e:CancellationException){throw e} catch(_:Exception){}
                delay(60_000)
            }
        }
    }
    suspend fun target(context:Context,assistant:String,preferred:String?=null):String {
        val repo=GlobalContext.get().get<ConversationRepository>()
        val saved=preferred?.let {repo.getConversationById(Uuid.parse(it))}?.takeIf {it.assistantId.toString()==assistant}
        return (saved ?: repo.getRecentConversations(Uuid.parse(assistant),1).firstOrNull())?.id?.toString()
            ?: error("请先和这位 AI 聊一句，再为它报名。")
    }
    suspend fun receive(context:Context,chat:ChatService=GlobalContext.get().get(),preferredAssistant:String?=null,preferredConversation:String?=null) = mutex.withLock {
        if(!MarketClient(context).hasSession())return@withLock
        val client=GatheringClient(context);val notices=client.get("notices").ma("items")
        if(notices.isEmpty())return@withLock
        val identities=CommunityClient(context).identities()
        for(n in notices) try {
            val local=identities.singleOrNull {it.ms("id")==n.ms("actor") && it.ms("kind")=="ai"}?.ms("local_key") ?: continue
            if(!CommunityRuntime.enabled(local))continue
            // Persist the exact target and message before accepting. Retry never switches chats.
            val f=file(context,n.ms("id"))
            val value=if(f.exists())Json.parseToJsonElement(AtomicFile(f).readFully().decodeToString()).jsonObject else {
                val conversation=runCatching { target(context,local,preferredConversation.takeIf {local==preferredAssistant}) }.getOrNull() ?: continue
                val stale=n.ms("kind") in setOf("signup","reschedule") && System.currentTimeMillis()/1000>=(n["starts_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0)+300
                val text=if(stale) "拼桌报名回执已过预约入场时间，请先用 gathering_detail 查询桌号 ${n.ms("table")} 的当前状态，不要建立过去时间的哨兵。" else n.ms("text")
                buildJsonObject { put("id",n.ms("id"));put("owner",client.owner);put("assistant",local);put("conversation",conversation)
                    put("created_ms",(n["created_at"]!!.jsonPrimitive.double*1000).toLong());put("text",text) }
                    .also {marketAtomic(f,it.toString().toByteArray())}
            }
            chat.bindOrbisEvents(setOf("gathering."+n.ms("id")),OrbisEventBinding(value.ms("assistant"),value.ms("conversation")))
            chat.acceptOrbisEvent(input(value))
            client.acknowledge(n.ms("actor"),n.ms("id"))
        } catch(e:CancellationException){throw e} catch(_:Exception){ /* One removed chat must not block other AIs' receipts. */ }
    }

    suspend fun join(context:Context,assistant:String,conversation:String,table:JsonObject):JsonObject {
        check(CommunityRuntime.enabled(assistant)) {"请先开启这位 AI 的社区工具"}
        val game=table.ms("game");val api=TabletopClient(context,assistant,game)
        val current=GatheringClient(context).get("detail",mapOf("table" to table.ms("code")))
        check(current.ms("state") in setOf("reserved","started","open") && current.mb("can_join")) {"预约已取消、结束或改期，请查看拼桌大厅。"}
        val result=api.connect(current.ms("room"),aiOnly=current.ms("mode")=="ai_only",gathering=buildJsonObject {put("table",current.ms("code"));put("revision",current.mi("revision"))})
        val host=TabletopConversationHost.get(context)
        host.bind(api,conversation,table.ms("title")+" · "+table.ms("code"))
        host.listen(assistant,GlobalContext.get().get<ChatService>())
        return JsonObject(result+mapOf("table" to JsonPrimitive(table.ms("code")),"joined" to JsonPrimitive(true)))
    }
}
