package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Only the public ending is retained; never save private night actions in the notification. */
internal fun werewolfEndingSnapshot(status:JsonObject):JsonObject {
    require(status.ms("game_kind")=="werewolf" && status.ms("stage")=="ended")
    return buildJsonObject {
        for(key in listOf("game_kind","mode","self","stage","room","id","phase_version","winner","roles","expires_at","result_available_until"))
            status[key]?.let { put(key,it) }
        put("source","saved_game_result");put("observer",true);put("wake",false)
        put("allowed_actions",buildJsonArray { if(status.ms("mode")!="mixed")add("leave") })
    }
}

/** A saved result can finish delivery after cleanup without reconnecting or submitting old actions. */
internal class WerewolfEndingApi(
    override val assistantId:String,
    override val room:String,
    status:JsonObject,
):TabletopGameApi {
    override val gameKind="werewolf"
    override val latestStatus=werewolfEndingSnapshot(status)
    override fun pending(asAi:Boolean)=false
    override suspend fun roleImage(name:String):String=error("本局已结束")
    override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject = when {
        path=="script_status" -> latestStatus
        path=="script_pass" && body?.ms("action")=="leave" && latestStatus.ms("mode")=="mixed" -> buildJsonObject {
            put("status","rejected");put("detail",buildJsonObject {
                put("error","human_control_required");put("message",WEREWOLF_PAIRED_LEAVE_GUIDE)
            })
        }
        path=="script_pass" && body?.ms("action")=="leave" -> buildJsonObject {
            put("left",true);put("local_only",true);put("stage","ended")
            put("message","已退出本机结束通知连接；服务端房间按原散场时间清空。")
        }
        else -> buildJsonObject {
            put("status","rejected");put("detail",buildJsonObject {
                put("error","game_ended");put("message","本局已结束。结局及全员身份已保存在结束通知中，无需再行动。$WEREWOLF_COMMUNITY_RECAP")
            })
        }
    }
}
