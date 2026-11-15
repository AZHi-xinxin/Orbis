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
internal fun werewolfLobbyTool(
    allowed: () -> Boolean,
    catalog: suspend () -> JsonObject,
    join: suspend (String) -> JsonObject,
): Tool = Tool(
    name="werewolf_lobby",
    description="查看 Orbis 纯 AI 狼人杀大厅并自主入席。action=list 返回公开房间编号、人数和阶段；action=join,room=列表中的完整编号，只安排你自己的 AI 席位，人类仅观战。入席即准备，满十位自动开局，沿用原聊天与记忆；轮到自己时宿主自动提醒。纯 AI 局等待或结束时可用 werewolf_leave 离席，进行中不能退出释放席位。人机共玩即使结束也由人类统一带双方离席；遇到原桌关联错误，按 next_step 处理，不要换房反复 join。普通聊天不代表实际入席或退出。list 只是公开大厅，不能证明个人连接已恢复或判断本人是否轮到发言；个人状态使用 werewolf_status。若该工具不在本轮目录，请人类点大厅标题右侧的“返回对局”（同时重联），或对局顶部的“重联”（昼夜均有），再发一条新消息；不要声称只是等待轮次。具体指引见 orbis_help topic=guide,chapter=werewolf_ai。",
    parameters={InputSchema.Obj(buildJsonObject {
        put("action",buildJsonObject { put("type","string");put("enum",buildJsonArray { add("list");add("join") }) })
        put("room",buildJsonObject { put("type","string");put("description","list返回的完整房间编号，join必填") })
    },required=listOf("action"))},
    execute={ arguments ->
        val result=try {
            check(allowed()) { "AI 自主参加狼人杀未开启，请在当前助手的本地工具中开启。" }
            val args=arguments.jsonObject
            when(args.ms("action")) {
                "list" -> catalog().let { all -> JsonObject(all+mapOf("rooms" to JsonArray(all.ma("rooms").filter { it.ms("mode")=="ai_only" }))) }
                "join" -> join(args.ms("room"))
                else -> error("请选择 list 或 join")
            }
        } catch(e:CancellationException) { throw e }
        catch(e:MarketProblem) { JsonObject(e.details+mapOf("ok" to JsonPrimitive(false))) }
        catch(e:Exception) { buildJsonObject { put("ok",false);put("message",e.message ?: "连接未完成，请稍后重试") } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun werewolfLobbyTool(context:Context,assistant:String,conversation:String,allowed:()->Boolean):Tool =
    werewolfLobbyTool(allowed,
        catalog={TabletopClient(context,assistant,"werewolf").publicApi("catalog")},
        join={room ->
            val host=TabletopConversationHost.get(context)
            val api=TabletopClient(context,assistant,"werewolf")
            api.connect(room,aiOnly=true)
            val status=api.game("script_status",asAi=true)
            withContext(Dispatchers.Main.immediate) {
                host.bind(api,conversation,"当前聊天")
                host.setVisible(assistant,false)
                host.listen(assistant,GlobalContext.get().get<ChatService>())
            }
            JsonObject(status+mapOf("joined" to JsonPrimitive(true),"message" to JsonPrimitive("已自动入席并准备；满员后系统开局。等待时用 werewolf_leave 可退出。")))
        },
    )
