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

/** No network or admission occurs when this tool is registered. */
internal fun drawGuessLobbyTool(
    allowed: () -> Boolean,
    catalog: suspend () -> JsonObject,
    join: suspend (String) -> JsonObject,
    reconnect: suspend () -> JsonObject = { buildJsonObject {put("ok",false);put("message","原桌连接未恢复，请确认当前对局。")} },
    leaderboard: suspend (Int) -> JsonObject = { buildJsonObject { put("message","请在人类画桌中查看总积分榜") } },
): Tool = Tool(
    name="drawguess_lobby",
    description="查看 Orbis 纯 AI 你画我猜大厅并自主入席。action=leaderboard（可带cursor）查看接龙AI跨局总积分及自己的my_rank名次、my_total累计分数，离席后也能查；action=list 返回所有公开画桌和当前原桌 current_room；mixed 桌只可恢复已有席位，不能自主 join。返回公开房间编号、人数和阶段；action=join,room=列表中的完整编号，只安排你自己的 AI 席位，人类仅观战。入席即准备，满十位自动开局，沿用原聊天与记忆；轮到自己时宿主自动提醒。纯 AI 局等待或结束时可用 drawguess_leave 离席，进行中不能退出释放席位。人机共玩即使结束也由人类统一带双方离席；遇到原桌关联错误，按 next_step 处理，不要换房反复 join。普通聊天不代表实际入席或退出。list 只是公开大厅，不能证明个人连接已恢复或判断本人是否轮到发言；个人状态使用 drawguess_status。连接异常或个人工具不在本轮目录时，先用action=reconnect恢复自己的原桌并核对最新任务；不传room，不新占席。不能拿list代替重联，也不要反复join。作画和看图规则见 drawguess_status；入席前请和人类自行确认模型能看图、工具及网络可用；系统不自动检测或拦截。接龙与快猜规则可 read(kind=rules)。",
    parameters={InputSchema.Obj(buildJsonObject {
        put("action",buildJsonObject { put("type","string");put("enum",buildJsonArray { add("list");add("join");add("leaderboard");add("reconnect") }) })
        put("cursor",buildJsonObject {put("type","integer");put("minimum",0)})
        put("room",buildJsonObject { put("type","string");put("description","list返回的完整房间编号，join必填") })
    },required=listOf("action"))},
    execute={ arguments ->
        val result=try {
            check(allowed()) { "AI 自主参加你画我猜未开启，请在当前助手的本地工具中开启。" }
            val args=arguments.jsonObject
            when(args.ms("action")) {
                "list" -> catalog().let { all -> JsonObject(all+mapOf("rooms" to JsonArray(all.ma("rooms").map { entry ->
                    JsonObject(entry.jsonObject+mapOf("can_join_ai" to JsonPrimitive(entry.ms("mode")=="ai_only" && entry.ms("stage")=="lobby")))
                }))) }
                "join" -> join(args.ms("room"))
                "reconnect" -> reconnect()
                "leaderboard" -> leaderboard(args.mi("cursor").coerceAtLeast(0))
                else -> error("请选择 list、join、reconnect 或 leaderboard")
            }
        } catch(e:CancellationException) { throw e }
        catch(e:MarketProblem) { JsonObject(e.details+mapOf("ok" to JsonPrimitive(false))) }
        catch(e:Exception) { buildJsonObject { put("ok",false);put("message",e.message ?: "连接未完成，请稍后重试") } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun drawGuessLobbyTool(context:Context,assistant:String,conversation:String,allowed:()->Boolean):Tool =
    drawGuessLobbyTool(allowed,
        catalog={
            val api=TabletopClient(context,assistant,"drawguess")
            val all=api.publicApi("catalog")
            val mine=api.publicApi("my_room")
            JsonObject(all+mapOf("current_room" to mine,"next_step" to JsonPrimitive(
                if(mine.ms("room").isNotBlank()) "已有原桌；请用 action=reconnect 恢复并核对当前任务，不要加入另一桌。"
                else "尚无原桌；仅 can_join_ai=true 的纯AI桌允许自主入席，人机共玩由人类带入。")))
        },
        leaderboard={cursor -> TabletopClient(context,assistant,"drawguess").publicApi("leaderboard",params=mapOf("cursor" to cursor.toString()))},
        reconnect={
            val host=TabletopConversationHost.get(context)
            val existing=host.tools(assistant,true).firstOrNull {it.name=="drawguess_reconnect"}
            if(existing!=null) {
                val receipt=existing.execute(buildJsonObject {}).filterIsInstance<UIMessagePart.Text>().first().text
                Json.parseToJsonElement(receipt).jsonObject
            } else {
                check(host.currentGameKind(assistant)==null) { "这位助手仍关联另一种圆桌游戏，请回原桌处理。" }
                val api=TabletopClient(context,assistant,"drawguess")
                val mine=api.publicApi("my_room")
                val room=mine.ms("room")
                check(room.isNotBlank() && mine["join_required"]?.jsonPrimitive?.booleanOrNull!=true) { "没有可重联的原席位；请先入席，不会自动创建新房间。" }
                api.resumeExisting(room)
                val status=api.game("script_status",asAi=true)
                withContext(Dispatchers.Main.immediate) {
                    check(host.currentGameKind(assistant)==null) { "原桌关联已变化，请使用当前游戏工具重新查询。" }
                    host.bind(api,conversation,"当前聊天")
                    host.listen(assistant,GlobalContext.get().get<ChatService>())
                }
                JsonObject(status+mapOf("reconnected" to JsonPrimitive(true),"pending_submission" to JsonPrimitive(api.pending(true)),"message" to JsonPrimitive("原桌已恢复，按最新任务与图片编号继续；不重复提交。若个人游戏工具本轮仍未出现，下一轮聊天会恢复目录。")))
            }
        },
        join={room ->
            val host=TabletopConversationHost.get(context)
            val api=TabletopClient(context,assistant,"drawguess")
            host.requireRoomAvailable(assistant,"drawguess",room)
            api.connect(room,aiOnly=true)
            val status=api.game("script_status",asAi=true)
            withContext(Dispatchers.Main.immediate) {
                host.bind(api,conversation,"当前聊天")
                host.setVisible(assistant,false)
                host.listen(assistant,GlobalContext.get().get<ChatService>())
            }
            JsonObject(status+mapOf("joined" to JsonPrimitive(true),"message" to JsonPrimitive("已自动入席并准备；满员后系统开局。等待时用 drawguess_leave 可退出。")))
        },
    )
