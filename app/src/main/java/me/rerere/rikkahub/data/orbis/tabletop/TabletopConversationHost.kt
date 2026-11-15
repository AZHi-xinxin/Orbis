package me.rerere.rikkahub.data.orbis.tabletop

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.orbis.market.ms
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.OrbisEventBinding
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

internal data class TabletopCompanionState(val enabled:Boolean=false,val message:String="和当前助手一起入席，沿用你们的聊天与记忆。")

/** Adds game tools to the chosen assistant's normal chats. Never copies or replaces its identity. */
internal class TabletopConversationHost private constructor(context:Context) {
    private val appContext=context.applicationContext
    private val prefs by lazy { context.applicationContext.getSharedPreferences("tabletop-assistant-chats-v1",0) }
    private val bindings=mutableMapOf<String,TabletopConversationBinding>()
    private val clients=mutableMapOf<String,TabletopGameApi>()
    private val restoring=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val listeners=mutableMapOf<String,Job>()
    private val states=mutableMapOf<String,MutableStateFlow<TabletopCompanionState>>()
    @Synchronized fun state(assistant:String)=states.getOrPut(assistant){MutableStateFlow(TabletopCompanionState())}
    @Synchronized fun report(assistant:String,message:String) { state(assistant).value=state(assistant).value.copy(message=message) }
    @Synchronized fun hasAutomatic()=bindings.values.any { it.automatic } || prefs.all.any { (k,v)->
        k.startsWith("active:") && v is String && prefs.getBoolean("automatic:${k.removePrefix("active:")}:$v",true)
    }
    @Synchronized fun pauseAll() {
        for((key,value) in prefs.all)if(key.startsWith("active:") && value is String)
            prefs.edit().putBoolean("automatic:${key.removePrefix("active:")}:$value",false).apply()
        bindings.keys.toList().forEach { setAutomatic(it,false) }
    }
    companion object {
        @Volatile private var instance:TabletopConversationHost?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:TabletopConversationHost(context).also{instance=it}}
    }
    @Synchronized fun current(assistant:String)=bindings[assistant]?.takeIf { it.connected(System.currentTimeMillis()) }
    fun saved(assistant:String,room:String):String?=prefs.getString("$assistant:$room",null)
    private fun savedEnding(assistant:String,room:String):JsonObject?=
        prefs.getString("ending:$assistant:$room",null)?.let { Json.parseToJsonElement(it).jsonObject }
    @Synchronized fun bind(api:TabletopGameApi,conversation:String,title:String):TabletopConversationBinding {
        val old=current(api.assistantId)?.takeIf { it.room==api.room }
        if(clients[api.assistantId]!==api)listeners.remove(api.assistantId)?.cancel()
        val binding=TabletopConversationBinding(api.assistantId,conversation,api.room,title,
            old?.automatic ?: prefs.getBoolean("automatic:${api.assistantId}:${api.room}",true))
        clients[api.assistantId]=api;bindings[api.assistantId]=binding
        prefs.edit().putString("${api.assistantId}:${api.room}",conversation)
            .putString("active:${api.assistantId}",api.room).putString("game:${api.assistantId}:${api.room}",api.gameKind).apply()
        state(api.assistantId).value=state(api.assistantId).value.copy(enabled=binding.automatic)
        return binding
    }
    @Synchronized fun useConversation(assistant:String,conversation:String,title:String?=null) {
        val room=knownRoom(assistant)?:return
        bindings[assistant]?.takeIf { it.room==room }?.let { old ->
            bindings[assistant]=old.copy(conversationId=conversation,title=title ?: "当前聊天")
        }
        prefs.edit().putString("$assistant:$room",conversation).apply()
    }
    @Synchronized fun setAutomatic(assistant:String,enabled:Boolean) {
        current(assistant)?.let {
            bindings[assistant]=it.copy(automatic=enabled)
            prefs.edit().putBoolean("automatic:$assistant:${it.room}",enabled).apply()
            state(assistant).value=state(assistant).value.copy(enabled=enabled,
                message=if(enabled)"本局 AI 监听已开启，断线会自动重联。" else "本局 AI 已由你暂停，可随时重新开启。")
            if(!enabled)listeners.remove(assistant)?.cancel()
        }
    }
    @Synchronized fun setVisible(assistant:String,visible:Boolean) {
        current(assistant)?.let { bindings[assistant]=it.copy(awaySince=if(visible)null else it.awaySince ?: System.currentTimeMillis()) }
    }
    @Synchronized fun clear(assistant:String) {
        bindings[assistant]?.let { prefs.edit().remove("ending:$assistant:${it.room}").apply() }
        listeners.remove(assistant)?.cancel();bindings.remove(assistant);clients.remove(assistant)
        prefs.edit().remove("active:$assistant").apply();state(assistant).value=TabletopCompanionState()
    }
    /** Only a confirmed human leave receipt removes a mixed pair and its listeners. */
    @Synchronized fun confirmHumanLeave(api:TabletopClient,result:JsonObject) {
        if(result["left"]?.jsonPrimitive?.booleanOrNull!=true)return
        val room=api.room
        val paired=api.gameKind in setOf("werewolf","drawguess","heartprison") && result["paired_leave"]?.jsonPrimitive?.booleanOrNull==true
        val assistants=mutableSetOf(api.assistantId)
        if(paired) {
            assistants.addAll(bindings.values.filter { it.room==room }.map { it.assistantId })
            prefs.all.forEach { (key,value)->if(key.startsWith("active:") && value==room)assistants.add(key.removePrefix("active:")) }
        }
        for(assistant in assistants) {
            val client=clients[assistant] as? TabletopClient
            clear(assistant)
            prefs.edit().remove("ending:$assistant:$room").apply()
            client?.takeIf { it.room==room }?.forgetConnection()
        }
        api.forgetConnection()
    }
    @Synchronized fun invalidateConnection(api:TabletopClient,room:String) {
        // A late failure from an old request must not disconnect a newer room.
        if(api.room!=room)return
        if(current(api.assistantId)?.room==room)clear(api.assistantId)
        api.forgetConnection()
    }
    @Synchronized private fun finish(assistant:String) {
        bindings[assistant]?.let { bindings[assistant]=it.copy(automatic=false) }
        prefs.edit().remove("active:$assistant").apply()
        // The loop reports whether play ended or an unused waiting seat expired.
        state(assistant).value=state(assistant).value.copy(enabled=false)
    }
    @Synchronized fun listen(assistant:String,chat:ChatService) {
        val api=clients[assistant]?:return
        val listeningRoom=api.room
        if(current(assistant)?.automatic!=true || listeners[assistant]?.isActive==true)return
        me.rerere.rikkahub.service.TabletopCompanionService.start(appContext)
        listeners[assistant]=scope.launch {
            TabletopCompanionLoop(api,
                enabled={current(assistant)?.let { it.automatic && it.room==api.room }==true},
                visible={current(assistant)?.awaySince==null},busy={chat.hasActiveGeneration()},
                eventState={chat.orbisEvents.inbox.get(it)?.state},
                restoreWake={key -> current(assistant)?.let { binding ->
                    latestHeartPrisonReceipt(chat.orbisEvents.inbox.state.value, assistant,
                        binding.source, api.room, key)
                }},
                quoteReceipt={seq -> current(assistant)?.let { binding ->
                    latestTabletopQuoteReceipt(chat.orbisEvents.inbox.state.value,assistant,
                        binding.source,api.room,seq)
                }},
                report={report(assistant,it)},
                finished={finish(assistant)},
                closed={if(api is TabletopClient)invalidateConnection(api,listeningRoom) else if(current(assistant)?.room==listeningRoom)clear(assistant)},
                loadEnding={savedEnding(assistant,api.room)},
                saveEnding={status -> check(prefs.edit().putString("ending:$assistant:${api.room}",status.toString()).commit()) { "结局暂未保存" }},
                deliver={id,status,cursor ->
                    val binding=current(assistant)?:error("本局助手已离席")
                    chat.bindOrbisEvents(setOf(binding.source),OrbisEventBinding(assistant,binding.conversationId))
                    chat.acceptOrbisEvent(OrbisIncomingEvent(event_id=id,source=binding.source,
                        text=tabletopReminder(status,cursor),occurred_at=System.currentTimeMillis())).first.id
                }).run()
        }
    }
    suspend fun restore(chat:ChatService,repo:ConversationRepository) {
        for((key,value) in prefs.all) {
            if(!key.startsWith("active:") || value !is String)continue
            val assistant=key.removePrefix("active:");val room=value
            if(!prefs.getBoolean("automatic:$assistant:$room",true) || current(assistant)!=null)continue
            try {
                val conversation=saved(assistant,room)?:continue
                val target=repo.getConversationById(Uuid.parse(conversation))?.takeIf { it.assistantId.toString()==assistant }?:continue
                val ending=savedEnding(assistant,room)
                val api:TabletopGameApi=if(ending!=null) savedTabletopEndingApi(assistant,room,ending)
                    else TabletopClient(appContext,assistant,prefs.getString("game:$assistant:$room","tabletop") ?: "tabletop").also { it.resume() }
                if(api.room!=room){prefs.edit().remove(key).apply();continue}
                bind(api,conversation,target.title);setVisible(assistant,false);listen(assistant,chat)
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){report(assistant,"暂未连接上圆桌，恢复网络后会继续重联。");throw java.io.IOException("tabletop_restore_unavailable")}
        }
    }
    @Synchronized fun accepts(event:OrbisInboxEvent)=current(event.assistantId)?.let {
        it.automatic && event.eventId.startsWith(it.room+":")
    }==true
    suspend fun prepareWake(event:OrbisInboxEvent):Boolean {
        val api=synchronized(this){clients[event.assistantId]} ?: return false
        if(api.gameKind !in setOf("werewolf","drawguess","heartprison"))return true
        if(!accepts(event))return false
        val status=savedEnding(event.assistantId,api.room) ?: try {
            api.game("script_status",asAi=true)
        }catch(e:me.rerere.rikkahub.data.orbis.market.MarketProblem) {
            if(e.status in setOf(404,410))return false
            throw e
        }
        return accepts(event) && (when(api.gameKind) {"heartprison"->heartPrisonEventMatchesTurn(event.eventId,api.room,status);"drawguess"->drawGuessEventMatchesTurn(event.eventId,api.room,status);else->werewolfEventMatchesTurn(event.eventId,api.room,status)})
    }
    @Synchronized fun requireRoomAvailable(assistant:String,kind:String,room:String) {
        val old=knownRoom(assistant) ?: return
        val previous=clients[assistant]?.gameKind ?: prefs.getString("game:$assistant:$old","tabletop")
        check(previous==kind || savedEnding(assistant,old)!=null) { "这位助手还在另一种圆桌游戏中，请先回原桌结束并离席。" }
    }
    @Synchronized fun currentGameKind(assistant:String):String? = clients[assistant]?.gameKind
        ?: knownRoom(assistant)?.let { prefs.getString("game:$assistant:$it","tabletop") }
    private fun knownRoom(assistant:String):String? =
        current(assistant)?.room ?: prefs.getString("active:$assistant",null)

    private suspend fun connection(assistant:String,room:String):TabletopGameApi = restoring.withLock {
        synchronized(this) {
            check(knownRoom(assistant)==room) { "原桌连接已改变，请重新查询当前对局。" }
            clients[assistant]?.takeIf { it.room==room }?.let { api ->
                // Leaving the game page does not remove game tools from normal chat.
                bindings[assistant]?.let { bindings[assistant]=it.copy(awaySince=null) }
                return@withLock api
            }
        }
        val api:TabletopGameApi = savedEnding(assistant,room)?.let { savedTabletopEndingApi(assistant,room,it) }
            ?: TabletopClient(appContext,assistant,prefs.getString("game:$assistant:$room","tabletop") ?: "tabletop")
                .also { it.resumeExisting(room) }
        withContext(Dispatchers.Main.immediate) {
            synchronized(this@TabletopConversationHost) {
                check(knownRoom(assistant)==room) { "原桌连接已改变，请重新查询当前对局。" }
                // Another entry may have completed while the network request was in flight.
                clients[assistant]?.takeIf { it.room==room } ?: api.also {
                    bind(it,checkNotNull(saved(assistant,room)),"当前聊天")
                    if(current(assistant)?.automatic==true)
                        listen(assistant,org.koin.core.context.GlobalContext.get().get<ChatService>())
                }
            }
        }
    }

    @Synchronized fun tools(assistant:String,supportsImages:Boolean):List<Tool> {
        val room=knownRoom(assistant) ?: return emptyList()
        val kind=clients[assistant]?.gameKind ?: prefs.getString("game:$assistant:$room","tabletop") ?: "tabletop"
        // Registration is local and stable. Restore the existing connection when a tool
        // actually runs; temporary disconnection must not make valid tool names disappear.
        val api=object:TabletopGameApi {
            override val assistantId=assistant
            override val room=room
            override val gameKind=kind
            override val latestStatus get()=synchronized(this@TabletopConversationHost) { clients[assistant]?.latestStatus ?: buildJsonObject {} }
            override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean)=
                connection(assistant,room).game(path,body,params,asAi)
            override suspend fun roleImage(name:String)=connection(assistant,room).roleImage(name)
            override suspend fun restoreConnection() {
                val api=connection(assistant,room)
                api.restoreConnection()
                withContext(Dispatchers.Main.immediate) {
                    synchronized(this@TabletopConversationHost) {
                        check(knownRoom(assistant)==room && clients[assistant]===api) { "原桌连接已改变，请重新查询当前对局。" }
                        // Renew the connection only; a human-paused listener stays paused.
                        if(current(assistant)?.automatic==true)
                            listen(assistant,org.koin.core.context.GlobalContext.get().get<ChatService>())
                    }
                }
            }
            override fun pending(asAi:Boolean)=synchronized(this@TabletopConversationHost) { clients[assistant]?.pending(asAi) ?: false }
        }
        return tabletopTools(api,supportsImages,onLeave={synchronized(this) { if(knownRoom(assistant)==room)clear(assistant) }}) {
            synchronized(this) { check(knownRoom(assistant)==room) { "已离开这张圆桌，请回游戏重新连接。" } }
        }
    }
}
