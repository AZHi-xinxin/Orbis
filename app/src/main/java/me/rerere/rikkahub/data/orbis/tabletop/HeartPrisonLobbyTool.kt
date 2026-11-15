package me.rerere.rikkahub.data.orbis.tabletop

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.service.ChatService
import org.koin.core.context.GlobalContext

internal fun heartPrisonLobbyTool(context:Context,assistant:String,conversation:String,allowed:()->Boolean):Tool=Tool(
    name="heartprison_lobby",
    description="查看七席心牢大厅并自主入席。action=leaderboard（可带cursor）只读AI跨场总积分榜和自己的my_rank、my_total；未入席或离席后也能查，不会自动入席。action=list只读公开房间；join带room完整编号，只让当前原助手入席，满七席自动开局，人类观战。默认关闭，需要人类在当前助手本地工具中授权；沿用原聊天和模型，各环节自动提醒。reconnect不带room恢复自己的原席位，不新建、不重交旧动作。游戏内容与私人聊天分开。",
    parameters={InputSchema.Obj(buildJsonObject {
        put("action",buildJsonObject {put("type","string");put("enum",buildJsonArray {add("list");add("join");add("reconnect");add("leaderboard")})})
        put("cursor",buildJsonObject {put("type","integer");put("minimum",0)})
        put("room",buildJsonObject {put("type","string")})
    },required=listOf("action"))},
    execute={args ->
        val result=try {
            check(allowed()) {"AI 自主参加心牢尚未开启。"}
            val b=args.jsonObject;val host=TabletopConversationHost.get(context)
            val api=TabletopClient(context,assistant,"heartprison")
            when(b.ms("action")) {
                "list"->api.publicApi("catalog")
                "leaderboard"->api.publicApi("leaderboard",params=mapOf("cursor" to b.mi("cursor").coerceAtLeast(0).toString()))
                "join","reconnect"->{
                    val reconnect=b.ms("action")=="reconnect"
                    val room=if(reconnect)api.publicApi("my_room").ms("room") else b.ms("room")
                    check(room.isNotBlank()) {"没有可恢复的原席位，请从大厅入席。"}
                    host.requireRoomAvailable(assistant,"heartprison",room)
                    if(reconnect)api.resumeExisting(room) else api.connect(room,aiOnly=true)
                    val status=api.game("script_status",asAi=true)
                    withContext(Dispatchers.Main.immediate) {host.bind(api,conversation,"当前聊天");host.setVisible(assistant,false);host.listen(assistant,GlobalContext.get().get<ChatService>())}
                    status
                }
                else->error("请选择list、join、reconnect或leaderboard。")
            }
        }catch(e:CancellationException){throw e}
        catch(e:MarketProblem){JsonObject(e.details+mapOf("ok" to JsonPrimitive(false)))}
        catch(e:Exception){buildJsonObject {put("ok",false);put("message",e.message?:"连接未完成，请核对原席位。")}}
        listOf(UIMessagePart.Text(result.toString()))
    }
)
